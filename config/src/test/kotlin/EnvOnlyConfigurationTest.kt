package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A deployment must be able to configure QueueBox with the QUEUEBOX_ variables alone.
 *
 * The packaged classpath resource is a fallback for a run that sets no variable, so an
 * environment only deployment inherits nothing from it. Every value it needs must therefore bind
 * from a variable, including the routes, which are a list.
 */
class EnvOnlyConfigurationTest {

    /** A complete deployment, expressed only as variables. No file exists on any path. */
    private val env = mapOf(
        "QUEUEBOX_DATABASE_URL" to "jdbc:postgresql://db:5432/app",
        "QUEUEBOX_DATABASE_USERNAME" to "app",
        "QUEUEBOX_DATABASE_PASSWORD" to "secret",
        "QUEUEBOX_SERVER_HTTPPORT" to "9099",

        "QUEUEBOX_OUTBOX_BATCHSIZE" to "42",
        "QUEUEBOX_OUTBOX_POLLINTERVALMS" to "250",
        "QUEUEBOX_OUTBOX_MAXATTEMPTS" to "7",

        "QUEUEBOX_INBOX_BASEPATH" to "/ingest",

        "QUEUEBOX_DESTINATIONS_BILLING_TYPE" to "http",
        "QUEUEBOX_DESTINATIONS_BILLING_BASEURL" to "https://billing.internal",
        "QUEUEBOX_DESTINATIONS_BILLING_PATH" to "/events",

        "QUEUEBOX_ROUTES_0_TOPICPATTERN" to "order.*",
        "QUEUEBOX_ROUTES_0_DESTINATION" to "billing",
        "QUEUEBOX_ROUTES_1_TOPICPATTERN" to "refund.**",
        "QUEUEBOX_ROUTES_1_DESTINATION" to "billing",

        "QUEUEBOX_SOURCES_STRIPE_PATH" to "/stripe",
        "QUEUEBOX_SOURCES_STRIPE_IDEMPOTENCYKEYPATH" to "\$.id",
        "QUEUEBOX_SOURCES_STRIPE_EVENTTYPEPATH" to "\$.type"
    )

    @Test
    fun `the outbox configures from variables alone`() {
        val config = ConfigLoader.load(env = { env })

        assertEquals(42, config.outbox.batchSize)
        assertEquals(250, config.outbox.pollIntervalMs)
        assertEquals(7, config.outbox.maxAttempts)
        assertEquals(9099, config.server.httpPort)
        assertEquals("jdbc:postgresql://db:5432/app", config.database.url)
    }

    @Test
    fun `the inbox and its sources configure from variables alone`() {
        val config = ConfigLoader.load(env = { env })

        assertEquals("/ingest", config.inbox.basePath)
        assertEquals(setOf("stripe"), config.sources.keys)

        val stripe = config.sources.getValue("stripe")
        assertTrue(stripe is SourceConfig.Http, "The source must bind as an HTTP source")
        assertEquals("/stripe", stripe.path)
    }

    /**
     * `routes` is a list. A flattened path such as `routes.0.topicpattern` reads as a map that is
     * keyed by `0`, and Hoplite refuses to build a list from a map, so an environment only
     * deployment could declare no route at all.
     */
    @Test
    fun `the routes bind as an ordered list, not as a map keyed by the index`() {
        val config = ConfigLoader.load(env = { env })

        assertEquals(2, config.routes.size, "Both routes must bind")
        assertEquals("order.*", config.routes[0].topicPattern)
        assertEquals("billing", config.routes[0].destination)
        assertEquals("refund.**", config.routes[1].topicPattern)
        assertEquals("billing", config.routes[1].destination)
        assertEquals(setOf("billing"), config.destinations.keys)
    }
}
