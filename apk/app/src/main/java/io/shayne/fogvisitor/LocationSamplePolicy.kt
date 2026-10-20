package io.shayne.fogvisitor

/** Use sample time, never callback arrival time (Android can deliver a whole batch at once). */
internal object LocationSamplePolicy {
    const val MAX_GAP_MS = 120_000L
    const val MAX_GAP_METERS = 1_000f

    fun isNewSample(sampleAt: Long, previousAt: Long): Boolean = sampleAt > previousAt

    fun sameUtcDay(previousAt: Long, sampleAt: Long): Boolean =
        Math.floorDiv(previousAt, 86_400_000L) == Math.floorDiv(sampleAt, 86_400_000L)

    fun isTooFast(distanceMeters: Float, elapsedMs: Long, limitMps: Float): Boolean =
        elapsedMs <= 0 || distanceMeters / (elapsedMs.toFloat() / 1000f) > limitMps

    fun breaksSegment(distanceMeters: Float, elapsedMs: Long): Boolean =
        elapsedMs > MAX_GAP_MS || distanceMeters > MAX_GAP_METERS
}
