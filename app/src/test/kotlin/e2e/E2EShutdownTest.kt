package org.nxtspec.e2e

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.nxtspec.app.RequestDrain
import org.nxtspec.app.ShutdownSequence
import org.nxtspec.app.configureRequestDrain
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers F-029. The shutdown must stop the HTTP server before it closes the data source, so an
 * in-flight request finishes with a success status.
 */
class E2EShutdownTest : E2ETestBase() {

    @Test
    fun `a slow request completes when the shutdown runs`() = runBlocking {
        val ownDataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 4
            }
        )

        val handlerStarted = java.util.concurrent.CountDownLatch(1)
        val drain = RequestDrain()
        val port = findFreePort()
        val server = embeddedServer(Netty, port = port) {
            configureRequestDrain(drain)
            routing {
                get("/slow") {
                    handlerStarted.countDown()
                    // The work outlives the moment the shutdown starts.
                    delay(700)
                    val value = withContext(Dispatchers.IO) {
                        ownDataSource.connection.use { connection ->
                            connection.createStatement().use { statement ->
                                statement.executeQuery("SELECT 1").use { rs ->
                                    rs.next()
                                    rs.getInt(1)
                                }
                            }
                        }
                    }
                    call.respondText("ok:$value")
                }
            }
        }
        server.start(wait = false)

        val client = HttpClient()
        val request = async(Dispatchers.IO) { client.get("http://localhost:$port/slow") }

        // Let the request reach the handler, then shut down while it is in flight.
        assertTrue(
            handlerStarted.await(10, java.util.concurrent.TimeUnit.SECONDS),
            "The handler must start before the shutdown"
        )
        delay(100)

        val sequence = ShutdownSequence(
            stopServer = {
                assertTrue(drain.await(10000), "Every in-flight request must finish before the stop")
                server.stop(gracePeriodMillis = 10000, timeoutMillis = 20000)
            },
            stopBackgroundServices = { },
            closeResources = { ownDataSource.close() }
        )
        sequence.run()

        val response = request.await()

        assertEquals(HttpStatusCode.OK, response.status, "The in-flight request must succeed")
        assertEquals("ok:1", response.bodyAsText())
        assertTrue(ownDataSource.isClosed, "The data source must close after the server stops")

        client.close()
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}
