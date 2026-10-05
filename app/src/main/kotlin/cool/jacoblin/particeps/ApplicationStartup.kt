package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.access.AccessManager
import cool.jacoblin.particeps.core.application.StartupStage
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.platform.AndroidActionOutboxNotifier
import cool.jacoblin.particeps.platform.AndroidStudyUploadPlatform
import cool.jacoblin.particeps.platform.JoinArtifactDownloader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Published only after durable session initialization; no partially constructed graph escapes. */
internal interface ApplicationGraph {
    val session: StudySessionManager
    val accessManager: AccessManager
    val joinArtifactDownloader: JoinArtifactDownloader
    val actionOutboxNotifier: AndroidActionOutboxNotifier
    val uploadPlatform: AndroidStudyUploadPlatform

    suspend fun reconcileTimerWakeups()
}

internal sealed interface ApplicationStartupState {
    data class Starting(val stage: StartupStage? = null) : ApplicationStartupState
    data class Ready(val graph: ApplicationGraph) : ApplicationStartupState
    data object Failed : ApplicationStartupState
}

/** The cause is diagnostic-only. Participant surfaces project only [ApplicationStartupState.Failed]. */
internal class ApplicationStartupException(cause: Throwable) :
    IllegalStateException("Application initialization failed", cause)

/** One process-owned initialization, shared by cancellable consumers without transferring ownership. */
internal class ApplicationStartup(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<ApplicationGraph>()
    private val mutableState = MutableStateFlow<ApplicationStartupState>(ApplicationStartupState.Starting())
    val state: StateFlow<ApplicationStartupState> = mutableState.asStateFlow()

    fun start(factory: suspend ((StartupStage?) -> Unit) -> ApplicationGraph) {
        check(started.compareAndSet(false, true)) { "Application initialization already started" }
        val job = scope.launch(dispatcher) {
            try {
                val graph = factory { stage ->
                    mutableState.update { current ->
                        if (current is ApplicationStartupState.Starting) {
                            ApplicationStartupState.Starting(stage)
                        } else {
                            current
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                mutableState.value = ApplicationStartupState.Ready(graph)
                ready.complete(graph)
            } catch (failure: CancellationException) {
                fail(failure)
                throw failure
            } catch (failure: Throwable) {
                fail(failure)
                if (failure is Error) throw failure
            }
        }
        // A cancelled owner can prevent the launch body from ever entering its try/finally.
        job.invokeOnCompletion { failure -> if (failure != null) fail(failure) }
    }

    suspend fun awaitReady(): ApplicationGraph = ready.await()

    private fun fail(failure: Throwable) {
        if (ready.isCompleted) return
        mutableState.value = ApplicationStartupState.Failed
        if (failure is CancellationException) {
            ready.cancel(failure)
        } else {
            ready.completeExceptionally(ApplicationStartupException(failure))
        }
    }
}
