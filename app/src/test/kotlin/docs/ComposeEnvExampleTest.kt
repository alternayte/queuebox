package docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F-073 and F-074: the Compose files and `.env.example` must configure QueueBox.
 *
 * The application reads the `QUEUEBOX_` prefixed variables. A variable with another name reaches
 * the application only when the packaged YAML happens to interpolate it, which ties the
 * deployment to a build artifact. `.env.example` must document the QueueBox variables, not the
 * keys of an unrelated tool.
 */
class ComposeEnvExampleTest {

    private val repositoryRoot = File("..").canonicalFile

    private fun read(name: String): String {
        val file = File(repositoryRoot, name)
        assertTrue(file.exists(), "$name must exist at ${file.path}")
        return file.readText()
    }

    /** The variable names that the `environment:` block of the `queuebox` service sets. */
    private fun appServiceVariables(composeFile: String): List<String> {
        val text = read(composeFile)
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == "environment:" }
        assertTrue(start >= 0, "$composeFile must set an environment block for the queuebox service")
        val names = mutableListOf<String>()
        for (line in lines.drop(start + 1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (!trimmed.startsWith("- ")) break
            names += trimmed.removePrefix("- ").substringBefore("=").trim()
        }
        return names
    }

    @Test
    fun `every variable docker-compose sets for queuebox is a QUEUEBOX variable`() {
        val names = appServiceVariables("docker-compose.yml")
        assertTrue(names.isNotEmpty(), "the queuebox service must set at least one variable")
        val foreign = names.filterNot { it.startsWith("QUEUEBOX_") }
        assertTrue(foreign.isEmpty(), "docker-compose.yml sets variables the application does not read: $foreign")
    }

    @Test
    fun `every variable the override file sets for queuebox is a QUEUEBOX variable`() {
        val names = appServiceVariables("docker-compose.override.yml")
        val foreign = names.filterNot { it.startsWith("QUEUEBOX_") }
        assertTrue(
            foreign.isEmpty(),
            "docker-compose.override.yml sets variables the application does not read: $foreign"
        )
    }

    @Test
    fun `env example documents the required QueueBox variables`() {
        val text = read(".env.example")
        val required = listOf(
            "QUEUEBOX_DATABASE_URL",
            "QUEUEBOX_DATABASE_USERNAME",
            "QUEUEBOX_DATABASE_PASSWORD",
            "QUEUEBOX_SERVER_HTTPPORT"
        )
        val missing = required.filterNot { text.contains(it) }
        assertTrue(missing.isEmpty(), ".env.example does not document: $missing")
    }

    @Test
    fun `env example documents no AI provider key`() {
        val text = read(".env.example")
        val keys = Regex("(?m)^\\s*([A-Z0-9_]*API_KEY)\\s*=").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(keys.isEmpty(), ".env.example still documents keys QueueBox does not read: $keys")
    }

    @Test
    fun `env example shows one destination one route and one source`() {
        val text = read(".env.example")
        for (marker in listOf("destinations:", "routes:", "sources:")) {
            assertTrue(text.contains(marker), ".env.example must show an example `$marker` entry")
        }
    }
}
