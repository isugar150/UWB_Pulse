package dev.uwbpulse.monitor.domain

enum class MonitorEventType { TAG_DISTANCE_EXCEEDED, TAG_RETURNED, TAG_DISCONNECTED, UWB_MONITORING_ERROR }
data class DistancePolicy(val threshold: Double = 5.0, val returnDistance: Double = 4.0, val dwellMillis: Long = 3_000) {
    companion object {
        /** Smallest return distance the UI offers; 0 m would need an exact 0.00 reading and never return. */
        const val MIN_RETURN = 0.5
    }
    init {
        require(threshold.isFinite() && threshold in 1.0..10.0 && threshold * 2 % 1 == 0.0)
        require(returnDistance.isFinite() && returnDistance >= 0 && returnDistance < threshold)
        require(dwellMillis in 500..30_000)
    }
}
/** No clocks, BLE state or synthetic samples: only valid UWB samples can produce distance events. */
class DistanceEventEngine(policy: DistancePolicy = DistancePolicy(), private val maxGapMillis: Long = 1_500, initialAway: Boolean = false) {
    var policy = policy; private set
    var away = initialAway; private set
    private var previous: Long? = null
    private var exceededSince: Long? = null
    fun configure(value: DistancePolicy) { if (value != policy) { policy = value; exceededSince = null } }
    fun signalLost() { previous = null; exceededSince = null } // Preserve the away latch through reconnects.
    fun sample(meters: Double, elapsedMillis: Long): MonitorEventType? {
        if (!meters.isFinite() || meters < 0) { signalLost(); return null }
        val last = previous
        if (last != null && elapsedMillis <= last) return null
        if (last != null && elapsedMillis - last > maxGapMillis) exceededSince = null
        previous = elapsedMillis
        if (away) {
            if (meters <= policy.returnDistance) { away = false; exceededSince = null; return MonitorEventType.TAG_RETURNED }
            return null
        }
        if (meters <= policy.threshold) { exceededSince = null; return null }
        val started = exceededSince ?: elapsedMillis.also { exceededSince = it }
        if (elapsedMillis - started >= policy.dwellMillis) {
            away = true; exceededSince = null; return MonitorEventType.TAG_DISTANCE_EXCEEDED
        }
        return null
    }
}
