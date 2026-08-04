package cool.linc.androiddatacollector.core.definition

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The one strict, integral-only RFC 8785 implementation shared by Protocol v1 readers. */
object ProtocolCanonicalJson {
    fun requireCanonical(bytes: ByteArray, maximumBytes: Int): JsonElement {
        val decoded = parse(bytes, maximumBytes)
        require(encode(decoded).contentEquals(bytes)) { "JSON is not canonical" }
        return decoded
    }

    fun canonicalize(bytes: ByteArray, maximumBytes: Int): ByteArray =
        encode(parse(bytes, maximumBytes))

    fun parse(bytes: ByteArray, maximumBytes: Int): JsonElement {
        require(maximumBytes > 0 && bytes.size in 1..maximumBytes) { "Invalid JSON size" }
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { throw IllegalArgumentException("JSON is not valid UTF-8", it) }
        val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
        return readStrict(reader).also {
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content" }
        }
    }

    fun encode(element: JsonElement): ByteArray = buildString {
        appendCanonical(element)
    }.toByteArray(Charsets.UTF_8)

    private fun readStrict(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JsonObject().also { result ->
            reader.beginObject()
            val names = mutableSetOf<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(names.add(name)) { "Duplicate JSON member: $name" }
                result.add(name, readStrict(reader))
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JsonArray().also { result ->
            reader.beginArray()
            while (reader.hasNext()) result.add(readStrict(reader))
            reader.endArray()
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonParser.parseString(reader.nextString())
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> JsonNull.INSTANCE.also { reader.nextNull() }
        else -> throw IllegalArgumentException("Invalid JSON value")
    }

    /** Protocol v1 permits only integral JSON numbers, so no floating-point JCS branch exists. */
    private fun StringBuilder.appendCanonical(element: JsonElement) {
        when {
            element.isJsonNull -> append("null")
            element.isJsonArray -> {
                append('[')
                element.asJsonArray.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendCanonical(item)
                }
                append(']')
            }
            element.isJsonObject -> {
                append('{')
                element.asJsonObject.keySet().sorted().forEachIndexed { index, name ->
                    if (index > 0) append(',')
                    appendJsonString(name)
                    append(':')
                    appendCanonical(element.asJsonObject.get(name))
                }
                append('}')
            }
            element.asJsonPrimitive.isString -> appendJsonString(element.asString)
            element.asJsonPrimitive.isBoolean -> append(element.asBoolean)
            else -> {
                val integer = runCatching { BigDecimal(element.asString).toBigIntegerExact() }.getOrNull()
                require(integer != null) { "Protocol JSON numbers must be integral" }
                append(integer)
            }
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")
                else -> when {
                    character < ' ' -> append("\\u%04x".format(character.code))
                    character.isHighSurrogate() -> {
                        require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                            "Invalid Unicode surrogate"
                        }
                        append(character).append(value[++index])
                    }
                    character.isLowSurrogate() -> throw IllegalArgumentException("Invalid Unicode surrogate")
                    else -> append(character)
                }
            }
            index++
        }
        append('"')
    }
}
