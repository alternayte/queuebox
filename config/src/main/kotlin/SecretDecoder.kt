package org.nxtspec

import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.decoder.NullHandlingDecoder
import com.sksamuel.hoplite.fp.invalid
import com.sksamuel.hoplite.fp.valid
import kotlin.reflect.KType

/**
 * Reads a [Secret] from a plain configuration string. See F-038 and F-045.
 *
 * The decoder calls [Secret.of], so a `file:` reference resolves at load time. A failure names
 * the path only, never the content.
 */
class SecretDecoder : NullHandlingDecoder<Secret> {

    override fun supports(type: KType): Boolean = type.classifier == Secret::class

    override fun safeDecode(node: Node, type: KType, context: DecoderContext): ConfigResult<Secret> = when (node) {
        is StringNode -> try {
            Secret.of(node.value).valid()
        } catch (e: IllegalArgumentException) {
            ConfigFailure.Generic(e.message ?: "Cannot read the secret").invalid()
        }

        // A DecodeError renders the offending node, which can be the credential itself.
        // The failure therefore names the path and the expected type only.
        else -> ConfigFailure.Generic(
            "A secret at '${node.path.flatten()}' must be a string. Found ${node::class.simpleName}."
        ).invalid()
    }
}
