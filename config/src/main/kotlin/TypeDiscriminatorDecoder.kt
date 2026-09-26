package org.nxtspec

import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.MapNode
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.Undefined
import com.sksamuel.hoplite.decoder.NullHandlingDecoder
import com.sksamuel.hoplite.fp.flatMap
import com.sksamuel.hoplite.fp.invalid
import kotlinx.serialization.SerialName
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.findAnnotation

/**
 * The kinds of each sealed configuration class, keyed by the `type` value that selects them.
 *
 * The `type` value of a kind is its `@SerialName`. A sealed class that the configuration writes
 * without `type` has a default kind here. Only an inbox source has one: HTTP is the one kind with
 * an obvious default, and the quick start relies on it. See `docs/specs/strict-config.md`.
 */
internal object ConfigKinds {

    /** The key that selects the kind. */
    const val TYPE_KEY = "type"

    private val defaults: Map<KClass<*>, KClass<*>> = mapOf(SourceConfig::class to SourceConfig.Http::class)

    /** The kinds of [sealed], in declaration order, keyed by their `type` value. */
    fun of(sealed: KClass<*>): Map<String, KClass<*>> = sealed.sealedSubclasses.associateBy { kind ->
        kind.findAnnotation<SerialName>()?.value ?: error("${kind.qualifiedName} has no @SerialName")
    }

    /** The kind that applies when an entry of [sealed] carries no `type`, or null if `type` is required. */
    fun default(sealed: KClass<*>): KClass<*>? = defaults[sealed]
}

/**
 * Decodes a sealed configuration class by its `type` key.
 *
 * Hoplite's own sealed decoder ignores `type`. It picks the kind whose constructor the keys fit,
 * so a typo could turn one kind into another in silence, which is issue #65. This decoder reads
 * `type`, or the default kind of [ConfigKinds], and decodes that kind only. [StrictConfigCheck]
 * reports a missing or unknown `type` before decoding starts, so the failures here are a backstop.
 */
class TypeDiscriminatorDecoder : NullHandlingDecoder<Any> {

    override fun supports(type: KType): Boolean = (type.classifier as? KClass<*>)?.isSealed == true

    override fun safeDecode(node: Node, type: KType, context: DecoderContext): ConfigResult<Any> {
        val sealed = type.classifier as KClass<*>
        if (node !is MapNode) {
            return ConfigFailure.Generic("'${node.path.flatten()}' must be a map with a type key").invalid()
        }
        val kinds = ConfigKinds.of(sealed)
        val typeNode = node.atKey(ConfigKinds.TYPE_KEY)
        val kind = when (typeNode) {
            is StringNode -> kinds[typeNode.value]
            is Undefined -> ConfigKinds.default(sealed)
            else -> null
        } ?: return ConfigFailure.Generic(
            "'${node.path.flatten()}' needs a type, one of ${kinds.keys.joinToString(", ")}"
        ).invalid()

        val kindType = kind.createType()
        @Suppress("UNCHECKED_CAST")
        return context.decoder(kindType).flatMap { decoder ->
            decoder.decode(node, kindType, context) as ConfigResult<Any>
        }
    }
}
