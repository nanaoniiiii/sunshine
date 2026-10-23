package com.alkaidlab.sunshine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Moonlight PIN 配对握手状态。
 *
 * 从 SunshineRuntimeManager 拆出（God object 增量拆分第 2 步）；行为与原内联实现逐字一致：
 * 状态流写入线程安全，调用方（引擎回调在 runtime 监视器内、UI 经 ViewModel）无需改变。
 *
 * [logger] 是测试缝（test seam）：默认转发到 [SunshineLogManager]（与原行为完全一致），
 * 纯 JVM 单测可注入 no-op 以避开 android.util.Log。
 */
internal class PinPairingController(
    private val submitToEngine: (String) -> Boolean,
    private val logger: (tag: String, message: String) -> Unit = SunshineLogManager::log,
) {
    private val _pinRequested = MutableStateFlow<String?>(null)
    val pinRequested: StateFlow<String?> = _pinRequested.asStateFlow()

    /** 引擎回调（在 runtime 监视器内）通知有客户端请求配对时调用。 */
    fun onPinRequested() {
        logger("PIN", "Moonlight client requested PIN pairing")
        _pinRequested.value = "PIN_REQUIRED"
    }

    fun submitPin(pin: String): Boolean {
        val cleanPin = pin.trim()
        if (cleanPin.length != 4 || !cleanPin.all { it.isDigit() }) {
            logger("PIN_ERR", "Invalid PIN format")
            return false
        }
        return try {
            if (!submitToEngine(cleanPin)) return false
            _pinRequested.value = null
            logger("PIN", "Native submitPin completed")
            true
        } catch (e: Throwable) {
            logger("PIN_ERR", "Failed native submitPin: ${e.message}")
            false
        }
    }

    fun dismissPinRequest() {
        _pinRequested.value = null
    }

    /** 清除未完成的配对请求，例如引擎断开连接时。 */
    fun clear() {
        _pinRequested.value = null
    }
}


