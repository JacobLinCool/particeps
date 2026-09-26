package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind

/**
 * Turns admitted source submissions into the observations, events and checkpoints of one commit.
 * Extracted from [ExperimentRuntime]; not a concurrent actor. Every member runs on the caller's
 * coroutine while the caller holds the runtime mutex, and never suspends. It reads runtime state
 * through [RuntimeMemory] at use time and never retains it.
 */
internal class SourcePreparation(private val ctx: RuntimeContext) {
    private val memory = ctx.memory

    fun prepareSources(
        document: RuntimeDocument,
        submissions: List<SourceSubmission>,
        conditionEpochId: ConditionEpochId,
        startingCheckpoints: Map<EventSourceId, SourceCheckpoint>,
        flushCursors: Map<EventSourceId, String?> = emptyMap(),
        semanticEventOrder: List<SourceSubmission> = submissions,
    ): PreparedSources {
        val submissionsByIdentity = submissions.uniqueByIdentity("Submission admission order")
        val semanticSubmissionsByIdentity = semanticEventOrder.uniqueByIdentity("Submission semantic event order")
        require(submissionsByIdentity == semanticSubmissionsByIdentity) {
            "Submission semantic event order is not an exact admission-order permutation"
        }
        if (submissions.isEmpty()) return PreparedSources.empty(document)
        val checkpoints = startingCheckpoints.toMutableMap()
        val observations = mutableListOf<SourceObservation>()
        val events = mutableListOf<RecordedEvent>()
        val eventRanges = mutableMapOf<SourceSubmissionIdentity, EventSequenceRange>()
        var observationSequence = document.nextObservationSequence
        var eventSequence = document.nextEventSequence
        semanticEventOrder.forEach { submission ->
            validateSubmission(submission, eventSequence, conditionEpochId)
            val first = eventSequence.takeIf { submission.events.isNotEmpty() }
            submission.events.forEach { event ->
                events += RecordedEvent(eventSequence++, event.type, event.observedTime, conditionEpochId, event.fields)
            }
            val last = (eventSequence - 1).takeIf { submission.events.isNotEmpty() }
            check(eventRanges.put(submission.identity(), EventSequenceRange(first, last)) == null) {
                "Submission event range was assigned twice"
            }
        }
        submissions.forEach { submission ->
            val resource = memory.appliedResources[ResourceKey(ResourceKind.COLLECTOR, submission.sourceId.value)]
                ?: throw IllegalArgumentException("Collector source has no applied resource")
            require(resource.status == AppliedResourceStatus.APPLIED) { "Collector resource is inactive" }
            require(resource.desiredGeneration.value.toLong() == submission.resourceGeneration) {
                "Collector batch generation is stale"
            }
            val prior = checkpoints[submission.sourceId]
            val expectedOrdinal = if (prior == null || prior.resourceGeneration != submission.resourceGeneration) {
                0L
            } else {
                prior.nextProducerOrdinal
            }
            require(submission.producerOrdinal == expectedOrdinal) { "Collector producer ordinal is not contiguous" }
            if (prior != null && prior.resourceGeneration == submission.resourceGeneration) {
                val oldCoverage = prior.coverage
                val newCoverage = submission.coverage
                if (oldCoverage != null && newCoverage != null &&
                    (oldCoverage.clockBasis != newCoverage.clockBasis || oldCoverage.endExclusive != newCoverage.startInclusive)
                ) {
                    throw SourceGap(SourceQualityGapReason.RETROSPECTIVE_COVERAGE_GAP)
                }
            }
            val eventRange = checkNotNull(eventRanges[submission.identity()]) {
                "Submission has no semantic event range"
            }
            observations += SourceObservation(
                observationSequence = observationSequence++,
                sourceId = submission.sourceId,
                schemaVersion = submission.schemaVersion,
                resourceGeneration = submission.resourceGeneration,
                admissionKind = submission.admissionKind,
                producerOrdinal = submission.producerOrdinal,
                conditionEpochId = conditionEpochId,
                eventCount = submission.events.size,
                firstEventSequence = eventRange.first,
                lastEventSequence = eventRange.last,
                coverage = submission.coverage,
                encodedSha256 = submissionDigest(submission, conditionEpochId),
            )
            checkpoints[submission.sourceId] = SourceCheckpoint(
                sourceId = submission.sourceId,
                resourceGeneration = submission.resourceGeneration,
                nextProducerOrdinal = submission.producerOrdinal + 1,
                coverage = submission.coverage ?: prior?.coverage,
                cursor = if (flushCursors.containsKey(submission.sourceId)) {
                    flushCursors[submission.sourceId]
                } else {
                    prior?.cursor
                },
            )
        }
        require(flushCursors.keys.all { sourceId ->
            observations.count {
                it.sourceId == sourceId && it.admissionKind == ObservationAdmissionKind.BARRIER_FLUSH
            } == 1
        }) { "A retrospective flush cursor requires a committed coverage observation" }
        return PreparedSources(observations, events, checkpoints.toSortedMap(), observationSequence, eventSequence)
    }

    fun preparePending(current: RuntimeDocument, pending: PendingEngineInput): PreparedSources {
        val submissions = pending.submissions.map(SourceSubmission::from)
        val semanticEventOrder = if (
            submissions.size > 1 && submissions.first().admissionKind == ObservationAdmissionKind.NORMAL
        ) {
            submissions.drop(1) + submissions.first()
        } else {
            submissions
        }
        return prepareSources(
            document = current,
            submissions = submissions,
            conditionEpochId = pending.conditionEpochId,
            startingCheckpoints = current.sourceCheckpoints,
            semanticEventOrder = semanticEventOrder,
        )
    }

    fun recordedEventInputs(
        events: List<RecordedEvent>,
        clock: StudyClockCheckpoint,
        base: AutomationCheckpoint,
    ): List<ReducerInput.Event> {
        val reducerClock = reducerClock(clock)
        return events.mapIndexed { index, event ->
            event.toReducerInput(base.evaluatedThroughSequence + index + 1L, reducerClock)
        }
    }
}
