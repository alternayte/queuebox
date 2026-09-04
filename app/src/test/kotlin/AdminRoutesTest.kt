package org.nxtspec.app

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.nxtspec.AdminConfig
import org.nxtspec.InboxAuthConfig
import org.nxtspec.Secret
import org.nxtspec.auth.InboxAuthValidator
import org.nxtspec.transform.TransformEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminRoutesTest {

    private val transformEngine = TransformEngine()

    private val openAdmin = AdminConfig(enabled = true, insecure = true)

    private val bearerAdmin = AdminConfig(
        enabled = true,
        auth = InboxAuthConfig.Bearer(token = Secret(TOKEN))
    )

    private fun ApplicationTestBuilder.setupApp(
        admin: AdminConfig = openAdmin,
        engine: TransformEngine = transformEngine
    ) {
        application {
            configureAdminApplication(admin, engine)
        }
    }

    private fun Application.configureAdminApplication(admin: AdminConfig, engine: TransformEngine) {
        install(ContentNegotiation) { json() }
        // Call the production wiring, so the test covers the shipped route.
        configureAdminRoutes(admin, InboxAuthValidator(), engine)
    }

    @Test
    fun `should return 200 for valid transform expression`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "name",
                    "payload": {"name": "Alice"}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertTrue(json["success"]?.jsonPrimitive?.content?.toBoolean() ?: false)
        assertEquals("Alice", json["result"]?.jsonPrimitive?.content)
        assertNotNull(json["context"])
    }

    @Test
    fun `should return 400 for invalid expression syntax`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "{ unclosed",
                    "payload": {}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertFalse(json["success"]?.jsonPrimitive?.content?.toBoolean() ?: true)
        assertTrue(json["error"]?.jsonPrimitive?.content?.contains("Invalid expression") ?: false)
    }

    @Test
    fun `should return 400 for malformed JSON request`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody("not valid json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertFalse(json["success"]?.jsonPrimitive?.content?.toBoolean() ?: true)
        assertTrue(json["error"]?.jsonPrimitive?.content?.contains("Invalid request") ?: false)
    }

    @Test
    fun `should include context variables in response`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "$",
                    "payload": {"test": true},
                    "mockTopic": "custom.topic"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        val context = json["context"]?.jsonObject
        assertNotNull(context)
        assertEquals("custom.topic", context["topic"]?.jsonPrimitive?.content)
        assertEquals(1, context["attempt"]?.jsonPrimitive?.content?.toInt())
        assertNotNull(context["messageId"]?.jsonPrimitive?.content)
        assertNotNull(context["timestamp"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should use default topic when mockTopic not provided`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "$",
                    "payload": {}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        val context = json["context"]?.jsonObject
        assertEquals("test.topic", context?.get("topic")?.jsonPrimitive?.content)
    }

    @Test
    fun `should allow access to context variables in expression`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "{ \"topic\": ${"$"}topic, \"attempt\": ${"$"}attempt }",
                    "payload": {},
                    "mockTopic": "my.event"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        val result = json["result"]?.jsonObject
        assertEquals("my.event", result?.get("topic")?.jsonPrimitive?.content)
        assertEquals(1, result?.get("attempt")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `should transform complex expressions with arrays`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "${"$"}sum(items.(price * qty))",
                    "payload": {"items": [{"price": 10, "qty": 2}, {"price": 5, "qty": 4}]}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertTrue(json["success"]?.jsonPrimitive?.content?.toBoolean() ?: false)
        assertEquals(40, json["result"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `should handle object transformation`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "{ \"orderId\": id, \"customerName\": customer.name }",
                    "payload": {"id": "123", "customer": {"name": "Bob"}}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        val result = json["result"]?.jsonObject
        assertEquals("123", result?.get("orderId")?.jsonPrimitive?.content)
        assertEquals("Bob", result?.get("customerName")?.jsonPrimitive?.content)
    }

    @Test
    fun `should return error when expression evaluation fails at runtime`() = testApplication {
        setupApp()

        // Use an expression that will fail at runtime (call undefined variable)
        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "${"$"}undefined()",
                    "payload": {}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertFalse(json["success"]?.jsonPrimitive?.content?.toBoolean() ?: true)
        assertNotNull(json["error"])
    }

    @Test
    fun `should respect custom timeout parameter`() = testApplication {
        setupApp()

        // Test with a reasonable timeout that should work
        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "name",
                    "payload": {"name": "test"},
                    "timeoutMs": 1000
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertTrue(json["success"]?.jsonPrimitive?.content?.toBoolean() ?: false)
    }

    @Test
    fun `should return JSON content type`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "name",
                    "payload": {"name": "test"}
                }
                """.trimIndent()
            )
        }

        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    }

    @Test
    fun `should handle null result from expression`() = testApplication {
        setupApp()

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "missing",
                    "payload": {"name": "test"}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertTrue(json["success"]?.jsonPrimitive?.content?.toBoolean() ?: false)
        // The result should be null when accessing a missing field
        assertEquals(JsonPrimitive(null as String?), json["result"])
    }

    @Test
    fun `should return 401 without credentials`() = testApplication {
        setupApp(bearerAdmin)

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "name",
                    "payload": {"name": "Alice"}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `should return 200 with credentials`() = testApplication {
        setupApp(bearerAdmin)

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            setBody(
                """
                {
                    "expression": "name",
                    "payload": {"name": "Alice"}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Alice", json["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should not register the route when admin is disabled`() = testApplication {
        setupApp(AdminConfig())

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody("""{"expression": "name", "payload": {"name": "Alice"}}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `should clamp a large caller timeout to the configured maximum`() = testApplication {
        val engine = spyk(TransformEngine())
        val timeout = slot<Long>()
        setupApp(openAdmin.copy(maxTransformTimeoutMs = 250), engine)

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "name",
                    "payload": {"name": "Alice"},
                    "timeoutMs": 600000
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify {
            engine.evaluate(any(), any(), any(), capture(timeout), any())
        }
        assertEquals(250L, timeout.captured)
    }

    @Test
    fun `should reject a payload larger than the configured maximum`() = testApplication {
        setupApp(openAdmin.copy(maxPayloadBytes = 64))

        val response = client.post("/admin/transform/test") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "expression": "name",
                    "payload": {"name": "${"A".repeat(500)}"}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }
}

private const val TOKEN = "admin-secret-token"
