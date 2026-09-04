package docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F-085 and section 10 of `hardening-doc.md`. One test per stated guarantee.
 *
 * The `## Guarantees` section of the README ends each guarantee with a `Proved by` line. That
 * line names the test that proves the sentence. A name that points at nothing turns the section
 * into a promise with no evidence. Each test here takes one guarantee, reads the names out of its
 * own `Proved by` line, and confirms that every named test class and test method exists.
 *
 * A named class of the `app` module resolves by reflection. The build gives the test task of a
 * module no access to the test classes of another module, so a class of `postgres`,
 * `outbox-service` or `inbox-service` cannot load here. For that class the test falls back to the
 * checked-in source file, and confirms the class declaration and the method declaration there.
 */
class GuaranteesTest {

    @Test
    fun `the at-least-once guarantee names tests that exist`() {
        assertProofOf("Delivery is at-least-once")
    }

    @Test
    fun `the ordering guarantee names tests that exist`() {
        assertProofOf("Ordering holds for one aggregate")
    }

    @Test
    fun `the crash duplicate guarantee names tests that exist`() {
        assertProofOf("A crash can produce a duplicate delivery")
    }

    @Test
    fun `the inbox deduplication guarantee names tests that exist`() {
        assertProofOf("The inbox deduplicates on")
    }

    @Test
    fun `the transform strategy guarantee names tests that exist`() {
        assertProofOf("A transform error follows the strategy you configure")
    }

    @Test
    fun `every guarantee of the readme carries a proof line`() {
        val paragraphs = guaranteeParagraphs()
        assertTrue(paragraphs.size >= EXPECTED_GUARANTEES, "the README states ${paragraphs.size} guarantees")
        val unproved = paragraphs.filterNot { it.contains(PROVED_BY) }
        assertTrue(unproved.isEmpty(), "a guarantee carries no proof line: $unproved")
    }

    /**
     * Reads the guarantee that starts with [opening] and checks every test it names.
     */
    private fun assertProofOf(opening: String) {
        val paragraph = guaranteeParagraphs().firstOrNull { it.contains(opening) }
            ?: fail("the Guarantees section of README.md states no guarantee that starts with `$opening`")

        val references = referencesIn(paragraph)
        assertTrue(
            references.isNotEmpty(),
            "the guarantee `$opening` names no test. Add a `Proved by` line that names one."
        )

        val missing = references.filterNot { exists(it) }
        assertTrue(
            missing.isEmpty(),
            "the guarantee `$opening` names a test that does not exist: " +
                missing.joinToString(", ") { it.description() }
        )
    }

    /**
     * One test that the README names.
     */
    private data class Reference(val className: String, val methodName: String?) {
        fun description(): String = if (methodName == null) className else "$className.$methodName"
    }

    /**
     * Splits the `## Guarantees` section into the blank-line separated paragraphs.
     */
    private fun guaranteeParagraphs(): List<String> = guaranteesSection()
        .split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.startsWith("**") }

    /**
     * Returns the body of the `## Guarantees` section of the README.
     */
    private fun guaranteesSection(): String {
        val text = File(File("..").canonicalFile, "README.md").readText()
        val start = text.indexOf("\n## Guarantees\n")
        assertTrue(start >= 0, "README.md holds no `## Guarantees` section")
        val body = text.substring(start + 1).substringAfter("\n")
        val end = body.indexOf("\n## ")
        return if (end >= 0) body.substring(0, end) else body
    }

    /**
     * Reads the test names out of the `Proved by` line of one guarantee.
     *
     * A name arrives inside a pair of backticks. The README wraps a long name over two lines, so
     * the reader collapses the white space inside a name first.
     */
    private fun referencesIn(paragraph: String): List<Reference> {
        val proof = paragraph.substringAfter(PROVED_BY, "")
        return BACKTICK.findAll(proof)
            .map { it.groupValues[1].replace(Regex("\\s+"), " ").trim() }
            .filter { TEST_NAME.matches(it) }
            .map { token ->
                val separator = token.indexOf('.')
                if (separator < 0) {
                    Reference(token, null)
                } else {
                    Reference(token.substring(0, separator), token.substring(separator + 1))
                }
            }
            .toList()
    }

    /**
     * Reports whether the named class, and the named method on it, exist.
     */
    private fun exists(reference: Reference): Boolean {
        val loaded = load(reference.className)
        if (loaded != null) {
            val method = reference.methodName ?: return true
            return loaded.declaredMethods.any { it.name == method }
        }
        return existsInSource(reference)
    }

    /**
     * Loads a test class of this module by reflection.
     */
    private fun load(simpleName: String): Class<*>? {
        for (packageName in TEST_PACKAGES) {
            val binaryName = if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
            try {
                return Class.forName(binaryName, false, javaClass.classLoader)
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
        return null
    }

    /**
     * Confirms a test of another module against its checked-in source file.
     */
    private fun existsInSource(reference: Reference): Boolean {
        val source = sourceFileOf(reference.className) ?: return false
        val text = source.readText()
        if (!Regex("\\bclass\\s+${Regex.escape(reference.className)}\\b").containsMatchIn(text)) return false
        val method = reference.methodName ?: return true
        return text.contains("fun `$method`(")
    }

    /**
     * Finds the Kotlin test source file that declares the named class.
     */
    private fun sourceFileOf(simpleName: String): File? {
        val root = File("..").canonicalFile
        return root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .firstOrNull { it.isFile && it.name == "$simpleName.kt" && it.path.contains(TEST_SOURCE_PATH) }
    }

    private companion object {
        const val EXPECTED_GUARANTEES = 5
        const val PROVED_BY = "Proved by"
        const val TEST_SOURCE_PATH = "src/test/kotlin"

        /** The packages that hold the test classes of the `app` module. */
        val TEST_PACKAGES = listOf("org.nxtspec.e2e", "org.nxtspec.app", "docs", "")

        val BACKTICK = Regex("`([^`]+)`")

        /** A class name, and an optional method name after the first dot. */
        val TEST_NAME = Regex("[A-Z][A-Za-z0-9]*Test(\\..+)?")
    }
}
