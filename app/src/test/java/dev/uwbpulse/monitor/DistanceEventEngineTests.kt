package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.*
import org.junit.Assert.*
import org.junit.Test

class DistanceEventEngineTests {
    @Test fun restoredAwayStateRequiresAnActualReturnBeforeAnotherExit() {
        val engine = DistanceEventEngine(initialAway=true)
        assertNull(engine.sample(8.0,0));assertNull(engine.sample(8.0,4000))
        assertEquals(MonitorEventType.TAG_RETURNED,engine.sample(3.9,4500))
        assertNull(engine.sample(8.0,5000))
    }
    @Test fun sustainedExitFiresOnceAndOnlyRearmsAfterReturn() {
        val engine = DistanceEventEngine()
        for (time in 0L until 3000 step 500) assertNull(engine.sample(5.8,time))
        assertEquals(MonitorEventType.TAG_DISTANCE_EXCEEDED,engine.sample(5.8,3000))
        assertNull(engine.sample(6.0,3500)); assertNull(engine.sample(4.5,4000))
        assertEquals(MonitorEventType.TAG_RETURNED,engine.sample(4.0,4500))
        assertNull(engine.sample(4.0,5000))
    }
    @Test fun equalityAtThresholdBreaksPendingExit() {
        val engine = DistanceEventEngine()
        assertNull(engine.sample(6.0,0));assertNull(engine.sample(6.0,1000));assertNull(engine.sample(5.0,2000))
        for (time in 3000L until 6000 step 500) assertNull(engine.sample(6.0,time))
        assertEquals(MonitorEventType.TAG_DISTANCE_EXCEEDED,engine.sample(6.0,6000))
    }
    @Test fun gapsCannotAccumulateDwellTime() {
        val engine = DistanceEventEngine()
        assertNull(engine.sample(6.0,0));assertNull(engine.sample(6.0,1000))
        assertNull(engine.sample(6.0,4000))
        for (time in 4500L until 7000 step 500) assertNull(engine.sample(6.0,time))
        assertEquals(MonitorEventType.TAG_DISTANCE_EXCEEDED,engine.sample(6.0,7000))
    }
    @Test fun disconnectNeverCreatesAnExitAndPreservesExistingAwayLatch() {
        val engine = DistanceEventEngine(DistancePolicy(dwellMillis=500))
        engine.sample(6.0,0);engine.signalLost();assertFalse(engine.away)
        assertNull(engine.sample(6.0,10000))
        assertEquals(MonitorEventType.TAG_DISTANCE_EXCEEDED,engine.sample(6.0,10500))
        engine.signalLost();assertTrue(engine.away)
        assertNull(engine.sample(7.0,20000))
        assertEquals(MonitorEventType.TAG_RETURNED,engine.sample(3.9,20500))
    }
    @Test fun invalidSamplesAndPolicyChangesResetDebounce() {
        val engine = DistanceEventEngine(DistancePolicy(dwellMillis=500))
        engine.sample(6.0,0);assertNull(engine.sample(Double.NaN,100));assertNull(engine.sample(6.0,500))
        engine.configure(DistancePolicy(7.0,6.0,500))
        assertNull(engine.sample(8.0,1000))
        assertEquals(MonitorEventType.TAG_DISTANCE_EXCEEDED,engine.sample(8.0,1500))
    }
    @Test fun outOfOrderReportsDoNotChangeState() {
        val engine = DistanceEventEngine(DistancePolicy(dwellMillis=500))
        assertNull(engine.sample(6.0,1000));assertNull(engine.sample(6.0,900));assertNull(engine.sample(6.0,1000))
        assertEquals(MonitorEventType.TAG_DISTANCE_EXCEEDED,engine.sample(6.0,1500))
    }
    @Test(expected=IllegalArgumentException::class) fun invalidHysteresisRejected() { DistancePolicy(5.0,5.0) }
}
