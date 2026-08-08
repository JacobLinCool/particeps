package cool.jacoblin.particeps.core.collector

import cool.jacoblin.particeps.core.model.EventDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Serializes callback events and owns the common strict collector lifecycle. */
abstract class SerializedCallbackCollector(
    protected val context: CollectorContext,
    queueCapacity: Int,
) : Collector {
    private val messages = Channel<Message>(queueCapacity)
    private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
    private var consumerJob: Job? = null
    private var sourceState = SourceState.RELEASED

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
        val job = context.scope.launch(Dispatchers.Default) { consume() }
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

    protected fun capture(draft: () -> EventDraft) {
        val token = context.eventSink.captureToken() ?: return
        if (!messages.trySend(Message.Event(token, draft())).isSuccess) {
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
        for (message in messages) {
            when (message) {
                is Message.Event -> {
                    val result = try {
                        context.eventSink.emit(message.token, message.draft)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        EmitResult.StorageFailure
                    }
                    when (result) {
                        EmitResult.ContractViolation -> fail("EVENT_CONTRACT_VIOLATION")
                        EmitResult.StorageFailure -> fail("STORAGE_WRITE_FAILED")
                        else -> Unit
                    }
                }
                is Message.Barrier -> message.completion.complete(Unit)
                Message.Stop -> return
            }
        }
    }

    private sealed interface Message {
        data class Event(
            val token: AdmissionToken,
            val draft: EventDraft,
        ) : Message

        data class Barrier(val completion: CompletableDeferred<Unit>) : Message

        data object Stop : Message
    }

    private enum class SourceState { RELEASED, REGISTERED, UNCERTAIN }
}
