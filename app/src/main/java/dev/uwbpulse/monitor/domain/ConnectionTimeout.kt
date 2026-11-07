package dev.uwbpulse.monitor.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Only this operation's timeout becomes a connection failure; caller cancellation still propagates. */
suspend fun <T : Any> connectionTimeout(millis: Long, code: String, block: suspend CoroutineScope.() -> T): T =
    withTimeoutOrNull(millis, block) ?: error(code)
