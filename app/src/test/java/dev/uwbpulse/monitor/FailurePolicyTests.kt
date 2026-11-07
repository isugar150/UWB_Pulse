package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.FailurePolicy
import dev.uwbpulse.monitor.domain.OutageGate
import dev.uwbpulse.monitor.domain.OutageGate.Decision
import org.junit.Assert.*
import org.junit.Test

class FailurePolicyTests {
    private val none = Decision(disconnected = false, error = false)
    private val grace = FailurePolicy.GRACE_MILLIS

    @Test fun transientReconnectFailuresPublishNothing() {
        val gate = OutageGate()
        assertEquals(none, gate.failed("OWNER_TAG_NOT_FOUND", hadSession = false, now = 0))
        assertEquals(none, gate.failed("GATT_CONNECTION_STATUS_147", hadSession = false, now = 70_000))
        gate.recovered()
        // A recovered outage must not count towards the next one.
        assertEquals(none, gate.failed("OWNER_TAG_NOT_FOUND", hadSession = true, now = 200_000))
        assertEquals(none, gate.failed("OWNER_TAG_NOT_FOUND", hadSession = true, now = 200_000 + grace - 1))
    }
    @Test fun lastingLinkLossPublishesOneDisconnectAndNeverAnError() {
        val gate = OutageGate()
        assertEquals(none, gate.failed("UWB_REPORTS_STALE_20S", hadSession = true, now = 0))
        assertEquals(none, gate.failed("OWNER_TAG_NOT_FOUND", hadSession = true, now = grace - 1))
        assertEquals(Decision(disconnected = true, error = false), gate.failed("OWNER_TAG_NOT_FOUND", hadSession = true, now = grace))
        assertEquals(none, gate.failed("OWNER_TAG_NOT_FOUND", hadSession = true, now = 10 * grace))
        gate.recovered()
        assertEquals(none, gate.failed("UWB_REPORTS_STALE_20S", hadSession = true, now = 11 * grace))
        assertEquals(Decision(disconnected = true, error = false), gate.failed("UWB_REPORTS_STALE_20S", hadSession = true, now = 12 * grace))
    }
    @Test fun noDisconnectIsPublishedForATagThatWasNeverConnected() {
        val gate = OutageGate()
        gate.failed("OWNER_TAG_NOT_FOUND", hadSession = false, now = 0)
        assertEquals(none, gate.failed("OWNER_TAG_NOT_FOUND", hadSession = false, now = 5 * grace))
    }
    @Test fun userFixableFailureBecomesAnErrorOnlyAfterTheGracePeriod() {
        val gate = OutageGate()
        assertEquals(none, gate.failed("BLUETOOTH_DISABLED", hadSession = false, now = 0))
        assertEquals(none, gate.failed("BLUETOOTH_DISABLED", hadSession = false, now = grace - 1))
        assertEquals(Decision(disconnected = false, error = true), gate.failed("BLUETOOTH_DISABLED", hadSession = false, now = grace))
        assertEquals(none, gate.failed("BLUETOOTH_DISABLED", hadSession = false, now = 2 * grace))
        gate.recovered()
        assertEquals(none, gate.failed("UWB_DISABLED_OR_UNAVAILABLE", hadSession = false, now = 3 * grace))
        assertTrue(gate.failed("UWB_DISABLED_OR_UNAVAILABLE", hadSession = false, now = 4 * grace).error)
    }
    @Test fun permanentFailuresArePublishedImmediately() {
        val gate = OutageGate()
        assertEquals(Decision(disconnected = true, error = true, retry = false), gate.failed("SAMSUNG_LOGIN_REQUIRED", hadSession = true, now = 0))
        val authFailure = OutageGate()
        assertEquals(Decision(disconnected = false, error = true, retry = false), authFailure.failed("GATT_CONNECTION_STATUS_137", hadSession = false, now = 0))
    }
    @Test fun ownerProofFailureIsRetriedAFewTimesThenBecomesPermanent() {
        val gate = OutageGate()
        repeat(FailurePolicy.MAX_AUTH_RETRIES) { i ->
            assertEquals("attempt $i", none, gate.failed(FailurePolicy.AUTH_FAILED, hadSession = false, now = i * 1_000L))
        }
        assertEquals(Decision(disconnected = false, error = true, retry = false), gate.failed(FailurePolicy.AUTH_FAILED, hadSession = false, now = 10_000))
        // A recovered link starts a fresh budget.
        gate.recovered()
        assertTrue(gate.failed(FailurePolicy.AUTH_FAILED, hadSession = false, now = 20_000).retry)
    }
    @Test fun onlyRealLinkDropsCountAsLinkLoss() {
        listOf("OWNER_TAG_NOT_FOUND", "GATT_CONNECTION_STATUS_147", "GATT_DISCONNECTED_19", "GATT_WRITE_STATUS_132", "GATT_OPERATION_TIMEOUT",
            "GATT_CONNECT_OR_DISCOVERY_TIMEOUT", "UWB_REPORTS_STALE_20S", "NO_UWB_REPORTS_20S")
            .forEach { assertTrue(it, FailurePolicy.isLinkLoss(it)) }
        listOf("GATT_MTU_TOO_SMALL_FOR_ENCRYPTED_UWB", "GATT_DISCOVERY_REJECTED", "GATT_OPERATION_NOT_QUEUED", "UWB_DISABLED_OR_UNAVAILABLE", "BLUETOOTH_DISABLED")
            .forEach { assertFalse(it, FailurePolicy.isLinkLoss(it)) }
        // A persistent non-link GATT problem is reported after the grace period even though it never authenticated.
        val gate = OutageGate()
        assertEquals(none, gate.failed("GATT_MTU_TOO_SMALL_FOR_ENCRYPTED_UWB", hadSession = false, now = 0))
        assertTrue(gate.failed("GATT_MTU_TOO_SMALL_FOR_ENCRYPTED_UWB", hadSession = false, now = grace).error)
    }
    @Test fun retryClassificationIsUnchanged() {
        listOf("OWNER_TAG_NOT_FOUND", "GATT_CONNECTION_STATUS_147", "GATT_DISCONNECTED_19", "UWB_REPORTS_STALE_20S",
            "UWB_DISABLED_OR_UNAVAILABLE", "BLE_SCAN_FAILED_2", "BLUETOOTH_DISABLED", "BLE_SCANNER_UNAVAILABLE", "NO_UWB_REPORTS_20S")
            .forEach { assertTrue(it, FailurePolicy.isRetryable(it)) }
        listOf("GATT_CONNECTION_STATUS_5", "GATT_CONNECTION_STATUS_15", "GATT_CONNECTION_STATUS_137",
            "SAMSUNG_LOGIN_REQUIRED", "NO_UWB_HARDWARE", "SecurityException")
            .forEach { assertFalse(it, FailurePolicy.isRetryable(it)) }
    }
    @Test fun onlyAnActivationRefusalCountsAsTheTagNotStartingUwb() {
        listOf("GATT_WRITE_STATUS_133", "GATT_WRITE_STATUS_5", "GATT_BUSY_RETRIES_EXHAUSTED", "UWB_STOPPED_0", "UWB_OPEN_FAILED_1", "UWB_CLOSED_2")
            .forEach { assertTrue(it, FailurePolicy.isUwbRefused(it)) }
        // No reports can simply mean a closed door or a blocked tag; a lost link is not a refusal either.
        listOf("NO_UWB_REPORTS_20S", "UWB_REPORTS_STALE_20S", "OWNER_TAG_NOT_FOUND", "GATT_CONNECTION_STATUS_147", "GATT_DISCONNECTED_8", "TAG_NONCE_TIMEOUT")
            .forEach { assertFalse(it, FailurePolicy.isUwbRefused(it)) }
    }
    @Test fun aRefusingTagIsRetriedRarely() {
        assertEquals(60_000L, FailurePolicy.backoffAfterRefusals(60_000, 1))
        assertEquals(FailurePolicy.UWB_REFUSED_BACKOFF_MILLIS, FailurePolicy.backoffAfterRefusals(60_000, FailurePolicy.UWB_REFUSALS_BEFORE_NOTICE))
        assertEquals(600_000L, FailurePolicy.backoffAfterRefusals(600_000, 5)) // never shorter than the user's own limit
    }
    @Test fun supervisionTimeoutIsLinkLossButAnAttStatusEightStaysPermanent() {
        // Connection-state status 8 is the link supervision timeout (tag went out of reach mid-operation, e.g. a closing car door).
        assertTrue(FailurePolicy.isRetryable("GATT_CONNECTION_STATUS_8"))
        assertTrue(FailurePolicy.isLinkLoss("GATT_CONNECTION_STATUS_8"))
        val gate = OutageGate()
        assertEquals(none, gate.failed("GATT_CONNECTION_STATUS_8", hadSession = true, now = 0))
        assertEquals(Decision(disconnected = true, error = false), gate.failed("GATT_CONNECTION_STATUS_8", hadSession = true, now = grace))
        // The same number from an ATT operation is "insufficient authorization": still permanent.
        listOf("GATT_WRITE_STATUS_8", "GATT_DESCRIPTOR_STATUS_8", "GATT_MTU_STATUS_8", "GATT_DISCOVERY_STATUS_8")
            .forEach { assertFalse(it, FailurePolicy.isRetryable(it)) }
    }
}
