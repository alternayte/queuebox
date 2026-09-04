package org.nxtspec.e2e

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.nxtspec.IdempotencyExtractor
import org.nxtspec.InboxConfig
import org.nxtspec.InboxHandler
import org.nxtspec.InboxMessage
import org.nxtspec.InboxRepository
import org.nxtspec.InboxResult
import org.nxtspec.SourceConfig
import org.nxtspec.app.RequestDrain
import org.nxtspec.app.ShutdownSequence
import org.nxtspec.app.configureRequestDrain
import org.nxtspec.configureInboxRoutes
import org.nxtspec.repository.InboxRepositoryInterface
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers F-029. A slow inbox request must finish with a success status while the shutdown runs.
 */
class E2EShutdownTest : E2ETestBase() {

    /**
     * Stores through the real repository, but takes its time. It stands for a slow database.
     */
    private class SlowInboxRepository(
        private val delegate: InboxRepositoryInterface,
        private val handlerStarted: CountDownLatch
    ) : InboxRepositoryInterface by delegate {
        override suspend fun store(message: InboxMessage): InboxResult {
            handlerStarted.countDown()
            delay(700)
            return delegate.store(message)
        }
    }

    @Test
    fun `a slow inbox request completes when the shutdown runs`() = runBlocking {
        val ownDataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 4
            }
        )

        val handlerStarted = CountDownLatch(1)
        val drain = RequestDrain()
        val port = findFreePort()

        val server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            configureRequestDrain(drain)
            configureInboxRoutes(
                config = InboxConfig(basePath = "/inbox"),
                sources = mapOf(
                    "stripe" to SourceConfig.Http(
                        path = "/stripe",
                        idempotencyKeyPath = "$.id",
                        eventTypePath = "$.type"
                    )
                ),
                handler = InboxHandler(
                    repository = SlowInboxRepository(InboxRepository(), handlerStarted),
                    extractor = IdempotencyExtractor()
                )
            )
        }
        server.start(wait = false)

        val client = HttpClient()
        val request = async(Dispatchers.IO) {
            client.post("http://localhost:$port/inbox/stripe") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"evt_shutdown_1","type":"payment.succeeded"}""")
            }
        }

        assertTrue(
            handlerStarted.await(10, TimeUnit.SECONDS),
            "The inbox handler must start before the shutdown"
        )

        // The shutdown runs while the request is still inside the handler.
        ShutdownSequence(
            stopServer = {
                drain.startDraining()
                assertTrue(drain.await(10000), "Every in-flight request must finish first")
                server.stop(gracePeriodMillis = 10000, timeoutMillis = 20000)
            },
            stopBackgroundServices = { },
            closeResources = { ownDataSource.close() }
        ).run()

        val response = request.await()

        assertEquals(HttpStatusCode.OK, response.status, "The in-flight request must succeed")
        assertTrue(response.bodyAsText().contains("messageId"))
        assertTrue(ownDataSource.isClosed, "The data source must close after the server stops")

        // The row really reached the database.
        assertEquals("pending", getInboxMessage("stripe", "evt_shutdown_1")!!.state)

        client.close()
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}
