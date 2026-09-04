package org.nxtspec

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Fifth review gate, a non-blocking finding that is still worth closing.
 *
 * The rejection reason reaches the 400 response body, and the caller of an inbox source is an
 * untrusted webhook sender. The reason used to carry the configured JSONPath, which is internal
 * configuration. The operator still needs that value, so the log line carries it instead.
 */
class InboxHandlerDisclosureTest {

    @Test
    fun `the rejection reason never carries the configured idempotency key path`() = runTest {
        val secretLookingPath = "$.internal.tenant.secretRef"
        // The extraction fails before any repository call, so a bare mock is enough.
        val handler = InboxHandler(mockk(relaxed = true), IdempotencyExtractor())

        val result = handler.handle(
            source = "stripe",
            sourceConfig = SourceConfig.Http(path = "/stripe", idempotencyKeyPath = secretLookingPath),
            payload = Json.parseToJsonElement("""{"unrelated":"value"}""")
        )

        val failed = assertIs<InboxHandlerResult.ExtractionFailed>(result)
        assertFalse(
            failed.reason.contains(secretLookingPath),
            "the response reason discloses the configured path: ${failed.reason}"
        )
        assertFalse(failed.reason.contains("$."), "the response reason carries a JSONPath: ${failed.reason}")
    }
}
