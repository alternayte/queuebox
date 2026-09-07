package org.nxtspec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Runs the shared redaction corpus. See `clients/redaction-corpus.json`.
 *
 * QueueBox and every client library redact the same text in the same way. A library that redacts
 * ALMOST the same as the others is a security defect, not a difference of idiom, so the cases
 * live in one file that all of them read.
 */
class RedactionCorpusTest {

    @Serializable
    private data class Case(
        val name: String,
        val input: String,
        val expected: String? = null,
        val mustNotContain: List<String> = emptyList(),
        val mustContain: List<String> = emptyList()
    )

    @Serializable
    private data class Corpus(val maxLength: Int, val truncationMarker: String, val cases: List<Case>)

    private val corpus: Corpus by lazy {
        val file = repositoryRoot().resolve("clients/redaction-corpus.json")
        JSON.decodeFromString<Corpus>(file.readText())
    }

    @Test
    fun `the corpus states the same maximum length as the sanitiser`() {
        assertEquals(ErrorSanitizer.MAX_LENGTH, corpus.maxLength)
    }

    @Test
    fun `every case of the shared corpus holds`() {
        assertTrue(corpus.cases.isNotEmpty(), "The corpus holds no case")

        corpus.cases.forEach { case ->
            val result = ErrorSanitizer.sanitize(case.input)!!

            if (case.expected != null) {
                assertEquals(case.expected, result, "The case '${case.name}' produced a different text")
            }

            case.mustNotContain.forEach { secret ->
                assertFalse(result.contains(secret), "The case '${case.name}' printed '$secret': $result")
            }

            case.mustContain.forEach { kept ->
                assertTrue(result.contains(kept), "The case '${case.name}' lost '$kept': $result")
            }
        }
    }

    @Test
    fun `the redaction stays linear in the length of the text`() {
        // The key prefix and the scheme name were unbounded, so the patterns were quadratic and a
        // five thousand character message cost hundreds of milliseconds. The redaction runs on
        // every failure, and the length of an error text is not ours to choose, so the cost was
        // also a denial of service. This bound is generous: the quadratic version needed minutes.
        val text = "amqp://user:aa  bb@rabbit:5672/vh " + "x".repeat(50_000)

        val elapsed = measureTime { ErrorSanitizer.sanitize(text) }

        assertTrue(elapsed.inWholeSeconds < 5, "The redaction took $elapsed, which means it is quadratic again")
    }

    private companion object {
        // The corpus carries a comment key, so the parser ignores what it does not know.
        private val JSON = Json { ignoreUnknownKeys = true }
    }

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir"))

        while (directory != null && !File(directory, ".git").exists()) {
            directory = directory.parentFile
        }

        return directory ?: error("The repository root was not found")
    }
}
