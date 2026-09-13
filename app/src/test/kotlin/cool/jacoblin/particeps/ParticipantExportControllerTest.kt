package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.export.ExportProgress
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.export.ExportStage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParticipantExportControllerTest {
    @Test
    fun fullEncryptionProgressIsNotSuccessUntilFinalizationAndCloseComplete() = runTest {
        val finish = CompletableDeferred<Unit>()
        val destination = TrackingStream()
        val diagnostics = mutableListOf<String>()
        var removed = false
        val controller = ParticipantExportController(
            scope = this,
            writeExport = { output, report ->
                report(ExportProgress(ExportStage.ENCRYPTING, 2, 2))
                report(ExportProgress(ExportStage.FINALIZING))
                finish.await()
                output.close()
                RECEIPT
            },
            reportDiagnostic = diagnostics::add,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(controller.chooseDestination())
        assertFalse(controller.chooseDestination())
        assertTrue(controller.start({ destination }, { removed = true; true }))
        runCurrent()
        assertEquals(
            ParticipantExportState.Running(ParticipantExportPhase.FINISHING),
            controller.state.value,
        )
        assertFalse(controller.start({ error("Duplicate export opened") }, { error("Duplicate cleanup") }))
        assertFalse(destination.closed)

        finish.complete(Unit)
        runCurrent()
        assertEquals(ParticipantExportState.Succeeded, controller.state.value)
        assertTrue(destination.closed)
        assertFalse(removed)
        val logged = diagnostics.joinToString("\n")
        assertTrue(logged.contains("phase=OPENING started"))
        assertTrue(logged.contains("phase=FINALIZING finished"))
        assertTrue(logged.contains("outcome=succeeded"))
        assertTrue(logged.contains("commits=2 events=3 bytes=64"))
        assertFalse(logged.contains(RECEIPT.bundleId.toString()))
        assertFalse(logged.contains(RECEIPT.configurationSha256))
    }

    @Test
    fun cancellationStaysActiveUntilSessionClosesAndCleanupFinishes() = runTest {
        val allowClose = CompletableDeferred<Unit>()
        val destination = TrackingStream()
        var removed = false
        val controller = ParticipantExportController(
            scope = this,
            writeExport = { output, report ->
                try {
                    report(ExportProgress(ExportStage.ENCRYPTING, 1, 2))
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        report(ExportProgress(ExportStage.FINALIZING))
                        allowClose.await()
                        output.close()
                    }
                }
            },
            reportDiagnostic = {},
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.start({ destination }) {
            assertTrue(destination.closed)
            removed = true
            true
        }
        runCurrent()
        controller.cancel()
        assertEquals(ParticipantExportState.Cancelling, controller.state.value)
        runCurrent()
        assertTrue(controller.state.value.isActive)
        assertFalse(removed)
        assertFalse(controller.chooseDestination())

        allowClose.complete(Unit)
        runCurrent()
        assertTrue(removed)
        assertEquals(ParticipantExportState.Cancelled(incompleteFileRemains = false), controller.state.value)
        assertTrue(controller.chooseDestination())
    }

    @Test
    fun failureClosesBeforeCleanupAndNeverExposesTheException() = runTest {
        val destination = TrackingStream()
        val diagnostics = mutableListOf<String>()
        val controller = ParticipantExportController(
            scope = this,
            writeExport = { output, _ ->
                output.use { throw IOException("private destination and study information") }
            },
            reportDiagnostic = diagnostics::add,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.start({ destination }) {
            assertTrue(destination.closed)
            true
        }
        runCurrent()
        assertEquals(ParticipantExportState.Failed(incompleteFileRemains = false), controller.state.value)
        assertTrue(diagnostics.any { "outcome=failed" in it })
        assertFalse(diagnostics.any { "private destination" in it || "commits=" in it })
    }

    @Test
    fun unsuccessfulOrThrowingRemovalIsDisclosedWithoutMaskingTheExportOutcome() = runTest {
        for (throwDuringRemoval in listOf(false, true)) {
            val controller = ParticipantExportController(
                scope = this,
                writeExport = { _, _ -> error("Must not export after open failed") },
                reportDiagnostic = {},
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            controller.start({ throw IOException("cannot open") }) {
                if (throwDuringRemoval) throw IOException("cannot remove")
                false
            }
            runCurrent()
            assertEquals(ParticipantExportState.Failed(incompleteFileRemains = true), controller.state.value)
        }
    }

    @Test
    fun cancellationDuringBlockingOpenClosesUnhandedStreamAndDeletesOnIo() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "export-test-ui") }.asCoroutineDispatcher().use { ui ->
            val fileThread = AtomicReference<Thread>()
            Executors.newSingleThreadExecutor {
                Thread(it, "export-test-file").also(fileThread::set)
            }.asCoroutineDispatcher().use { io ->
                val scope = CoroutineScope(SupervisorJob() + ui)
                val openEntered = CountDownLatch(1)
                val allowOpenToReturn = CountDownLatch(1)
                val handedToSession = AtomicBoolean(false)
                val closedOn = AtomicReference<Thread>()
                val removedOn = AtomicReference<Thread>()
                val destination = object : ByteArrayOutputStream() {
                    override fun close() { closedOn.set(Thread.currentThread()) }
                }
                val controller = ParticipantExportController(
                    scope,
                    writeExport = { _, _ -> handedToSession.set(true); RECEIPT },
                    reportDiagnostic = {},
                    ioDispatcher = io,
                )
                try {
                    withContext(ui) {
                        controller.start(
                            openDestination = {
                                openEntered.countDown()
                                assertSame(fileThread.get(), Thread.currentThread())
                                check(allowOpenToReturn.await(5, TimeUnit.SECONDS))
                                destination
                            },
                            removeIncomplete = {
                                assertSame(fileThread.get(), closedOn.get())
                                removedOn.set(Thread.currentThread())
                                true
                            },
                        )
                    }
                    assertTrue(openEntered.await(5, TimeUnit.SECONDS))
                    withContext(ui) { controller.cancel() }
                    assertEquals(ParticipantExportState.Cancelling, controller.state.value)
                    allowOpenToReturn.countDown()
                    val finished = withTimeout(5_000) { controller.state.first { !it.isActive } }
                    assertEquals(ParticipantExportState.Cancelled(incompleteFileRemains = false), finished)
                    assertFalse(handedToSession.get())
                    assertSame(fileThread.get(), removedOn.get())
                } finally {
                    allowOpenToReturn.countDown()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun cancellingThePickerReleasesReservationWithoutCreatingAnExport() = runTest {
        val controller = ParticipantExportController(
            this,
            writeExport = { _, _ -> error("No file was chosen") },
            reportDiagnostic = {},
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertTrue(controller.chooseDestination())
        assertTrue(controller.state.value.isActive)
        controller.destinationCancelled()
        assertEquals(ParticipantExportState.Idle, controller.state.value)
        assertTrue(controller.chooseDestination())
        controller.destinationUnavailable()
        assertEquals(ParticipantExportState.Failed(incompleteFileRemains = false), controller.state.value)
    }

    @Test
    fun percentagesAreRestrictedToMeasurablePhasesAndHandleLargeCounts() {
        assertEquals(
            0.5f,
            ParticipantExportState.Running(ParticipantExportPhase.READING, 1, 2).phaseFraction,
        )
        assertEquals(
            1f,
            ParticipantExportState.Running(ParticipantExportPhase.ENCRYPTING, Long.MAX_VALUE, Long.MAX_VALUE)
                .phaseFraction,
        )
        assertEquals(null, ParticipantExportState.Running(ParticipantExportPhase.PREPARING, 1, 2).phaseFraction)
        assertEquals(null, ParticipantExportState.Running(ParticipantExportPhase.FINISHING, 2, 2).phaseFraction)
        assertEquals(null, ParticipantExportState.Running(ParticipantExportPhase.ENCRYPTING, 0, 0).phaseFraction)
    }

    private class TrackingStream : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true }
    }

    private companion object {
        val RECEIPT = ExportReceipt(
            bundleId = UUID.fromString("ee17ffb7-eaa0-4dba-8cb5-692ea33a30e2"),
            configurationSha256 = "1".repeat(64),
            firstCommitSequence = 1,
            lastCommitSequence = 2,
            commitCount = 2,
            eventCount = 3,
            sha256 = "2".repeat(64),
            byteCount = 64,
        )
    }
}
