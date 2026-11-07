package dev.uwbpulse.monitor.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stored only in the encrypted SmartThings vault. Never put the target or credentials in work data/logs. */
data class SwitchRequest(val id: String, val state: String, val createdAtEpochMillis: Long, val targetDeviceId: String, val test: Boolean = false) {
    override fun toString() = "SwitchRequest(REDACTED)"
}

object SwitchDeliveryPolicy {
    const val TTL_MILLIS = 30_000L
    fun isFresh(request: SwitchRequest, now: Long): Boolean =
        request.createdAtEpochMillis > 0 && now >= request.createdAtEpochMillis && now - request.createdAtEpochMillis < TTL_MILLIS
}

/** Also shared by cancelled/replaced workers: the next HTTP send waits until the old one actually returns. */
class LatestSwitchDelivery {
    private val serial = Mutex()
    private class Obsolete : RuntimeException()

    suspend fun deliver(request: SwitchRequest, current: () -> SwitchRequest?, now: () -> Long,
                        send: suspend (beforeSend: () -> Unit) -> Int): Int? = serial.withLock {
        val context = currentCoroutineContext()
        val guard = {
            context.ensureActive()
            if (current() != request || !SwitchDeliveryPolicy.isFresh(request, now())) throw Obsolete()
        }
        try { guard(); send(guard) }
        catch (_: Obsolete) { null }
    }
}
