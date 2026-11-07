package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionRecoveryTests {
    @Test fun newSessionDoesNotInheritAStaleDeadlineAndRetainsCounters() {
        val evidence = ReceptionEvidence()
        evidence.accept(2.0,1_000,false,false)
        assertEquals(61_000L,evidence.age(62_000))
        evidence.beginSession()
        assertNull(evidence.age(62_000)); assertFalse(evidence.isFresh(62_000))
        assertEquals(1L,evidence.count); assertEquals(1L,evidence.backgroundCount); assertEquals(1L,evidence.screenOffCount)
        assertTrue(evidence.accept(2.0,65_000,false,false)) // First callback after 4 s is still accepted.
        assertTrue(evidence.isFresh(65_000)); assertEquals(2L,evidence.count)
    }
    @Test fun newSessionStillDetectsStalenessAfterItsOwnFirstReport() {
        val evidence = ReceptionEvidence()
        evidence.accept(2.0,1_000,false,false); evidence.beginSession()
        evidence.accept(2.0,65_000,false,false)
        assertTrue(evidence.isFresh(68_000)); assertFalse(evidence.isFresh(68_001))
    }
    @Test fun authenticationTimeoutsRetryWithoutImmediateAutomation() {
        for (code in listOf("TAG_NONCE_TIMEOUT","TAG_PROOF_TIMEOUT")) {
            val gate = OutageGate()
            assertEquals(OutageGate.Decision(false,false,true),gate.failed(code,true,0))
            assertEquals(OutageGate.Decision(true,false,true),gate.failed(code,true,120_000))
        }
    }
    @Test fun operationTimeoutBecomesRetryableConnectionError() = runBlocking {
        val failure = try {
            connectionTimeout(5,"UWB_CAPABILITIES_TIMEOUT") { awaitCancellation() }
        } catch (e: IllegalStateException) { e.message }
        assertEquals("UWB_CAPABILITIES_TIMEOUT",failure)
        assertTrue(FailurePolicy.isRetryable(failure!!))
    }
    @Test fun cancellationFromCallerRemainsCancellation() = runBlocking {
        try {
            withTimeout(5) { connectionTimeout(10_000,"UWB_CAPABILITIES_TIMEOUT") { awaitCancellation() } }
            fail("caller timeout did not propagate")
        } catch (_: TimeoutCancellationException) { /* expected */ }
    }
    @Test fun successfulCapabilityQueryIsReturned() = runBlocking {
        assertEquals("enabled",connectionTimeout(1_000,"UWB_CAPABILITIES_TIMEOUT") { "enabled" })
    }
    @Test fun balancedScanOnlyStartsAfterSustainedHiddenFailures() {
        for (attempt in 0..2) assertFalse(RescanPolicy.balancedScan(attempt,false))
        assertTrue(RescanPolicy.balancedScan(3,false))
        assertTrue(RescanPolicy.balancedScan(4,false))
        assertFalse(RescanPolicy.balancedScan(4,true))
    }
}
