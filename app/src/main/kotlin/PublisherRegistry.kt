package org.nxtspec.app

import org.nxtspec.Destination
import org.nxtspec.Publisher

/**
 * Thrown at startup when a configured destination has no publisher that supports it.
 */
class UnsupportedDestinationException(message: String) : RuntimeException(message)

/**
 * F-003: fails fast when a configured destination has no registered publisher.
 *
 * Without this check the outbox poller marks every message for that destination as dead, and
 * the operator sees a silent data loss instead of a startup error.
 *
 * @throws UnsupportedDestinationException when one or more destinations have no publisher
 */
fun validatePublisherCoverage(destinations: Map<String, Destination>, publishers: List<Publisher>) {
    val unsupported = destinations.filter { (_, destination) ->
        publishers.none { it.supports(destination) }
    }

    if (unsupported.isEmpty()) return

    val detail = unsupported.entries.joinToString(", ") { (name, destination) ->
        "'$name' of type '${destinationTypeName(destination)}'"
    }

    throw UnsupportedDestinationException(
        "No registered publisher supports these destinations: $detail. " +
            "Register a publisher for the destination type, or remove the destination from the " +
            "configuration."
    )
}

private fun destinationTypeName(destination: Destination): String = when (destination) {
    is Destination.Http -> "http"
    is Destination.Kafka -> "kafka"
    is Destination.Nats -> "nats"
    is Destination.RabbitMQ -> "rabbitmq"
}
