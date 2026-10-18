package dev.atlas.coach.focus

/** Wall time is UTC epoch milliseconds; monotonic time is used only for elapsed durations. */
interface Clock {
    fun nowMillis(): Long
    fun monotonicMillis(): Long
}

/** A controllable clock suitable for a local demonstration and deterministic unit tests. */
class FakeClock(
    startUtcMillis: Long = 0L,
    startMonotonicMillis: Long = 0L,
) : Clock {
    private var utcMillis = startUtcMillis
    private var elapsedMillis = startMonotonicMillis

    override fun nowMillis(): Long = utcMillis

    override fun monotonicMillis(): Long = elapsedMillis

    /** Advances wall and monotonic time together. */
    fun advanceMillis(millis: Long) {
        require(millis >= 0L) { "millis must be non-negative" }
        utcMillis += millis
        elapsedMillis += millis
    }

    /** Moves wall time only. A negative value can model a clock correction. */
    fun advanceWallMillis(millis: Long) {
        utcMillis += millis
    }

    /** Sets wall time without affecting elapsed-time measurements. */
    fun setWallMillis(millis: Long) {
        utcMillis = millis
    }

    /** Advances monotonic time only. */
    fun advanceMonotonicMillis(millis: Long) {
        require(millis >= 0L) { "millis must be non-negative" }
        elapsedMillis += millis
    }

    /** Sets monotonic time to model a process/clock discontinuity in deterministic tests. */
    fun setMonotonicMillis(millis: Long) {
        require(millis >= 0L) { "millis must be non-negative" }
        elapsedMillis = millis
    }
}
