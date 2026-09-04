package docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F-072, F-082 and F-085: the README is an entry point, not the manual.
 *
 * A reference section belongs in `docs/`. Every link from the README must resolve, or the
 * restructure has lost a section. Every roadmap item must carry a target version or an explicit
 * refusal, so an adopter can plan. The guarantees of an infrastructure component must be stated.
 */
class ReadmeStructureTest {

    private val repositoryRoot = File("..").canonicalFile
    private val readme = File(repositoryRoot, "README.md")
    private val text = readme.readText()

    @Test
    fun `the readme is under 200 lines`() {
        val lines = text.lines().size
        assertTrue(lines < 200, "README.md is $lines lines. F-072 caps it at 200.")
    }

    @Test
    fun `every relative link resolves to a file that exists`() {
        val links = Regex("\\[[^\\]]*]\\(([^)\\s]+)\\)").findAll(text)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("#") }
            .map { it.substringBefore('#') }
            .filter { it.isNotEmpty() }
            .toList()

        assertTrue(links.isNotEmpty(), "the README must link to the documents that hold the detail")
        val broken = links.filterNot { File(repositoryRoot, it).exists() }
        assertTrue(broken.isEmpty(), "README.md links to files that do not exist: $broken")
    }

    @Test
    fun `every roadmap item carries a target version or an explicit refusal`() {
        val section = sectionOf("Roadmap")
        val items = section.lines().filter { it.trimStart().startsWith("- ") || it.trimStart().startsWith("| ") }
            // Drop the header row and the separator row of the table.
            .filterNot { it.trimStart().startsWith("|---") || it.contains("| Target |") }
        assertTrue(items.isNotEmpty(), "the README must hold a roadmap")

        val vague = items.filterNot {
            Regex("\\d+\\.\\d+").containsMatchIn(it) || it.contains("not scheduled") || it.contains("Not planned")
        }
        assertTrue(vague.isEmpty(), "roadmap items carry no version and no refusal: $vague")
    }

    @Test
    fun `the readme states the delivery guarantees`() {
        val section = sectionOf("Guarantees")
        for (word in listOf("at-least-once", "X-Message-Id", "Ordering", "idempotency_key")) {
            assertTrue(section.contains(word), "the Guarantees section does not state `$word`")
        }
    }

    /** The body of a top level section, without its heading. */
    private fun sectionOf(title: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == "## $title" }
        assertTrue(start >= 0, "README.md holds no `## $title` section")
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("## ") }
        return (if (end >= 0) rest.take(end) else rest).joinToString("\n")
    }
}
