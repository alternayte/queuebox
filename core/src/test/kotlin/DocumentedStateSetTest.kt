package org.nxtspec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-077. The state set in `docs/architecture.md` must equal the set of state literals that the
 * repositories write. The test reads both sides at run time, so a change on either side breaks it.
 */
class DocumentedStateSetTest {

    private val repositoryRoot: File = findRepositoryRoot()

    private val architectureDocument: File = File(repositoryRoot, "docs/architecture.md")

    private val outboxRepositorySources = listOf(
        "postgres/src/main/kotlin/OutboxRepository.kt",
        "sqlserver/src/main/kotlin/org/nxtspec/SqlServerOutboxRepository.kt"
    )

    private val inboxRepositorySources = listOf(
        "postgres/src/main/kotlin/InboxRepository.kt",
        "sqlserver/src/main/kotlin/org/nxtspec/SqlServerInboxRepository.kt"
    )

    private val migrationSources = listOf(
        "postgres/src/main/resources/db/postgresql/V1__create_outbox.sql",
        "sqlserver/src/main/resources/db/sqlserver/V1__create_outbox.sql"
    )

    @Test
    fun `documented outbox states equal the literals the outbox repositories write`() {
        val documented = documentedStates("outbox")
        val written = writtenStates(outboxRepositorySources)
        assertSetsAreEqual("outbox", documented, written)
    }

    @Test
    fun `documented inbox states equal the literals the inbox repositories write`() {
        val documented = documentedStates("inbox")
        val written = writtenStates(inboxRepositorySources)
        assertSetsAreEqual("inbox", documented, written)
    }

    @Test
    fun `every written state maps to a MessageState variant`() {
        val variants = setOf("Pending", "Processing", "Sent", "Dead", "Failed")
        val mapped = MessageState::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        assertEquals(variants, mapped, "MessageState changed. Correct docs/architecture.md too.")
        val written = writtenStates(outboxRepositorySources) + writtenStates(inboxRepositorySources)
        assertTrue(written.isNotEmpty(), "No state literal was found in the repository sources.")
    }

    @Test
    fun `the documented column width equals the migration column width`() {
        val widths = migrationSources
            .map { readSource(it) }
            .flatMap { WIDTH_PATTERN.findAll(it).map { match -> match.groupValues[1] }.toList() }
            .toSet()
        assertEquals(setOf("50"), widths, "The migrations changed the state column width.")
        val documentText = readDocument()
        assertTrue(
            documentText.contains("VARCHAR(50)"),
            "docs/architecture.md must state the real column width VARCHAR(50)."
        )
    }

    private fun assertSetsAreEqual(name: String, documented: Set<String>, written: Set<String>) {
        val undocumented = written - documented
        val extra = documented - written
        assertTrue(
            undocumented.isEmpty(),
            "The $name states $undocumented are written by the code and absent from docs/architecture.md."
        )
        assertTrue(
            extra.isEmpty(),
            "The $name states $extra are in docs/architecture.md and no repository writes them."
        )
        assertEquals(written, documented, "The documented $name state set differs from the code.")
    }

    /** Reads the state names from the fenced block that carries the marker for [name]. */
    private fun documentedStates(name: String): Set<String> {
        val text = readDocument()
        val marker = "<!-- states:$name -->"
        val markerIndex = text.indexOf(marker)
        assertTrue(markerIndex >= 0, "docs/architecture.md must contain the marker $marker.")
        val afterMarker = text.substring(markerIndex + marker.length)
        val start = afterMarker.indexOf("```")
        assertTrue(start >= 0, "The marker $marker must be followed by a fenced block.")
        val bodyStart = afterMarker.indexOf('\n', start) + 1
        val end = afterMarker.indexOf("```", bodyStart)
        assertTrue(end >= 0, "The fenced block after $marker is not closed.")
        return afterMarker.substring(bodyStart, end)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** Reads every state literal that the named repository sources write or match. */
    private fun writtenStates(sources: List<String>): Set<String> = sources
        .map { readSource(it) }
        .flatMap { source -> STATE_PATTERNS.flatMap { pattern -> literalsOf(pattern, source) } }
        .toSet()

    private fun literalsOf(pattern: Regex, source: String): List<String> =
        pattern.findAll(source).map { it.groupValues[1] }.toList()

    private fun readDocument(): String {
        assertTrue(
            architectureDocument.isFile,
            "docs/architecture.md does not exist at ${architectureDocument.path}."
        )
        return architectureDocument.readText()
    }

    private fun readSource(relativePath: String): String {
        val file = File(repositoryRoot, relativePath)
        assertTrue(file.isFile, "The source $relativePath does not exist.")
        return file.readText()
    }

    private fun findRepositoryRoot(): File {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile) return candidate
            candidate = candidate.parentFile
        }
        error("The repository root was not found from ${File(".").absolutePath}.")
    }

    private companion object {
        val WIDTH_PATTERN = Regex("""state\s+N?VARCHAR\((\d+)\)""", RegexOption.IGNORE_CASE)

        /** Each pattern matches one way a repository names a state literal. */
        val STATE_PATTERNS = listOf(
            Regex("""\[\s*(?:table\.)?state\s*\]\s*=\s*"([a-z]+)""""),
            Regex("""updateState\([^,]+,\s*"([a-z]+)"\)"""),
            Regex("""state\s+eq\s+"([a-z]+)""""),
            Regex("""stateCol\s*=\s*'([a-z]+)'"""),
            Regex("""\bstate\s*=\s*'([a-z]+)'"""),
            Regex("""VALUES\s*\([^)]*'([a-z]+)'[^)]*\)""")
        )
    }
}
