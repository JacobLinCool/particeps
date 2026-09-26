package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationEvent
import cool.jacoblin.particeps.core.automation.ReducerClock
import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.RegistryClockBasis
import cool.jacoblin.particeps.core.collector.RegistryEmissionAuthority
import cool.jacoblin.particeps.core.collector.RegistrySourceKind
import cool.jacoblin.particeps.core.collector.acceptedEncodedBytes
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.MAX_OBSERVATION_ENCODED_BYTES
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SourceCoverage

internal fun validateSubmission(
    submission: SourceSubmission,
    firstSequence: Long,
    epochId: ConditionEpochId,
) {
    val source = requireNotNull(ProtocolEventSourceRegistry[submission.sourceId.value]) { "Unknown source" }
    require(source.sourceKind == RegistrySourceKind.COLLECTOR) { "Event sink only admits collector sources" }
    require(source.emissionAuthority == RegistryEmissionAuthority.SOURCE_PLUGIN_ONLY) { "Invalid source authority" }
    require(source.schemaVersion == submission.schemaVersion) { "Source schema mismatch" }
    require(submission.events.isNotEmpty() || submission.coverage != null) { "Empty source input needs coverage" }
    if (source.isRetrospective) requireNotNull(submission.coverage) { "Retrospective source needs coverage" }
    var bytes = 0L
    submission.events.forEachIndexed { index, event ->
        val sequence = firstSequence + index
        bytes += requireNotNull(source.acceptedEncodedBytes(event, sequence, epochId)) {
            "Collector event contract violation"
        }
    }
    require(bytes <= MAX_OBSERVATION_ENCODED_BYTES) { "Collector batch exceeds encoded-size bound" }
    submission.events.zipWithNext().forEach { (left, right) ->
        require(
            left.observedTime.bootSessionId == right.observedTime.bootSessionId &&
                left.observedTime.elapsedRealtimeNanos <= right.observedTime.elapsedRealtimeNanos
        ) { "Collector batch source time is not ordered" }
    }
}

internal fun List<SourceSubmission>.uniqueByIdentity(label: String): Map<SourceSubmissionIdentity, SourceSubmission> {
    val indexed = associateBy(SourceSubmission::identity)
    require(indexed.size == size) { "$label contains a duplicate producer identity" }
    return indexed
}

internal fun RecordedEvent.toReducerInput(sequence: Long, clock: ReducerClock): ReducerInput.Event {
    val registry = ProtocolEventSourceRegistry[type.sourceId.value]!!
    val contract = registry.events.getValue(type.eventType)
    val primary = contract.primarySourceTimeField?.let(fields::get)?.let { encoded ->
        when (contract.primarySourceBasis) {
            RegistryClockBasis.UTC_WALL -> encoded.toLongOrNull()?.let { ResearchTime(it, observedTime.elapsedRealtimeNanos, observedTime.bootSessionId) }
            RegistryClockBasis.CONTINUOUS_MONOTONIC_SINCE_BOOT,
            RegistryClockBasis.BOOT_SESSION_MONOTONIC,
            -> encoded.toLongOrNull()?.let { ResearchTime(observedTime.wallTimeUtcMillis, it, observedTime.bootSessionId) }
            else -> null
        }
    }
    val event = AutomationEvent(sequence, type, observedTime, primary, fields)
    return ReducerInput.Event(sequence, clock, event)
}

internal fun SourceCoverage?.doesNotEndAfter(boundary: ResearchTime): Boolean {
    val coverage = this ?: return true
    val end = coverage.endExclusive.toLongOrNull() ?: return false
    return when (coverage.clockBasis) {
        cool.jacoblin.particeps.core.model.SourceClockBasis.SOURCE_WALL_TIME ->
            end <= boundary.wallTimeUtcMillis
        cool.jacoblin.particeps.core.model.SourceClockBasis.SOURCE_MONOTONIC_TIME ->
            end <= boundary.elapsedRealtimeNanos
        cool.jacoblin.particeps.core.model.SourceClockBasis.OBSERVED_RESEARCH_TIME -> false
    }
}

internal fun SourceCoverage?.endsAt(boundary: ResearchTime): Boolean {
    val coverage = this ?: return false
    return when (coverage.clockBasis) {
        cool.jacoblin.particeps.core.model.SourceClockBasis.SOURCE_WALL_TIME ->
            coverage.endExclusive == boundary.wallTimeUtcMillis.toString()
        cool.jacoblin.particeps.core.model.SourceClockBasis.SOURCE_MONOTONIC_TIME ->
            coverage.endExclusive == boundary.elapsedRealtimeNanos.toString()
        cool.jacoblin.particeps.core.model.SourceClockBasis.OBSERVED_RESEARCH_TIME -> false
    }
}
