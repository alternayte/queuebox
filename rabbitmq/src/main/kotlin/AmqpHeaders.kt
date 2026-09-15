package org.nxtspec

import com.rabbitmq.client.LongString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts the typed AMQP headers of a delivery to the stored string map.
 *
 * A text value stays text. A number, a boolean or a timestamp becomes its string form. A nested
 * table or array becomes JSON text. A byte array that is not UTF-8 is stored with the `base64:`
 * prefix.
 */
fun amqpHeaders(headers: Map<String, Any?>?): Map<String, String> =
    headers.orEmpty().mapNotNull { (name, value) -> value?.let { name to amqpValueToString(it) } }.toMap()

private fun amqpValueToString(value: Any): String = when (value) {
    is Map<*, *>, is List<*> -> amqpValueToJson(value).toString()
    else -> amqpScalarToString(value)
}

private fun amqpScalarToString(value: Any): String = when (value) {
    is LongString -> headerValueFromBytes(value.bytes)
    is ByteArray -> headerValueFromBytes(value)
    is java.util.Date -> value.toInstant().toString()
    else -> value.toString()
}

private fun amqpValueToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to amqpValueToJson(v) })
    is List<*> -> JsonArray(value.map(::amqpValueToJson))
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> JsonPrimitive(amqpScalarToString(value))
}
