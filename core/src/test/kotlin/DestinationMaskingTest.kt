package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The adversarial review gate of section 11 found this as a latent leak.
 *
 * The configuration classes mask their credentials in `toString`. The domain twins in
 * `Destination` did not, so a log line or an exception that printed a destination leaked the AMQP
 * password and every static header value. Nothing interpolated them at the time, which is exactly
 * why a test must hold the property.
 */
class DestinationMaskingTest {

    @Test
    fun `a rabbitmq destination never prints the broker password`() {
        val destination = Destination.RabbitMQ(
            name = "events",
            url = "amqp://guest:Sup3rS3cret@broker:5672/vhost",
            exchange = "queuebox-events",
            headers = mapOf("Authorization" to "Bearer topsecret")
        )

        val printed = destination.toString()

        assertFalse(printed.contains("Sup3rS3cret"), "the broker password printed: $printed")
        assertFalse(printed.contains("topsecret"), "a static header value printed: $printed")
        // An operator needs the host and the exchange to debug.
        assertTrue(printed.contains("broker"), "the host must survive: $printed")
        assertTrue(printed.contains("queuebox-events"), "the exchange must survive: $printed")
    }

    @Test
    fun `an http destination never prints a credential in the url or in a header`() {
        val destination = Destination.Http(
            name = "orders",
            baseUrl = "https://user:Sup3rS3cret@api.example.com",
            headers = mapOf("X-Api-Key" to "topsecret")
        )

        val printed = destination.toString()

        assertFalse(printed.contains("Sup3rS3cret"), "the url password printed: $printed")
        assertFalse(printed.contains("topsecret"), "a static header value printed: $printed")
        assertTrue(printed.contains("api.example.com"), "the host must survive: $printed")
    }
}
