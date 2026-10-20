package io.shayne.fogvisitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSamplePolicyTest {
    @Test fun batchUsesSampleIntervals() {
        val times = listOf(10_000L, 20_000L, 30_000L)
        times.zipWithNext().forEach { (previous, current) ->
            assertTrue(LocationSamplePolicy.isNewSample(current, previous))
            assertFalse(LocationSamplePolicy.isTooFast(100f, current - previous, 80f))
        }
    }

    @Test fun ignoresDuplicateAndOutOfOrderSamples() {
        assertFalse(LocationSamplePolicy.isNewSample(10_000L, 10_000L))
        assertFalse(LocationSamplePolicy.isNewSample(9_000L, 10_000L))
    }

    @Test fun filtersShortIntervalJumpsToo() {
        assertTrue(LocationSamplePolicy.isTooFast(300f, 1_000L, 80f))
        assertTrue(LocationSamplePolicy.isTooFast(1f, 0L, 80f))
        assertFalse(LocationSamplePolicy.isTooFast(80f, 1_000L, 80f))
        assertFalse(LocationSamplePolicy.isTooFast(200f, 1_000L, 250f))
    }

    @Test fun preservesTwoMinuteOneKilometerBoundaries() {
        assertFalse(LocationSamplePolicy.breaksSegment(1_000f, 120_000L))
        assertTrue(LocationSamplePolicy.breaksSegment(1_001f, 10_000L))
        assertTrue(LocationSamplePolicy.breaksSegment(1f, 120_001L))
    }

    @Test fun preventsCrossMidnightMerges() {
        assertTrue(LocationSamplePolicy.sameUtcDay(0L, 86_399_999L))
        assertFalse(LocationSamplePolicy.sameUtcDay(86_399_999L, 86_400_000L))
        assertFalse(LocationSamplePolicy.sameUtcDay(-1L, 0L))
    }
}
