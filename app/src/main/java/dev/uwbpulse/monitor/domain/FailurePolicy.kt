package dev.uwbpulse.monitor.domain

/**
 * Which connection failures may reach external automation. Every failure stays in the journal and the UI;
 * a failure becomes an event only when it is not transient or when it needs the user.
 */
object FailurePolicy {
    /** A recoverable outage shorter than this is transient and is not published. */
    const val GRACE_MILLIS = 120_000L
    private val permanentGatt = Regex("GATT_.*_STATUS_(5|8|15|137)")

    fun isRetryable(code: String): Boolean = !permanentGatt.matches(code) &&
        (code.startsWith("GATT_") || code.startsWith("UWB_") || code.startsWith("BLE_SCAN_FAILED_") ||
            code == "BLUETOOTH_DISABLED" || code == "BLE_SCANNER_UNAVAILABLE" || code == "OWNER_TAG_NOT_FOUND" || code == "NO_UWB_REPORTS_20S")

    /** The tag is out of reach or the link dropped. That is a disconnect, never a monitoring error by itself. */
    fun isLinkLoss(code: String): Boolean = code == "OWNER_TAG_NOT_FOUND" || code.startsWith("GATT_") || code == "NO_UWB_REPORTS_20S" ||
        code == "UWB_REPORTS_STALE_20S" || code == "UWB_PEER_DISCONNECTED" || code == "UWB_FLOW_ENDED" ||
        code.startsWith("UWB_STOPPED_") || code.startsWith("UWB_CLOSED_")
}

/**
 * Per-outage decision for TAG_DISCONNECTED and UWB_MONITORING_ERROR. An outage starts at the first failure
 * and ends with the next valid measurement. Each event is published at most once per outage.
 */
class OutageGate(private val graceMillis: Long = FailurePolicy.GRACE_MILLIS) {
    data class Decision(val disconnected: Boolean, val error: Boolean)

    private var since: Long? = null
    private var disconnectedSent = false
    private var errorSent = false

    fun failed(code: String, hadSession: Boolean, now: Long): Decision {
        val start = since ?: now.also { since = it }
        val retryable = FailurePolicy.isRetryable(code)
        val settled = !retryable || now - start >= graceMillis
        val disconnected = hadSession && settled && !disconnectedSent
        val error = settled && !errorSent && (!retryable || !FailurePolicy.isLinkLoss(code))
        if (disconnected) disconnectedSent = true
        if (error) errorSent = true
        return Decision(disconnected, error)
    }

    fun recovered() { since = null; disconnectedSent = false; errorSent = false }
}
