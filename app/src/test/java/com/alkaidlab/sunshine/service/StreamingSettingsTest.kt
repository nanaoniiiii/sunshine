package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class StreamingSettingsTest {
    @Test fun oldConfigGetsSafeNewDefaults() {
        assertEquals(StreamingSettings(), StreamingSettings.from(mapOf("hevc_mode" to "enabled")))
    }

    @Test fun invalidBoundsAndBooleansAreRejectedBeforeWriting() {
        for (values in listOf(
            mapOf("max_stream_fps" to "0"), mapOf("max_stream_fps" to "500"),
            mapOf("audio_buffer_ms" to "5000"), mapOf("audio_buffer_ms" to "bad"),
            mapOf("repeat_frames" to "yes"), mapOf("wifi_low_latency" to "1")
        )) assertNotNull(StreamingSettings.validate(values))
        assertNull(StreamingSettings.validate(mapOf("max_stream_fps" to "120", "audio_buffer_ms" to "20")))
    }

    @Test fun malformedLegacyFilesAreClampedAndDoNotCrashRuntime() {
        val result = StreamingSettings.from(mapOf("max_stream_fps" to "-1", "audio_buffer_ms" to "999999"))
        assertEquals(15, result.maxFps)
        assertEquals(100, result.audioBufferMs)
    }

    @Test fun multilineConfigInjectionIsRejected() {
        assertNotNull(StreamingSettings.validate(mapOf("auto_scale" to "true\nmax_stream_fps = 999")))
    }

    @Test fun bitrateLimitsMustBeOrderedAndNumeric() {
        assertNotNull(StreamingSettings.validate(mapOf("min_bitrate" to "60000", "max_bitrate" to "50000")))
        assertNotNull(StreamingSettings.validate(mapOf("max_bitrate" to "NaN")))
        assertNotNull(StreamingSettings.validate(mapOf("min_bitrate" to "0")))
        assertNull(StreamingSettings.validate(mapOf("min_bitrate" to "1000", "max_bitrate" to "4000")))
    }
}
