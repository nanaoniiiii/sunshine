package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class ConfigRequestTest {
    @Test fun acceptsOldStringAndScalarSettings() {
        assertEquals(mapOf("max_bitrate" to "4000", "repeat_frames" to "false"),
            ConfigRequest.parse("{\"max_bitrate\":4000,\"repeat_frames\":false}"))
        assertEquals("5000", ConfigRequest.parse("{\"min_bitrate\":\"5000\"}")["min_bitrate"])
    }
    @Test fun malformedRequestsDoNotReachConfigWriter() {
        for (json in listOf("null", "[]", "{\"max_bitrate\":null}", "{\"max_bitrate\":{}}")) {
            try { ConfigRequest.parse(json); fail(json) } catch (_: IllegalArgumentException) {}
        }
    }
}
