package com.alkaidlab.sunshine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object SunshineLogManager {
    // Ring-style bounded buffer: COW list copied the whole backing array on every
    // mutation (add/removeAt), i.e. O(MAX_LOGS) allocations per log() call. The
    // buffer is private and only observed through snapshots (getRecentLogs /
    // logsFlow), so a synchronized deque with the same insertion-order semantics
    // and MAX_LOGS eviction policy has O(1) amortized append/eviction; producing
    // each published history snapshot still takes O(MAX_LOGS) work.
    private val buffer = ArrayDeque<String>()
    private val bufferLock = Any()
    private const val MAX_LOGS = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    @JvmStatic
    fun log(tag: String, message: String) {
        val time = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = "[$time][$tag] $message"
        android.util.Log.d("SunshineLog", "[$tag] $message")
        synchronized(bufferLock) {
            buffer.addLast(entry)
            if (buffer.size > MAX_LOGS) {
                buffer.removeFirst()
            }
            // Publish under the same lock so an older append cannot republish
            // its snapshot after clear() or a newer append has completed.
            _logsFlow.value = buffer.toList()
        }
    }

    @JvmStatic
    fun getRecentLogs(): List<String> {
        return synchronized(bufferLock) { buffer.toList() }
    }

    @JvmStatic
    fun clear() {
        synchronized(bufferLock) {
            buffer.clear()
            _logsFlow.value = emptyList()
        }
    }
}
