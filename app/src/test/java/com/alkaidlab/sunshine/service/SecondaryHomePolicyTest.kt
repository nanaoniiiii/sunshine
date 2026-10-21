package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class SecondaryHomePolicyTest {
    private val sunshine = "sunshine/Desktop"
    private val homes = setOf("oem/SecondaryHome", "samsung/DeXHome")
    @Test fun primaryHomeIsNeverReplaced() {
        assertFalse(SecondaryHomePolicy.restore(0, "samsung/DeXHome", sunshine, homes, true))
        assertFalse(SecondaryHomePolicy.restore(0, null, sunshine, homes, false))
    }
    @Test fun otherAppsAndPermissionActivitiesKeepFocus() {
        for (app in listOf("browser/Main", "settings/Main", "permission/Grant"))
            assertFalse(SecondaryHomePolicy.restore(2, app, sunshine, homes, false))
    }
    @Test fun oemHomeAndEmptyStackReturnToSunshine() {
        for (home in homes) assertTrue(SecondaryHomePolicy.restore(2, home, sunshine, homes, false))
        assertTrue(SecondaryHomePolicy.restore(2, null, sunshine, homes, false))
    }
    @Test fun unknownOemHomeTaskStillReturnsToSunshine() {
        assertTrue(SecondaryHomePolicy.restore(2, "vendor/OtherHome", sunshine, homes, true))
    }
    @Test fun ownDesktopIsNotRestarted() {
        assertFalse(SecondaryHomePolicy.restore(2, sunshine, sunshine, homes, true))
    }
}
