package org.nxtspec

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.TransformEngine
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InboxHandlerTest {

    private lateinit var mockRepository: InboxRepository
    private lateinit var extractor: IdempotencyExtractor
    private lateinit var handler: InboxHandler

    @BeforeEach
    fun setup() {
        mockRepository = mockk()
        extractor = IdempotencyExtractor()
        handler = InboxHandler(mockRepository, extractor)
    }

    @Test
    fun `should return Accepted when message stored successfully`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.orderId"
        )
        val payload = Json.parseToJsonElement("""{ "orderId": "order-123", "data": "test" }""")

        val result = handler.handle("stripe", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Accepted)
        coVerify { mockRepository.store(match { it.idempotencyKey == "order-123" }) }
    }

    @Test
    fun `should return Duplicate when repository detects duplicate`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Duplicate

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "dup-123" }""")

        val result = handler.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Duplicate)
    }

    @Test
    fun `should return ExtractionFailed when idempotency key path not found`() = runTest {
        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.missing.field"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        val result = handler.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.ExtractionFailed)
        coVerify(exactly = 0) { mockRepository.store(any()) }
    }

    @Test
    fun `should return StorageFailed when repository errors`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Error("DB connection failed")

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        val result = handler.handle("source", sourceConfig, payload)

        require(result is InboxHandlerResult.StorageFailed)
        assertEquals("DB connection failed", result.reason)
    }

    @Test
    fun `should extract eventType when eventTypePath configured`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            eventTypePath = "$.type"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "type": "payment.completed" }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.eventType == "payment.completed" }) }
    }

    @Test
    fun `should set eventType to null when eventTypePath not configured`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "type": "payment.completed" }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.eventType == null }) }
    }

    @Test
    fun `should set eventType to null when eventTypePath does not match`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            eventTypePath = "$.missing.type"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.eventType == null }) }
    }

    @Test
    fun `should set correct source in message`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        handler.handle("stripe-webhooks", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.source == "stripe-webhooks" }) }
    }

    @Test
    fun `should include payload in stored message`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "data": { "nested": "value" } }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.payload == payload }) }
    }

    // --- Aggregate ID extraction tests ---

    @Test
    fun `should extract aggregateId when aggregateIdPath configured`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            aggregateIdPath = "$.orderId"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "orderId": "order-456" }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.aggregateId == "order-456" }) }
    }

    @Test
    fun `should set aggregateId to null when aggregateIdPath not configured`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "orderId": "order-456" }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.aggregateId == null }) }
    }

    @Test
    fun `should set aggregateId to null when aggregateIdPath does not match`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            aggregateIdPath = "$.missing.path"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "orderId": "order-456" }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.aggregateId == null }) }
    }

    @Test
    fun `should extract nested aggregateId from payload`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            aggregateIdPath = "$.data.customerId"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "data": { "customerId": "cust-789" } }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.aggregateId == "cust-789" }) }
    }

    // --- Transform Tests ---

    @Test
    fun `should work without transform pipeline (backwards compatible)`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val handlerWithoutPipeline = InboxHandler(mockRepository, extractor)
        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        val result = handlerWithoutPipeline.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Accepted)
        coVerify { mockRepository.store(match { it.payload == payload }) }
    }

    @Test
    fun `should pass through original payload when no transform configured`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val transformPipeline = InboxTransformPipeline(TransformEngine())
        val handlerWithPipeline = InboxHandler(mockRepository, extractor, transformPipeline = transformPipeline)

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            transform = null  // No transform
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "data": "value" }""")

        val result = handlerWithPipeline.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Accepted)
        coVerify { mockRepository.store(match { it.payload == payload }) }
    }

    @Test
    fun `should apply transform and store transformed payload`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val transformPipeline = InboxTransformPipeline(TransformEngine())
        val handlerWithPipeline = InboxHandler(mockRepository, extractor, transformPipeline = transformPipeline)

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            transform = TransformConfig(
                expression = """{ "transformedId": id, "normalized": true }"""
            )
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "data": "value" }""")

        val result = handlerWithPipeline.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Accepted)
        coVerify {
            mockRepository.store(match { msg ->
                val obj = msg.payload.jsonObject
                obj["transformedId"] == JsonPrimitive("123") && obj["normalized"] == JsonPrimitive(true)
            })
        }
    }

    @Test
    fun `should extract idempotency key from original payload before transform`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val transformPipeline = InboxTransformPipeline(TransformEngine())
        val handlerWithPipeline = InboxHandler(mockRepository, extractor, transformPipeline = transformPipeline)

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.originalId",
            transform = TransformConfig(
                expression = """{ "newId": "transformed" }"""  // Transform removes originalId
            )
        )
        val payload = Json.parseToJsonElement("""{ "originalId": "key-from-original" }""")

        val result = handlerWithPipeline.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Accepted)
        // Idempotency key should be from ORIGINAL payload, not transformed
        coVerify { mockRepository.store(match { it.idempotencyKey == "key-from-original" }) }
    }

    @Test
    fun `should extract eventType from original payload before transform`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val transformPipeline = InboxTransformPipeline(TransformEngine())
        val handlerWithPipeline = InboxHandler(mockRepository, extractor, transformPipeline = transformPipeline)

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            eventTypePath = "$.type",
            transform = TransformConfig(
                expression = """{ "data": "transformed" }"""  // Transform removes type field
            )
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "type": "order.created" }""")

        val result = handlerWithPipeline.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Accepted)
        // Event type should be from ORIGINAL payload, not transformed
        coVerify { mockRepository.store(match { it.eventType == "order.created" }) }
    }

    @Test
    fun `should return TransformFailed when transform rejects message`() = runTest {
        val transformPipeline = InboxTransformPipeline(TransformEngine())
        val handlerWithPipeline = InboxHandler(mockRepository, extractor, transformPipeline = transformPipeline)

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            transform = TransformConfig(
                expression = """${"$"}nonExistentFunction()""",  // Will fail
                onError = TransformErrorStrategy.Fail
            )
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        val result = handlerWithPipeline.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.TransformFailed)
        coVerify(exactly = 0) { mockRepository.store(any()) }  // Should NOT store
    }

    @Test
    fun `should use original payload when transform fails with skip strategy`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val transformPipeline = InboxTransformPipeline(TransformEngine())
        val handlerWithPipeline = InboxHandler(mockRepository, extractor, transformPipeline = transformPipeline)

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            transform = TransformConfig(
                expression = """${"$"}nonExistentFunction()""",  // Will fail
                onError = TransformErrorStrategy.Skip  // Use original on failure
            )
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "data": "original" }""")

        val result = handlerWithPipeline.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Accepted)
        coVerify { mockRepository.store(match { it.payload == payload }) }  // Should store original
    }
}
