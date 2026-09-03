package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.FieldOperator
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EventRegistryTest {
    @Test
    fun predicateDecoderEnforcesCanonicalBooleanIntegerFloatUuidAndDigest() {
        assertEquals(
            TypedFieldValue.Bool(true),
            TypedFieldDecoder.decodePredicateLiteral(contract(ScalarType.BOOLEAN), "true"),
        )
        assertEquals(
            TypedFieldValue.Integer(BigInteger("18446744073709551616")),
            TypedFieldDecoder.decodePredicateLiteral(contract(ScalarType.INTEGER), "18446744073709551616"),
        )
        assertEquals(
            TypedFieldValue.Float64(1.5, "1.5"),
            TypedFieldDecoder.decodePredicateLiteral(contract(ScalarType.FLOAT), "1.5"),
        )
        assertEquals(
            TypedFieldValue.Uuid("b7f90e3c-2f22-4fe5-b838-d8b5d3082e69"),
            TypedFieldDecoder.decodePredicateLiteral(
                contract(ScalarType.UUID),
                "b7f90e3c-2f22-4fe5-b838-d8b5d3082e69",
            ),
        )
        val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals(
            TypedFieldValue.Digest(digest),
            TypedFieldDecoder.decodePredicateLiteral(contract(ScalarType.SHA256), digest),
        )

        listOf(
            ScalarType.BOOLEAN to "TRUE",
            ScalarType.INTEGER to "01",
            ScalarType.FLOAT to "+1",
            ScalarType.FLOAT to "01",
            ScalarType.FLOAT to ".5",
            ScalarType.FLOAT to "1.",
            ScalarType.FLOAT to "1e-3",
            ScalarType.FLOAT to "1E+3",
            ScalarType.FLOAT to "NaN",
            ScalarType.UUID to "B7F90E3C-2F22-4FE5-B838-D8B5D3082E69",
            ScalarType.UUID to "b7f90e3c-2f22-6fe5-b838-d8b5d3082e69",
            ScalarType.SHA256 to digest.uppercase(),
        ).forEach { (type, value) ->
            assertThrows(IllegalArgumentException::class.java) {
                TypedFieldDecoder.decodePredicateLiteral(contract(type), value)
            }
        }
    }

    @Test
    fun eventWireFloatAcceptsProtocolDecimalsAndRejectsHostileValues() {
        val field = contract(ScalarType.FLOAT)
        mapOf(
            "+1" to 1.0,
            "01" to 1.0,
            ".5" to 0.5,
            "1." to 1.0,
            "1e-3" to 0.001,
            "1E+3" to 1_000.0,
        ).forEach { (wire, expected) ->
            val decoded = TypedFieldDecoder.decodeEventWire(field, wire) as TypedFieldValue.Float64
            assertEquals(expected, decoded.value, 0.0)
            assertEquals(java.lang.Double.toString(expected), decoded.canonical)
        }
        listOf("NaN", "Infinity", "-Infinity", "1e309", "0x1.0p0", "1_0", " 1", "1 ", ".", "+").forEach { wire ->
            assertThrows(IllegalArgumentException::class.java) {
                TypedFieldDecoder.decodeEventWire(field, wire)
            }
        }
        val bounded = field.copy(minimumFloat = -10.0, maximumFloat = 10.0)
        assertThrows(IllegalArgumentException::class.java) {
            TypedFieldDecoder.decodeEventWire(bounded, "10.0001")
        }
    }

    @Test
    fun registryCapabilitiesAreClosedWorld() {
        assertThrows(IllegalArgumentException::class.java) {
            FieldContract(ScalarType.STRING, setOf(FieldOperator.GT))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FieldContract(
                ScalarType.INTEGER,
                setOf(FieldOperator.EQ),
                required = false,
                windowSumAllowed = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FieldContract(
                ScalarType.STRING,
                setOf(FieldOperator.EQ),
                required = false,
                keyedPresenceKey = true,
            )
        }
    }

    private fun contract(type: ScalarType): FieldContract = FieldContract(
        type,
        if (type in setOf(ScalarType.INTEGER, ScalarType.FLOAT)) {
            setOf(FieldOperator.EQ, FieldOperator.LT)
        } else {
            setOf(FieldOperator.EQ)
        },
    )
}
