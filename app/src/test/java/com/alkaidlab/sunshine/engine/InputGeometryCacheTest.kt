package com.alkaidlab.sunshine.engine

import org.junit.Assert.*
import org.junit.Test

class InputGeometryCacheTest {
    @Test fun movementBurstReadsGeometryOnceUntilDisplayChanges() {
        val cache = InputGeometryCache(); var reads = 0
        repeat(1000) { cache.refresh { reads++ } }; assertEquals(1, reads)
        cache.displayChanged(0); cache.refresh { reads++ }; assertEquals(2, reads)
    }
    @Test fun unrelatedDisplaysCannotCauseRepeatedInputQueries() {
        val cache = InputGeometryCache(); cache.sourceChanged(37); var reads = 0
        cache.refresh { reads++ }
        repeat(100) { cache.displayChanged(0); cache.refresh { reads++ } }
        assertEquals(1, reads)
        cache.displayChanged(37); cache.refresh { reads++ }; assertEquals(2, reads)
    }
    @Test fun dexToPrimaryTransitionRefreshesEvenWhenDisplayIdStaysZero() {
        val cache = InputGeometryCache(); var size = 0
        cache.refresh { size = 2800 }; cache.sourceChanged(0); cache.refresh { size = 1440 }
        assertEquals(1440, size)
    }
    @Test fun failedReadRemainsDirtyForRetry() {
        val cache = InputGeometryCache()
        try { cache.refresh { error("display not ready") }; fail() } catch (_: IllegalStateException) { }
        var recovered = false; cache.refresh { recovered = true }; assertTrue(recovered)
    }
}
