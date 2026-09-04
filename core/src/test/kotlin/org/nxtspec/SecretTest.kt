package org.nxtspec

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers F-038 and F-045. A credential must never print, and a `file:` reference must resolve.
 */
class SecretTest {

    @Test
    fun `toString masks the value`() {
        val secret = Secret("super-secret-token")

        assertFalse(secret.toString().contains("super-secret-token"))
        assertEquals("Secret(***)", secret.toString())
    }

    @Test
    fun `toString marks an empty value`() {
        assertEquals("Secret(empty)", Secret("").toString())
    }

    @Test
    fun `reveal returns the value`() {
        assertEquals("super-secret-token", Secret("super-secret-token").reveal())
    }

    @Test
    fun `equality compares the value`() {
        assertEquals(Secret("a"), Secret("a"))
        assertNotEquals(Secret("a"), Secret("b"))
    }

    @Test
    fun `isBlank reports a blank value`() {
        assertTrue(Secret("  ").isBlank())
        assertFalse(Secret("x").isBlank())
    }

    @Test
    fun `of resolves a file reference and trims the trailing newline`() {
        val file = Files.createTempFile("queuebox-secret", ".txt")
        Files.writeString(file, "file-secret-value\n")

        val secret = Secret.of("file:$file")

        assertEquals("file-secret-value", secret.reveal())
        assertFalse(secret.toString().contains("file-secret-value"))

        Files.deleteIfExists(file)
    }

    @Test
    fun `of keeps a plain value`() {
        assertEquals("plain", Secret.of("plain").reveal())
    }

    @Test
    fun `of fails with a message that names the path but not the content`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Secret.of("file:/does/not/exist/queuebox-secret")
        }

        assertTrue(exception.message!!.contains("/does/not/exist/queuebox-secret"))
    }
}
