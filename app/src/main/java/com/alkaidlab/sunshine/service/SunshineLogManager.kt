package com.alkaidlab.sunshine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object SunshineLogManager {
    private val buffer = CopyOnWriteArrayList<String>()
    private const val MAX_LOGS = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    @JvmStatic
    fun log(tag: String, message: String) {
        val time = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = "[$time][$tag] $message"
        android.util.Log.d("SunshineLog", "[$tag] $message")
        buffer.add(entry)
        while (buffer.size > MAX_LOGS) {
            buffer.removeAt(0)
        }
        _logsFlow.value = buffer.toList()
    }

    @JvmStatic
    fun getRecentLogs(): List<String> {
        return buffer.toList()
    }

    @JvmStatic
    fun clear() {
        buffer.clear()
        _logsFlow.value = emptyList()
    }
}
