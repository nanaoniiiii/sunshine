package com.alkaidlab.sunshine.service

import android.os.SystemClock
import com.alkaidlab.sunshine.data.RuntimeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 三星本机 DeX 就绪等待编排。
 *
 * 从 SunshineRuntimeManager.createVirtualScreen() 抽出（路线图 #6 虚拟屏拆分）。
 * 循环体与原内联代码逐字一致；调用方须在 Main + NonCancellable 协程上下文中调用，
 * 外部状态读写全部经 [Host] 在调用线程完成。
 */
internal class LocalDexWaiter(private val host: Host) {

    internal interface Host {
        /** 原 activeSessions.any { it.videoThreadId == session.videoThreadId } */
        fun isStreamSessionConnected(session: RuntimeSession): Boolean
        fun virtualScreenDetail(): String
        fun updateVirtualScreenDetail(detail: String)
        fun setPreparingLocalDex(preparing: Boolean)
    }

    /**
     * 若本机 DeX 需要用户确认则阻塞等待就绪；失败时抛 IllegalStateException，
     * 语义与原 check(...) 完全一致（由 createVirtualScreen 的 catch 统一处理）。
     *
     * 注意：`setPreparingLocalDex(false)` 仍在 `check(ready)` 之后、无 try/finally 包裹——
     * check 抛异常时标记保持 true，与原代码一致（最终由 createVirtualScreen 的 finally 复位）。
     */
    suspend fun awaitLocalDexIfPending(client: VirtualScreenClient, session: RuntimeSession) {
        val pending = withContext(Dispatchers.IO) { client.prepareLocalDex() }
        if (!pending) return
        host.setPreparingLocalDex(true)
        val route = JSONObject(client.snapshot()).optString("dex_route_detail")
        host.updateVirtualScreenDetail(route.ifBlank { "请在三星 DeX 列表选择 Sunshine Local DeX，并确认立即开始" })
        var ready = false
        // This includes human interaction with Samsung's device list,
        // consent and first-use pages. A one-minute deadline can tear
        // down the receiver while the user is still confirming it.
        val confirmationDeadline = SystemClock.uptimeMillis() + 180_000
        while (SystemClock.uptimeMillis() < confirmationDeadline) {
            check(host.isStreamSessionConnected(session)) { "连接已结束" }
            val status = withContext(Dispatchers.IO) { JSONObject(client.snapshot()) }
            val stage = status.optString("local_dex_stage")
            val hint = when {
                stage.contains("main_channel") || stage.contains("connection_allowed") -> "请确认三星系统弹出的立即开始；正在建立本机 DeX"
                stage.startsWith("wfd_") -> "三星 DeX 已连接，正在等待系统桌面显示"
                else -> route.ifBlank { "请在三星 DeX 列表选择 Sunshine Local DeX，再确认立即开始" }
            }
            if (host.virtualScreenDetail() != hint) host.updateVirtualScreenDetail(hint)
            check(status.optString("local_dex_error").isEmpty()) { "本机 DeX：${status.optString("local_dex_error")}" }
            if (status.optBoolean("local_dex_ready")) { ready = true; break }
            delay(250)
        }
        check(ready) { "三星 DeX 等待超时：${host.virtualScreenDetail()}" }
        host.setPreparingLocalDex(false)
    }
}
