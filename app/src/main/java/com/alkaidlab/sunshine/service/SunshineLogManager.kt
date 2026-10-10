package com.alkaidlab.sunshine.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object SunshineLogManager {
    private val buffer = CopyOnWriteArrayList<String>()
    private const val MAX_LOGS = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @JvmStatic
    fun log(tag: String, message: String) {
        val time = dateFormat.format(Date())
        val entry = "[$time][$tag] $message"
        buffer.add(entry)
        if (buffer.size > MAX_LOGS) {
            buffer.removeAt(0)
        }
    }

    @JvmStatic
    fun getRecentLogs(): List<String> {
        return buffer.toList()
    }

    @JvmStatic
    fun clear() {
        buffer.clear()
    }
}
