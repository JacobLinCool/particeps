package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.DeliveryMode
import cool.jacoblin.particeps.core.automation.EventClockSupport
import cool.jacoblin.particeps.core.automation.EventConditionKind
import cool.jacoblin.particeps.core.automation.EventContractRegistry
import cool.jacoblin.particeps.core.automation.EventPresenceContract
import cool.jacoblin.particeps.core.automation.EventPresenceRole
import cool.jacoblin.particeps.core.automation.EventRateBound
import cool.jacoblin.particeps.core.automation.EventSourceKind
import cool.jacoblin.particeps.core.automation.EventTypeContract
import cool.jacoblin.particeps.core.automation.FieldContract
import cool.jacoblin.particeps.core.automation.FloatWireWidth
import cool.jacoblin.particeps.core.automation.ScalarType
import cool.jacoblin.particeps.core.automation.StringLengthUnit
import cool.jacoblin.particeps.core.automation.TriggerScope
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.RegistryDeliveryKind
import cool.jacoblin.particeps.core.collector.RegistryFieldWireType
import cool.jacoblin.particeps.core.collector.RegistryRateKind
import cool.jacoblin.particeps.core.collector.RegistrySourceKind
import cool.jacoblin.particeps.core.collector.RegistryTriggerScope
import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.model.EventTypeKey
import java.math.BigInteger

/** The sole Kotlin bridge from the generated Protocol registry into the automation compiler. */
object GeneratedEventContractRegistry : EventContractRegistry {
    override fun contract(key: EventTypeKey): EventTypeContract? {
        val source = ProtocolEventSourceRegistry[key.sourceId.value] ?: return null
        if (source.schemaVersion != key.schemaVersion) return null
        val event = source.events[key.eventType] ?: return null
        return EventTypeContract(
            key = key,
            sourceKind = when (source.sourceKind) {
                RegistrySourceKind.COLLECTOR -> EventSourceKind.COLLECTOR
                RegistrySourceKind.SYSTEM -> EventSourceKind.SYSTEM
            },
            fields = event.fields.mapValues { (_, field) ->
                val scalarType = when (field.wireType) {
                    RegistryFieldWireType.BOOLEAN -> ScalarType.BOOLEAN
                    RegistryFieldWireType.ENUM -> ScalarType.ENUM
                    RegistryFieldWireType.FLOAT32,
                    RegistryFieldWireType.FLOAT64,
                    -> ScalarType.FLOAT
                    RegistryFieldWireType.INT32,
                    RegistryFieldWireType.INT64_DECIMAL,
                    RegistryFieldWireType.UINT64_DECIMAL,
                    -> ScalarType.INTEGER
                    RegistryFieldWireType.SHA256_HEX -> ScalarType.SHA256
                    RegistryFieldWireType.UUID -> ScalarType.UUID
                    RegistryFieldWireType.JSON_STRING,
                    RegistryFieldWireType.STRING,
                    -> ScalarType.STRING
                }
                FieldContract(
                    type = scalarType,
                    operators = field.operators.mapTo(linkedSetOf()) {
                        FieldOperator.valueOf(it.name)
                    },
                    required = field.required,
                    keyedPresenceKey = field.keyedPresenceKey,
                    windowSumAllowed = field.windowSum,
                    enumValues = field.enumValues,
                    minimumInteger = integerMinimum(field.wireType, field.minimum),
                    maximumInteger = integerMaximum(field.wireType, field.maximum),
                    minimumFloat = field.minimum?.toDouble().takeIf { scalarType == ScalarType.FLOAT },
                    maximumFloat = field.maximum?.toDouble().takeIf { scalarType == ScalarType.FLOAT },
                    maximumEncodedBytes = event.maximumEncodedEventBytes.coerceAtMost(MAXIMUM_FIELD_BYTES),
                    floatWireWidth = when (field.wireType) {
                        RegistryFieldWireType.FLOAT32 -> FloatWireWidth.BINARY32
                        RegistryFieldWireType.FLOAT64 -> FloatWireWidth.BINARY64
                        else -> null
                    },
                    minimumLength = field.minimumLength.takeIf { scalarType in TEXT_TYPES },
                    maximumLength = field.maximumLength.takeIf { scalarType in TEXT_TYPES },
                    stringLengthUnit = if (
                        scalarType in TEXT_TYPES && (field.minimumLength != null || field.maximumLength != null)
                    ) {
                        if (field.utf8Length) StringLengthUnit.UTF8_BYTES else StringLengthUnit.UTF16_CODE_UNITS
                    } else {
                        null
                    },
                )
            },
            triggerScope = when (event.triggerScope) {
                RegistryTriggerScope.RESEARCHER -> TriggerScope.RESEARCHER
                RegistryTriggerScope.RUNTIME_ONLY -> TriggerScope.RUNTIME_ONLY
                RegistryTriggerScope.AUDIT_ONLY -> TriggerScope.AUDIT_ONLY
            },
            deliveryMode = if (event.deliveryKind == RegistryDeliveryKind.POLL) {
                DeliveryMode.RETROSPECTIVE
            } else {
                DeliveryMode.LIVE
            },
            clockSupport = event.automationTimeInputs.mapTo(linkedSetOf()) {
                EventClockSupport.valueOf(it)
            },
            conditionKinds = event.conditionKinds.mapTo(linkedSetOf()) {
                EventConditionKind.valueOf(it.name)
            },
            presence = event.presence?.let { presence ->
                EventPresenceContract(
                    groupId = presence.groupId,
                    role = EventPresenceRole.valueOf(presence.role),
                    keyFields = presence.keyFields.toSet(),
                )
            },
            rateBound = if (event.rateKind == RegistryRateKind.HARD) {
                val maximum = event.maximumEventsPerPeriod ?: return null
                val period = event.ratePeriodSeconds ?: return null
                EventRateBound(maximum, period)
            } else {
                null
            },
        )
    }

    private fun integerMinimum(wireType: RegistryFieldWireType, declared: BigInteger?): BigInteger? = when (wireType) {
        RegistryFieldWireType.INT32 -> maxOf(INT32_MIN, declared ?: INT32_MIN)
        RegistryFieldWireType.INT64_DECIMAL -> maxOf(INT64_MIN, declared ?: INT64_MIN)
        RegistryFieldWireType.UINT64_DECIMAL -> maxOf(BigInteger.ZERO, declared ?: BigInteger.ZERO)
        else -> null
    }

    private fun integerMaximum(wireType: RegistryFieldWireType, declared: BigInteger?): BigInteger? = when (wireType) {
        RegistryFieldWireType.INT32 -> minOf(INT32_MAX, declared ?: INT32_MAX)
        RegistryFieldWireType.INT64_DECIMAL -> minOf(INT64_MAX, declared ?: INT64_MAX)
        RegistryFieldWireType.UINT64_DECIMAL -> minOf(UINT64_MAX, declared ?: UINT64_MAX)
        else -> null
    }

    private val TEXT_TYPES = setOf(ScalarType.STRING, ScalarType.ENUM)
    private val INT32_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val INT32_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    private val INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private val UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private const val MAXIMUM_FIELD_BYTES = 60 * 1_024
}
