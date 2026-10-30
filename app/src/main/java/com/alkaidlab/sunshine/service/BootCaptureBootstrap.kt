package com.alkaidlab.sunshine.service

import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.alkaidlab.sunshine.ui.BootCaptureActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Requests coalesce; foreign recording is observed, never stopped or repeatedly replaced. */
class BootCaptureBootstrap(private val context: Context, private val scope: CoroutineScope) {
    private enum class Reason { STARTUP, STREAM, FOREIGN_CAPTURE_ENDED }
    private var request: Deferred<Boolean>? = null // Main dispatcher only.
    private var foreignWatcher: Job? = null
    private var retryAfter = 0L

    companion object {
        @Volatile var pending = false
            private set
        private val state = MutableStateFlow("等待服务启动")
        val status = state.asStateFlow()

        fun authorized(context: Context): Boolean {
            if (Build.VERSION.SDK_INT !in 29..33) return false
            return try {
                context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
                    "android:project_media", Process.myUid(), context.packageName
                ) == AppOpsManager.MODE_ALLOWED
            } catch (_: RuntimeException) { false }
        }

        suspend fun configure(context: Context) = withContext(Dispatchers.IO) {
            check(Build.VERSION.SDK_INT in 29..33) { "当前免确认开机录屏支持 Android 10–13" }
            if (PrivilegedShell.available()) {
                for (op in listOf("PROJECT_MEDIA", "SYSTEM_ALERT_WINDOW", "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND")) {
                    check(PrivilegedShell.run(arrayOf("cmd", "appops", "set", "--user", "0", context.packageName, op, "allow")) != null) {
                        "后台授权未完成，请检查 Shizuku 后重试"
                    }
                }
                PrivilegedShell.run(arrayOf("cmd", "deviceidle", "whitelist", "+${context.packageName}"))
            }
            check(authorized(context) && Settings.canDrawOverlays(context)) {
                "请先启动并授权 Shizuku，完成一次后台录屏授权后即可启用"
            }
        }
    }

    private fun enabled() = ServiceStartupSettings.read(context).instantConnect
    private fun runtime() = SunshineRuntimeManager.getInstance()
    private fun report(message: String) {
        state.value = message
        SunshineLogManager.log("AUTO_CAPTURE", message)
    }

    private suspend fun owner(): CaptureOwnership = withContext(Dispatchers.IO) {
        CaptureOwnership.parse(PrivilegedShell.run(arrayOf("dumpsys", "media_projection"), timeoutMs = 1200))
    }

    fun prepare(onFinished: () -> Unit = {}) {
        scope.launch(Dispatchers.Main.immediate) {
            try { acquire(Reason.STARTUP) } finally { onFinished() }
        }
    }

    /** Runs on the native launch Binder worker, without holding the runtime lock. */
    fun ensureForStream(): Boolean {
        if (runtime().getMediaProjection() != null) return true
        if (!enabled() || Looper.myLooper() == Looper.getMainLooper()) return false
        return try {
            runBlocking { withTimeout(10000) { withContext(Dispatchers.Main) { acquire(Reason.STREAM) } } }
        } catch (_: Exception) { false }
    }

    private suspend fun acquire(reason: Reason): Boolean {
        if (runtime().getMediaProjection() != null) {
            if (enabled()) report("录屏已就绪，可直接连接 Moonlight")
            return true
        }
        if (!enabled()) return false
        val existing = request
        if (existing?.isActive == true) return existing.await()
        if (SystemClock.elapsedRealtime() < retryAfter) return false
        val next = scope.async(Dispatchers.Main.immediate) { attempt(reason) }
        request = next
        return next.await()
    }

    private suspend fun attempt(reason: Reason): Boolean {
        if (!authorized(context) || !Settings.canDrawOverlays(context)) {
            report("服务已运行，开机即连需要重新完成后台录屏授权")
            return false
        }
        pending = true
        try {
            return withTimeout(if (reason == Reason.STARTUP) 60000L else 8000L) {
                runtime().engineReady.first { it }
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                if (reason != Reason.STARTUP && keyguard.isDeviceLocked) {
                    report("设备已锁定，解锁后可自动恢复录屏")
                    return@withTimeout false
                }
                while (keyguard.isDeviceLocked) delay(500)
                if (!enabled()) return@withTimeout false
                val current = owner()
                if (current is CaptureOwnership.Active && current.packageName != context.packageName) {
                    report("其他投屏工具正在录屏，等待其结束后恢复")
                    watchForeignCapture()
                    return@withTimeout false
                }
                // Shizuku may not yet run at cold boot. Only that configured
                // initial attempt can proceed without a privileged owner query.
                if (current === CaptureOwnership.Unknown && reason != Reason.STARTUP) {
                    report("无法确认录屏占用，等待 Shizuku 恢复后重试串流")
                    return@withTimeout false
                }
                if (runtime().getMediaProjection() == null) {
                    report("正在后台准备录屏，无需打开主页")
                    context.startActivity(Intent(context, BootCaptureActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }, ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
                    while (runtime().getMediaProjection() == null) delay(100)
                }
                report("录屏已就绪，可直接连接 Moonlight")
                true
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            retryAfter = SystemClock.elapsedRealtime() + 5000
            report("服务仍在运行，后台录屏暂未恢复：${error.javaClass.simpleName}")
            return false
        } finally { pending = false }
    }

    fun onCaptureLost() {
        scope.launch(Dispatchers.Main) {
            if (!enabled()) return@launch
            report("录屏会话已结束，下次串流时自动重新申请")
            // An explicit system Stop must not cause a capture-restart loop.
            if (owner() is CaptureOwnership.Active) watchForeignCapture()
        }
    }

    private fun watchForeignCapture() {
        if (foreignWatcher?.isActive == true) return
        foreignWatcher = scope.launch(Dispatchers.Main) {
            while (enabled() && runtime().getMediaProjection() == null) {
                delay(2000)
                when (val current = owner()) {
                    CaptureOwnership.None -> {
                        acquire(Reason.FOREIGN_CAPTURE_ENDED)
                        return@launch
                    }
                    CaptureOwnership.Unknown -> {
                        report("录屏占用状态暂不可读，下次串流时重试")
                        return@launch
                    }
                    is CaptureOwnership.Active -> if (current.packageName == context.packageName) return@launch
                }
            }
        }
    }
}
