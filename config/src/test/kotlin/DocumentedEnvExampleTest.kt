package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The environment only example in `docs/configuration.md` must start.
 *
 * That section documents a container deployment that sets the QUEUEBOX_ variables and mounts no
 * file. Its `docker run` block and its Compose block carry the same variables, and this test
 * carries them again. A change to either block that breaks the deployment fails here.
 */
class DocumentedEnvExampleTest {

    /** The variables of the documented example, in the order the document lists them. */
    private val documented = mapOf(
        "QUEUEBOX_DATABASE_URL" to "jdbc:postgresql://db:5432/queuebox",
        "QUEUEBOX_DATABASE_USERNAME" to "postgres",
        "QUEUEBOX_DATABASE_PASSWORD" to "secret",
        "QUEUEBOX_DESTINATIONS_WEBHOOK_TYPE" to "http",
        "QUEUEBOX_DESTINATIONS_WEBHOOK_BASEURL" to "https://api.example.com",
        "QUEUEBOX_ROUTES_0_TOPICPATTERN" to "order.*",
        "QUEUEBOX_ROUTES_0_DESTINATION" to "webhook",
        "QUEUEBOX_SOURCES_STRIPE_TYPE" to "http",
        "QUEUEBOX_SOURCES_STRIPE_PATH" to "/stripe",
        "QUEUEBOX_SOURCES_STRIPE_IDEMPOTENCYKEYPATH" to "\$.id",
        "QUEUEBOX_SOURCES_STRIPE_EVENTTYPEPATH" to "\$.type"
    )

    @Test
    fun `the documented environment only example configures QueueBox`() {
        val config = ConfigLoader.load(env = { documented })

        assertEquals(setOf("webhook"), config.destinations.keys)
        assertEquals(setOf("stripe"), config.sources.keys)
        assertEquals(1, config.routes.size, "The documented route must bind")
        assertEquals("order.*", config.routes[0].topicPattern)
        assertEquals("webhook", config.routes[0].destination)
    }
}
