package org.nxtspec

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Issue #80: an HTTP source reads its keys from a request header. See `docs/specs/header-keys.md`. */
class InboxRoutesHeaderKeysTest {

    private val repository = mockk<InboxRepository>(relaxed = true)
    private val stored = mutableListOf<InboxMessage>()

    init {
        coEvery { repository.store(capture(stored)) } returns InboxResult.Stored
    }

    private val logbackLogger = LoggerFactory.getLogger("org.nxtspec") as ch.qos.logback.classic.Logger
    private val appender = ListAppender<ILoggingEvent>().apply {
        context = LoggerFactory.getILoggerFactory() as LoggerContext
        start()
    }

    @AfterTest
    fun stopCapture() {
        logbackLogger.detachAppender(appender)
        appender.stop()
    }

    private val both = SourceConfig.Http(
        path = "/github",
        idempotencyKeyHeader = "X-GitHub-Delivery",
        idempotencyKeyPath = "$.id",
        eventTypeHeader = "X-GitHub-Event",
        eventTypePath = "$.type",
        aggregateIdHeader = "X-Aggregate",
        aggregateIdPath = "$.repo"
    )

    private fun ApplicationTestBuilder.route(vararg sources: Pair<String, SourceConfig.Http>) {
        application {
            install(ContentNegotiation) { json() }
            configureInboxRoutes(
                InboxConfig(basePath = "/inbox"),
                sources.toMap(),
                InboxHandler(repository, IdempotencyExtractor())
            )
        }
    }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
        vararg headers: Pair<String, String>
    ): HttpResponse = client.post("/inbox$path") {
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(body)
    }

    @Test
    fun `a header wins over its path for each of the three attributes`() = testApplication {
        route("github" to both)

        val response = post(
            "/github",
            """{"id":"body-id","type":"body.type","repo":"body/repo"}""",
            "X-GitHub-Delivery" to "d-1",
            "X-GitHub-Event" to "pull_request",
            "X-Aggregate" to "acme/api"
        )

        assertEquals(HttpStatusCode.Accepted, response.status)
        val message = stored.single()
        assertEquals("d-1", message.idempotencyKey)
        assertEquals("pull_request", message.eventType)
        assertEquals("acme/api", message.aggregateId)
    }

    @Test
    fun `the path is the fallback when a header is absent or empty`() = testApplication {
        route("github" to both)

        val response = post(
            "/github",
            """{"id":"body-id","type":"body.type","repo":"body/repo"}""",
            "X-GitHub-Delivery" to "   ",
            "X-GitHub-Event" to ""
        )

        assertEquals(HttpStatusCode.Accepted, response.status)
        val message = stored.single()
        assertEquals("body-id", message.idempotencyKey)
        assertEquals("body.type", message.eventType)
        assertEquals("body/repo", message.aggregateId)
    }

    @Test
    fun `header names match in any letter case and values are trimmed`() = testApplication {
        route("github" to both.copy(idempotencyKeyHeader = "x-github-delivery", eventTypeHeader = "X-GITHUB-EVENT"))

        post("/github", """{"id":"body-id"}""", "X-GitHub-Delivery" to "  d-2  ", "x-github-event" to " push ")

        val message = stored.single()
        assertEquals("d-2", message.idempotencyKey)
        assertEquals("push", message.eventType)
    }

    @Test
    fun `a blank key header with no path fallback is rejected`() = testApplication {
        route("github" to SourceConfig.Http(path = "/github", idempotencyKeyHeader = "X-GitHub-Delivery"))

        val response = post("/github", """{"id":"body-id"}""", "X-GitHub-Delivery" to "  ")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) { repository.store(any()) }
    }

    @Test
    fun `a missing key header gets the same response and log line as a missing path`() = testApplication {
        route(
            "github" to SourceConfig.Http(path = "/github", idempotencyKeyHeader = "X-GitHub-Delivery"),
            "stripe" to SourceConfig.Http(path = "/stripe", idempotencyKeyPath = "$.id")
        )
        logbackLogger.addAppender(appender)

        val fromHeader = post("/github", """{"id":"body-id"}""")
        val fromPath = post("/stripe", """{"other":"x"}""")

        assertEquals(HttpStatusCode.BadRequest, fromHeader.status)
        assertEquals(fromPath.status, fromHeader.status)
        assertEquals(fromPath.bodyAsText(), fromHeader.bodyAsText())
        coVerify(exactly = 0) { repository.store(any()) }

        val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertEquals(
            listOf(
                "The idempotency key header 'X-GitHub-Delivery' of source 'github' matched nothing. " +
                    "The message is rejected with 400.",
                "The idempotency key path '$.id' of source 'stripe' matched nothing. " +
                    "The message is rejected with 400."
            ),
            warnings
        )
    }

    @Test
    fun `with both set, the log line names the header and the path`() = testApplication {
        route("github" to both)
        logbackLogger.addAppender(appender)

        val response = post("/github", """{"other":"x"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            "The idempotency key header 'X-GitHub-Delivery' and path '$.id' of source 'github' matched " +
                "nothing. The message is rejected with 400.",
            appender.list.single { it.level == Level.WARN }.formattedMessage
        )
    }
}
