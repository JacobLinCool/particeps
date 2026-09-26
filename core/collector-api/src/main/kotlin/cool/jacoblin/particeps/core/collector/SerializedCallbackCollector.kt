package cool.jacoblin.particeps.core.collector

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.MAX_OBSERVATION_EVENTS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Serializes callback events and owns the common strict collector lifecycle.
 *
 * One consumer on [consumerDispatcher] submits queued callbacks in capture order. When it wakes it
 * merges the callbacks that are already queued into as few observations as their admission tokens,
 * source-time order, and [callbackBatchEventLimit] allow. It never waits for a later callback, so
 * merging adds no delivery latency; it only removes per-callback commits when callbacks queue up
 * behind a slow commit or arrive together.
 */
abstract class SerializedCallbackCollector(
    protected val context: CollectorContext,
    queueCapacity: Int,
    private val consumerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Collector {
    private val messages = Channel<Message>(queueCapacity)
    private val batchEventLimit = callbackBatchEventLimit(context.sourceContract)
    private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
    private var consumerJob: Job? = null
    private var sourceState = SourceState.RELEASED
    private var nextProducerOrdinal = 0L

    final override val health: StateFlow<CollectorHealth>
        get() = mutableHealth.asStateFlow()

    final override val requiresStop: Boolean
        get() = consumerJob != null

    final override suspend fun start() {
        check(consumerJob == null) { "Collector is already started" }
        check(sourceState == SourceState.RELEASED) { "Collector source is not released" }
        check(mutableHealth.value.status in setOf(CollectorStatus.STOPPED, CollectorStatus.FAILED)) {
            "Collector cannot be started"
        }
        val job = context.scope.launch(consumerDispatcher) { consume() }
        consumerJob = job
        try {
            register()
            mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        } catch (failure: Throwable) {
            if (sourceState == SourceState.RELEASED) stopConsumer(job)
            fail("SOURCE_REGISTRATION_FAILED")
            throw failure
        }
    }

    final override suspend fun onAdmissionOpened() {
        checkNotNull(consumerJob) { "Collector is not started" }
        check(sourceState == SourceState.REGISTERED) { "Collector source is not registered" }
        onSourceAdmitted()
    }

    final override suspend fun pause() {
        checkNotNull(consumerJob) { "Collector is not started" }
        val failure = runCatching { unregister() }.exceptionOrNull()
        // unregisterSource owns physical teardown and must finish it before reporting failure. Drain
        // every event admitted before that boundary even when Android reports a cleanup error.
        flush()
        if (failure != null) {
            fail("SOURCE_UNREGISTRATION_FAILED")
            throw failure
        }
        if (mutableHealth.value.status != CollectorStatus.FAILED) {
            mutableHealth.value = CollectorHealth(CollectorStatus.PAUSED)
        }
    }

    final override suspend fun resume() {
        checkNotNull(consumerJob) { "Collector is not started" }
        check(mutableHealth.value.status in setOf(CollectorStatus.PAUSED, CollectorStatus.FAILED)) {
            "Collector is not resumable"
        }
        try {
            register()
            mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        } catch (failure: Throwable) {
            fail("SOURCE_REGISTRATION_FAILED")
            throw failure
        }
    }

    final override suspend fun stop() {
        val job = consumerJob ?: return
        var failure = runCatching { unregister() }.exceptionOrNull()
        if (sourceState == SourceState.UNCERTAIN) {
            try {
                flush()
            } catch (flushFailure: Throwable) {
                val first = failure
                if (first == null) {
                    failure = flushFailure
                } else if (first !== flushFailure) {
                    first.addSuppressed(flushFailure)
                }
            }
            fail("SOURCE_UNREGISTRATION_FAILED")
            throw checkNotNull(failure) { "Uncertain source teardown did not report a failure" }
        }
        try {
            flush()
        } finally {
            stopConsumer(job)
        }
        if (failure != null) {
            fail("SOURCE_UNREGISTRATION_FAILED")
            throw failure
        }
        mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED)
    }

    /** Queues one observation; [draft] runs only after the runtime has issued an admission token. */
    protected fun capture(draft: () -> EventDraft) {
        val token = context.eventSink.captureToken() ?: return
        enqueue(Message.Event(token, listOf(draft())))
    }

    /**
     * Queues every draft one platform callback delivered, in the given order, under one admission
     * token and one queue slot. [drafts] runs only after admission; an empty result queues nothing.
     */
    protected fun captureAll(drafts: () -> List<EventDraft>) {
        val token = context.eventSink.captureToken() ?: return
        val captured = drafts().toList()
        if (captured.isNotEmpty()) enqueue(Message.Event(token, captured))
    }

    private fun enqueue(message: Message.Event) {
        if (!messages.trySend(message).isSuccess) {
            fail("CALLBACK_QUEUE_FULL")
        }
    }

    protected fun fail(reasonCode: String) {
        mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, reasonCode)
    }

    protected abstract suspend fun registerSource(): SourceRegistrationResult

    /** Publishes source state that must be observed once, after runtime admission is open. */
    protected open suspend fun onSourceAdmitted() = Unit

    /**
     * Returns only when a fresh source generation is safe. An exception means physical teardown is
     * uncertain, so the base class deliberately keeps the logical registration and blocks resume.
     */
    protected abstract suspend fun unregisterSource(): SourceTeardownResult

    private suspend fun register() {
        check(sourceState == SourceState.RELEASED) { "Collector source is not released" }
        when (val result = registerSource()) {
            SourceRegistrationResult.Registered -> sourceState = SourceState.REGISTERED
            is SourceRegistrationResult.Released -> throw result.failure
            is SourceRegistrationResult.Uncertain -> {
                sourceState = SourceState.UNCERTAIN
                throw result.failure
            }
        }
    }

    private suspend fun unregister() {
        if (sourceState == SourceState.RELEASED) return
        try {
            when (val result = unregisterSource()) {
                SourceTeardownResult.Released -> sourceState = SourceState.RELEASED
                is SourceTeardownResult.ReleasedWithFailure -> {
                    sourceState = SourceState.RELEASED
                    throw result.failure
                }
            }
        } catch (failure: Throwable) {
            if (sourceState != SourceState.RELEASED) sourceState = SourceState.UNCERTAIN
            throw failure
        }
    }

    private suspend fun stopConsumer(job: Job) {
        messages.send(Message.Stop)
        job.join()
        consumerJob = null
    }

    private suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        messages.send(Message.Barrier(completion))
        completion.await()
    }

    private suspend fun consume() {
        val batch = ArrayList<EventDraft>()
        var carried: Message? = null
        while (true) {
            val message = carried ?: messages.receive()
            carried = null
            when (message) {
                is Message.Event -> {
                    var event: Message.Event = message
                    while (true) {
                        event.drafts.forEach { draft ->
                            if (batch.isNotEmpty() && !batch.accepts(draft)) {
                                submit(event.token, batch)
                                batch.clear()
                            }
                            batch += draft
                        }
                        // Merge only what is queued now; the batch never waits for a later callback.
                        val queued = messages.tryReceive().getOrNull()
                        if (queued is Message.Event && queued.token == event.token) {
                            event = queued
                        } else {
                            // An empty queue, a barrier, a stop, or another token ends the batch. A
                            // carried barrier or stop is handled only after this batch is submitted.
                            carried = queued
                            break
                        }
                    }
                    submit(event.token, batch)
                    batch.clear()
                }
                is Message.Barrier -> message.completion.complete(Unit)
                Message.Stop -> return
            }
        }
    }

    /** One observation must stay within its bound, source-time order, and boot session. */
    private fun List<EventDraft>.accepts(next: EventDraft): Boolean {
        val previous = last().observedTime
        return size < batchEventLimit &&
            next.observedTime.bootSessionId == previous.bootSessionId &&
            next.observedTime.elapsedRealtimeNanos >= previous.elapsedRealtimeNanos
    }

    /**
     * Submits [drafts], in capture order, as few observations as the runtime records.
     *
     * The runtime records an offer whole or, when a desired resource first changes after the
     * offer's first event, only the events before that one. The rest is then offered under the next
     * producer ordinal, so the observation that stages the change starts with the event that
     * causes it, and the callbacks captured before it commit first, as when each was offered alone.
     *
     * An offer refused by the admission gate or for a contract violation is halved until a prefix
     * of it is accepted, and after each acceptance the whole remainder is offered again, because
     * admission can widen when a drain begins after the study deadline. Each part accepted this way
     * holds at least half of what is still admissible, so a batch takes O(log n) accepted
     * observations, which bounds a barrier drain's pending-slot rewrites, and O(log² n) offers. A refused one-event
     * offer ends the batch: the gate's refusal drops that event and every later one, and a contract
     * violation fails the collector there, after the valid events before it were recorded. A storage
     * failure or quality gap fails the collector at once. Parts accepted earlier stay recorded.
     */
    private suspend fun submit(token: AdmissionToken, drafts: List<EventDraft>) {
        var start = 0
        // Exclusive end of the shortest offer refused since the last acceptance.
        var refusedEnd = NO_REFUSAL
        while (start < drafts.size) {
            val end = if (refusedEnd == NO_REFUSAL) drafts.size else start + (refusedEnd - start) / 2
            val producerOrdinal = nextProducerOrdinal
            val result = try {
                context.eventSink.emitBatch(
                    token,
                    SourceEventBatch(
                        sourceId = EventSourceId(context.sourceContract.sourceId),
                        schemaVersion = context.sourceContract.schemaVersion,
                        resourceGeneration = context.resourceGeneration,
                        producerOrdinal = producerOrdinal,
                        events = drafts.subList(start, end).toList(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                EmitBatchResult.StorageFailure
            }
            when (result) {
                is EmitBatchResult.Accepted -> {
                    nextProducerOrdinal = Math.addExact(producerOrdinal, 1L)
                    // An accepted offer records at least its first event and at most all of them.
                    if (result.recordedEvents !in 1..end - start) {
                        return fail("EVENT_SINK_CONTRACT_VIOLATION")
                    }
                    start += result.recordedEvents
                    refusedEnd = NO_REFUSAL
                }
                EmitBatchResult.RejectedByAdmissionGate -> {
                    if (end == start + 1) return
                    refusedEnd = end
                }
                EmitBatchResult.ContractViolation -> {
                    if (end == start + 1) return fail("EVENT_CONTRACT_VIOLATION")
                    refusedEnd = end
                }
                EmitBatchResult.StorageFailure -> return fail("STORAGE_WRITE_FAILED")
                is EmitBatchResult.SourceQualityGap -> return fail("SOURCE_QUALITY_GAP")
            }
        }
    }

    private sealed interface Message {
        /** Drafts from one callback, captured under one admission token and in source order. */
        class Event(
            val token: AdmissionToken,
            val drafts: List<EventDraft>,
        ) : Message

        data class Barrier(val completion: CompletableDeferred<Unit>) : Message

        data object Stop : Message
    }

    private enum class SourceState { RELEASED, REGISTERED, UNCERTAIN }

    private companion object {
        const val NO_REFUSAL = -1
    }
}

/**
 * Encoded-size budget for one merged callback observation. Every event is counted at its source's
 * largest registry bound, so the budget holds without measuring events, and one observation stays
 * well inside the runtime's per-observation and pending-slot limits.
 */
internal const val CALLBACK_BATCH_ENCODED_BYTES = 1_024 * 1_024

/**
 * The most callback events one observation of [contract] may carry: [MAX_OBSERVATION_EVENTS], the
 * registry's own per-batch rate bound for any of the source's events, and as many worst-case events
 * as fit in [encodedByteBudget].
 */
internal fun callbackBatchEventLimit(
    contract: RegistrySourceContract,
    encodedByteBudget: Int = CALLBACK_BATCH_ENCODED_BYTES,
): Int {
    val registryBound = contract.events.values.mapNotNull(RegistryEventContract::maximumEventsPerBatch).minOrNull()
        ?: MAX_OBSERVATION_EVENTS
    val byteBound = encodedByteBudget / contract.maximumEncodedEventBytes
    return minOf(MAX_OBSERVATION_EVENTS, registryBound, byteBound).coerceAtLeast(1)
}
