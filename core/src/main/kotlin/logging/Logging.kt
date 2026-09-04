package org.nxtspec.logging

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Builds a named logger for a class. See F-046.
 *
 * Every QueueBox diagnostic goes through SLF4J. The application module supplies the binding, so
 * a library module never fixes the output format.
 */
inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

/**
 * Builds a named logger for a file that holds no class.
 */
fun logger(name: String): Logger = LoggerFactory.getLogger(name)

/**
 * The mapped diagnostic context keys that a message-scoped log line carries. See F-046 and
 * F-047.
 *
 * A key is a fixed name, so an operator can filter on it, and a log aggregator can index it.
 */
object LogKeys {
    const val MESSAGE_ID: String = "messageId"
    const val TOPIC: String = "topic"
    const val DESTINATION: String = "destination"
    const val ATTEMPT: String = "attempt"
    const val SOURCE: String = "source"
    const val CORRELATION_ID: String = "correlationId"
}

/**
 * Runs a suspending block with the given mapped diagnostic context entries.
 *
 * The mapped diagnostic context of SLF4J is thread local. A coroutine can resume on another
 * thread after a suspension point, so a plain put and remove pair loses the entries, and it can
 * leave an entry behind on the first thread. MDCContext carries the map across every dispatch,
 * and it restores the previous map afterwards.
 *
 * A null value is skipped, so a caller does not have to build the map twice.
 */
suspend fun <T> withLogContext(
    vararg entries: Pair<String, Any?>,
    block: suspend () -> T
): T {
    val merged = (MDC.getCopyOfContextMap() ?: emptyMap()).toMutableMap()
    entries.forEach { (key, value) ->
        value?.let { merged[key] = sanitiseValue(it.toString()) }
    }

    return withContext(MDCContext(merged)) { block() }
}

/**
 * Removes the characters that could forge a second log line.
 *
 * A caller supplies the correlation identifier, so its value reaches a log line. A newline in it
 * would let a caller write a line of its own.
 */
private fun sanitiseValue(value: String): String =
    value.map { if (it.isISOControl()) ' ' else it }.joinToString("")

/**
 * The HTTP and AMQP header that carries the correlation identifier. See F-047.
 *
 * The inbox accepts it, the relay copies it into the outbox headers, and every publisher
 * forwards the message headers, so the identifier reaches the destination.
 */
const val CORRELATION_ID_HEADER: String = "X-Correlation-Id"

/** A caller-supplied identifier is bounded, so it cannot fill a log line or a column. */
const val MAX_CORRELATION_ID_LENGTH: Int = 128
