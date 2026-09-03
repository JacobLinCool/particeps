package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.model.EventTypeKey
import java.math.BigInteger
import java.util.UUID

/** Compiler-facing projection of the registry wire types. */
enum class ScalarType { STRING, ENUM, BOOLEAN, INTEGER, FLOAT, UUID, SHA256 }
enum class FloatWireWidth { BINARY32, BINARY64 }
enum class StringLengthUnit { UTF16_CODE_UNITS, UTF8_BYTES }
enum class TriggerScope { RESEARCHER, RUNTIME_ONLY, AUDIT_ONLY }
enum class EventSourceKind { COLLECTOR, SYSTEM }
enum class DeliveryMode { LIVE, RETROSPECTIVE }
enum class EventClockSupport { OBSERVED_RESEARCH_TIME, PRIMARY_SOURCE_TIME }
enum class EventConditionKind { EVENT_MATCH, KEYED_PRESENCE_ENTER, KEYED_PRESENCE_EXIT, SEQUENCE_STEP, WINDOW_COUNT, WINDOW_SUM }
enum class EventPresenceRole { ENTER, EXIT }

data class EventPresenceContract(
    val groupId: String,
    val role: EventPresenceRole,
    val keyFields: Set<String>,
) {
    init {
        require(groupId.isNotBlank()) { "Presence group ID must not be blank" }
        require(keyFields.isNotEmpty()) { "Presence contract needs at least one key field" }
    }
}

data class EventRateBound(val maximumEvents: Int, val periodSeconds: Int) {
    init {
        require(maximumEvents in 1..1_000_000) { "Invalid maximum event count" }
        require(periodSeconds in 1..604_800) { "Invalid event-rate period" }
    }

    fun maximumEntries(windowSeconds: Int): Long =
        maximumEvents.toLong() * ((windowSeconds.toLong() + periodSeconds - 1) / periodSeconds)
}
data class FieldContract(
    val type: ScalarType,
    val operators: Set<FieldOperator>,
    val required: Boolean = true,
    val keyedPresenceKey: Boolean = false,
    val windowSumAllowed: Boolean = false,
    val enumValues: Set<String> = emptySet(),
    val minimumInteger: BigInteger? = null,
    val maximumInteger: BigInteger? = null,
    val minimumFloat: Double? = null,
    val maximumFloat: Double? = null,
    val maximumEncodedBytes: Int = 4_096,
    val floatWireWidth: FloatWireWidth? = if (type == ScalarType.FLOAT) FloatWireWidth.BINARY64 else null,
    val minimumLength: Int? = null,
    val maximumLength: Int? = null,
    val stringLengthUnit: StringLengthUnit? = null,
) {
    init {
        val allowedOperators = when (type) {
            ScalarType.STRING, ScalarType.ENUM, ScalarType.BOOLEAN, ScalarType.UUID, ScalarType.SHA256 -> setOf(
                FieldOperator.EQ,
                FieldOperator.NE,
                FieldOperator.IN,
            )
            ScalarType.INTEGER, ScalarType.FLOAT -> FieldOperator.entries.toSet()
        }
        require(operators.all(allowedOperators::contains)) { "Field exposes an operator unsupported by its type" }
        require(maximumEncodedBytes in 1..60 * 1_024) { "Invalid field encoded-size bound" }
        require(enumValues.isEmpty() || type == ScalarType.ENUM) { "Only enum fields declare enum values" }
        if (type == ScalarType.ENUM) require(enumValues.isNotEmpty()) { "Enum field needs values" }
        require(!keyedPresenceKey || required) { "A keyed-presence field must be required" }
        require(!windowSumAllowed || type == ScalarType.INTEGER) { "Only integer fields may be summed" }
        require(!windowSumAllowed || required) { "A window-sum field must be required" }
        require(minimumInteger == null || type == ScalarType.INTEGER) { "Integer minimum on non-integer field" }
        require(maximumInteger == null || type == ScalarType.INTEGER) { "Integer maximum on non-integer field" }
        require(minimumInteger == null || maximumInteger == null || minimumInteger <= maximumInteger) {
            "Invalid integer bounds"
        }
        require(minimumFloat == null || type == ScalarType.FLOAT) { "Float minimum on non-float field" }
        require(maximumFloat == null || type == ScalarType.FLOAT) { "Float maximum on non-float field" }
        require(minimumFloat?.isFinite() != false && maximumFloat?.isFinite() != false) { "Float bounds must be finite" }
        require(minimumFloat == null || maximumFloat == null || minimumFloat <= maximumFloat) { "Invalid float bounds" }
        require((type == ScalarType.FLOAT) == (floatWireWidth != null)) { "Float wire width disagrees with field type" }
        require(minimumLength == null || minimumLength >= 0) { "Invalid minimum string length" }
        require(maximumLength == null || maximumLength >= 0) { "Invalid maximum string length" }
        require(minimumLength == null || maximumLength == null || minimumLength <= maximumLength) {
            "Invalid string-length bounds"
        }
        require(minimumLength == null || type in TEXT_TYPES) { "Minimum length on non-text field" }
        require(maximumLength == null || type in TEXT_TYPES) { "Maximum length on non-text field" }
        require((minimumLength != null || maximumLength != null) == (stringLengthUnit != null)) {
            "String length unit and bounds disagree"
        }
    }

    private companion object {
        val TEXT_TYPES = setOf(ScalarType.STRING, ScalarType.ENUM)
    }
}
data class EventTypeContract(
    val key: EventTypeKey,
    val sourceKind: EventSourceKind,
    val fields: Map<String, FieldContract>,
    val triggerScope: TriggerScope,
    val deliveryMode: DeliveryMode,
    val clockSupport: Set<EventClockSupport>,
    val conditionKinds: Set<EventConditionKind>,
    val presence: EventPresenceContract?,
    val rateBound: EventRateBound?,
) {
    init {
        require(fields.size <= 32) { "Event has too many fields" }
        require(fields.keys.all(FIELD_NAME::matches)) { "Invalid event field name" }
        require(clockSupport.isNotEmpty()) { "Event must support an occurrence clock" }
        require((triggerScope == TriggerScope.RESEARCHER) == conditionKinds.isNotEmpty()) {
            "Trigger scope and condition kinds disagree"
        }
        require(
            triggerScope == TriggerScope.RESEARCHER || fields.values.all {
                it.operators.isEmpty() && !it.keyedPresenceKey && !it.windowSumAllowed
            },
        ) { "Non-researcher event exposes field trigger metadata" }
        require(
            (presence != null) == conditionKinds.any {
                it == EventConditionKind.KEYED_PRESENCE_ENTER || it == EventConditionKind.KEYED_PRESENCE_EXIT
            },
        ) { "Presence metadata and keyed-presence condition kinds disagree" }
        presence?.let { contract ->
            require(triggerScope == TriggerScope.RESEARCHER) { "Only researcher events can declare presence" }
            val roleKind = when (contract.role) {
                EventPresenceRole.ENTER -> EventConditionKind.KEYED_PRESENCE_ENTER
                EventPresenceRole.EXIT -> EventConditionKind.KEYED_PRESENCE_EXIT
            }
            require(roleKind in conditionKinds) {
                "Presence role is absent from condition kinds"
            }
            require(contract.keyFields.all { field ->
                fields[field]?.let { it.required && it.keyedPresenceKey } == true
            }) { "Presence keys must be required registry-authorized fields" }
        }
    }

    private companion object { val FIELD_NAME = Regex("[a-z][a-z0-9_]{0,63}") }
}

fun interface EventContractRegistry {
    fun contract(key: EventTypeKey): EventTypeContract?
}

sealed interface TypedFieldValue : Comparable<TypedFieldValue> {
    val canonical: String
    data class Text(override val canonical: String) : TypedFieldValue
    data class Bool(val value: Boolean) : TypedFieldValue { override val canonical = value.toString() }
    data class Integer(val value: BigInteger) : TypedFieldValue { override val canonical = value.toString() }
    data class Float64(val value: Double, override val canonical: String) : TypedFieldValue
    data class Uuid(override val canonical: String) : TypedFieldValue
    data class Digest(override val canonical: String) : TypedFieldValue
    override fun compareTo(other: TypedFieldValue): Int = when {
        this is Text && other is Text -> canonical.compareTo(other.canonical)
        this is Bool && other is Bool -> value.compareTo(other.value)
        this is Integer && other is Integer -> value.compareTo(other.value)
        this is Float64 && other is Float64 -> when {
            value < other.value -> -1
            value > other.value -> 1
            else -> 0
        }
        this is Uuid && other is Uuid -> canonical.compareTo(other.canonical)
        this is Digest && other is Digest -> canonical.compareTo(other.canonical)
        else -> throw IllegalArgumentException("Cannot compare different field types")
    }
}

object TypedFieldDecoder {
    /** Decode an event field exactly as admitted by the Protocol v1 wire contract. */
    fun decodeEventWire(contract: FieldContract, wire: String): TypedFieldValue = decode(
        contract = contract,
        encoded = wire,
        requireCanonicalFloatPredicate = false,
    )

    /** Decode a signed matcher literal, whose float spelling must match Java Double.toString. */
    fun decodePredicateLiteral(contract: FieldContract, literal: String): TypedFieldValue = decode(
        contract = contract,
        encoded = literal,
        requireCanonicalFloatPredicate = true,
    )

    private fun decode(
        contract: FieldContract,
        encoded: String,
        requireCanonicalFloatPredicate: Boolean,
    ): TypedFieldValue {
        require(encoded.toByteArray(Charsets.UTF_8).size <= contract.maximumEncodedBytes) {
            "Field value exceeds its encoded-size bound"
        }
        validateStringLength(contract, encoded)
        return when (contract.type) {
            ScalarType.STRING -> TypedFieldValue.Text(encoded)
            ScalarType.ENUM -> {
                require(encoded in contract.enumValues) { "Unknown enum value" }
                TypedFieldValue.Text(encoded)
            }
            ScalarType.BOOLEAN -> when (encoded) {
                "true" -> TypedFieldValue.Bool(true)
                "false" -> TypedFieldValue.Bool(false)
                else -> throw IllegalArgumentException("Boolean field is not canonical")
            }
            ScalarType.INTEGER -> {
                val value = encoded.toBigIntegerOrNull() ?: throw IllegalArgumentException("Invalid integer field")
                require(value.toString() == encoded) { "Integer field is not canonical" }
                require(contract.minimumInteger == null || value >= contract.minimumInteger) { "Integer below minimum" }
                require(contract.maximumInteger == null || value <= contract.maximumInteger) { "Integer above maximum" }
                TypedFieldValue.Integer(value)
            }
            ScalarType.FLOAT -> {
                require(DECIMAL_FLOAT.matches(encoded)) { "Float event field is not a Protocol decimal" }
                val value = encoded.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid float field")
                require(value.isFinite()) { "Float field must be finite" }
                if (contract.floatWireWidth == FloatWireWidth.BINARY32) {
                    require(value.toFloat().isFinite()) { "Float field is outside binary32" }
                }
                if (requireCanonicalFloatPredicate) {
                    require(java.lang.Double.toString(value) == encoded) { "Float predicate literal is not canonical" }
                }
                require(contract.minimumFloat == null || value >= contract.minimumFloat) { "Float below minimum" }
                require(contract.maximumFloat == null || value <= contract.maximumFloat) { "Float above maximum" }
                TypedFieldValue.Float64(value, java.lang.Double.toString(value))
            }
            ScalarType.UUID -> {
                val parsed = runCatching { UUID.fromString(encoded) }.getOrNull()
                require(
                    parsed != null &&
                        parsed.toString() == encoded &&
                        parsed.variant() == 2 &&
                        parsed.version() in 1..5,
                ) { "UUID field must be canonical RFC 4122 version 1-5" }
                TypedFieldValue.Uuid(encoded)
            }
            ScalarType.SHA256 -> {
                require(SHA256.matches(encoded)) { "SHA-256 field is not canonical" }
                TypedFieldValue.Digest(encoded)
            }
        }
    }

    private fun validateStringLength(contract: FieldContract, value: String) {
        val unit = contract.stringLengthUnit ?: return
        val length = when (unit) {
            StringLengthUnit.UTF16_CODE_UNITS -> value.length
            StringLengthUnit.UTF8_BYTES -> value.toByteArray(Charsets.UTF_8).size
        }
        require(contract.minimumLength == null || length >= contract.minimumLength) { "String below minimum length" }
        require(contract.maximumLength == null || length <= contract.maximumLength) { "String above maximum length" }
    }

    private val DECIMAL_FLOAT = Regex("[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)")
    private val SHA256 = Regex("[0-9a-f]{64}")
}
