package com.alkaidlab.sunshine.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerNavigationSettingsTest {
    private val component = "sunshine/Navigation"
    private val services = ControllerNavigationSettings.SERVICES
    private val enabled = ControllerNavigationSettings.ENABLED
    private val owned = ControllerNavigationSettings.OWNED
    private fun adapter(settings: MutableMap<String, String>, failKey: String? = null) =
        ControllerNavigationSettings(component, { it == component }, { settings[it] }) { key, value ->
            check(key != failKey) { "write failed" }
            settings[key] = value
        }

    @Test fun startingAndStoppingPreservesOtherServicesIncludingNewEntries() {
        val settings = mutableMapOf(services to "reader/First", enabled to "1")
        adapter(settings).setEnabled(true)
        assertEquals("reader/First:$component", settings[services])
        settings[services] = "${settings[services]}:reader/AddedWhileRunning"
        adapter(settings).setEnabled(false)
        assertEquals("reader/First:reader/AddedWhileRunning", settings[services])
        assertEquals("1", settings[enabled])
        assertEquals("0", settings[owned])
    }

    @Test fun existingUserEnabledServiceIsNeverRemovedOnStop() {
        val settings = mutableMapOf(services to component, enabled to "1")
        val adapter = adapter(settings)
        adapter.setEnabled(true)
        adapter.setEnabled(false)
        assertEquals(component, settings[services])
        assertEquals("1", settings[enabled])
        assertFalse(settings.containsKey(owned))
    }

    @Test fun entryAlreadyPresentStillEnablesMasterSwitch() {
        val settings = mutableMapOf(services to component, enabled to "0")
        adapter(settings).setEnabled(true)
        assertEquals("1", settings[enabled])
        assertEquals(component, settings[services])
    }

    @Test fun processRestartRetainsOwnershipAndRestoresEmptyServiceList() {
        val settings = mutableMapOf(services to "", enabled to "0")
        adapter(settings).setEnabled(true)
        adapter(settings).setEnabled(false)
        assertEquals("", settings[services])
        assertEquals("0", settings[enabled])
        assertEquals("0", settings[owned])
    }

    @Test fun failedEnableCanBeCleanedUpByNewProcess() {
        val settings = mutableMapOf(services to "", enabled to "0")
        assertTrue(runCatching { adapter(settings, enabled).setEnabled(true) }.isFailure)
        assertEquals("1", settings[owned])
        assertEquals(component, settings[services])
        adapter(settings).setEnabled(false)
        assertEquals("", settings[services])
        assertEquals("0", settings[owned])
    }

    @Test fun failedRemovalRetainsOwnershipForRetry() {
        val settings = mutableMapOf(services to component, enabled to "1", owned to "1")
        assertTrue(runCatching { adapter(settings, services).setEnabled(false) }.isFailure)
        assertEquals("1", settings[owned])
        adapter(settings).setEnabled(false)
        assertEquals("", settings[services])
        assertEquals("0", settings[owned])
    }
}
