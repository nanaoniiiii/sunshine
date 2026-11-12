package com.alkaidlab.sunshine.service

/**
 * createVirtualScreen() 的失败恢复路径。
 *
 * 原实现逐字搬运自 SunshineRuntimeManager.createVirtualScreen() 的 catch 块
 * （d3d800ad 版）：最佳努力关闭半成品客户端、经 DisplayFailureMessage 格式化
 * 错误信息、上报错误状态（desktop 模式下恢复物理采集）、打错误日志并返回
 * 失败 Result。行为保持不变。
 */
internal class VirtualScreenFailureRecovery(
    private val host: Host,
    private val logger: (tag: String, message: String) -> Unit = SunshineLogManager::log,
) {
    internal interface Host {
        /** 最佳努力关闭虚拟屏客户端（IO 线程、不可取消，异常吞掉）。 */
        suspend fun closeVirtualClientBestEffort()
        /** 发布创建失败状态（busy=true 保持，后续 finally 会清 busy）。 */
        fun setCreateErrorState(message: String)
        /** 恢复物理采集（仅 desktop 模式调用）。 */
        fun restorePhysicalCapture()
    }

    suspend fun handleCreateFailure(error: Exception, mode: String): FullscreenDisplay.Result {
        host.closeVirtualClientBestEffort()
        val message = DisplayFailureMessage.from(error)
        host.setCreateErrorState(message)
        if (mode == "desktop") host.restorePhysicalCapture()
        logger("VIRTUAL_SCREEN_ERR", message)
        return FullscreenDisplay.Result(false, "虚拟屏未创建：$message")
    }
}
