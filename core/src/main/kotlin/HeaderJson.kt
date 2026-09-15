package org.nxtspec

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The stored form of a header map: one JSON object with a string value per key.
 */
object HeaderJson {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun encode(headers: Map<String, String>): String = Json.encodeToString(serializer, headers)

    fun decode(json: String): Map<String, String> = Json.decodeFromString(serializer, json)

    fun toElement(headers: Map<String, String>): JsonElement = JsonObject(headers.mapValues { JsonPrimitive(it.value) })

    fun fromElement(element: JsonElement): Map<String, String> =
        (element as JsonObject).mapValues { it.value.jsonPrimitive.content }
}

/**
 * Converts raw header bytes to the stored string form. Valid UTF-8 is kept as text. Any other
 * value is stored as `base64:` followed by the Base64 of the bytes, so no header is lost.
 */
fun headerValueFromBytes(bytes: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        "base64:" + java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
