package cool.jacoblin.particeps.core.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalJsonWriterTest {
    @Test
    fun writesCanonicalScalarsAndNestedContainers() {
        val actual = encode {
            beginObject()
            name("a").beginArray()
            value(null)
            nullValue()
            value(true)
            value(false)
            value(Int.MIN_VALUE)
            value(Int.MAX_VALUE)
            valueCanonicalInteger("-42")
            valueDecimal(Long.MAX_VALUE)
            beginObject().name("a").value("v").endObject()
            endArray()
            name("b").beginObject().endObject()
            name("c").rawCanonicalJson("[1,2,3]".toByteArray(Charsets.UTF_8))
            endObject()
        }

        assertUtf8Equals(
            "{\"a\":[null,null,true,false,-2147483648,2147483647,-42,\"9223372036854775807\",{\"a\":\"v\"}]," +
                "\"b\":{},\"c\":[1,2,3]}",
            actual,
        )
    }

    @Test
    fun escapesEveryControlCharacterAndJsonDelimiterExactly() {
        val controls = (0..31).map(Int::toChar).joinToString("")
        val actual = encode { value(controls + "\"\\/\u007f\u2028\u2029") }

        assertUtf8Equals(
            "\"\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007" +
                "\\b\\t\\n\\u000b\\f\\r\\u000e\\u000f" +
                "\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017" +
                "\\u0018\\u0019\\u001a\\u001b\\u001c\\u001d\\u001e\\u001f" +
                "\\\"\\\\/\u007f\u2028\u2029\"",
            actual,
        )
    }

    @Test
    fun preservesUnicodeWithoutNormalizationAndUsesUtf16MemberOrder() {
        val actual = encode {
            beginObject()
            name("\r").value("é")
            name("1").value("e\u0301")
            name("\u0080").value("中文")
            name("ö").value("Ελληνικά")
            name("€").value("😀")
            name("😀").value("\u2028\u2029")
            name("\ue000").value("")
            endObject()
        }

        assertUtf8Equals(
            "{\"\\r\":\"é\",\"1\":\"e\u0301\",\"\u0080\":\"中文\",\"ö\":\"Ελληνικά\"," +
                "\"€\":\"😀\",\"😀\":\"\u2028\u2029\",\"\ue000\":\"\"}",
            actual,
        )
    }

    @Test
    fun preservesSurrogatePairsAndEscapesAcrossBufferBoundaries() {
        for (paddingLength in listOf(0, 1, 8185, 8186, 8187, 16377, 16378, 16379)) {
            val padding = "a".repeat(paddingLength)
            val longUnicode = "中文😀".repeat(4096)
            val actual = encode {
                beginArray()
                value(padding)
                value("😀\n\"\\")
                value(longUnicode)
                endArray()
            }

            assertUtf8Equals("[\"$padding\",\"😀\\n\\\"\\\\\",\"$longUnicode\"]", actual)
        }
    }

    @Test
    fun rejectsMalformedSurrogatesInValuesAndMemberNames() {
        val invalidStrings = listOf(
            "\ud800",
            "\udc00",
            "\ud800a",
            "\ud800\ud800",
            "\udc00\ud800",
            "😀\udc00",
            "a".repeat(8191) + "\ud800",
            "a".repeat(8192) + "\udc00",
        )
        for (value in invalidStrings) {
            val valueFailure = assertThrows(IllegalArgumentException::class.java) {
                encode { value(value) }
            }
            assertEquals("Invalid Unicode surrogate", valueFailure.message)

            val nameFailure = assertThrows(IllegalArgumentException::class.java) {
                encode { beginObject().name(value) }
            }
            assertEquals("Invalid Unicode surrogate", nameFailure.message)
        }
    }

    @Test
    fun rejectsDuplicateAndOutOfOrderMemberNames() {
        for (nextName in listOf("b", "a")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                encode { beginObject().name("b").value(1).name(nextName) }
            }
            assertEquals("Object members are not in canonical order", failure.message)
        }
    }

    @Test
    fun rejectsInvalidDocumentAndObjectState() {
        assertThrows(IllegalArgumentException::class.java) { encode { } }
        assertThrows(IllegalArgumentException::class.java) { encode { beginArray() } }
        assertThrows(IllegalArgumentException::class.java) { encode { value(1).value(2) } }
        assertThrows(IllegalArgumentException::class.java) { encode { beginObject().value(1) } }
        assertThrows(IllegalArgumentException::class.java) { encode { beginObject().name("a").name("b") } }
        assertThrows(IllegalArgumentException::class.java) { encode { beginObject().name("a").endObject() } }
        assertThrows(IllegalStateException::class.java) { encode { name("a") } }
        assertThrows(IllegalStateException::class.java) { encode { beginArray().endObject() } }
        assertThrows(IllegalArgumentException::class.java) { encode { beginObject().endArray() } }
    }

    @Test
    fun rejectsNoncanonicalProtocolNumbers() {
        for (value in listOf("", "01", "-01", "+1", "1.0", "1e2", " 1", "1 ")) {
            assertThrows(IllegalArgumentException::class.java) { encode { valueCanonicalInteger(value) } }
        }
        assertThrows(IllegalArgumentException::class.java) { encode { valueDecimal(-1) } }
    }

    @Test
    fun flushMakesPendingBytesAvailableWithoutClosingTheDestination() {
        val destination = object : ByteArrayOutputStream() {
            var closed = false

            override fun close() {
                closed = true
                super.close()
            }
        }
        val writer = CanonicalJsonWriter(destination)
        writer.beginArray().value("中文😀")

        writer.flush()

        assertUtf8Equals("[\"中文😀\"", destination.toByteArray())
        assertFalse(destination.closed)
        writer.value(42).endArray()
        writer.close()
        assertUtf8Equals("[\"中文😀\",42]", destination.toByteArray())
        assertTrue(destination.closed)
    }

    @Test
    fun propagatesDestinationWriteFailuresOnFlushAndClose() {
        val failure = IOException("Destination is full")
        for (close in listOf(false, true)) {
            val writer = CanonicalJsonWriter(failingDestination(failure))
            writer.value("pending")

            val actual = assertThrows(IOException::class.java) {
                if (close) writer.close() else writer.flush()
            }

            assertSame(failure, actual)
        }
    }

    @Test
    fun propagatesDestinationWriteFailuresWhileStreamingALargeValue() {
        val failure = IOException("Destination is full")
        val writer = CanonicalJsonWriter(failingDestination(failure))

        val actual = assertThrows(IOException::class.java) {
            writer.value("a".repeat(32768))
        }

        assertSame(failure, actual)
    }

    private fun encode(write: CanonicalJsonWriter.() -> Unit): ByteArray {
        val destination = ByteArrayOutputStream()
        CanonicalJsonWriter(destination).use(write)
        return destination.toByteArray()
    }

    private fun assertUtf8Equals(expected: String, actual: ByteArray) {
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), actual)
    }

    private fun failingDestination(failure: IOException): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            throw failure
        }

        override fun write(value: ByteArray, offset: Int, length: Int) {
            throw failure
        }
    }
}
