package com.alkaidlab.sunshine.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungDexPolicyTest {
    private fun route(samsung: Boolean = true, active: Boolean = false, external: Boolean = false, pc: Boolean = false, standalone: Boolean = false) =
        SamsungDexPolicy.route(samsung, active, external, pc, standalone)
    @Test fun existingDesktopIsPreservedEvenWhenPcPackageIsAbsent() {
        assertEquals(SamsungDexPolicy.Route.EXISTING, route(active = true))
    }
    @Test fun physicalDisplayDoesNotStartALocalReceiverOrTabletTransition() {
        assertEquals(SamsungDexPolicy.Route.EXISTING, route(external = true, pc = true, standalone = true))
    }
    @Test fun compatiblePhoneUsesPcReceiverBeforeStandalone() {
        assertEquals(SamsungDexPolicy.Route.LOCAL_PC, route(pc = true, standalone = true))
    }
    @Test fun tabletWithoutPcCanUseItsActualStandaloneCapability() {
        assertEquals(SamsungDexPolicy.Route.STANDALONE, route(standalone = true))
    }
    @Test fun samsungWithoutNativeEntryRemainsUsableWithoutClaimingDex() {
        assertEquals(SamsungDexPolicy.Route.COMPATIBLE, route())
    }
    @Test fun anotherManufacturerNeverGetsSamsungNativeTransitions() {
        assertEquals(SamsungDexPolicy.Route.COMPATIBLE, route(samsung = false, active = true, pc = true, standalone = true))
    }
}
