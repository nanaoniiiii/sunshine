package com.alkaidlab.sunshine.service

import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Route #6 step 6: virtual-desktop provisioning extracted from
 * SunshineRuntimeManager.createVirtualScreen.
 *
 * Resizes the external DeX workspace (when present), swaps the capture surface,
 * creates the virtual display through [VirtualScreenClient], publishes the active
 * state, switches the frame dispatcher to the virtual source and reconfigures the
 * engine canvas.
 *
 * Pure move of the original block: call order, thrown errors (check/error still
 * propagate into the caller's try, whose catch/finally stay in the manager) and
 * logging are unchanged. All external state goes through [Host] on the caller's
 * thread, so the Main-thread + NonCancellable guarantees are preserved.
 */
internal class VirtualScreenProvisioner(private val host: Host) {

    interface Host {
        fun releaseVirtualDisplay()
        fun resizeCaptureSync(width: Int, height: Int): Surface?
        fun setVirtualSource(enabled: Boolean, refreshRate: Float)
        fun setSourceRotation(rotation: Int)
        fun configureCanvas(width: Int, height: Int, autoRotate: Boolean, rotationCompat: Boolean)
        fun setVirtualScreenState(state: VirtualScreenState)
        fun isSessionActive(videoThreadId: Long): Boolean
    }

    suspend fun provision(
        client: VirtualScreenClient,
        width: Int,
        height: Int,
        samsungDex: Boolean,
        density: Int,
        mode: String,
        refreshRate: Int,
        systemDesktop: Boolean,
        highRefresh: Boolean,
        videoThreadId: Long,
    ): FullscreenDisplay.Result {
        // Resize the real external DeX workspace before allocating the capture
        // surface. Primary/tablet DeX keeps its existing main-screen dimensions.
        val ready = org.json.JSONObject(withContext(Dispatchers.IO) { client.configureDesktop(width, height, samsungDex) })
        val captureWidth = if (samsungDex) ready.optInt("native_dex_width").takeIf { it in 1..8192 } ?: width else width
        val captureHeight = if (samsungDex) ready.optInt("native_dex_height").takeIf { it in 1..8192 } ?: height else height
        host.releaseVirtualDisplay()
        val surface = host.resizeCaptureSync(captureWidth, captureHeight) ?: error("无法切换虚拟屏采集画布")
        val id = withContext(Dispatchers.IO) { client.create(captureWidth, captureHeight, density, surface, mode, refreshRate, systemDesktop, highRefresh) }
        val created = org.json.JSONObject(client.snapshot())
        check(id > 0 || (id == 0 && created.optBoolean("system_dex_mirror") && created.optBoolean("dex_active"))) { "系统没有返回有效的桌面显示 ID" }
        check(host.isSessionActive(videoThreadId)) { "创建期间连接已结束" }
        val snapshot = org.json.JSONObject(client.snapshot())
        snapshot.optString("local_dex_stage").takeIf { it.startsWith("wfd_status:") }?.let {
            SunshineLogManager.log("DEX_TRANSPORT", it.removePrefix("wfd_status:"))
        }
        val description = snapshot.optString("launcher", "Sunshine 桌面")
        host.setVirtualScreenState(VirtualScreenState(active = true, displayId = id, width = captureWidth, height = captureHeight,
            mode = mode, launcher = description, requestedRefreshRate = snapshot.optInt("requested_refresh_rate"),
            actualRefreshRate = snapshot.optDouble("actual_refresh_rate", 0.0).toFloat(),
            refreshRateRequestSupported = snapshot.optBoolean("refresh_rate_request_supported"),
            dexRequested = samsungDex, dexActive = snapshot.optBoolean("dex_active"), detail = snapshot.optString("detail"),
            systemDexMirror = snapshot.optBoolean("system_dex_mirror"), desktopControls = false,
            retainedDisplayId = snapshot.optInt("retained_display_id", -1)))
        host.setVirtualSource(true, snapshot.optDouble("actual_refresh_rate", 60.0).toFloat())
        host.setSourceRotation(0)
        host.configureCanvas(captureWidth, captureHeight, false, false)
        SunshineLogManager.log("VIRTUAL_SCREEN", "Created display=$id, ${captureWidth}x$captureHeight, mode=$mode, launcher=$description")
        FullscreenDisplay.Result(true, if (snapshot.optBoolean("system_dex_mirror"))
            "已开始串流 $captureWidth × $captureHeight · $description" else "已创建 $captureWidth × $captureHeight 虚拟屏 · $description")
    }
}
