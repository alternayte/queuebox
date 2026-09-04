package org.nxtspec

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Carries one credential. See F-038 and F-045.
 *
 * `toString` returns a mask, so a log line, an exception message, or a crash dump that prints a
 * configuration object cannot leak the credential. Only [reveal] returns the value, and every
 * call to it is a place to check when you audit the code.
 *
 * [of] resolves a `file:` reference, so an operator can mount a Kubernetes secret and point the
 * configuration at the path.
 */
@Serializable(with = SecretSerializer::class)
@JvmInline
value class Secret(private val raw: String) {

    /** Returns the credential. Never pass the result to a log. */
    fun reveal(): String = raw

    fun isBlank(): Boolean = raw.isBlank()

    fun isNotBlank(): Boolean = raw.isNotBlank()

    override fun toString(): String = if (raw.isEmpty()) EMPTY_MASK else MASK

    companion object {
        const val MASK: String = "Secret(***)"
        const val EMPTY_MASK: String = "Secret(empty)"
        const val FILE_PREFIX: String = "file:"

        /**
         * Builds a secret from a configured value.
         *
         * A value that starts with `file:` names a file that holds the credential. The file is
         * read once, at load time, and the trailing newline is removed, because an editor and a
         * shell both add one.
         *
         * @throws IllegalArgumentException when the file is missing or unreadable
         */
        fun of(value: String): Secret {
            if (!value.startsWith(FILE_PREFIX)) return Secret(value)

            val path = value.removePrefix(FILE_PREFIX)
            val file = Path.of(path)

            require(Files.isReadable(file)) {
                "Cannot read the secret file '$path'. Check that the path exists and that the " +
                    "process can read it."
            }

            val contents = try {
                Files.readString(file)
            } catch (e: Exception) {
                // The message names the path only. It never names the content.
                throw IllegalArgumentException("Cannot read the secret file '$path'", e)
            }

            return Secret(contents.trimEnd('\n', '\r'))
        }
    }
}

/**
 * Reads and writes a [Secret] as a plain string, so a configuration file needs no wrapper.
 */
object SecretSerializer : KSerializer<Secret> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.nxtspec.Secret", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Secret = Secret.of(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Secret) {
        encoder.encodeString(value.reveal())
    }
}
