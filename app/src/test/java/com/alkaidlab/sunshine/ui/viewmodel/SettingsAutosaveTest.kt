package com.alkaidlab.sunshine.ui.viewmodel

import com.alkaidlab.sunshine.data.ConfigResponse
import org.junit.Assert.*
import org.junit.Test

class SettingsAutosaveTest {
    private fun state() = SettingsAutosave().apply { refreshed(ConfigResponse()) }

    @Test fun numberWaitsForEditingToFinish() {
        val state = state()
        state.edit("max_stream_fps", "60")
        assertNull(state.nextBatch())
        state.finishEdit("max_stream_fps")
        assertEquals(mapOf("max_stream_fps" to "60"), state.nextBatch()!!.values)
    }

    @Test fun switchDoesNotSubmitAnotherFieldStillBeingTyped() {
        val state = state()
        state.edit("max_stream_fps", "120")
        state.edit("auto_rotate", "false")
        assertEquals(mapOf("auto_rotate" to "false"), state.nextBatch()!!.values)
    }

    @Test fun revertingDuringRequestSurvivesAcknowledgement() {
        val state = state()
        state.edit("auto_rotate", "false")
        val first = state.nextBatch()!!
        state.edit("auto_rotate", "true")
        val after = state.acknowledged(first, ConfigResponse(autoRotate = "false"))
        assertEquals("true", after.draft!!["auto_rotate"])
        assertEquals(mapOf("auto_rotate" to "true"), state.nextBatch()!!.values)
    }

    @Test fun secondNumberEditNeedsItsOwnCompletionEvenAfterOldSaveReturns() {
        val state = state()
        state.edit("max_stream_fps", "60")
        state.finishEdit("max_stream_fps")
        val first = state.nextBatch()!!
        state.edit("max_stream_fps", "120")
        state.acknowledged(first, ConfigResponse(maxStreamFps = "60"))
        assertNull(state.nextBatch())
        state.finishEdit("max_stream_fps")
        assertEquals("120", state.nextBatch()!!.values["max_stream_fps"])
    }

    @Test fun pollingPreservesEditingAndUpdatesUntouchedFields() {
        val state = state()
        state.edit("max_stream_fps", "12")
        val refreshed = state.refreshed(ConfigResponse(encoderH264 = "OMX.other.encoder"))
        assertEquals("12", refreshed.draft!!["max_stream_fps"])
        assertEquals("OMX.other.encoder", refreshed.draft!!["encoder_h264"])
    }

    @Test fun invalidNumberDoesNotBlockIndependentSwitch() {
        val state = state()
        state.edit("max_stream_fps", "1")
        state.finishEdit("max_stream_fps")
        state.edit("repeat_frames", "false")
        assertEquals(mapOf("repeat_frames" to "false"), state.nextBatch()!!.values)
    }

    @Test fun bitratePairCannotBePartiallySavedToInvalidRange() {
        val state = state()
        state.edit("min_bitrate", "60000")
        state.finishEdit("min_bitrate")
        assertNull(state.nextBatch())
        state.edit("max_bitrate", "90000")
        assertNull(state.nextBatch())
        state.finishEdit("max_bitrate")
        assertEquals(mapOf("min_bitrate" to "60000", "max_bitrate" to "90000"), state.nextBatch()!!.values)
    }

    @Test fun failedRequestCanRetryAndSuccessfulRequestBecomesClean() {
        val state = state()
        state.edit("auto_scale", "false")
        val first = state.nextBatch()!!
        assertEquals(first, state.nextBatch())
        state.acknowledged(first, ConfigResponse(autoScale = "false"))
        assertFalse(state.hasUnsavedEdits())
        assertNull(state.nextBatch())
    }
}
