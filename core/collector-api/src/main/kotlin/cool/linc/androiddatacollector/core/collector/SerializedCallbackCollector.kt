package cool.linc.androiddatacollector.core.collector

import cool.linc.androiddatacollector.core.model.EventDraft
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
    private var sourceRegistered = false

    final override val health: StateFlow<CollectorHealth>
        get() = mutableHealth.asStateFlow()

    final override suspend fun start() {
        check(consumerJob == null) { "Collector is already started" }
        check(mutableHealth.value.status in setOf(CollectorStatus.STOPPED, CollectorStatus.FAILED)) {
            "Collector cannot be started"
        }
        val job = context.scope.launch(Dispatchers.Default) { consume() }
        consumerJob = job
        try {
            register()
            mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        } catch (failure: Throwable) {
            messages.send(Message.Stop)
            job.join()
            consumerJob = null
            fail("SOURCE_REGISTRATION_FAILED")
            throw failure
        }
    }

    final override suspend fun pause() {
        checkNotNull(consumerJob) { "Collector is not started" }
        try {
            unregister()
        } catch (failure: Throwable) {
            fail("SOURCE_UNREGISTRATION_FAILED")
            throw failure
        }
        flush()
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
        val failure = runCatching { unregister() }.exceptionOrNull()
        try {
            flush()
        } finally {
            messages.send(Message.Stop)
            job.join()
            consumerJob = null
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

    protected abstract suspend fun registerSource()
    protected abstract suspend fun unregisterSource()

    private suspend fun register() {
        check(!sourceRegistered) { "Collector source is already registered" }
        registerSource()
        sourceRegistered = true
    }

    private suspend fun unregister() {
        if (!sourceRegistered) return
        unregisterSource()
        sourceRegistered = false
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
                    if (result == EmitResult.StorageFailure) fail("STORAGE_WRITE_FAILED")
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
}
