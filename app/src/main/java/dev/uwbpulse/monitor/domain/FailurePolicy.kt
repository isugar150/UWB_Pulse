package dev.uwbpulse.monitor.domain

/**
 * Which connection failures may reach external automation. Every failure stays in the journal and the UI;
 * a failure becomes an event only when it is not transient or when it needs the user.
 */
object FailurePolicy {
    /** A recoverable outage shorter than this is transient and is not published. */
    const val GRACE_MILLIS = 120_000L
    const val AUTH_FAILED = "TAG_AUTHENTICATION_FAILED"
    /** Consecutive owner-proof failures tolerated before the failure is treated as permanent (key or tag changed). */
    const val MAX_AUTH_RETRIES = 3
    private val permanentGatt = Regex("GATT_.*_STATUS_(5|8|15|137)")
    private val linkStatus = Regex("GATT_(CONNECTION|WRITE|DESCRIPTOR|MTU|DISCOVERY)_STATUS_\\d+")
    /**
     * Status 8 from a connection-state callback is the link supervision timeout: the tag went out of reach mid-operation
     * (for example a car door closing while the link is being rebuilt). For an ATT operation the same number means
     * "insufficient authorization", which is permanent, so only the connection variant is exempt.
     */
    private const val SUPERVISION_TIMEOUT = "GATT_CONNECTION_STATUS_8"

    /** After a successful owner authentication the tag itself would not start UWB (typically a weak battery). Not "no reports": a closed door gives that too. */
    fun isUwbRefused(code: String): Boolean = Regex("GATT_WRITE_STATUS_\\d+").matches(code) || code == "GATT_BUSY_RETRIES_EXHAUSTED" ||
        code.startsWith("UWB_STOPPED_") || code.startsWith("UWB_OPEN_FAILED_") || code.startsWith("UWB_CLOSED_")
    /** Refusals in a row before the user is told. */
    const val UWB_REFUSALS_BEFORE_NOTICE = 2
    /** Every failed activation also costs the tag battery, so a refusing tag is retried rarely. */
    const val UWB_REFUSED_BACKOFF_MILLIS = 300_000L
    fun backoffAfterRefusals(baseMillis: Long, refusals: Int): Long =
        if (refusals >= UWB_REFUSALS_BEFORE_NOTICE) maxOf(baseMillis, UWB_REFUSED_BACKOFF_MILLIS) else baseMillis

    fun isRetryable(code: String): Boolean = (code == SUPERVISION_TIMEOUT || !permanentGatt.matches(code)) &&
        (code.startsWith("GATT_") || code.startsWith("UWB_") || code.startsWith("BLE_SCAN_FAILED_") ||
            code == "BLUETOOTH_DISABLED" || code == "BLE_SCANNER_UNAVAILABLE" || code == "OWNER_TAG_NOT_FOUND" || code == "NO_UWB_REPORTS_20S" ||
            code == "TAG_NONCE_TIMEOUT" || code == "TAG_PROOF_TIMEOUT")

    /**
     * The tag is out of reach or the link dropped. That is a disconnect, never a monitoring error by itself.
     * Other GATT failures (MTU too small, discovery rejected, ...) are not link loss and stay user-actionable.
     */
    fun isLinkLoss(code: String): Boolean = code == "OWNER_TAG_NOT_FOUND" || linkStatus.matches(code) ||
        code.startsWith("GATT_DISCONNECTED_") || code == "GATT_CONNECT_OR_DISCOVERY_TIMEOUT" || code == "GATT_OPERATION_TIMEOUT" ||
        code == "NO_UWB_REPORTS_20S" || code == "UWB_REPORTS_STALE_20S" || code == "UWB_PEER_DISCONNECTED" || code == "UWB_FLOW_ENDED" ||
        code.startsWith("UWB_STOPPED_") || code.startsWith("UWB_CLOSED_") || code == "TAG_NONCE_TIMEOUT" || code == "TAG_PROOF_TIMEOUT"
}

/**
 * Per-outage decision for TAG_DISCONNECTED and UWB_MONITORING_ERROR, and whether the loop should try again.
 * An outage starts at the first failure and ends with the next valid measurement. Each event is published at most
 * once per outage. An owner-proof failure is retried a few times in a row; repeating it ends the retries.
 */
class OutageGate(private val graceMillis: Long = FailurePolicy.GRACE_MILLIS) {
    data class Decision(val disconnected: Boolean, val error: Boolean, val retry: Boolean = true)

    private var since: Long? = null
    private var disconnectedSent = false
    private var errorSent = false
    private var authFailures = 0

    fun failed(code: String, hadSession: Boolean, now: Long): Decision {
        val start = since ?: now.also { since = it }
        val authExhausted = code == FailurePolicy.AUTH_FAILED && ++authFailures > FailurePolicy.MAX_AUTH_RETRIES
        val retryable = (FailurePolicy.isRetryable(code) || code == FailurePolicy.AUTH_FAILED) && !authExhausted
        val settled = !retryable || now - start >= graceMillis
        val disconnected = hadSession && settled && !disconnectedSent
        val error = settled && !errorSent && (!retryable || !FailurePolicy.isLinkLoss(code))
        if (disconnected) disconnectedSent = true
        if (error) errorSent = true
        return Decision(disconnected, error, retryable)
    }

    fun recovered() { since = null; disconnectedSent = false; errorSent = false; authFailures = 0 }
}
