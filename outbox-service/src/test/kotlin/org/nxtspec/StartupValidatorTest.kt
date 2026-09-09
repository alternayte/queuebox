package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers F-057. An invalid expression must stop the start, not every message.
 */
class StartupValidatorTest {

    private fun config(
        destinations: Map<String, DestinationConfig> = emptyMap(),
        routes: List<RouteConfig> = emptyList(),
        sources: Map<String, SourceConfig> = emptyMap()
    ) = QueueBoxConfig(
        database = DatabaseConfig(
            url = "jdbc:postgresql://localhost:5432/queuebox",
            username = "postgres",
            password = Secret("secret")
        ),
        destinations = destinations,
        routes = routes,
        sources = sources
    )

    private fun http(expression: String?) = DestinationConfig.Http(
        baseUrl = "https://api.example.com",
        path = "/hook",
        transform = expression?.let { TransformConfig(expression = it) }
    )

    private fun configWith(
        exchange: String = "public.{{ aggregateType }}.v1",
        exchangeFrom: String? = null,
        destinationName: String = "events"
    ) = config(
        destinations = mapOf(
            destinationName to DestinationConfig.RabbitMQ(
                url = "amqp://localhost",
                exchange = exchange,
                exchangeFrom = exchangeFrom
            )
        )
    )

    @Test
    fun `accepts a configuration with no transform`() {
        StartupValidator.validateTransforms(config(destinations = mapOf("api" to http(null))))
    }

    @Test
    fun `accepts a valid expression`() {
        StartupValidator.validateTransforms(
            config(destinations = mapOf("api" to http("""{ "id": id }""")))
        )
    }

    @Test
    fun `refuses an invalid destination transform and names the path`() {
        val exception = assertFailsWith<InvalidTransformException> {
            StartupValidator.validateTransforms(
                config(destinations = mapOf("api" to http("""{ "id": """)))
            )
        }

        assertContains(exception.message!!, "destinations.api.transform.expression")
    }

    @Test
    fun `refuses an invalid route transform and names the path`() {
        val exception = assertFailsWith<InvalidTransformException> {
            StartupValidator.validateTransforms(
                config(
                    destinations = mapOf("api" to http(null)),
                    routes = listOf(
                        RouteConfig(
                            topicPattern = "order.*",
                            destination = "api",
                            transform = TransformConfig(expression = "$$$$ ~~>")
                        )
                    )
                )
            )
        }

        assertContains(exception.message!!, "routes[0].transform.expression")
    }

    @Test
    fun `refuses an invalid source transform and names the path`() {
        val exception = assertFailsWith<InvalidTransformException> {
            StartupValidator.validateTransforms(
                config(
                    sources = mapOf(
                        "stripe" to SourceConfig.Http(
                            path = "/stripe",
                            idempotencyKeyPath = "$.id",
                            eventTypePath = "$.type",
                            transform = TransformConfig(expression = "{ unclosed")
                        )
                    )
                )
            )
        }

        assertContains(exception.message!!, "sources.stripe.transform.expression")
    }

    @Test
    fun `an unknown template field fails the startup and names the field`() {
        val config = configWith(exchange = "public.{{ nosuchfield }}.v1", destinationName = "events")

        val error = assertFailsWith<InvalidDestinationException> {
            StartupValidator.validateAddressTemplates(config)
        }

        assertTrue(error.message!!.contains("nosuchfield"))
        assertTrue(error.message!!.contains("events"))
    }

    @Test
    fun `an exchangeFrom outside the permitted set fails the startup`() {
        val config = configWith(exchangeFrom = "payload", destinationName = "events")

        val error = assertFailsWith<InvalidDestinationException> {
            StartupValidator.validateAddressTemplates(config)
        }

        assertTrue(error.message!!.contains("payload"))
        assertTrue(error.message!!.contains("aggregate_type"))
    }

    @Test
    fun `a valid template and a valid column start cleanly`() {
        val config = configWith(exchange = "public.{{ aggregateType }}.v1", exchangeFrom = null)

        StartupValidator.validateAddressTemplates(config)
    }

    @Test
    fun `a payload prefixed field starts cleanly`() {
        val config = configWith(exchange = "public.{{ payload.orderId }}.v1", exchangeFrom = null)

        StartupValidator.validateAddressTemplates(config)
    }
}
