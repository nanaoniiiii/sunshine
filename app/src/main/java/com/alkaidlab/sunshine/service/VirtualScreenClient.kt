package com.alkaidlab.sunshine.service

import android.content.*
import android.os.*
import android.view.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

data class VirtualScreenState(val active: Boolean = false, val busy: Boolean = false, val displayId: Int = -1,
    val width: Int = 0, val height: Int = 0, val mode: String = "desktop", val launcher: String = "", val error: String? = null,
    val requestedRefreshRate: Int = 0, val actualRefreshRate: Float = 0f, val refreshRateRequestSupported: Boolean = false,
    val dexRequested: Boolean = false, val dexActive: Boolean = false, val detail: String = "", val systemDexMirror: Boolean = false,
    val mainPanelOff: Boolean = false, val desktopControls: Boolean = false, val retainedDisplayId: Int = -1)

class VirtualScreenClient(private val context: Context, private val disconnected: () -> Unit) {
    private var remote: ISunshineDisplayController? = null
    private var waiting: CompletableDeferred<ISunshineDisplayController>? = null
    private val owner = Binder()
    private val installVersion = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, VirtualDisplayUserService::class.java.name))
        .daemon(false).processNameSuffix("virtualdisplay").tag("sunshine-virtualdisplay")
        .version((installVersion xor (installVersion ushr 32)).toInt())
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val pending = waiting ?: return
            if (!pending.isActive) return
            val controller = ISunshineDisplayController.Stub.asInterface(service)
            remote = controller
            service.linkToDeath({ remote = null; disconnected() }, 0)
            pending.complete(controller)
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; disconnected() }
    }
    suspend fun connect(): ISunshineDisplayController {
        remote?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        check(PrivilegedShell.available()) { "创建虚拟屏需要 Shizuku 授权" }
        val promise = CompletableDeferred<ISunshineDisplayController>()
        waiting = promise
        Shizuku.bindUserService(args, connection)
        return try {
            val service = withTimeout(8000) { promise.await() }
            check(org.json.JSONObject(service.snapshot()).optInt("protocol_version") == 10) { "显示服务版本过旧，请重新绑定" }
            service.attachOwner(owner)
            service
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) {}
            remote = null
            throw IllegalStateException("Shizuku 显示服务未能启动。请打开 Shizuku／Stellar 管理器，取消其休眠或后台限制，返回 Sunshine 后重试；应用授权无需重置。", error)
        } catch (error: Exception) {
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) {}
            remote = null
            throw error
        } finally { waiting = null }
    }
    suspend fun create(width: Int, height: Int, density: Int, surface: Surface, mode: String, fps: Int, systemDesktop: Boolean, highRefresh: Boolean): Int =
        connect().createDisplay(width, height, density, surface, mode, owner, fps, systemDesktop, highRefresh)
    suspend fun blankMain(): Boolean = connect().blankMainScreen(owner)
    suspend fun prepareLocalDex(): Boolean = connect().prepareLocalDex(owner)
    suspend fun configureDesktop(width: Int, height: Int, samsungDex: Boolean): String =
        connect().configureDesktop(width, height, samsungDex, owner)
    suspend fun setMainPanelOff(off: Boolean): Boolean = connect().setMainPanelOff(off)
    fun close() { remote?.closeDisplay() }
    fun snapshot(): String = remote?.snapshot().orEmpty()
    fun launch(component: String): Boolean = remote?.launchApp(component) == true
    fun tasks(): String = remote?.desktopTasks() ?: "[]"
    fun action(action: String, taskId: Int = -1): Boolean = remote?.desktopAction(action, taskId) == true
    fun motion(event: MotionEvent): Boolean = remote?.injectMotion(event) == true
    fun key(code: Int, down: Boolean, modifiers: Int): Boolean = remote?.injectKey(code, down, modifiers) == true
    fun release() {
        try { close() } catch (_: Exception) {}
        try { remote?.destroy() } catch (_: Exception) {}
        try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) {}
        remote = null
    }
}
