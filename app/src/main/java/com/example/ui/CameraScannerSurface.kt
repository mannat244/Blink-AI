package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.BlinkSettings
import com.example.data.FrameTelemetry
import com.example.data.ScanPhase
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LockMint
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.OpticalCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VerifiedAmber
import com.example.vision.BitmapIntelligence
import com.example.vision.FrameIntelligenceAnalyzer
import com.example.vision.OpticalTargetCard
import com.example.vision.TargetLensGenerator
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

@Composable
fun CameraScannerSurface(
    hasCameraPermission: Boolean,
    scanPhase: ScanPhase,
    isScannerActiveNow: () -> Boolean,
    isBlackScreenMode: Boolean,
    settings: BlinkSettings,
    telemetry: FrameTelemetry,
    activeTargetIndex: Int?,
    existingHashes: List<Long>,
    onTelemetryUpdated: (FrameTelemetry) -> Unit,
    onFrameCaptured: (Bitmap, Float, Long, String, String, Boolean) -> Unit,
    onForceCaptureTarget: () -> Unit,
    onRegisterManualCameraSnap: ((() -> Boolean)?) -> Unit = {},
    onCameraFrameUnavailable: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var motionDelta by remember { mutableFloatStateOf(0.05f) }
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastMag = 9.81f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size >= 3) {
                    val mag = sqrt(
                        values[0] * values[0] +
                            values[1] * values[1] +
                            values[2] * values[2]
                    )
                    val delta = abs(mag - lastMag)
                    lastMag = mag
                    motionDelta = (motionDelta * 0.7f) + (delta * 0.3f)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (accelSensor != null) {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    val currentScannerCheck by rememberUpdatedState(isScannerActiveNow)
    val currentSettings by rememberUpdatedState(settings)
    val currentHashes by rememberUpdatedState(existingHashes)

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var activeAnalyzerRef by remember { mutableStateOf<FrameIntelligenceAnalyzer?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            onRegisterManualCameraSnap(null)
            cameraExecutor.shutdown()
        }
    }

    // Register a real-camera manual capture trigger that works in both normal and Black-Screen modes
    LaunchedEffect(activeAnalyzerRef, previewViewRef, hasCameraPermission, activeTargetIndex) {
        if (!hasCameraPermission || activeTargetIndex != null) {
            onRegisterManualCameraSnap(null)
        } else {
            onRegisterManualCameraSnap {
                val previewBmp = previewViewRef?.bitmap
                if (previewBmp != null) {
                    val scaled = BitmapIntelligence.scaleDownIfNeeded(
                        previewBmp,
                        currentSettings.imageResolution.maxDimensionPx
                    )
                    val (sharpness, dHash) = BitmapIntelligence.computeBitmapMetrics(scaled)
                    onFrameCaptured(scaled, sharpness, dHash, "Camera Capture", "", true)
                    true
                } else {
                    val analyzer = activeAnalyzerRef
                    analyzer?.requestManualCapture() ?: false
                }
            }
        }
    }

    LaunchedEffect(hasCameraPermission, isBlackScreenMode, activeTargetIndex, previewViewRef) {
        if (!hasCameraPermission || activeTargetIndex != null) return@LaunchedEffect
        val previewView = previewViewRef
        if (!isBlackScreenMode && previewView == null) {
            // Wait until AndroidView creates the PreviewView before binding Preview + ImageAnalysis
            return@LaunchedEffect
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val analyzer = FrameIntelligenceAnalyzer(
                    isScanningActive = { currentScannerCheck() },
                    getAutoCaptureEnabled = { currentSettings.autoCaptureEnabled },
                    getLaptopScreenMode = { currentSettings.laptopScreenMode },
                    getSensitivity = { currentSettings.captureSensitivity },
                    getMaxResolutionPx = { currentSettings.imageResolution.maxDimensionPx },
                    getDeviceMotionDelta = { motionDelta },
                    getExistingHashes = { currentHashes },
                    onTelemetryUpdated = { t ->
                        ContextCompat.getMainExecutor(context).execute {
                            onTelemetryUpdated(t)
                        }
                    },
                    onAutoCaptureTriggered = { bmp, sharpness, dHash, surfaceType, ocrText, bypassDup ->
                        ContextCompat.getMainExecutor(context).execute {
                            onFrameCaptured(bmp, sharpness, dHash, surfaceType, ocrText, bypassDup)
                        }
                    }
                )
                activeAnalyzerRef = analyzer

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 960))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, analyzer)
                    }

                val currentPreviewView = previewViewRef
                if (!isBlackScreenMode && currentPreviewView != null) {
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = currentPreviewView.surfaceProvider
                    }
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } else {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        imageAnalysis
                    )
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
    ) {
        if (!isBlackScreenMode) {
            // Keep camera preview visible even during CONTEXT_CONFIRMATION so the user can aim through the translucent UI!
            if (activeTargetIndex != null) {
                val target = TargetLensGenerator.targets.getOrNull(activeTargetIndex)
                if (target != null) {
                    OpticalTargetProjectionView(
                        target = target,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            if (previewViewRef !== this) {
                                previewViewRef = this
                            }
                        }
                    },
                    update = { view ->
                        if (previewViewRef !== view) {
                            previewViewRef = view
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("camera_preview_surface")
                )
            }

            // Subtle top/bottom vignette for HUD legibility while keeping the center completely clear
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.45f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.55f)
                            )
                        )
                    )
            )

            // Smart Text Auto-Click Overlay (shown during SCANNING — no shape or screen rectangles)
            if (scanPhase == ScanPhase.SCANNING) {
                SmartTextAutoClickOverlay(
                    telemetry = telemetry,
                    autoCaptureEnabled = settings.autoCaptureEnabled,
                    onManualSnapTap = {
                        if (activeTargetIndex != null) {
                            onForceCaptureTarget()
                        } else {
                            val bmp = previewViewRef?.bitmap
                            if (bmp != null) {
                                val scaled = BitmapIntelligence.scaleDownIfNeeded(
                                    bmp,
                                    settings.imageResolution.maxDimensionPx
                                )
                                val (sharpness, dHash) = BitmapIntelligence.computeBitmapMetrics(scaled)
                                onFrameCaptured(scaled, sharpness, dHash, "Smart Camera", "", true)
                            } else {
                                val requested = activeAnalyzerRef?.requestManualCapture() ?: false
                                if (!requested) {
                                    onCameraFrameUnavailable()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun OpticalTargetProjectionView(
    target: OpticalTargetCard,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF101C30),
                        Color(0xFF060A12)
                    )
                )
            )
            .padding(horizontal = 24.dp, vertical = 96.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ObsidianCard.copy(alpha = 0.94f))
                .border(
                    width = 1.5.dp,
                    color = Color(target.accentColorHex).copy(alpha = 0.65f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Text(
                text = target.categoryBadge,
                style = MaterialTheme.typography.labelMedium,
                color = Color(target.accentColorHex)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = target.headerLine,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF070B12))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                target.bodyLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = TextPrimary.copy(alpha = 0.92f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = target.footerQuestion,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(target.accentColorHex),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Clean, minimal Smart Text Auto-Click reticle:
 * No shape/screen boundary rectangles — only a subtle center focus point and status pill.
 */
@Composable
private fun SmartTextAutoClickOverlay(
    telemetry: FrameTelemetry,
    autoCaptureEnabled: Boolean,
    onManualSnapTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = telemetry.lockProgress,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "auto_click_progress"
    )

    val ringColor = when {
        telemetry.isDuplicateFrame -> VerifiedAmber
        telemetry.hasReadableContent -> LockMint
        else -> OpticalCyan.copy(alpha = 0.65f)
    }

    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onManualSnapTap
            )
            .testTag("optical_reticle_capture_area")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val ringRadius = 26.dp.toPx()

            drawCircle(
                color = ringColor.copy(alpha = 0.35f),
                radius = ringRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.8.dp.toPx())
            )

            if (animatedProgress > 0.02f) {
                drawArc(
                    color = LockMint,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(cx - ringRadius, cy - ringRadius),
                    size = ComposeSize(ringRadius * 2, ringRadius * 2),
                    style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            drawCircle(
                color = ringColor,
                radius = 4.dp.toPx(),
                center = Offset(cx, cy)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 82.dp, start = 20.dp, end = 20.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.50f))
                .border(0.8.dp, ringColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (autoCaptureEnabled) {
                    telemetry.statusHint
                } else {
                    "Manual Mode • Tap anywhere to click picture"
                },
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
        }
    }
}
