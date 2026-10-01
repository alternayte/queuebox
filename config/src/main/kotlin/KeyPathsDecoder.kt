package org.nxtspec

import com.sksamuel.hoplite.ArrayNode
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
 * Reads a [KeyPaths] from one string or from a list of strings. See issue #85.
 *
 * Hoplite's list decoder splits a string on every comma, and a JSONPath such as `$['a,b']` holds
 * one. This decoder keeps a string whole, so a configuration with one path loads as before.
 */
class KeyPathsDecoder : NullHandlingDecoder<KeyPaths> {

    override fun supports(type: KType): Boolean = type.classifier == KeyPaths::class

    override fun safeDecode(node: Node, type: KType, context: DecoderContext): ConfigResult<KeyPaths> {
        val paths = when (node) {
            is StringNode -> listOf(node.value)
            is ArrayNode -> node.elements.map { (it as? StringNode)?.value }
            else -> listOf(null)
        }
        return if (paths.all { it != null }) {
            KeyPaths(paths.filterNotNull()).valid()
        } else {
            ConfigFailure.Generic(
                "'${node.path.flatten()}' must be a JSONPath, or a list of JSONPaths."
            ).invalid()
        }
    }
}
