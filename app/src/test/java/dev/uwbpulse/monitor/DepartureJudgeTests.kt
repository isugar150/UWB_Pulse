package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.DepartureJudge
import dev.uwbpulse.monitor.domain.PresencePolicy
import org.junit.Assert.*
import org.junit.Test

/** Timelines of one car window. Times are milliseconds after the car link dropped. */
class DepartureJudgeTests {
    @Test fun neverMeasuredTimeoutClosesTheWindowWithoutASwitchSignalByDefault() {
        assertFalse(dev.uwbpulse.monitor.data.MonitorPreferences().carUnmeasuredDeparture)
        val judge = DepartureJudge(0)
        assertTrue(judge.lost(PresencePolicy.NEVER_MEASURED_MILLIS,true))
        assertFalse(judge.shouldSignalDeparture(PresencePolicy.NEVER_MEASURED_MILLIS))
        assertFalse(judge.shouldSignalDeparture(10 * PresencePolicy.NEVER_MEASURED_MILLIS))
    }
    @Test fun unmeasuredSignalRequiresOptInAndCanBeTurnedOffAgain() {
        val judge = DepartureJudge(0)
        assertTrue(judge.shouldSignalDeparture(PresencePolicy.NEVER_MEASURED_MILLIS,onNeverMeasured=true))
        assertFalse(judge.shouldSignalDeparture(PresencePolicy.NEVER_MEASURED_MILLIS,onNeverMeasured=false))
    }
    @Test fun anEarlyConnectionFailureDoesNotBypassTheOneMinuteLimitEvenWhenEnabled() {
        val judge = DepartureJudge(10_000)
        assertFalse(judge.shouldSignalDeparture(11_000,onNeverMeasured=true))
        assertFalse(judge.shouldSignalDeparture(10_000 + PresencePolicy.NEVER_MEASURED_MILLIS - 1,onNeverMeasured=true))
    }
    @Test fun disablingTheUnmeasuredSignalPreservesTheSeenThenLostRule() {
        val judge = DepartureJudge(0).also { it.measurement(2_000) }
        val at = 2_000 + PresencePolicy.SEEN_THEN_LOST_MILLIS
        assertTrue(judge.lost(at,true))
        assertTrue(judge.shouldSignalDeparture(at,onNeverMeasured=false))
    }
    private val seen = PresencePolicy.SEEN_THEN_LOST_MILLIS
    private val never = PresencePolicy.NEVER_MEASURED_MILLIS
    private fun DepartureJudge.firstLostTick(from: Long, until: Long, bluetoothOn: Boolean = true): Long? =
        (from..until step 1_000).firstOrNull { lost(it, bluetoothOn) }

    @Test fun tagNeverFoundLocksAtTheNeverMeasuredLimit() {
        val judge = DepartureJudge(0)
        assertEquals(never, judge.firstLostTick(0, 3 * never))
    }
    @Test fun tagFoundNearThenDoorClosesLocksAfterTheShortWaitCountedFromTheLastDistance() {
        val judge = DepartureJudge(0)
        val times = (6_000L..40_000L step 600).toList()           // measured until the door closes at about 40 s
        times.forEach { judge.measurement(it) }
        val last = times.last()
        assertFalse(judge.lost(last + seen - 1, true))
        assertEquals(last + seen, (last..last + 2 * seen step 100).first { judge.lost(it, true) })
    }
    @Test fun aMeasurementResetsTheClockSoSittingInTheCarNeverLocks() {
        val judge = DepartureJudge(0)
        for (t in 0L..600_000L step 600) { judge.measurement(t); assertFalse(judge.lost(t + 1, true)) }
    }
    @Test fun aMeasurementJustBeforeTheNeverMeasuredLimitChangesTheRuleToTheShortOne() {
        val judge = DepartureJudge(0)
        judge.measurement(never - 2_000)
        assertFalse(judge.lost(never - 2_000 + seen - 1, true))
        assertTrue(judge.lost(never - 2_000 + seen, true))
    }
    @Test fun bluetoothSwitchedOffByTheUserNeverLocksHoweverLongTheSilence() {
        assertNull(DepartureJudge(0).firstLostTick(0, 10 * never, bluetoothOn = false))
        val seenFirst = DepartureJudge(0).also { it.measurement(1_000) }
        assertNull(seenFirst.firstLostTick(1_000, 10 * never, bluetoothOn = false))
    }
    @Test fun bluetoothComingBackOnLocksAtOnceWhenTheSilenceIsAlreadyLongEnough() {
        val judge = DepartureJudge(0)
        assertFalse(judge.lost(5 * never, false))
        assertTrue(judge.lost(5 * never, true))
    }
    @Test fun aClockThatGoesBackwardsCountsAsNoSilence() {
        val judge = DepartureJudge(10_000)
        assertEquals(0, judge.silentMillis(5_000))
        assertFalse(judge.lost(5_000, true))
    }
}
