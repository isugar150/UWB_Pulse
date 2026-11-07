package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.data.SmartTagSessionConfig
import dev.uwbpulse.monitor.domain.*
import org.junit.Assert.*
import org.junit.Test

class TrackingPolicyTests {
    @Test fun unsetRateIsTheSaverAndAStoredChoiceIsKept() {
        assertEquals(TrackingRate.SAVER, TrackingRate.DEFAULT)
        assertEquals(TrackingRate.SAVER, TrackingRate.parse(null))
        assertEquals(TrackingRate.SAVER, TrackingRate.parse("unknown"))
        assertEquals(TrackingRate.NORMAL, TrackingRate.parse("NORMAL")) // an earlier explicit choice is not overridden
        assertEquals(TrackingRate.FAST, TrackingRate.parse("FAST"))
        assertEquals(600, TrackingRate.DEFAULT.intervalMillis)
    }
    @Test fun normalRateKeepsThePreviousBehaviour() {
        assertEquals(240, TrackingRate.NORMAL.intervalMillis)
        assertEquals(1_500L, TrackingRate.NORMAL.maxGapMillis)
        assertEquals(3_000L, TrackingRate.NORMAL.staleAfterMillis)
        assertEquals(setOf(1, 2, 3), TrackingRate.entries.map { it.platformRate }.toSet())
    }
    @Test fun slowerRateWidensGapAndStalenessLimits() {
        assertTrue(TrackingRate.SAVER.maxGapMillis > TrackingRate.NORMAL.maxGapMillis)
        assertTrue(TrackingRate.SAVER.staleAfterMillis >= 5L * TrackingRate.SAVER.intervalMillis)
        assertEquals(TrackingRate.NORMAL.maxGapMillis, TrackingRate.FAST.maxGapMillis)
    }
    @Test fun slowRateDoesNotRestartDebounceOnAnOrdinaryMissedRound() {
        fun fires(rate: TrackingRate): Boolean {
            val engine = DistanceEventEngine(DistancePolicy(dwellMillis = 3_000), rate.maxGapMillis)
            var fired = false
            // Two rounds are missed after 600 ms: the gap between neighbouring samples is 1,800 ms.
            for (time in listOf(0L, 600, 2_400, 3_000, 3_600)) if (engine.sample(6.0, time) != null) fired = true
            return fired
        }
        assertTrue(fires(TrackingRate.SAVER))
        assertFalse(fires(TrackingRate.NORMAL))
    }
    @Test fun rescanLadderAtDefaultMatchesThePreviousBackoff() {
        val delays = (0..6).map { RescanPolicy.delayMillis(it, RescanPolicy.DEFAULT_MAX_MILLIS) }
        assertEquals(listOf(2_000L, 5_000, 15_000, 60_000, 60_000, 60_000, 60_000), delays)
    }
    @Test fun rescanMaximumCapsOrExtendsTheLadder() {
        assertEquals(listOf(2_000L, 5_000, 10_000, 10_000, 10_000), (0..4).map { RescanPolicy.delayMillis(it, 10_000) })
        assertEquals(listOf(2_000L, 5_000, 15_000, 60_000, 300_000, 300_000), (0..5).map { RescanPolicy.delayMillis(it, 300_000) })
        assertEquals(5_000L, RescanPolicy.coerce(1))
        assertEquals(600_000L, RescanPolicy.coerce(Long.MAX_VALUE))
        assertTrue(RescanPolicy.DEFAULT_MAX_MILLIS in RescanPolicy.choicesMillis)
    }
    @Test fun tagConfigurationCarriesOnlyTheRangingIntervalChange() {
        val config = SmartTagSessionConfig(0x01020304, byteArrayOf(0x11, 0x22), 10)
        val local = byteArrayOf(0x33, 0x44)
        val normal = config.payload(local)
        val saver = config.payload(local, TrackingRate.SAVER.intervalMillis)
        assertArrayEquals(normal, config.payload(local, TrackingRate.NORMAL.intervalMillis))
        assertEquals(0x00f0, ((normal[16].toInt() and 255) shl 8) or (normal[17].toInt() and 255))
        assertEquals(600, ((saver[16].toInt() and 255) shl 8) or (saver[17].toInt() and 255))
        for (i in normal.indices) if (i != 16 && i != 17) assertEquals("byte $i", normal[i], saver[i])
    }
    @Test(expected = IllegalArgumentException::class) fun absurdRangingIntervalRejected() {
        SmartTagSessionConfig(1, byteArrayOf(1, 2), 10).payload(byteArrayOf(3, 4), 10)
    }
}
