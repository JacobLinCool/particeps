package cool.jacoblin.particeps.core.collector

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.MAX_OBSERVATION_EVENTS
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Serializes callback events and owns the common strict collector lifecycle.
 *
 * One consumer on [consumerDispatcher] submits queued callbacks in capture order. When it wakes it
 * merges the callbacks that are already queued into as few observations as their admission tokens,
 * source-time order, and [callbackBatchEventLimit] allow. Without a [commitWindow] it never waits
 * for a later callback, so merging adds no delivery latency; it only removes per-callback commits
 * when callbacks queue up behind a slow commit or arrive together.
 *
 * A collector of a continuously sampled source may pass a [commitWindow]. It applies only when the
 * study's automation does not read the source's events ([CollectorContext.referencedByAutomation]).
 * The consumer then keeps a batch open for later callbacks until the window has elapsed since the
 * batch's first callback was captured, a barrier or stop arrives, or a merge rule ends the batch.
 * The window adds delivery latency and, until the batch commits, holds admitted samples only in
 * process memory; see [CallbackCommitWindow].
 */
abstract class SerializedCallbackCollector(
    protected val context: CollectorContext,
    queueCapacity: Int,
    private val consumerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    commitWindow: CallbackCommitWindow? = null,
) : Collector {
    private val messages = Channel<Message>(queueCapacity)
    private val batchEventLimit = callbackBatchEventLimit(context.sourceContract)
    private val window = commitWindow?.takeUnless { context.referencedByAutomation }

    // After the gate refuses a windowed batch, no callback is admitted under that token again, so
    // what the consumer holds for the next barrier is one batch plus what was already queued.
    private val heldEventLimit =
        (batchEventLimit.toLong() + queueCapacity).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
        enqueue(Message.Event(token, listOf(draft()), window?.timeSource?.markNow()))
    }

    /**
     * Queues every draft one platform callback delivered, in the given order, under one admission
     * token and one queue slot. [drafts] runs only after admission; an empty result queues nothing.
     */
    protected fun captureAll(drafts: () -> List<EventDraft>) {
        val token = context.eventSink.captureToken() ?: return
        val captured = drafts().toList()
        if (captured.isNotEmpty()) enqueue(Message.Event(token, captured, window?.timeSource?.markNow()))
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

    /**
     * Gathers queued callbacks into batches and offers each batch through [submit].
     *
     * A batch ends, and is offered, when the queue is empty (or, with a [window], when the window
     * of the callback that opened it has elapsed and the queue is empty), before a barrier or stop
     * is handled, before a callback under an unequal token, and at a callback [accepts] refuses;
     * the callback that ends a batch opens the next one. A barrier therefore completes only after
     * every earlier callback has been offered, and never waits for a window.
     *
     * With a window, the events of a batch the admission gate refuses are held and offered once
     * more, together with same-token callbacks that were already queued, before the next barrier,
     * stop, or callback under another token: a drain that begins after the study deadline admits
     * events observed before it, and the deadline stop's drain can begin after a window closed.
     * What that final offer refuses is dropped. Without a window a refusal drops the events at
     * once, as it always did.
     */
    private suspend fun consume() {
        val batch = ArrayList<EventDraft>()
        var token: AdmissionToken? = null
        var opened: ComparableTimeMark? = null
        var held = false

        suspend fun offer(final: Boolean) {
            val refused = submit(checkNotNull(token), batch)
            batch.clear()
            held = !final && window != null && refused.isNotEmpty()
            if (held) batch += refused
        }

        while (true) {
            val message = if (batch.isEmpty()) messages.receive() else nextMessage(held, opened)
            when (message) {
                null -> offer(final = false)
                is Message.Event -> {
                    if (batch.isNotEmpty() && message.token != token) offer(final = true)
                    for (draft in message.drafts) {
                        if (batch.isNotEmpty() && !held && !batch.accepts(draft)) offer(final = false)
                        if (batch.isEmpty()) {
                            token = message.token
                            opened = message.capturedAt
                        }
                        batch += draft
                    }
                    if (held && batch.size > heldEventLimit) {
                        batch.clear()
                        held = false
                        fail("CALLBACK_QUEUE_FULL")
                    }
                }
                is Message.Barrier -> {
                    if (batch.isNotEmpty()) offer(final = true)
                    message.completion.complete(Unit)
                }
                Message.Stop -> {
                    if (batch.isNotEmpty()) offer(final = true)
                    return
                }
            }
        }
    }

    /**
     * The next message for an open batch, or null when the batch should be offered now: at once
     * when nothing is queued and there is no [window]; otherwise when the window of the callback
     * that opened the batch has elapsed with nothing queued. A held batch waits for a message.
     * [select] takes either one message or the timeout, so a message is never lost to the window.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun nextMessage(held: Boolean, opened: ComparableTimeMark?): Message? {
        messages.tryReceive().getOrNull()?.let { return it }
        val window = window ?: return null
        if (held) return messages.receive()
        val remaining = window.duration - checkNotNull(opened).elapsedNow()
        if (!remaining.isPositive()) return null
        return select {
            messages.onReceive { it }
            onTimeout(remaining) { null }
        }
    }

    /** One observation must stay within its bound, source-time order, and boot session. */
    private fun List<EventDraft>.accepts(next: EventDraft): Boolean = size < batchEventLimit && next.follows(last())

    private fun EventDraft.follows(previous: EventDraft): Boolean =
        observedTime.bootSessionId == previous.observedTime.bootSessionId &&
            observedTime.elapsedRealtimeNanos >= previous.observedTime.elapsedRealtimeNanos

    /** The exclusive end of the longest run of [drafts] from [start] that one observation may hold. */
    private fun partEnd(drafts: List<EventDraft>, start: Int): Int {
        var end = start + 1
        while (end < drafts.size && end - start < batchEventLimit && drafts[end].follows(drafts[end - 1])) end++
        return end
    }

    /**
     * Submits [drafts], in capture order, as few observations as the runtime records, and returns
     * the events the admission gate refused: empty unless a one-event offer was refused, and then
     * that event and every later one.
     *
     * Each offer holds at most the run of events that [partEnd] allows, so a held batch that grew
     * beyond one observation is offered in parts. The runtime records an offer whole or, when a
     * desired resource first changes after the offer's first event, only the events before that
     * one. The rest is then offered under the next producer ordinal, so the observation that
     * stages the change starts with the event that causes it, and the callbacks captured before
     * it commit first, as when each was offered alone.
     *
     * An offer refused by the admission gate or for a contract violation is halved until a prefix
     * of it is accepted, and after each acceptance the whole remainder, up to the part bound, is
     * offered again, because admission can widen when a drain begins after the study deadline. Each
     * part accepted this way holds at least half of what is still admissible, so a batch takes
     * O(log n) accepted observations, which bounds a barrier drain's pending-slot rewrites, and
     * O(log² n) offers. A refused one-event offer ends the submission: the gate refuses that event
     * and every later one, and a contract violation fails the collector there, after the valid
     * events before it were recorded. A storage failure or quality gap fails the collector at once.
     * Parts accepted earlier stay recorded.
     */
    private suspend fun submit(token: AdmissionToken, drafts: List<EventDraft>): List<EventDraft> {
        var start = 0
        // Exclusive end of the shortest offer refused since the last acceptance.
        var refusedEnd = NO_REFUSAL
        while (start < drafts.size) {
            val end = if (refusedEnd == NO_REFUSAL) partEnd(drafts, start) else start + (refusedEnd - start) / 2
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
                        fail("EVENT_SINK_CONTRACT_VIOLATION")
                        return emptyList()
                    }
                    start += result.recordedEvents
                    refusedEnd = NO_REFUSAL
                }
                EmitBatchResult.RejectedByAdmissionGate -> {
                    if (end == start + 1) return drafts.subList(start, drafts.size).toList()
                    refusedEnd = end
                }
                EmitBatchResult.ContractViolation -> {
                    if (end == start + 1) {
                        fail("EVENT_CONTRACT_VIOLATION")
                        return emptyList()
                    }
                    refusedEnd = end
                }
                EmitBatchResult.StorageFailure -> {
                    fail("STORAGE_WRITE_FAILED")
                    return emptyList()
                }
                is EmitBatchResult.SourceQualityGap -> {
                    fail("SOURCE_QUALITY_GAP")
                    return emptyList()
                }
            }
        }
        return emptyList()
    }

    private sealed interface Message {
        /**
         * Drafts from one callback, captured under one admission token and in source order.
         * [capturedAt] is the [CallbackCommitWindow.timeSource] mark of the capture, when windowed.
         */
        class Event(
            val token: AdmissionToken,
            val drafts: List<EventDraft>,
            val capturedAt: ComparableTimeMark?,
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
 * How long a [SerializedCallbackCollector] may keep a batch open for later callbacks: [duration]
 * after the batch's first callback was captured, at most [MAXIMUM_CALLBACK_COMMIT_WINDOW].
 *
 * Only a continuously sampled source whose samples no automation needs promptly opts in; the
 * collector ignores the window when [CollectorContext.referencedByAutomation] is true. Events keep
 * their capture-time observed time and admission token, so the window changes when a sample is
 * committed, never what it records or which condition epoch it belongs to. Until the batch commits
 * its samples exist only in process memory: process death loses them, inside the quality gap that
 * recovery records. A barrier or stop offers the batch at once. A safety pause or wall-clock
 * discontinuity closes admission before it pauses the collector, so that offer is refused and the
 * batch is dropped, with no quality gap that names the source.
 *
 * The window is a coroutine timeout on the collector's consumer dispatcher and adds no timer,
 * alarm, or wake lock. [timeSource] must measure the same clock as that dispatcher's delays:
 * [TimeSource.Monotonic] for the default dispatcher, which is awake monotonic time on Android. If
 * the CPU suspends while a batch is open, the window pauses with it, so a source that holds no wake
 * lock commits such a batch only after the CPU wakes and the rest of the window has elapsed, or
 * sooner at a barrier, stop, or merge rule.
 */
class CallbackCommitWindow(
    val duration: Duration,
    val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    init {
        require(duration.isPositive() && duration <= MAXIMUM_CALLBACK_COMMIT_WINDOW) {
            "A callback commit window must be positive and at most $MAXIMUM_CALLBACK_COMMIT_WINDOW"
        }
    }

    companion object {
        /** The window of a continuously sampled sensor: its samples commit in batches of up to 5 s. */
        val SAMPLED_SENSOR = CallbackCommitWindow(MAXIMUM_CALLBACK_COMMIT_WINDOW)
    }
}

/** The longest [CallbackCommitWindow]: the most delivery latency and memory-only exposure accepted. */
val MAXIMUM_CALLBACK_COMMIT_WINDOW: Duration = 5.seconds

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
