package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Third review gate, defect B5.
 *
 * Nine log sites passed the raw throwable to SLF4J. Logback then prints the message of every
 * cause and of every suppressed exception with no redaction, so a broker URI or a database
 * password reached the container log. Every site now logs
 * `ErrorSanitizer.sanitize(e)` instead. This test holds the property that makes that safe.
 */
class SanitizedLoggingTest {

    @Test
    fun `the sanitised text of a nested cause carries no credential`() {
        val root = IllegalStateException("connect failed amqp://guest:Sup3rS3cret@broker:5672/vh")
        val wrapper = RuntimeException("The poll cycle failed", root)

        val text = ErrorSanitizer.sanitize(wrapper)!!

        assertFalse(text.contains("Sup3rS3cret"), "the broker password printed: $text")
        // An operator still needs the host and the type chain.
        assertTrue(text.contains("broker"), "the host must survive: $text")
        assertTrue(text.contains("IllegalStateException"), "the cause type must survive: $text")
    }

    @Test
    fun `the sanitised text of a jdbc password carries no credential`() {
        val error = RuntimeException("FATAL: password authentication failed, password=hunter2")

        val text = ErrorSanitizer.sanitize(error)!!

        assertFalse(text.contains("hunter2"), "the database password printed: $text")
    }
}
