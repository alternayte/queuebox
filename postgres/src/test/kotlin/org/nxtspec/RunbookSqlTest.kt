package org.nxtspec

import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F-054 and F-055.
 *
 * Executes every fenced `sql` block of the two operations documents against the shipped schema.
 * The SQL is never pasted into this test. A document that drifts from the schema fails here.
 */
class RunbookSqlTest : PostgresTestBase() {

    companion object {
        /** The placeholder convention that both documents state. */
        private val PLACEHOLDERS = mapOf(
            ":message_id" to "'00000000-0000-0000-0000-000000000000'",
            ":topic" to "'test-topic'",
            ":state" to "'pending'",
            ":destination" to "'test-http'",
            ":limit" to "10"
        )

        // A cast writes two colons, so the pattern must not treat "::text" as a placeholder.
        private val PLACEHOLDER_PATTERN = Regex("(?<!:):[a-z_]+")

        private val BLOCK_PATTERN = Regex(
            "(?:<!--\\s*sql-id:\\s*([A-Za-z0-9_-]+)\\s*-->\\s*)?```sql[ \\t]*\\r?\\n(.*?)```",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        // Every opening fence of the document. The count must match the count of extracted
        // blocks, so a block cannot escape the extraction because of its fence spelling.
        private val ANY_FENCE_PATTERN = Regex("^```([A-Za-z0-9_+-]*)[ \\t]*$", RegexOption.MULTILINE)

        private fun repoRoot(): File {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            while (!File(dir, "docs/operations").isDirectory) {
                dir = dir.parentFile ?: fail("Repository root not found from ${System.getProperty("user.dir")}")
            }
            return dir
        }

        fun documentFile(name: String): File {
            val file = File(repoRoot(), "docs/operations/$name")
            assertTrue(file.isFile, "Missing document: ${file.absolutePath}")
            return file
        }

        /** A fenced sql block, with its optional `sql-id` name. */
        data class SqlBlock(val id: String?, val body: String)

        fun sqlBlocks(file: File): List<SqlBlock> {
            val text = file.readText()
            val blocks = BLOCK_PATTERN.findAll(text)
                .map { SqlBlock(it.groupValues[1].ifEmpty { null }, it.groupValues[2]) }
                .toList()

            // A fence whose language names SQL in any spelling must be one of the extracted
            // blocks. Without the check a renamed fence would leave a statement untested, and
            // the test would still pass.
            val sqlLikeFences = ANY_FENCE_PATTERN.findAll(text)
                .map { it.groupValues[1].lowercase() }
                .filter { it in SQL_LANGUAGES }
                .count()

            assertEquals(
                sqlLikeFences,
                blocks.size,
                "The extraction missed a fenced SQL block in ${file.name}. Every SQL block must " +
                    "open with three back ticks and the word sql."
            )

            return blocks
        }

        private val SQL_LANGUAGES = setOf("sql", "postgresql", "psql", "plpgsql")

        /** Splits a block into statements. A statement ends with a semicolon at the end of a line. */
        fun statements(body: String): List<String> = body.split(Regex(";\\s*\\n"))
            .map { it.trim().removeSuffix(";").trim() }
            .filter { it.isNotEmpty() }

        /** Replaces every documented placeholder with a test value. */
        fun bind(statement: String): String {
            val bound = PLACEHOLDERS.entries.fold(statement) { acc, (key, value) ->
                acc.replace(key, value)
            }
            val unknown = PLACEHOLDER_PATTERN.findAll(bound)
                .map { it.value }
                .filter { it !in PLACEHOLDERS.keys }
                .toList()
            assertTrue(
                unknown.isEmpty(),
                "Undocumented placeholder $unknown in statement:\n$statement"
            )
            return bound
        }
    }

    /** Runs a block on a plain JDBC connection and commits the work. */
    private fun <T> withConnection(block: (java.sql.Connection) -> T): T = dataSource.connection.use { connection ->
        val result = block(connection)
        if (!connection.autoCommit) connection.commit()
        result
    }

    private fun seedRows() {
        val dead = insertOutboxMessage(state = "dead", attempt = 5)
        insertOutboxMessage(state = "pending", attempt = 2)
        insertOutboxMessage(state = "processing")
        insertOutboxMessage(state = "sent")
        insertInboxMessage(source = "stripe", idempotencyKey = "evt_1", state = "dead")
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE outbox SET last_error = 'timeout after 30000 ms' WHERE id = '$dead'")
            }
        }
    }

    private fun executeDocument(name: String) {
        seedRows()
        val file = documentFile(name)
        val blocks = sqlBlocks(file)
        assertTrue(blocks.isNotEmpty(), "No sql block found in ${file.name}")
        var executed = 0
        withConnection { connection ->
            for (block in blocks) {
                for (raw in statements(block.body)) {
                    val sql = bind(raw)
                    try {
                        connection.createStatement().use { it.execute(sql) }
                        executed++
                    } catch (e: Exception) {
                        fail("${file.name} block '${block.id ?: "unnamed"}' failed:\n$sql\n\n${e.message}")
                    }
                }
            }
        }
        assertTrue(executed > 0, "No statement executed from ${file.name}")
        println("Executed $executed statements from ${file.name}")
    }

    @Test
    fun `every sql statement in the runbook runs against the shipped schema`() {
        executeDocument("runbook.md")
    }

    @Test
    fun `every sql statement in the dead-letter document runs against the shipped schema`() {
        executeDocument("dead-letter.md")
    }

    @Test
    fun `the documented requeue resets the state, the attempt and the schedule`() {
        val id = insertOutboxMessage(state = "dead", attempt = 5)
        val block = sqlBlocks(documentFile("dead-letter.md")).single { it.id == "requeue-one" }
        val sql = statements(block.body).single().replace(":message_id", "'$id'")
        withConnection { connection ->
            connection.createStatement().use { it.executeUpdate(sql) }
        }
        val (state, attempt) = getOutboxMessageStateAndAttempt(UUID.fromString(id.toString()))
        assertTrue(state == "pending", "Expected pending, got $state")
        assertTrue(attempt == 0, "Expected attempt 0, got $attempt")
        assertTrue(getOutboxClaimedAt(id) == null, "Expected claimed_at to be cleared")
        assertTrue(getOutboxLastError(id) == null, "Expected last_error to be cleared")
    }

    @Test
    fun `the runbook covers the five documented scenarios`() {
        val text = documentFile("runbook.md").readText()
        listOf(
            "Scenario 1: Inspect dead-lettered messages",
            "Scenario 2: Replay a dead-lettered message",
            "Scenario 3: The pending gauge grows",
            "Scenario 4: Size the pool and the batch",
            "Scenario 5: A destination is slow"
        ).forEach { heading ->
            assertTrue(text.contains(heading), "Missing scenario heading: $heading")
        }
    }
}
