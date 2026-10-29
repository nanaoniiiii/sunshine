package com.alkaidlab.sunshine.service

import android.content.Context

/** The saved choice is independent of the temporary display-restore journal. */
enum class StreamDisplayMode(val value: String) {
    DEFAULT("default"), FULLSCREEN("fullscreen");

    companion object {
        fun parse(value: String?): StreamDisplayMode? = values().firstOrNull { it.value == value }
    }
}

internal class StreamDisplayPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("stream_display", Context.MODE_PRIVATE)
    fun read(): StreamDisplayMode = StreamDisplayMode.parse(preferences.getString("mode", null)) ?: StreamDisplayMode.DEFAULT
    fun write(mode: StreamDisplayMode): Boolean = preferences.edit().putString("mode", mode.value).commit()
}
