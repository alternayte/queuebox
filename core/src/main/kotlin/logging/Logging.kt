package org.nxtspec.logging

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
 * Runs a block with the given mapped diagnostic context entries, and removes them afterwards.
 *
 * A null value is skipped, so a caller does not have to build the map twice.
 */
inline fun <T> withLogContext(vararg entries: Pair<String, Any?>, block: () -> T): T {
    val applied = entries.mapNotNull { (key, value) ->
        value?.let { key to it.toString() }
    }

    applied.forEach { (key, value) -> MDC.put(key, value) }
    try {
        return block()
    } finally {
        applied.forEach { (key, _) -> MDC.remove(key) }
    }
}

/**
 * The HTTP and AMQP header that carries the correlation identifier. See F-047.
 *
 * The inbox accepts it, the relay copies it into the outbox headers, and every publisher
 * forwards the message headers, so the identifier reaches the destination.
 */
const val CORRELATION_ID_HEADER: String = "X-Correlation-Id"

/** A caller-supplied identifier is bounded, so it cannot fill a log line or a column. */
const val MAX_CORRELATION_ID_LENGTH: Int = 128
