package com.alkaidlab.sunshine.service

data class StreamingSettings(
    val maxFps: Int = 240,
    val repeatFrames: Boolean = true,
    val audioBufferMs: Int = 40,
    val wifiLowLatency: Boolean = true
) {
    companion object {
        fun from(properties: Map<String, String>): StreamingSettings = StreamingSettings(
            maxFps = properties["max_stream_fps"]?.toIntOrNull()?.coerceIn(15, 240) ?: 240,
            repeatFrames = properties["repeat_frames"] != "false",
            audioBufferMs = properties["audio_buffer_ms"]?.toIntOrNull()?.coerceIn(20, 100) ?: 40,
            wifiLowLatency = properties["wifi_low_latency"] != "false"
        )

        fun validate(properties: Map<String, String>): String? {
            for ((key, range) in listOf("max_stream_fps" to 15..240, "audio_buffer_ms" to 20..100,
                "min_bitrate" to 1000..150000, "max_bitrate" to 1000..150000)) {
                properties[key]?.let { if (it.toIntOrNull() !in range) return "$key must be within $range" }
            }
            if ((properties["min_bitrate"]?.toIntOrNull() ?: 5000) >
                (properties["max_bitrate"]?.toIntOrNull() ?: 50000)) return "min_bitrate must not exceed max_bitrate"
            for (key in listOf("encoder_h264", "encoder_hevc")) {
                properties[key]?.let { if (it.length !in 1..128 || !it.matches(Regex("[A-Za-z0-9_.-]+"))) return "$key must be auto or a codec name" }
            }
            for (key in listOf("repeat_frames", "wifi_low_latency")) {
                properties[key]?.let { if (it !in listOf("true", "false")) return "$key must be true or false" }
            }
            if (properties.any { (key, value) -> key.contains('\n') || key.contains('\r') || key.contains('=') ||
                        value.contains('\n') || value.contains('\r') }) return "Configuration must contain single-line values"
            return null
        }
    }
}
