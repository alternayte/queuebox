package org.nxtspec.app

import org.nxtspec.Destination
import org.nxtspec.OutboxMessage
import org.nxtspec.PublishContext
import org.nxtspec.Publisher
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * Covers F-003. A destination whose type has no registered publisher must fail at startup,
 * not dead-letter every message at runtime.
 */
class PublisherRegistrationTest {

    private class HttpOnlyPublisher : Publisher {
        override fun supports(destination: Destination): Boolean = destination is Destination.Http
        override suspend fun publish(
            message: OutboxMessage,
            destination: Destination,
            context: PublishContext
        ): Result<Unit> = Result.success(Unit)
    }

    private val httpDestination = Destination.Http(
        name = "webhook",
        baseUrl = "https://example.com",
        path = "/hook"
    )

    private val rabbitDestination = Destination.RabbitMQ(
        name = "events-exchange",
        url = "amqp://localhost:5672",
        exchange = "events"
    )

    @Test
    fun `startup fails when a destination has no publisher`() {
        val exception = assertFailsWith<UnsupportedDestinationException> {
            validatePublisherCoverage(
                destinations = mapOf(
                    "webhook" to httpDestination,
                    "events-exchange" to rabbitDestination
                ),
                publishers = listOf(HttpOnlyPublisher())
            )
        }

        assertContains(exception.message!!, "events-exchange")
        assertContains(exception.message!!, "rabbitmq")
    }

    @Test
    fun `startup passes when every destination has a publisher`() {
        validatePublisherCoverage(
            destinations = mapOf("webhook" to httpDestination),
            publishers = listOf(HttpOnlyPublisher())
        )
    }
}
