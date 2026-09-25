package org.nxtspec

import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.decoder.NullHandlingDecoder
import com.sksamuel.hoplite.fp.invalid
import com.sksamuel.hoplite.fp.valid
import kotlinx.serialization.SerialName
import kotlin.reflect.KType

/**
 * Reads a [SignaturePayloadFormat] by its serial name or by its constant name. See issue #63.
 *
 * Hoplite's enum decoder matches the constant name only and ignores `@SerialName`, so the
 * spelling that the validator and the docs name, `timestamp-dot-body`, did not load. Every
 * release before the fix accepted `BODY` and `TIMESTAMP_DOT_BODY`, so those spellings still load.
 */
class SignaturePayloadFormatDecoder : NullHandlingDecoder<SignaturePayloadFormat> {

    private val bySpelling: Map<String, SignaturePayloadFormat> =
        SignaturePayloadFormat.entries.flatMap { format ->
            val serialName = SignaturePayloadFormat::class.java
                .getField(format.name)
                .getAnnotation(SerialName::class.java)
                .value
            listOf(serialName to format, format.name to format)
        }.toMap()

    override fun supports(type: KType): Boolean = type.classifier == SignaturePayloadFormat::class

    override fun safeDecode(node: Node, type: KType, context: DecoderContext): ConfigResult<SignaturePayloadFormat> {
        val value = (node as? StringNode)?.value
        return bySpelling[value]?.valid()
            ?: ConfigFailure.Generic(
                "'${node.path.flatten()}' must be one of ${bySpelling.keys.joinToString { "'$it'" }}."
            ).invalid()
    }
}
