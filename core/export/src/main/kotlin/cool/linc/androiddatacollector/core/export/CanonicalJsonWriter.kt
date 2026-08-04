package cool.linc.androiddatacollector.core.export

import java.io.Closeable
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer

/** Small RFC 8785 writer that enforces lexical member order while streaming bundle events. */
internal class CanonicalJsonWriter(output: OutputStream) : Closeable {
    private val output: Writer = OutputStreamWriter(output, Charsets.UTF_8)
    private val scopes = ArrayDeque<Scope>()
    private var rootWritten = false

    fun beginObject(): CanonicalJsonWriter {
        beforeValue()
        output.write("{")
        scopes.addLast(Scope.Object())
        return this
    }

    fun endObject(): CanonicalJsonWriter {
        val scope = scopes.removeLastOrNull() as? Scope.Object
            ?: throw IllegalStateException("Not inside an object")
        require(!scope.awaitingValue) { "Object member is missing a value" }
        output.write("}")
        return this
    }

    fun beginArray(): CanonicalJsonWriter {
        beforeValue()
        output.write("[")
        scopes.addLast(Scope.Array())
        return this
    }

    fun endArray(): CanonicalJsonWriter {
        require(scopes.removeLastOrNull() is Scope.Array) { "Not inside an array" }
        output.write("]")
        return this
    }

    fun name(name: String): CanonicalJsonWriter {
        val scope = scopes.lastOrNull() as? Scope.Object
            ?: throw IllegalStateException("A name requires an object")
        require(!scope.awaitingValue) { "Previous object member is missing a value" }
        require(scope.lastName == null || scope.lastName!! < name) { "Object members are not in canonical order" }
        if (!scope.first) output.write(",")
        writeString(name)
        output.write(":")
        scope.first = false
        scope.lastName = name
        scope.awaitingValue = true
        return this
    }

    fun value(value: String?): CanonicalJsonWriter {
        beforeValue()
        if (value == null) output.write("null") else writeString(value)
        return this
    }

    fun value(value: Int): CanonicalJsonWriter {
        beforeValue()
        output.write(value.toString())
        return this
    }

    fun value(value: Boolean): CanonicalJsonWriter {
        beforeValue()
        output.write(value.toString())
        return this
    }

    fun nullValue(): CanonicalJsonWriter {
        beforeValue()
        output.write("null")
        return this
    }

    fun valueCanonicalInteger(value: String): CanonicalJsonWriter {
        require(CANONICAL_INTEGER.matches(value)) { "Protocol JSON integer is not canonical" }
        beforeValue()
        output.write(value)
        return this
    }

    fun valueDecimal(value: Long): CanonicalJsonWriter {
        require(value >= 0) { "Protocol decimal values must be non-negative" }
        return value(value.toString())
    }

    fun rawCanonicalJson(value: ByteArray): CanonicalJsonWriter {
        beforeValue()
        output.write(value.toString(Charsets.UTF_8))
        return this
    }

    fun flush() = output.flush()

    override fun close() {
        require(scopes.isEmpty()) { "Incomplete JSON document" }
        require(rootWritten) { "Empty JSON document" }
        output.close()
    }

    private fun beforeValue() {
        when (val scope = scopes.lastOrNull()) {
            null -> {
                require(!rootWritten) { "Multiple JSON roots" }
                rootWritten = true
            }
            is Scope.Object -> {
                require(scope.awaitingValue) { "Object value requires a member name" }
                scope.awaitingValue = false
            }
            is Scope.Array -> {
                if (!scope.first) output.write(",")
                scope.first = false
            }
        }
    }

    private fun writeString(value: String) {
        output.write("\"")
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '"' -> output.write("\\\"")
                '\\' -> output.write("\\\\")
                '\b' -> output.write("\\b")
                '\t' -> output.write("\\t")
                '\n' -> output.write("\\n")
                '\u000c' -> output.write("\\f")
                '\r' -> output.write("\\r")
                else -> when {
                    character < ' ' -> output.write("\\u%04x".format(character.code))
                    character.isHighSurrogate() -> {
                        require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                            "Invalid Unicode surrogate"
                        }
                        output.write(character.code)
                        output.write(value[++index].code)
                    }
                    character.isLowSurrogate() -> throw IllegalArgumentException("Invalid Unicode surrogate")
                    else -> output.write(character.code)
                }
            }
            index++
        }
        output.write("\"")
    }

    private sealed interface Scope {
        data class Object(
            var first: Boolean = true,
            var lastName: String? = null,
            var awaitingValue: Boolean = false,
        ) : Scope

        data class Array(var first: Boolean = true) : Scope
    }

    private companion object {
        val CANONICAL_INTEGER = Regex("-?(0|[1-9][0-9]*)")
    }
}
