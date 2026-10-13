package com.alkaidlab.sunshine.ui.viewmodel

import com.alkaidlab.sunshine.data.ConfigResponse
import org.junit.Assert.*
import org.junit.Test

class SettingsDraftTest {
    @Test fun pollingNeverOverwritesUnsavedInputEvenIfServerChanges() {
        val old = ConfigResponse()
        val draft = SettingsDraft.from(old).withValue("max_stream_fps", "12")
        val refreshed = old.copy(maxStreamFps = "120", encoderH264 = "OMX.other.encoder")
        assertEquals(draft, SettingsDraft.reconcile(draft, old, refreshed))
    }
    @Test fun cleanDraftTracksServerAndFirstLoadGetsActualValues() {
        val old = ConfigResponse()
        val current = old.copy(maxBitrate = "30000", encoderHevc = "OMX.qcom.encoder.hevc")
        assertEquals(SettingsDraft.from(current), SettingsDraft.reconcile(null, null, current))
        assertEquals(SettingsDraft.from(current), SettingsDraft.reconcile(SettingsDraft.from(old), old, current))
    }
    @Test fun revertingAnEditAllowsSubsequentRefresh() {
        val old = ConfigResponse()
        val draft = SettingsDraft.from(old).withValue("max_stream_fps", "60").withValue("max_stream_fps", "240")
        val current = old.copy(minBitrate = "6000")
        assertEquals(SettingsDraft.from(current), SettingsDraft.reconcile(draft, old, current))
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
