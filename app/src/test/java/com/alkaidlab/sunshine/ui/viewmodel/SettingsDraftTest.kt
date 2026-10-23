package com.alkaidlab.sunshine.ui.viewmodel

import com.alkaidlab.sunshine.data.ConfigResponse
import org.junit.Assert.*
import org.junit.Test

class SettingsDraftTest {
    @Test fun virtualScreenStartsWithPrimaryPanelOffUntilSecondDesktopIsExplicitlyEnabled() {
        assertEquals("screen_off", SettingsDraft.from(ConfigResponse())["virtual_screen_mode"])
        assertEquals("screen_off", SettingsDraft.from(ConfigResponse(virtualScreenDesktop = "system"))["virtual_screen_mode"])
        assertEquals("desktop", SettingsDraft.from(ConfigResponse(virtualScreenMode = "desktop"))["virtual_screen_mode"])
    }
    @Test fun desktopDefaultsToSunshineAndKeepsExplicitSystemChoice() {
        assertEquals("sunshine", SettingsDraft.from(ConfigResponse(experimentalSamsungDex = "true"))["virtual_screen_desktop"])
        assertEquals("system", SettingsDraft.from(ConfigResponse(virtualScreenDesktop = "system"))["virtual_screen_desktop"])
        assertFalse(SettingsDraft.from(ConfigResponse()).values.containsKey("experimental_samsung_dex"))
    }
    @Test fun invalidAndReversedNumbersBlockSaveButDefaultsAreValid() {
        val valid = SettingsDraft.from(ConfigResponse())
        assertTrue(valid.errors().isEmpty())
        assertTrue(valid.withValue("max_stream_fps", "abc").errors().containsKey("max_stream_fps"))
        assertTrue(valid.withValue("min_bitrate", "60000").errors().containsKey("min_bitrate"))
        assertTrue(valid.withValue("web_port", "65536").errors().containsKey("web_port"))
        assertTrue(valid.withValue("audio_buffer_ms", "19").errors().containsKey("audio_buffer_ms"))
    }
    @Test fun explicitEncoderAndDisabledOptionsArePreservedInSavePayload() {
        val draft = SettingsDraft.from(ConfigResponse(encoderH264 = "OMX.old.device.encoder", hevcMode = "disabled", repeatFrames = "false"))
        assertEquals("OMX.old.device.encoder", draft.values["encoder_h264"])
        assertEquals("disabled", draft.values["hevc_mode"])
        assertFalse(draft.checked("repeat_frames"))
    }
}
