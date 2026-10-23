package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class ManagerBootstrapPolicyTest {
    private val shizuku = "moe.shizuku.privileged.api"
    private val stellar = "roro.stellar.manager"
    private val installed = mapOf(shizuku to listOf("/data/app/shizuku/base.apk"),
        stellar to listOf("/data/app/stellar/base.apk", "/data/app/stellar/split.apk"))

    @Test fun selectsActualServerApkWhenBothManagersAreInstalled() {
        assertEquals(shizuku, ManagerBootstrapPolicy.activeManager("/data/app/shizuku/base.apk\n", installed))
        assertEquals(stellar, ManagerBootstrapPolicy.activeManager("/other.jar:/data/app/stellar/split.apk", installed))
    }
    @Test fun skipsUnknownAmbiguousAndSubstringMatches() {
        assertNull(ManagerBootstrapPolicy.activeManager("/data/app/shizuku/base.apk.old", installed))
        assertNull(ManagerBootstrapPolicy.activeManager("/data/app/custom/base.apk", installed))
        assertNull(ManagerBootstrapPolicy.activeManager("/data/app/shizuku/base.apk:/data/app/stellar/base.apk", installed))
        assertNull(ManagerBootstrapPolicy.activeManager("", installed))
    }
    @Test fun doesNotAllowArbitraryPackageEvenWithMatchingApk() {
        assertNull(ManagerBootstrapPolicy.activeManager("/data/app/custom/base.apk",
            mapOf("com.other.app" to listOf("/data/app/custom/base.apk"))))
    }
    @Test fun onlyExplicitRestrictionsNeedRecovery() {
        val op = "RUN_ANY_IN_BACKGROUND"
        assertTrue(ManagerBootstrapPolicy.restricted("$op: ignore; rejectTime=now", op))
        assertTrue(ManagerBootstrapPolicy.restricted("Uid mode: $op: deny", op))
        assertTrue(ManagerBootstrapPolicy.restricted("No operations.\nDefault mode: ignore", op))
        assertFalse(ManagerBootstrapPolicy.restricted("No operations.\nDefault mode: allow", op))
        assertFalse(ManagerBootstrapPolicy.restricted("RUN_IN_BACKGROUND: ignore", op))
        assertFalse(ManagerBootstrapPolicy.restricted("Unknown operation", op))
    }
    @Test fun batteryExemptionRequiresExactPackageAndFullIdleScope() {
        assertTrue(ManagerBootstrapPolicy.whitelisted("user,$shizuku,10509", shizuku))
        assertTrue(ManagerBootstrapPolicy.whitelisted("system,$shizuku,10509", shizuku))
        assertFalse(ManagerBootstrapPolicy.whitelisted("user,$shizuku.fake,10509", shizuku))
        assertFalse(ManagerBootstrapPolicy.whitelisted("system-excidle,$shizuku,10509", shizuku))
        assertFalse(ManagerBootstrapPolicy.whitelisted("Error: $shizuku", shizuku))
    }
}
