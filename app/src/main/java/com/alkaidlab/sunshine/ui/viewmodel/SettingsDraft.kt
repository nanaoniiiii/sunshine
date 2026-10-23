package com.alkaidlab.sunshine.ui.viewmodel

import com.alkaidlab.sunshine.data.ConfigResponse

/** Editable values live separately from the periodically refreshed server snapshot. */
data class SettingsDraft(val values: Map<String, String>) {
    fun withValue(key: String, value: String) = copy(values = values + (key to value))
    operator fun get(key: String): String = values[key].orEmpty()
    fun checked(key: String) = this[key] != "false"
    fun errors(): Map<String, String> = buildMap {
        for ((key, range) in listOf("max_stream_fps" to 15..240, "audio_buffer_ms" to 20..100,
            "min_bitrate" to 1000..150000, "max_bitrate" to 1000..150000,
            "virtual_screen_density" to 120..640, "port" to 1024..65530, "web_port" to 1024..65535)) {
            if (this@SettingsDraft.values[key]?.toIntOrNull() !in range) put(key, "请输入 ${range.first}–${range.last} 范围内的整数")
        }
        val min = this@SettingsDraft.values["min_bitrate"]?.toIntOrNull()
        val max = this@SettingsDraft.values["max_bitrate"]?.toIntOrNull()
        if (min != null && max != null && min > max) put("min_bitrate", "最小码率不能高于最大码率")

    }
    companion object {
        fun from(c: ConfigResponse) = SettingsDraft(mapOf(
            "encoder_h264" to c.encoderH264, "encoder_hevc" to c.encoderHevc,
            "virtual_screen_desktop" to c.virtualScreenDesktop,
            "experimental_desktop_high_refresh" to c.experimentalDesktopHighRefresh,
            "hevc_mode" to c.hevcMode, "auto_rotate" to c.autoRotate, "auto_scale" to c.autoScale, "rotation_compat" to c.rotationCompat, "virtual_screen_mode" to c.virtualScreenMode, "virtual_screen_density" to c.virtualScreenDensity,
            "max_bitrate" to c.maxBitrate, "min_bitrate" to c.minBitrate, "port" to c.port,
            "web_port" to c.webPort, "address_family" to c.addressFamily, "max_stream_fps" to c.maxStreamFps,
            "repeat_frames" to c.repeatFrames, "audio_buffer_ms" to c.audioBufferMs, "wifi_low_latency" to c.wifiLowLatency))
    }
}
