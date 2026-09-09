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
        // also a denial of service.
        //
        // A wall-clock bound on one run is not a fit measure on a shared or loaded machine. This
        // test times the sanitiser at a small size and at a size four times as large, both over
        // several repetitions, and compares the growth of the total time to the growth of the
        // length. A linear implementation takes about four times as long. A quadratic one takes
        // about sixteen times as long. The ratio threshold sits between the two, far from both,
        // so a slow machine cannot push a linear result across it, and a quadratic result cannot
        // hide under it.
        fun textOfLength(length: Int) = "amqp://user:aa  bb@rabbit:5672/vh " + "x".repeat(length)

        val smallText = textOfLength(12_500)
        val largeText = textOfLength(50_000)
        val repetitions = 20

        // A JVM pays a one-time cost for class loading and JIT compilation on the first calls to
        // a code path. Without a warm-up, that fixed cost would dominate the small-size timing
        // and understate the apparent growth, letting a quadratic implementation pass by chance.
        repeat(5) {
            ErrorSanitizer.sanitize(smallText)
            ErrorSanitizer.sanitize(largeText)
        }

        val smallElapsed = measureTime { repeat(repetitions) { ErrorSanitizer.sanitize(smallText) } }
        val largeElapsed = measureTime { repeat(repetitions) { ErrorSanitizer.sanitize(largeText) } }

        val growthRatio = largeElapsed.inWholeNanoseconds.toDouble() /
            smallElapsed.inWholeNanoseconds.coerceAtLeast(1).toDouble()

        assertTrue(
            growthRatio < 9.0,
            "A four fold increase in length took the sanitiser $growthRatio times as long " +
                "(small: $smallElapsed, large: $largeElapsed), which means it is quadratic again"
        )
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
