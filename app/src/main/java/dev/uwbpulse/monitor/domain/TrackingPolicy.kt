package dev.uwbpulse.monitor.domain

/**
 * UWB ranging round interval. The intervals are the Android Ranging unicast values
 * (FREQUENT 120 ms, NORMAL 240 ms, INFREQUENT 600 ms) and are also written into the tag's OOB configuration.
 * SAVER is the default: the tag's UWB radio is its main battery cost, and a car-lock decision does not need 4 rounds a
 * second. NORMAL is the behaviour before this setting existed and stays selectable.
 */
enum class TrackingRate(val intervalMillis: Int, val platformRate: Int) {
    FAST(120, 3),
    NORMAL(240, 1),
    SAVER(600, 2);

    /** Gap between samples after which a pending exit debounce restarts. */
    val maxGapMillis: Long get() = maxOf(1_500L, intervalMillis * 3L)

    /** Age after which a reading no longer counts as a current distance. */
    val staleAfterMillis: Long get() = maxOf(3_000L, intervalMillis * 5L)

    companion object {
        val DEFAULT = SAVER
        fun parse(name: String?): TrackingRate = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** How quickly the service searches for its tag again after the link or the UWB reports were lost. */
object RescanPolicy {
    const val DEFAULT_MAX_MILLIS = 60_000L
    val choicesMillis = listOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    private val ladderMillis = longArrayOf(2_000, 5_000, 15_000, 60_000)

    fun coerce(value: Long): Long = value.coerceIn(5_000L, 600_000L)

    /** Keep initial acquisition fast; reduce scan duty only during a sustained hidden reconnect. */
    fun balancedScan(attempt: Int, activityVisible: Boolean) = attempt >= 3 && !activityVisible

    /** Waits 2, 5, 15, 60 s and then [maxMillis]; every step is capped by [maxMillis]. */
    fun delayMillis(attempt: Int, maxMillis: Long): Long {
        val cap = coerce(maxMillis)
        val step = if (attempt < ladderMillis.size) ladderMillis[attempt.coerceAtLeast(0)] else cap
        return step.coerceAtMost(cap)
    }
}
