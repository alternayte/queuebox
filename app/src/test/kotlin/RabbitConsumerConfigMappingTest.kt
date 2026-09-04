package org.nxtspec.app

import org.junit.jupiter.api.Test
import org.nxtspec.SourceConfig
import kotlin.test.assertEquals

/**
 * The RabbitMQ source configuration must reach the consumer complete. See the second review
 * gate, defect 3.
 */
class RabbitConsumerConfigMappingTest {

    @Test
    fun `the aggregate identifier path reaches the consumer configuration`() {
        val source = SourceConfig.RabbitMQ(
            queueName = "orders",
            connectionUrl = "amqp://guest:guest@localhost:5672",
            idempotencyKeyPath = "$.id",
            aggregateIdPath = "$.orderId",
            prefetchCount = 7
        )

        val config = rabbitConsumerConfig("orders-source", source)

        assertEquals("orders", config.queueName)
        assertEquals("orders-source", config.sourceName)
        assertEquals(7, config.prefetchCount)
        assertEquals("$.id", config.idempotencyKeyPath)
        assertEquals("$.orderId", config.aggregateIdPath, "The aggregate identifier path must be wired.")
    }
}
