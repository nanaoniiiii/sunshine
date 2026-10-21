package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class StreamingSettingsTest {
    @Test fun desktopChoiceOnlyAcceptsSunshineOrSystem() {
        assertNull(StreamingSettings.validate(mapOf("virtual_screen_desktop" to "sunshine")))
        assertNull(StreamingSettings.validate(mapOf("virtual_screen_desktop" to "system")))
        assertNotNull(StreamingSettings.validate(mapOf("virtual_screen_desktop" to "dex")))
        assertNotNull(StreamingSettings.validate(mapOf("virtual_screen_desktop" to "")))
    }
    @Test fun experimentalOptionsRequireExplicitBooleans() {
        assertNull(StreamingSettings.validate(mapOf("experimental_samsung_dex" to "true", "experimental_desktop_high_refresh" to "false")))
        assertNotNull(StreamingSettings.validate(mapOf("experimental_samsung_dex" to "yes")))
        assertNotNull(StreamingSettings.validate(mapOf("experimental_desktop_high_refresh" to "120")))
    }
    @Test fun virtualScreenConfigurationRejectsUnknownModesAndInvalidDensity() {
        assertNull(StreamingSettings.validate(mapOf("virtual_screen_mode" to "desktop", "virtual_screen_density" to "240")))
        assertNull(StreamingSettings.validate(mapOf("virtual_screen_mode" to "screen_off", "virtual_screen_density" to "640")))
        assertNotNull(StreamingSettings.validate(mapOf("virtual_screen_mode" to "mirror")))
        assertNotNull(StreamingSettings.validate(mapOf("virtual_screen_density" to "0")))
        assertNotNull(StreamingSettings.validate(mapOf("virtual_screen_density" to "8000")))
    }
    @Test fun encoderChoicesAllowStaleDeviceNamesButRejectMalformedValues() {
        assertNull(StreamingSettings.validate(mapOf("encoder_h264" to "auto", "encoder_hevc" to "OMX.qcom.video.encoder.hevc")))
        assertNull(StreamingSettings.validate(mapOf("encoder_h264" to "OMX.device.no-longer-present")))
        assertNotNull(StreamingSettings.validate(mapOf("encoder_h264" to "")))
        assertNotNull(StreamingSettings.validate(mapOf("encoder_hevc" to "name\nother")))
        assertNotNull(StreamingSettings.validate(mapOf("encoder_h264" to "x".repeat(129))))
    }
    @Test fun oldConfigGetsSafeNewDefaults() {
        assertEquals(StreamingSettings(), StreamingSettings.from(mapOf("hevc_mode" to "enabled")))
    }

    @Test fun invalidBoundsAndBooleansAreRejectedBeforeWriting() {
        for (values in listOf(
            mapOf("max_stream_fps" to "0"), mapOf("max_stream_fps" to "500"),
            mapOf("audio_buffer_ms" to "5000"), mapOf("audio_buffer_ms" to "bad"),
            mapOf("repeat_frames" to "yes"), mapOf("wifi_low_latency" to "1"), mapOf("rotation_compat" to "yes")
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
