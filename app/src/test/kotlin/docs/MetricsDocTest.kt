package docs

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Tag
import org.nxtspec.DatabaseConfig
import org.nxtspec.DatabaseFactory
import org.nxtspec.Secret
import org.nxtspec.app.MetricsCollector
import org.nxtspec.app.configureMetricsRoutes
import org.nxtspec.metrics.InboxRejectionReason
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F-079: `docs/operations/metrics.md` must list every metric that a live scrape exposes.
 *
 * The test starts the metrics route against a real PostgreSQL container, so the HikariCP pool
 * metrics reach the registry. It then compares the scraped names against the document. A prefix
 * allowlist inside the document covers the families that the document does not enumerate one by
 * one.
 */
@Tag("integration")
class MetricsDocTest {

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(System.getenv("QUEUEBOX_TEST_POSTGRES_IMAGE") ?: "postgres:16")
                .withDatabaseName("queuebox_docs")
                .withUsername("test")
                .withPassword("test")
                .also { it.start() }
    }

    private val documentFile = File(File("..").canonicalFile, "docs/operations/metrics.md")

    /** Drives one call per metric family, so every lazy meter registers before the scrape. */
    private fun exerciseEveryMetric(collector: MetricsCollector) {
        collector.recordMessageSent()
        collector.recordMessageFailed()
        collector.recordMessageDead()
        collector.recordMessageReclaimed(1)
        collector.recordProcessError()
        collector.recordProcessingDuration(5)
        collector.recordPublishDuration(5, "http")
        collector.updatePendingCount(1)
        collector.recordDestinationSuccess("orders-api")
        collector.recordDestinationFailure("orders-api")
        collector.changeQueueDepth("orders-api", 2)
        collector.recordTransformFailure("fail")
        collector.recordHttpStatus(503)
        collector.recordInboxReceived()
        collector.recordInboxDuplicate()
        collector.recordInboxForwarded()
        collector.recordInboxRelayError()
        collector.recordInboxRejection(InboxRejectionReason.EXTRACTION_FAILED)
        collector.recordCleanupRun("outbox", 1, 1_000_000)
    }

    /** The family names of a scrape. The exporter prints one `# TYPE` line per family. */
    private fun scrapedMetricNames(body: String): Set<String> = body.lines()
        .filter { it.startsWith("# TYPE ") }
        .map { it.removePrefix("# TYPE ").trim().substringBefore(' ') }
        .toSet()

    /** The metric names of the document table. The first cell holds the name in backticks. */
    private fun documentedMetricNames(text: String): Set<String> = text.lines()
        .filter { it.trimStart().startsWith("| `") }
        .map { it.split("|")[1].trim().removeSurrounding("`") }
        .toSet()

    /** The prefixes of the `metrics:allowlist` block. */
    private fun allowedPrefixes(text: String): List<String> {
        val start = text.indexOf("<!-- metrics:allowlist -->")
        val end = text.indexOf("<!-- /metrics:allowlist -->")
        assertTrue(
            start >= 0 && end > start,
            "docs/operations/metrics.md must carry a block between " +
                "<!-- metrics:allowlist --> and <!-- /metrics:allowlist -->"
        )
        return text.substring(start, end).lines()
            .filter { it.trimStart().startsWith("- `") }
            .map { it.substringAfter('`').substringBefore('`') }
    }

    @Test
    fun `the document lists every metric of a live scrape`() = testApplication {
        assertTrue(
            documentFile.exists(),
            "docs/operations/metrics.md must exist at ${documentFile.path}"
        )
        val text = documentFile.readText()

        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val dataSource = DatabaseFactory.create(
            DatabaseConfig(
                url = postgres.jdbcUrl,
                username = postgres.username,
                password = Secret(postgres.password),
                poolSize = 2
            ),
            registry
        )
        try {
            // One connection makes the pool report its usage metrics.
            dataSource.connection.use { it.isValid(5) }
            exerciseEveryMetric(MetricsCollector(registry))

            application { configureMetricsRoutes(registry) }
            val body = client.get("/metrics").bodyAsText()

            val scraped = scrapedMetricNames(body)
            val documented = documentedMetricNames(text)
            val prefixes = allowedPrefixes(text)

            val missing = documented - scraped
            assertTrue(
                missing.isEmpty(),
                "docs/operations/metrics.md documents a metric that the scrape does not carry: " +
                    missing.sorted().joinToString(", ")
            )

            val undocumented = scraped
                .filterNot { it in documented }
                .filterNot { name -> prefixes.any { name.startsWith(it) } }
            assertTrue(
                undocumented.isEmpty(),
                "the scrape carries a metric that docs/operations/metrics.md neither documents " +
                    "nor allows by prefix: " + undocumented.sorted().joinToString(", ")
            )
        } finally {
            dataSource.close()
        }
    }
}
