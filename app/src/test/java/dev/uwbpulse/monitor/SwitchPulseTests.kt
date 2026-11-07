package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.SwitchPulse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SwitchPulseTests {
    @Test fun departureStateFollowsTheSetting() {
        assertEquals("pulse", SwitchPulse.departureState(true))
        assertEquals("off", SwitchPulse.departureState(false))
    }
    @Test fun pulseSendsOnThenWaitsThenOffAndReturnsTheFinalStatus() = runBlocking {
        val calls = mutableListOf<String>()
        val status = SwitchPulse.run({ calls += "send:$it"; 200 }, { calls += "gap" })
        assertEquals(listOf("send:on", "gap", "send:off"), calls)
        assertEquals(200, status)
    }
    @Test fun aFailedOnStopsThePulseBeforeOffAndReportsThatStatus() = runBlocking {
        val calls = mutableListOf<String>()
        val status = SwitchPulse.run({ calls += it; 503 }, { calls += "gap" })
        assertEquals(listOf("on"), calls)
        assertEquals(503, status)
    }
    @Test fun aDroppedOnMeansNothingWasSent() = runBlocking {
        val calls = mutableListOf<String>()
        assertNull(SwitchPulse.run({ calls += it; null }, { calls += "gap" }))
        assertEquals(listOf("on"), calls)
    }
    @Test fun aDroppedOffAfterAnOnIsReportedAsDropped() = runBlocking {
        val status = SwitchPulse.run({ if (it == "on") 200 else null }, {})
        assertNull(status)
    }
    @Test fun gapIsShortEnoughToFitTheDeliveryLifetime() {
        assertTrue(SwitchPulse.GAP_MILLIS in 500..5_000)
        assertTrue(SwitchPulse.GAP_MILLIS * 4 < dev.uwbpulse.monitor.domain.SwitchDeliveryPolicy.TTL_MILLIS)
    }
}
