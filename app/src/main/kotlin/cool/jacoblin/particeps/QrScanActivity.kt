package cool.jacoblin.particeps

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Scanning returns the same validated join input as a deep link; cancellation has no side effect. */
class QrScanContract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(context, QrScanActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) {
            intent?.getStringExtra(QrScanActivity.RESULT_JOIN_LINK)?.let(::canonicalStudyQr)
        } else {
            null
        }
}

internal enum class QrScannerStatus { OPENING, SCANNING, INVALID_CODE, PERMISSION_REQUIRED, CAMERA_UNAVAILABLE }

class QrScanActivity : ComponentActivity() {
    private var status by mutableStateOf(QrScannerStatus.OPENING)
    private var hasFlash by mutableStateOf(false)
    private var torchOn by mutableStateOf(false)
    private var canRequestPermission by mutableStateOf(false)
    private lateinit var previewView: PreviewView
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var analyzerExecutor: ExecutorService? = null
    private var acceptingFrames: AtomicBoolean? = null
    private var cameraGeneration = 0
    private var cameraStarting = false
    private var scannerResumed = false
    private var permissionAttempted = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            status = QrScannerStatus.OPENING
            startCamera()
        } else {
            showPermissionRequired()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        permissionAttempted = savedInstanceState?.getBoolean(STATE_PERMISSION_ATTEMPTED) == true
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF91DCCE),
                    onPrimary = Color(0xFF102437),
                    background = Color(0xFF102437),
                    surface = Color(0xFF102437),
                    onSurface = Color(0xFFF4F7FB),
                ),
            ) {
                Surface {
                    QrScannerScreen(
                        previewView = previewView,
                        status = status,
                        hasFlash = hasFlash,
                        torchOn = torchOn,
                        canRequestPermission = canRequestPermission,
                        onCancel = ::finish,
                        onRetry = ::retryScan,
                        onRequestPermission = ::requestCameraPermission,
                        onOpenSettings = ::openAppSettings,
                        onToggleTorch = ::toggleTorch,
                    )
                }
            }
        }
        if (!hasCameraPermission()) {
            if (!permissionAttempted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                requestCameraPermission()
            } else {
                showPermissionRequired()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scannerResumed = true
        if (!hasCameraPermission()) {
            showPermissionRequired()
        } else if (status == QrScannerStatus.PERMISSION_REQUIRED || status == QrScannerStatus.SCANNING) {
            status = QrScannerStatus.OPENING
        }
        if (status == QrScannerStatus.OPENING) startCamera()
    }

    override fun onPause() {
        scannerResumed = false
        stopCamera()
        super.onPause()
    }

    override fun onDestroy() {
        stopCamera()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PERMISSION_ATTEMPTED, permissionAttempted)
        super.onSaveInstanceState(outState)
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        permissionAttempted = true
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionRequired() {
        stopCamera()
        canRequestPermission = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        status = QrScannerStatus.PERMISSION_REQUIRED
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: ActivityNotFoundException) {
            showPermissionRequired()
        }
    }

    private fun retryScan() {
        stopCamera()
        if (hasCameraPermission()) {
            status = QrScannerStatus.OPENING
            startCamera()
        } else {
            showPermissionRequired()
        }
    }

    private fun startCamera() {
        if (cameraStarting || camera != null || !hasCameraPermission()) return
        if (!scannerResumed) return
        cameraStarting = true
        val generation = ++cameraGeneration
        val future = try {
            ProcessCameraProvider.getInstance(this)
        } catch (_: IllegalStateException) {
            showCameraUnavailable()
            return
        }
        future.addListener({
            if (generation != cameraGeneration || !scannerResumed) {
                return@addListener
            }
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val selector = when {
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        showCameraUnavailable()
                        return@addListener
                    }
                }
                bindCamera(cameraProvider, selector, generation)
            } catch (_: ExecutionException) {
                showCameraUnavailable()
            } catch (_: CameraInfoUnavailableException) {
                showCameraUnavailable()
            } catch (_: CancellationException) {
                showCameraUnavailable()
            } catch (_: IllegalArgumentException) {
                showCameraUnavailable()
            } catch (_: IllegalStateException) {
                showCameraUnavailable()
            } catch (_: SecurityException) {
                showPermissionRequired()
            }
        }, mainExecutor)
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider, selector: CameraSelector, generation: Int) {
        val rotation = requireNotNull(display).rotation
        val cameraPreview = Preview.Builder().setTargetRotation(rotation).build()
        preview = cameraPreview
        cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
        val cameraAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                ).build(),
            )
            .build()
        analysis = cameraAnalysis
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "particeps-qr-analysis") }
        analyzerExecutor = executor
        val accepting = AtomicBoolean(true)
        acceptingFrames = accepting
        val decoder = QrFrameDecoder()
        var lastAnalyzedAt = Long.MIN_VALUE
        cameraAnalysis.setAnalyzer(executor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (!accepting.get() || (lastAnalyzedAt != Long.MIN_VALUE && now - lastAnalyzedAt < FRAME_INTERVAL_MS)) {
                    return@setAnalyzer
                }
                lastAnalyzedAt = now
                val crop = image.cropRect
                val plane = image.planes[0]
                val frame = copyQrLuminance(
                    plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride,
                    crop.left, crop.top, crop.width(), crop.height(), image.imageInfo.rotationDegrees,
                )
                val text = decoder.decode(frame) ?: return@setAnalyzer
                if (accepting.compareAndSet(true, false)) {
                    val link = canonicalStudyQr(text)
                    runOnUiThread {
                        if (generation == cameraGeneration && !isFinishing) {
                            stopCamera()
                            if (link == null) {
                                status = QrScannerStatus.INVALID_CODE
                            } else {
                                setResult(RESULT_OK, Intent().putExtra(RESULT_JOIN_LINK, link))
                                finish()
                            }
                        }
                    }
                }
            } catch (_: IllegalArgumentException) {
                runOnUiThread { if (generation == cameraGeneration) showCameraUnavailable() }
            } finally {
                image.close()
            }
        }
        val boundCamera = cameraProvider.bindToLifecycle(this, selector, cameraPreview, cameraAnalysis)
        camera = boundCamera
        cameraStarting = false
        hasFlash = boundCamera.cameraInfo.hasFlashUnit()
        boundCamera.cameraInfo.torchState.observe(this) { torchOn = it == TorchState.ON }
        boundCamera.cameraInfo.cameraState.observe(this) { cameraState ->
            if (generation == cameraGeneration) {
                if (cameraState.error != null) {
                    showCameraUnavailable()
                } else if (cameraState.type == CameraState.Type.OPEN) {
                    status = QrScannerStatus.SCANNING
                }
            }
        }
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        if (!hasFlash) return
        val generation = cameraGeneration
        val future = activeCamera.cameraControl.enableTorch(!torchOn)
        future.addListener({
            try {
                future.get()
            } catch (_: ExecutionException) {
                if (generation == cameraGeneration) showCameraUnavailable()
            } catch (_: CancellationException) {
                // The lifecycle has already released this camera.
            }
        }, mainExecutor)
    }

    private fun showCameraUnavailable() {
        stopCamera()
        status = QrScannerStatus.CAMERA_UNAVAILABLE
    }

    private fun stopCamera() {
        ++cameraGeneration
        cameraStarting = false
        acceptingFrames?.set(false)
        acceptingFrames = null
        analysis?.clearAnalyzer()
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        camera?.cameraInfo?.torchState?.removeObservers(this)
        val boundUseCases = listOfNotNull(preview, analysis)
        if (boundUseCases.isNotEmpty()) provider?.unbind(*boundUseCases.toTypedArray())
        analyzerExecutor?.shutdown()
        analyzerExecutor = null
        preview = null
        analysis = null
        camera = null
        hasFlash = false
        torchOn = false
    }

    companion object {
        internal const val RESULT_JOIN_LINK = "qr_join_link"
        private const val STATE_PERMISSION_ATTEMPTED = "camera_permission_attempted"
        private const val FRAME_INTERVAL_MS = 200L
    }
}

@Composable
private fun QrScannerScreen(
    previewView: PreviewView,
    status: QrScannerStatus,
    hasFlash: Boolean,
    torchOn: Boolean,
    canRequestPermission: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTorch: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.qr_scan_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.qr_scan_cancel)) }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (status == QrScannerStatus.OPENING || status == QrScannerStatus.SCANNING) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().clearAndSetSemantics {})
                ScanFrame(Modifier.fillMaxSize())
                if (status == QrScannerStatus.OPENING) CircularProgressIndicator()
            } else {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    val message = when (status) {
                        QrScannerStatus.INVALID_CODE -> R.string.qr_scan_invalid
                        QrScannerStatus.PERMISSION_REQUIRED -> R.string.qr_scan_permission
                        else -> R.string.qr_scan_unavailable
                    }
                    Text(
                        stringResource(message),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (status == QrScannerStatus.PERMISSION_REQUIRED) {
                        if (canRequestPermission) {
                            Button(onClick = onRequestPermission) { Text(stringResource(R.string.qr_scan_allow_camera)) }
                        }
                        OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.qr_scan_open_settings)) }
                    } else {
                        Button(onClick = onRetry) { Text(stringResource(R.string.qr_scan_retry)) }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status == QrScannerStatus.OPENING || status == QrScannerStatus.SCANNING) {
                Text(
                    stringResource(R.string.qr_scan_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                stringResource(R.string.qr_scan_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (hasFlash && status == QrScannerStatus.SCANNING) {
                OutlinedButton(onClick = onToggleTorch) {
                    Text(stringResource(if (torchOn) R.string.qr_scan_torch_off else R.string.qr_scan_torch_on))
                }
            }
        }
    }
}

@Composable
private fun ScanFrame(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.clearAndSetSemantics {}) {
        val edge = size.minDimension * 0.74f
        val left = (size.width - edge) / 2
        val top = (size.height - edge) / 2
        val arm = edge * 0.13f
        val width = 4.dp.toPx()
        for ((x, horizontal) in listOf(left to 1f, (left + edge) to -1f)) {
            for ((y, vertical) in listOf(top to 1f, (top + edge) to -1f)) {
                drawLine(color, Offset(x, y), Offset(x + arm * horizontal, y), width, StrokeCap.Round)
                drawLine(color, Offset(x, y), Offset(x, y + arm * vertical), width, StrokeCap.Round)
            }
        }
    }
}
