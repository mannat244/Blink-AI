package com.example.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.data.CaptureSensitivity
import com.example.data.FrameTelemetry
import com.example.data.NormalizedQuad
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class OcrExtractionResult(
    val fullText: String,
    val snippet: String,
    val charCount: Int,
    val blockCount: Int,
    val detectedQuad: NormalizedQuad?,
    val surfaceType: String
)

/**
 * Smart Camera Auto-Click Analyzer powered by Google ML Kit On-Device Text Recognition.
 *
 * Behavior:
 * - Clicks a picture automatically as soon as it sees readable text on a sharp, steady frame.
 * - NO shape, rectangle, or screen boundary detection is required.
 * - Applies ImageProxy rotationDegrees so upright text is recognized reliably.
 * - Never fabricates OCR characters when ML Kit finds no text or fails.
 */
class FrameIntelligenceAnalyzer(
    private val isScanningActive: () -> Boolean,
    private val getAutoCaptureEnabled: () -> Boolean,
    private val getLaptopScreenMode: () -> Boolean = { true },
    private val getSensitivity: () -> CaptureSensitivity,
    private val getMaxResolutionPx: () -> Int = { 1280 },
    private val getDeviceMotionDelta: () -> Float,
    private val getExistingHashes: () -> List<Long>,
    private val onTelemetryUpdated: (FrameTelemetry) -> Unit,
    private val onAutoCaptureTriggered: (Bitmap, Float, Long, String, String, Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalysisTimeMs = 0L
    private var lastAutoCaptureTimeMs = 0L
    private var previousGridLuma: IntArray? = null
    private var consecutiveLockedTicks = 0
    private var lastVerifiedOcr: OcrExtractionResult? = null
    private var lastOcrTimeMs = 0L

    private val manualCaptureRequested = AtomicBoolean(false)

    @Volatile
    private var latestFrameBitmap: Bitmap? = null

    /**
     * Requests an immediate real camera frame capture from the analyzer.
     */
    fun requestManualCapture(): Boolean {
        val cached = latestFrameBitmap
        if (cached != null && !cached.isRecycled) {
            val (sharpness, dHash) = BitmapIntelligence.computeBitmapMetrics(cached)
            onAutoCaptureTriggered(
                cached,
                sharpness,
                dHash,
                "Smart Camera",
                "",
                true
            )
            return true
        }
        manualCaptureRequested.set(true)
        return true
    }

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        val maxDim = getMaxResolutionPx().coerceIn(720, 1280)

        try {
            // Handle explicit manual capture request immediately using the live camera ImageProxy
            if (manualCaptureRequested.compareAndSet(true, false)) {
                val bmp = BitmapIntelligence.imageProxyToBitmap(image, maxDim = maxDim)
                if (bmp != null) {
                    latestFrameBitmap = bmp
                    val (sharpness, dHash) = BitmapIntelligence.computeBitmapMetrics(bmp)
                    onAutoCaptureTriggered(
                        bmp,
                        sharpness,
                        dHash,
                        "Smart Camera",
                        "",
                        true
                    )
                }
                return
            }

            if (!isScanningActive()) {
                consecutiveLockedTicks = 0
                return
            }

            // Fast analysis cadence (180ms) so the smart camera reacts immediately to readable text
            val throttleIntervalMs = 180L
            if (now - lastAnalysisTimeMs < throttleIntervalMs) {
                return
            }
            lastAnalysisTimeMs = now

            val plane = image.planes.firstOrNull() ?: return
            val rotationDegrees = image.imageInfo.rotationDegrees

            val gridW = 32
            val gridH = 32
            val grid = BitmapIntelligence.sampleRotatedLumaGrid(
                buffer = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                rawWidth = image.width,
                rawHeight = image.height,
                rotationDegrees = rotationDegrees,
                gridW = gridW,
                gridH = gridH
            )

            var totalLuma = 0L
            for (v in grid) {
                totalLuma += v
            }
            val avgLuma = totalLuma.toFloat() / (gridW * gridH)

            // 1. Compute sharpness from local luminance gradients (no shape/rectangle requirement)
            var gradientSum = 0f
            for (gy in 1 until gridH - 1) {
                for (gx in 1 until gridW - 1) {
                    val dx = abs(grid[gy * gridW + (gx + 1)] - grid[gy * gridW + (gx - 1)]).toFloat()
                    val dy = abs(grid[(gy + 1) * gridW + gx] - grid[(gy - 1) * gridW + gx]).toFloat()
                    gradientSum += (dx + dy) * 0.5f
                }
            }
            val rawSharpness = (gradientSum / ((gridW - 2) * (gridH - 2))) * 1.5f

            // 2. Inter-frame visual stability + accelerometer stability
            val prev = previousGridLuma
            val frameDiff = if (prev != null && prev.size == grid.size) {
                var diff = 0L
                for (i in grid.indices) {
                    diff += abs(grid[i] - prev[i])
                }
                diff.toFloat() / grid.size
            } else {
                10f
            }
            previousGridLuma = grid

            val accelDelta = getDeviceMotionDelta()
            val visualStability = (1f - (frameDiff / 28f)).coerceIn(0f, 1f)
            val sensorStability = (1f - (accelDelta / 3.5f)).coerceIn(0f, 1f)
            val combinedStability = (visualStability * 0.65f + sensorStability * 0.35f).coerceIn(0f, 1f)

            // 3. Perceptual dHash duplicate check
            val dHash = BitmapIntelligence.computeDHashFromGrid(grid, gridW, gridH)
            val isDuplicate = getExistingHashes().any { existing ->
                BitmapIntelligence.hammingDistance(existing, dHash) <= 8
            }

            val sensitivity = getSensitivity()
            // Any sharp, steady frame is immediately checked for readable text — NO shape or screen detection!
            val isSteadyAndSharp = rawSharpness >= sensitivity.minSharpness * 0.8f &&
                combinedStability >= sensitivity.minStability * 0.85f &&
                avgLuma >= 16f &&
                !isDuplicate

            var currentBitmap: Bitmap? = null
            if (combinedStability >= 0.60f && rawSharpness >= 10f) {
                currentBitmap = BitmapIntelligence.imageProxyToBitmap(image, maxDim = maxDim)
                if (currentBitmap != null) {
                    latestFrameBitmap = currentBitmap
                }
            }

            // 4. Run On-Device ML Kit Text Recognition to detect readable text (solely to trigger auto-click)
            val ocrResult: OcrExtractionResult? = if (isSteadyAndSharp && (now - lastOcrTimeMs >= 260L || lastVerifiedOcr == null)) {
                val bmpForOcr = currentBitmap ?: BitmapIntelligence.imageProxyToBitmap(image, maxDim = maxDim).also {
                    if (it != null) {
                        currentBitmap = it
                        latestFrameBitmap = it
                    }
                }
                if (bmpForOcr != null) {
                    lastOcrTimeMs = now
                    val extracted = BitmapIntelligence.extractOcrFromBitmap(bitmap = bmpForOcr)
                    lastVerifiedOcr = extracted
                    extracted
                } else {
                    lastVerifiedOcr
                }
            } else if (!isSteadyAndSharp) {
                if (combinedStability < 0.50f) {
                    lastVerifiedOcr = null
                }
                lastVerifiedOcr
            } else {
                lastVerifiedOcr
            }

            val verifiedCharCount = ocrResult?.charCount ?: 0
            val verifiedBlockCount = ocrResult?.blockCount ?: 0
            val hasReadableText = verifiedCharCount >= sensitivity.minOcrChars &&
                verifiedBlockCount >= 1 &&
                !ocrResult?.fullText.isNullOrBlank()

            // Lock as soon as readable text is visible on a steady, sharp frame (no shape/screen quad required)
            val isReadyToClick = hasReadableText &&
                rawSharpness >= sensitivity.minSharpness &&
                combinedStability >= sensitivity.minStability &&
                !isDuplicate

            val cooldownPassed = (now - lastAutoCaptureTimeMs) >= 1_600L

            if (isReadyToClick && getAutoCaptureEnabled() && cooldownPassed) {
                consecutiveLockedTicks++
            } else if (!isReadyToClick) {
                consecutiveLockedTicks = 0
            }

            val requiredTicks = sensitivity.requiredHoldTicks.coerceAtLeast(1)
            val lockProgress = (consecutiveLockedTicks.toFloat() / requiredTicks.toFloat()).coerceIn(0f, 1f)

            val hint = when {
                isDuplicate -> "Already captured — point at new text"
                avgLuma < 16f -> "Too dark — point at lit text"
                !hasReadableText -> "Point at readable text to auto-click"
                combinedStability < sensitivity.minStability -> "Hold steady — readable text detected..."
                lockProgress >= 1f -> "Auto-clicked readable text!"
                else -> "Auto-clicking readable text (${(lockProgress * 100).roundToInt()}%)"
            }

            onTelemetryUpdated(
                FrameTelemetry(
                    sharpnessScore = rawSharpness,
                    stabilityScore = combinedStability,
                    luminance = avgLuma,
                    lockProgress = lockProgress,
                    isDuplicateFrame = isDuplicate,
                    hasReadableContent = hasReadableText,
                    isDisplayScreenDetected = hasReadableText,
                    screenConfidence = if (hasReadableText) 0.95f else 0f,
                    detectedQuad = null,
                    ocrSnippet = ocrResult?.snippet.orEmpty(),
                    ocrCharCount = verifiedCharCount,
                    ocrBlockCount = verifiedBlockCount,
                    statusHint = hint
                )
            )

            // Auto-click the full upright camera frame immediately!
            if (lockProgress >= 1f && getAutoCaptureEnabled() && cooldownPassed && hasReadableText) {
                consecutiveLockedTicks = 0
                lastAutoCaptureTimeMs = now
                val finalBitmap = currentBitmap ?: BitmapIntelligence.imageProxyToBitmap(image, maxDim = maxDim)
                if (finalBitmap != null) {
                    latestFrameBitmap = finalBitmap
                    onAutoCaptureTriggered(
                        finalBitmap,
                        rawSharpness,
                        dHash,
                        ocrResult?.surfaceType ?: "Readable Text",
                        "", // No OCR text needed in prompt
                        false
                    )
                }
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }
}

object BitmapIntelligence {

    private val textRecognizer by lazy {
        runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }

    /**
     * Samples an upright [gridW]x[gridH] luminance grid from the Y-plane buffer,
     * applying [rotationDegrees] (0, 90, 180, 270) so horizontal and vertical axes
     * match the user's upright display orientation.
     */
    fun sampleRotatedLumaGrid(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        gridW: Int = 32,
        gridH: Int = 32
    ): IntArray {
        val grid = IntArray(gridW * gridH)
        if (rawWidth <= 0 || rawHeight <= 0 || buffer.limit() <= 0) return grid

        val normRotation = ((rotationDegrees % 360) + 360) % 360
        val margin = 0.06f
        val span = 0.88f
        val limit = buffer.limit()

        for (gy in 0 until gridH) {
            val v = margin + (gy.toFloat() / (gridH - 1).coerceAtLeast(1)) * span
            for (gx in 0 until gridW) {
                val u = margin + (gx.toFloat() / (gridW - 1).coerceAtLeast(1)) * span
                val (normRawX, normRawY) = when (normRotation) {
                    90 -> v to (1f - u)
                    180 -> (1f - u) to (1f - v)
                    270 -> (1f - v) to u
                    else -> u to v
                }
                val px = (normRawX * (rawWidth - 1)).roundToInt().coerceIn(0, rawWidth - 1)
                val py = (normRawY * (rawHeight - 1)).roundToInt().coerceIn(0, rawHeight - 1)
                val idx = py * rowStride + px * pixelStride
                val luma = if (idx in 0 until limit) {
                    buffer.get(idx).toInt() and 0xFF
                } else {
                    128
                }
                grid[gy * gridW + gx] = luma
            }
        }
        return grid
    }

    /**
     * Runs Google ML Kit On-Device Text Recognition solely to detect whether readable text
     * is present in the camera frame so the smart camera can auto-click immediately.
     *
     * NEVER fabricates characters when ML Kit fails, times out, or finds no text.
     */
    fun extractOcrFromBitmap(
        bitmap: Bitmap,
        fallbackQuad: NormalizedQuad? = null,
        isScreenMode: Boolean = true
    ): OcrExtractionResult {
        val emptyResult = OcrExtractionResult(
            fullText = "",
            snippet = "",
            charCount = 0,
            blockCount = 0,
            detectedQuad = null,
            surfaceType = "Readable Text"
        )

        return try {
            val recognizer = textRecognizer ?: return emptyResult
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = Tasks.await(recognizer.process(inputImage), 850, TimeUnit.MILLISECONDS)
            val blocks = visionText.textBlocks
            if (blocks.isEmpty()) {
                return emptyResult
            }

            val fullText = blocks.joinToString("\n") { it.text.trim() }.trim()
            val meaningfulChars = fullText.count { !it.isWhitespace() }
            if (meaningfulChars == 0) {
                return emptyResult
            }

            val snippet = fullText
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.length >= 3 }
                ?.take(48)
                .orEmpty()

            OcrExtractionResult(
                fullText = fullText,
                snippet = snippet,
                charCount = meaningfulChars,
                blockCount = blocks.size,
                detectedQuad = null,
                surfaceType = "Readable Text"
            )
        } catch (_: Exception) {
            emptyResult
        }
    }

    fun computeDHashFromGrid(grid: IntArray, gridW: Int, gridH: Int): Long {
        var hash = 0L
        var bitIndex = 0
        for (y in 0 until 8) {
            val gy = (y * gridH) / 8
            for (x in 0 until 8) {
                val gxLeft = (x * gridW) / 9
                val gxRight = ((x + 1) * gridW) / 9
                val leftVal = grid[gy * gridW + gxLeft]
                val rightVal = grid[gy * gridW + gxRight]
                if (leftVal > rightVal) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        return hash
    }

    fun computeBitmapMetrics(bitmap: Bitmap): Pair<Float, Long> {
        val gridW = 32
        val gridH = 32
        val scaled = Bitmap.createScaledBitmap(bitmap, gridW, gridH, true)
        val pixels = IntArray(gridW * gridH)
        scaled.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
        val lumaGrid = IntArray(gridW * gridH)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lumaGrid[i] = ((r * 299 + g * 587 + b * 114) / 1000)
        }
        var gradSum = 0f
        for (y in 1 until gridH - 1) {
            for (x in 1 until gridW - 1) {
                val dx = abs(lumaGrid[y * gridW + (x + 1)] - lumaGrid[y * gridW + (x - 1)])
                val dy = abs(lumaGrid[(y + 1) * gridW + x] - lumaGrid[(y - 1) * gridW + x])
                gradSum += (dx + dy) * 0.5f
            }
        }
        val sharpness = (gradSum / ((gridW - 2) * (gridH - 2))) * 1.45f
        val dHash = computeDHashFromGrid(lumaGrid, gridW, gridH)
        return sharpness to dHash
    }

    fun hammingDistance(hashA: Long, hashB: Long): Int {
        return java.lang.Long.bitCount(hashA xor hashB)
    }

    fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val boundedMax = maxDim.coerceIn(320, 1280)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= boundedMax && h <= boundedMax) return bitmap
        val ratio = min(boundedMax.toFloat() / w.toFloat(), boundedMax.toFloat() / h.toFloat())
        val targetW = (w * ratio).roundToInt().coerceAtLeast(1)
        val targetH = (h * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    fun imageProxyToBitmap(image: ImageProxy, maxDim: Int): Bitmap? {
        return try {
            val rawBitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val rotated = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rot = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rot !== rawBitmap) {
                    rawBitmap.recycle()
                }
                rot
            } else {
                rawBitmap
            }
            val scaled = scaleDownIfNeeded(rotated, maxDim)
            if (scaled !== rotated) {
                rotated.recycle()
            }
            scaled
        } catch (_: Exception) {
            null
        }
    }
}
