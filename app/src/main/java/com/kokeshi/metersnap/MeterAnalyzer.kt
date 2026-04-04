package com.kokeshi.metersnap

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kokeshi.metersnap.utils.HistoryManager
import com.kokeshi.metersnap.utils.ReadingValidator
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Result from the MeterAnalyzer containing detection info and validation status.
 */
data class AnalyzerResult(
    val reading: String,
    val confidence: Float,
    val allRects: List<Rect>,
    val acceptedRects: List<Rect>,
    val imageWidth: Int,
    val imageHeight: Int,
    val source: String,
    val hasFreshEvidence: Boolean,
    val validationResult: ReadingValidator.ValidationResult?,
    val mlKitDisplayText: String,
    val paddleDisplayText: String,
    val ocrDebug: String,
    val shouldSuggestManualInput: Boolean = false
)

private data class OcrCandidate(
    val reading: String,
    val confidence: Float,
    val slotRects: List<Rect>,
    val source: String
)

private data class DigitToken(
    val digit: Char,
    val rect: Rect,
    val source: String,
    val weight: Float
)

private data class OcrEngineResult(
    val candidate: OcrCandidate?,
    val rawText: String,
    val digits: String,
    val pass: String,
    val tokens: List<DigitToken> = emptyList()
)

private data class MlKitVariant(
    val bitmap: Bitmap,
    val tag: String
)

class MeterAnalyzer(
    private val context: Context,
    private var previousReading: Int = 0,
    private val onResult: (AnalyzerResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val historyManager = HistoryManager(context)
    private val failureTracker = ReadingValidator.FailureTracker()
    private val readingPredictor = ReadingValidator.ReadingPredictor()

    private val readingHistory = ArrayDeque<String>()
    private var lastAnalyzedTimestamp = 0L
    private val intervalMs = 110L
    private var lastCutoffRecoveryMs = 0L
    private val cutoffRecoveryCooldownMs = 260L
    @Volatile
    private var guideBoxLeftN = 0.13f
    @Volatile
    private var guideBoxTopN = 0.31f
    @Volatile
    private var guideBoxRightN = 0.87f
    @Volatile
    private var guideBoxBottomN = 0.49f
    private var lastLowerLimit = 0
    private var lastUpperLimit = ReadingValidator.MAX_READING_VALUE
    private var lastPredictiveReading = "-"

    private val dateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val debugFolderName = "MeterSnapDigits"
    private val debugRelativePath = "Pictures/$debugFolderName"
    var captureDebugNextFrame = false

    init {
        val seeded = mutableListOf<String>()
        if (previousReading > 0) seeded.add(String.format("%05d", previousReading))
        val latestHistory = historyManager.getAllHistory().firstOrNull()?.currentReading
        if (latestHistory != null) seeded.add(String.format("%05d", latestHistory))
        seeded.distinct().forEach {
            readingHistory.addLast(it)
            readingPredictor.recordObservation(it)
        }
    }

    fun setPreviousReading(reading: Int) {
        if (reading != previousReading) reset()
        previousReading = reading
        if (reading > 0) {
            val formatted = String.format("%05d", reading)
            readingHistory.addLast(formatted)
            readingPredictor.recordObservation(formatted)
        }
        while (readingHistory.size > 8) readingHistory.removeFirst()
    }

    fun setGuideBoxNormalized(left: Float, top: Float, right: Float, bottom: Float) {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeTop = top.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(safeLeft + 0.02f, 1f)
        val safeBottom = bottom.coerceIn(safeTop + 0.02f, 1f)

        guideBoxLeftN = safeLeft
        guideBoxTopN = safeTop
        guideBoxRightN = safeRight
        guideBoxBottomN = safeBottom
    }

    fun reset() {
        readingHistory.clear()
        failureTracker.reset()
        readingPredictor.reset()
        lastAnalyzedTimestamp = 0L
        lastLowerLimit = previousReading
        lastUpperLimit = ReadingValidator.MAX_READING_VALUE
        lastPredictiveReading = "-"
        if (previousReading > 0) {
            val formatted = String.format("%05d", previousReading)
            readingHistory.addLast(formatted)
            readingPredictor.recordObservation(formatted)
        }
    }

    fun shutdown() {
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Log.d("MeterAnalyzer", "Failed to close text recognizer", e)
        }
    }

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalyzedTimestamp < intervalMs) {
            image.close()
            return
        }
        lastAnalyzedTimestamp = now

        try {
            val frameBitmap = image.toBitmapCompat() ?: return
            val rotated = rotateIfNeeded(frameBitmap, image.imageInfo.rotationDegrees)

            val frameW = rotated.width
            val frameH = rotated.height
            val cropRect = buildMeterCropRect(frameW, frameH)
            val maskedFrame = maskOutsideGuideBox(rotated, cropRect)
            val cropped = Bitmap.createBitmap(maskedFrame, cropRect.x, cropRect.y, cropRect.width, cropRect.height)
            val focusedCrop = maskInsideGuideCrop(cropped, 0f)

            val mlPrimary = runMlKitPipeline(
                cropped = focusedCrop,
                cropRect = cropRect,
                pass = "ROI",
                includeThresholdVariant = false,
                allowSlotFallback = false
            )
            val mlThreshold = if (needsFallback(mlPrimary)) {
                runMlKitPipeline(
                    cropped = focusedCrop,
                    cropRect = cropRect,
                    pass = "THR",
                    includeThresholdVariant = true,
                    allowSlotFallback = false
                )
            } else {
                null
            }
            val mlCutoff = if ((mlThreshold ?: mlPrimary).candidate?.reading?.length ?: 0 < 5) {
                runCutoffRecovery(cropped, cropRect, now)
            } else {
                null
            }

            val finalMlEngineResult = listOfNotNull(mlPrimary, mlThreshold, mlCutoff)
                .reduce(::chooseBetterEngineResult)

            val geometryCandidate = buildAlignedFiveDigitCandidate(
                finalMlEngineResult.tokens
            )

            val mlCandidate = finalMlEngineResult.candidate
            val hasAnyOcrSignal =
                finalMlEngineResult.rawText.isNotBlank() ||
                finalMlEngineResult.tokens.isNotEmpty()

            val candidates = listOfNotNull(geometryCandidate, mlCandidate)

            val bestRawCandidate = candidates.maxByOrNull { (it.reading.length * 100f) + it.confidence }
            val hasPredictiveSignal = hasAnyOcrSignal || bestRawCandidate != null
            val canPredict = previousReading > 0 || readingHistory.isNotEmpty()

            val resolved = when {
                candidates.isNotEmpty() -> resolveFinalReading(candidates)
                hasPredictiveSignal || canPredict -> resolveFromPredictionOnly()
                else -> ResolvedReading("", 0f, emptyList(), "N", false)
            }
            val hasDecisionReading = resolved.reading.length == 5
            val hasStrictFiveDigitEvidence = hasDecisionReading && resolved.hasFreshEvidence
            val readingForValidation = if (hasStrictFiveDigitEvidence) resolved.reading else ""

            val validation = if (readingForValidation.isNotEmpty() && previousReading > 0) {
                ReadingValidator.validateReading(resolved.reading, previousReading)
            } else {
                null
            }

            val isPredictiveOnly = resolved.source == "X"
            val predictiveValidCap = 78f
            val predictiveWarnCap = 72f

            val finalReading = when {
                hasStrictFiveDigitEvidence &&
                    validation != null &&
                    validation.isValid &&
                    validation.cleanedReading != resolved.reading -> {
                    validation.cleanedReading
                }
                hasStrictFiveDigitEvidence -> resolved.reading
                !hasAnyOcrSignal -> ""
                else -> bestRawCandidate?.reading ?: ""
            }

            val finalConfidence = when {
                hasStrictFiveDigitEvidence && finalReading.isEmpty() -> 0f
                !hasStrictFiveDigitEvidence -> when (finalReading.length) {
                    4 -> minOf(78f, (bestRawCandidate?.confidence ?: 0f) + 5f)
                    3 -> minOf(65f, bestRawCandidate?.confidence ?: 0f)
                    in 1..2 -> minOf(50f, bestRawCandidate?.confidence ?: 0f)
                    else -> 0f
                }
                validation == null -> {
                    if (isPredictiveOnly) minOf(predictiveValidCap, maxOf(62f, resolved.confidence)) else resolved.confidence
                }
                validation.isValid && validation.errorCode == ReadingValidator.ErrorCode.VALID -> {
                    if (isPredictiveOnly) minOf(predictiveValidCap, maxOf(66f, resolved.confidence)) else maxOf(90f, resolved.confidence)
                }
                validation.isValid -> {
                    if (isPredictiveOnly) minOf(predictiveWarnCap, maxOf(60f, resolved.confidence)) else maxOf(80f, resolved.confidence)
                }
                validation.errorCode == ReadingValidator.ErrorCode.READING_TOO_LOW -> minOf(35f, resolved.confidence)
                else -> minOf(60f, resolved.confidence)
            }

            if (validation != null) {
                if (validation.isValid) {
                    failureTracker.recordSuccess()
                } else {
                    failureTracker.recordFailure(validation.errorCode)
                }
            } else if (hasStrictFiveDigitEvidence) {
                failureTracker.recordSuccess()
            } else if (finalReading.length < 4 && !hasAnyOcrSignal) {
                failureTracker.recordFailure(ReadingValidator.ErrorCode.TOO_FEW_DIGITS)
            }

            if (hasStrictFiveDigitEvidence && finalReading.length == 5 && (validation == null || validation.isValid)) {
                readingHistory.addLast(finalReading)
                while (readingHistory.size > 8) readingHistory.removeFirst()
                readingPredictor.recordObservation(finalReading)
            }

            val finalRects = if (hasStrictFiveDigitEvidence) {
                resolved.rects
            } else {
                bestRawCandidate?.slotRects ?: emptyList()
            }

            val finalSource = if (hasStrictFiveDigitEvidence) {
                resolved.source
            } else {
                bestRawCandidate?.source ?: "N"
            }

            val mlKitDisplayText = buildEngineDisplayText(finalMlEngineResult)
            val yoloDisplayText = "OFF"

            val debugText = buildLiveDebugText(
                mlEngineResult = finalMlEngineResult,
                finalReading = finalReading,
                finalConfidence = finalConfidence,
                finalSource = finalSource,
                hasStrictFiveDigitEvidence = hasStrictFiveDigitEvidence
            )

            val result = AnalyzerResult(
                reading = finalReading,
                confidence = finalConfidence,
                allRects = finalRects,
                acceptedRects = finalRects,
                imageWidth = frameW,
                imageHeight = frameH,
                source = finalSource,
                hasFreshEvidence = resolved.hasFreshEvidence,
                validationResult = validation,
                mlKitDisplayText = mlKitDisplayText,
                paddleDisplayText = yoloDisplayText,
                ocrDebug = debugText,
                shouldSuggestManualInput = !resolved.hasFreshEvidence && !hasAnyOcrSignal && failureTracker.shouldSuggestManualInput()
            )
            onResult(result)

            if (captureDebugNextFrame) {
                saveDebugImage(cropped, "debug_crop_${dateFormatter.format(Date())}.png")
                captureDebugNextFrame = false
            }
        } catch (e: Exception) {
            Log.e("MeterAnalyzer", "Error analyzing image", e)
        } finally {
            image.close()
        }
    }

    private fun buildMeterCropRect(width: Int, height: Int): Rect {
        val leftN = guideBoxLeftN.coerceIn(0f, 1f)
        val topN = guideBoxTopN.coerceIn(0f, 1f)
        val rightN = guideBoxRightN.coerceIn((leftN + 0.02f).coerceAtMost(1f), 1f)
        val bottomN = guideBoxBottomN.coerceIn((topN + 0.02f).coerceAtMost(1f), 1f)

        var left = (leftN * width).roundToInt()
        var top = (topN * height).roundToInt()
        var right = (rightN * width).roundToInt()
        var bottom = (bottomN * height).roundToInt()

        val baseW = (right - left).coerceAtLeast(1)
        val baseH = (bottom - top).coerceAtLeast(1)
        val insetX = (baseW * 0.04f).roundToInt().coerceAtLeast(1)
        val insetY = (baseH * 0.06f).roundToInt().coerceAtLeast(1)

        left = (left + insetX).coerceAtMost(width - 1)
        top = (top + insetY).coerceAtMost(height - 1)
        right = (right - insetX).coerceIn(left + 1, width)
        bottom = (bottom - insetY).coerceIn(top + 1, height)

        val safeW = (right - left).coerceAtLeast(1)
        val safeH = (bottom - top).coerceAtLeast(1)
        return Rect(left, top, safeW, safeH)
    }

    private fun maskOutsideGuideBox(frame: Bitmap, cropRect: Rect): Bitmap {
        val out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val src = AndroidRect(
            cropRect.x,
            cropRect.y,
            cropRect.x + cropRect.width,
            cropRect.y + cropRect.height
        )
        canvas.drawBitmap(frame, src, src, null)
        return out
    }

    private fun maskInsideGuideCrop(cropped: Bitmap, verticalShiftN: Float): Bitmap {
        val out = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val insetX = (cropped.width * 0.04f).roundToInt().coerceAtLeast(0)
        val baseTop = (cropped.height * 0.16f).roundToInt()
        val baseBottom = (cropped.height * 0.94f).roundToInt()
        val shiftPx = (cropped.height * verticalShiftN).roundToInt()

        val bandTop = (baseTop + shiftPx).coerceIn(0, cropped.height - 1)
        var bandBottom = (baseBottom + shiftPx).coerceIn(bandTop + 1, cropped.height)
        if ((bandBottom - bandTop) < (cropped.height * 0.45f).roundToInt()) {
            bandBottom = (bandTop + (cropped.height * 0.45f).roundToInt()).coerceAtMost(cropped.height)
        }

        val left = insetX
        val right = (cropped.width - insetX).coerceAtLeast(left + 1)
        val src = AndroidRect(left, bandTop, right, bandBottom)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(cropped, src, src, paint)
        return out
    }

    private fun runMlKitPipeline(
        cropped: Bitmap,
        cropRect: Rect,
        pass: String,
        includeThresholdVariant: Boolean,
        allowSlotFallback: Boolean
    ): OcrEngineResult {
        return runMlKit(
            cropped = cropped,
            cropRect = cropRect,
            pass = pass,
            includeThresholdVariant = includeThresholdVariant,
            allowSlotFallback = allowSlotFallback
        )
    }

    private fun runCutoffRecovery(cropped: Bitmap, cropRect: Rect, now: Long): OcrEngineResult? {
        if (now - lastCutoffRecoveryMs < cutoffRecoveryCooldownMs) return null
        lastCutoffRecoveryMs = now

        val shifts = floatArrayOf(-0.10f, 0.10f, -0.18f, 0.18f)
        var best: OcrEngineResult? = null

        for (shift in shifts) {
            val shifted = maskInsideGuideCrop(cropped, shift)
            val result = runMlKitPipeline(
                cropped = shifted,
                cropRect = cropRect,
                pass = if (shift < 0f) "CUT-U" else "CUT-D",
                includeThresholdVariant = false,
                allowSlotFallback = false
            )

            if (best == null || scoreEngine(result.candidate) > scoreEngine(best?.candidate)) {
                best = result
            }

            shifted.recycle()
        }

        return best
    }

    private fun runMlKit(
        cropped: Bitmap,
        cropRect: Rect,
        pass: String,
        includeThresholdVariant: Boolean,
        allowSlotFallback: Boolean
    ): OcrEngineResult {
        return try {
            val variants = buildMlKitVariants(cropped, includeThresholdVariant)
            val aggregateTokens = ArrayList<DigitToken>()

            var debugRaw = ""
            var bestVariantTag = "RAW"
            var bestDigits = ""
            var bestBox: AndroidRect? = null
            var bestScore = Float.NEGATIVE_INFINITY

            for (variant in variants) {
                val input = InputImage.fromBitmap(variant.bitmap, 0)
                val result = Tasks.await(textRecognizer.process(input))
                val rawText = result.text ?: ""
                if (debugRaw.isBlank() && rawText.isNotBlank()) {
                    debugRaw = "${variant.tag}:${compactForDebug(rawText)}"
                }

                val scaleX = cropped.width.toFloat() / variant.bitmap.width.toFloat().coerceAtLeast(1f)
                val scaleY = cropped.height.toFloat() / variant.bitmap.height.toFloat().coerceAtLeast(1f)

                val candidates = mutableListOf<Pair<String, AndroidRect?>>()
                if (rawText.isNotBlank()) {
                    candidates.add(rawText to null)
                }
                for (block in result.textBlocks) {
                    candidates.add(block.text to block.boundingBox)
                    for (line in block.lines) {
                        candidates.add(line.text to (line.boundingBox ?: block.boundingBox))
                        for (element in line.elements) {
                            candidates.add(element.text to (element.boundingBox ?: line.boundingBox ?: block.boundingBox))
                        }
                    }
                }

                for ((text, box) in candidates) {
                    if (box != null) {
                        val mappedBox = scaleRect(box, scaleX, scaleY, cropped.width, cropped.height)
                        aggregateTokens.addAll(
                            extractDigitTokensFromBox(
                                text = text,
                                mappedBox = mappedBox,
                                cropRect = cropRect,
                                source = "M",
                                weight = 1.0f
                            )
                        )
                    }

                    val digits = extractDigitCandidate(text) ?: continue
                    val mappedBox = box?.let { scaleRect(it, scaleX, scaleY, cropped.width, cropped.height) }
                    val score = candidateScore(digits.length, mappedBox != null)
                    if (score > bestScore) {
                        bestScore = score
                        bestDigits = digits
                        bestBox = mappedBox
                        bestVariantTag = variant.tag
                    }
                }
            }

            if (bestDigits.isEmpty() && allowSlotFallback) {
                val slotFallback = runMlKitSlotFallback(cropped, cropRect, pass)
                if (slotFallback != null) {
                    return slotFallback
                }
            }

            if (bestDigits.isEmpty()) {
                return OcrEngineResult(
                    candidate = null,
                    rawText = if (debugRaw.isBlank()) "" else debugRaw,
                    digits = "",
                    pass = pass,
                    tokens = aggregateTokens
                )
            }

            val rects = splitIntoSlotRects(bestBox, bestDigits.length, cropped.width, cropped.height, cropRect)
            val confidence = when (bestDigits.length) {
                5 -> 95f
                4 -> 80f
                else -> 65f
            }

            OcrEngineResult(
                candidate = OcrCandidate(bestDigits, confidence, rects, "M"),
                rawText = if (debugRaw.isBlank()) bestVariantTag else debugRaw,
                digits = bestDigits,
                pass = pass,
                tokens = aggregateTokens
            )
        } catch (e: Exception) {
            Log.d("MeterAnalyzer", "ML Kit candidate failed", e)
            OcrEngineResult(
                candidate = null,
                rawText = "ERR",
                digits = "",
                pass = pass
            )
        }
    }

    private fun runMlKitSlotFallback(cropped: Bitmap, cropRect: Rect, pass: String): OcrEngineResult? {
        if (cropped.width <= 0 || cropped.height <= 0) return null

        val stripLeft = (cropped.width * 0.14f).toInt().coerceAtLeast(0)
        val stripTop = (cropped.height * 0.38f).toInt().coerceAtLeast(0)
        val stripWidth = (cropped.width * 0.72f).toInt().coerceAtLeast(1)
            .coerceAtMost(cropped.width - stripLeft)
        val stripHeight = (cropped.height * 0.48f).toInt().coerceAtLeast(1)
            .coerceAtMost(cropped.height - stripTop)

        if (stripWidth <= 5 || stripHeight <= 5) return null

        val slotW = (stripWidth / 5).coerceAtLeast(1)
        val digitsBySlot = Array<Char?>(5) { null }

        for (i in 0 until 5) {
            val localX = stripLeft + i * slotW
            val localW = if (i == 4) (stripLeft + stripWidth) - localX else slotW
            if (localW <= 0) continue

            val slotBitmap = Bitmap.createBitmap(cropped, localX, stripTop, localW, stripHeight)
            val variants = buildMlKitVariants(slotBitmap, includeThresholdVariant = false)

            for (variant in variants) {
                try {
                    val input = InputImage.fromBitmap(variant.bitmap, 0)
                    val result = Tasks.await(textRecognizer.process(input))
                    val digit = extractSingleDigitCandidate(result.text ?: "")
                    if (digit != null) {
                        digitsBySlot[i] = digit
                        break
                    }
                } catch (e: Exception) {
                    Log.d("MeterAnalyzer", "Slot OCR failed", e)
                }
            }

            slotBitmap.recycle()
        }

        val known = digitsBySlot.filterNotNull()
        if (known.size < 4) return null

        val reading = known.joinToString(separator = "")
        val slotRects = ArrayList<Rect>(5)
        for (i in 0 until 5) {
            val localX = stripLeft + i * slotW
            val localW = if (i == 4) (stripLeft + stripWidth) - localX else slotW
            if (localW <= 0) continue
            slotRects.add(Rect(cropRect.x + localX, cropRect.y + stripTop, localW, stripHeight))
        }

        val confidence = if (known.size == 5) 92f else 75f
        val raw = "SLOT:${digitsBySlot.joinToString(separator = "") { it?.toString() ?: "_" }}"
        val tokens = buildTokensFromSlotData(digitsBySlot, slotRects, "M", 1.3f)

        return OcrEngineResult(
            candidate = OcrCandidate(reading, confidence, slotRects, "M"),
            rawText = raw,
            digits = reading,
            pass = pass,
            tokens = tokens
        )
    }

    private fun buildAlignedFiveDigitCandidate(tokens: List<DigitToken>): OcrCandidate? {
        val digitTokens = tokens
            .filter { it.digit.isDigit() && it.rect.width > 0 && it.rect.height > 0 }
            .sortedBy { centerY(it.rect) }

        if (digitTokens.size < 5) return null

        val yGroups = mutableListOf<MutableList<DigitToken>>()
        for (token in digitTokens) {
            val y = centerY(token.rect)
            var bestGroup: MutableList<DigitToken>? = null
            var bestDelta = Float.MAX_VALUE

            for (group in yGroups) {
                val meanY = group.map { centerY(it.rect) }.average().toFloat()
                val avgH = group.map { it.rect.height.toFloat() }.average().toFloat().coerceAtLeast(1f)
                val tolerance = maxOf(8f, avgH * 0.55f)
                val delta = abs(y - meanY)
                if (delta <= tolerance && delta < bestDelta) {
                    bestGroup = group
                    bestDelta = delta
                }
            }

            if (bestGroup == null) {
                yGroups.add(mutableListOf(token))
            } else {
                bestGroup.add(token)
            }
        }

        var best: OcrCandidate? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (group in yGroups) {
            val dedup = dedupeTokensByX(group)
            if (dedup.size < 5) continue

            for (start in 0..(dedup.size - 5)) {
                val window = dedup.subList(start, start + 5)
                val xCenters = window.map { centerX(it.rect) }
                var monotonic = true
                for (i in 1 until xCenters.size) {
                    if (xCenters[i] <= xCenters[i - 1]) {
                        monotonic = false
                        break
                    }
                }
                if (!monotonic) continue

                val gaps = (1 until xCenters.size).map { xCenters[it] - xCenters[it - 1] }
                if (gaps.any { it <= 1f }) continue

                val avgGap = gaps.average().toFloat().coerceAtLeast(1f)
                val variance = gaps.map { gap ->
                    val d = gap - avgGap
                    d * d
                }.average().toFloat()
                val normVar = variance / (avgGap * avgGap + 1f)
                val gapBalance = (1f / (1f + normVar)).coerceIn(0f, 1f)

                val yCenters = window.map { centerY(it.rect) }
                val ySpread = (yCenters.maxOrNull() ?: 0f) - (yCenters.minOrNull() ?: 0f)
                val avgHeight = window.map { it.rect.height.toFloat() }.average().toFloat().coerceAtLeast(1f)
                val lineTightness = (1f - (ySpread / (avgHeight * 1.1f))).coerceIn(0f, 1f)

                val avgWeight = window.map { it.weight }.average().toFloat()
                val score = gapBalance * 220f + lineTightness * 130f + avgWeight * 25f

                if (score > bestScore) {
                    val reading = window.joinToString(separator = "") { it.digit.toString() }
                    val rects = window.map { it.rect }
                    val confidence = (60f + gapBalance * 25f + lineTightness * 10f + avgWeight * 4f)
                        .coerceIn(55f, 97f)

                    best = OcrCandidate(
                        reading = reading,
                        confidence = confidence,
                        slotRects = rects,
                        source = "G"
                    )
                    bestScore = score
                }
            }
        }

        return best
    }

    private fun dedupeTokensByX(tokens: List<DigitToken>): List<DigitToken> {
        if (tokens.isEmpty()) return emptyList()

        val sorted = tokens.sortedBy { centerX(it.rect) }
        val out = mutableListOf<DigitToken>()

        for (token in sorted) {
            if (out.isEmpty()) {
                out.add(token)
                continue
            }

            val last = out.last()
            val xDelta = abs(centerX(token.rect) - centerX(last.rect))
            val widthTol = minOf(last.rect.width, token.rect.width).toFloat() * 0.55f
            val tolerance = maxOf(4f, widthTol)

            if (xDelta <= tolerance) {
                val lastScore = last.weight + (last.rect.width * last.rect.height) * 0.001f
                val tokenScore = token.weight + (token.rect.width * token.rect.height) * 0.001f
                if (tokenScore > lastScore) {
                    out[out.lastIndex] = token
                }
            } else {
                out.add(token)
            }
        }

        return out
    }

    private fun extractDigitTokensFromBox(
        text: String,
        mappedBox: AndroidRect,
        cropRect: Rect,
        source: String,
        weight: Float
    ): List<DigitToken> {
        val digits = normalizeDigitsLoose(text)
        if (digits.isEmpty()) return emptyList()

        val left = mappedBox.left
        val top = mappedBox.top
        val right = mappedBox.right.coerceAtLeast(left + digits.length)
        val bottom = mappedBox.bottom.coerceAtLeast(top + 1)

        val width = (right - left).coerceAtLeast(digits.length)
        val height = (bottom - top).coerceAtLeast(1)
        val slotW = (width / digits.length).coerceAtLeast(1)

        val tokens = ArrayList<DigitToken>(digits.length)
        for (i in digits.indices) {
            val localX = left + i * slotW
            val localW = if (i == digits.lastIndex) right - localX else slotW
            if (localW <= 0) continue

            val rect = Rect(cropRect.x + localX, cropRect.y + top, localW, height)
            tokens.add(DigitToken(digits[i], rect, source, weight))
        }

        return tokens
    }

    private fun buildTokensFromReading(
        reading: String,
        rects: List<Rect>,
        source: String,
        weight: Float
    ): List<DigitToken> {
        val digits = normalizeDigitsLoose(reading)
        if (digits.isEmpty() || rects.isEmpty()) return emptyList()

        val tokens = ArrayList<DigitToken>(digits.length)
        for (i in digits.indices) {
            val rect = if (i < rects.size) rects[i] else rects.last()
            tokens.add(DigitToken(digits[i], rect, source, weight))
        }
        return tokens
    }

    private fun buildTokensFromSlotData(
        digitsBySlot: Array<Char?>,
        slotRects: List<Rect>,
        source: String,
        weight: Float
    ): List<DigitToken> {
        val tokens = ArrayList<DigitToken>()
        val limit = minOf(digitsBySlot.size, slotRects.size)

        for (i in 0 until limit) {
            val digit = digitsBySlot[i] ?: continue
            if (!digit.isDigit()) continue
            tokens.add(DigitToken(digit, slotRects[i], source, weight))
        }

        return tokens
    }

    private fun buildTokenRectsForReading(reading: String, cropRect: Rect): List<Rect> {
        val digits = normalizeDigitsLoose(reading)
        if (digits.isEmpty()) return emptyList()

        val slots = digits.length
        val slotW = (cropRect.width / slots).coerceAtLeast(1)
        val rects = ArrayList<Rect>(slots)

        for (i in 0 until slots) {
            val x = cropRect.x + i * slotW
            val w = if (i == slots - 1) cropRect.width - (slotW * i) else slotW
            rects.add(Rect(x, cropRect.y, w, cropRect.height))
        }

        return rects
    }

    private fun normalizeDigitsLoose(text: String): String {
        if (text.isBlank()) return ""
        return text.uppercase(Locale.US)
            .replace('O', '0')
            .replace('Q', '0')
            .replace('D', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('S', '5')
            .replace('B', '8')
            .filter { it.isDigit() }
    }

    private fun centerX(rect: Rect): Float = rect.x + (rect.width / 2f)

    private fun centerY(rect: Rect): Float = rect.y + (rect.height / 2f)

    private fun buildLiveDebugText(
        mlEngineResult: OcrEngineResult,
        finalReading: String,
        finalConfidence: Float,
        finalSource: String,
        hasStrictFiveDigitEvidence: Boolean
    ): String {
        val pick = compactForDebug(finalReading)
        val fiveState = if (hasStrictFiveDigitEvidence) "Y" else "N"
        val mlPass = mlEngineResult.pass
        val tokenCount = mlEngineResult.tokens.size
        val lb = String.format("%05d", lastLowerLimit.coerceAtLeast(0))
        val ub = String.format("%05d", lastUpperLimit.coerceAtLeast(lastLowerLimit + 1))
        val pred = compactForDebug(lastPredictiveReading)

        return "DBG PAR:M ML:$mlPass TK:$tokenCount LB:$lb UB:$ub PR:$pred PICK:$pick ${finalConfidence.toInt()}% SRC:$finalSource 5D:$fiveState"
    }

    private fun needsFallback(result: OcrEngineResult): Boolean {
        val candidate = result.candidate ?: return true
        return candidate.reading.length < 4
    }

    private fun chooseBetterEngineResult(primary: OcrEngineResult, fallback: OcrEngineResult): OcrEngineResult {
        val primaryScore = scoreEngine(primary.candidate)
        val fallbackScore = scoreEngine(fallback.candidate)

        return when {
            fallbackScore > primaryScore -> fallback
            primaryScore > fallbackScore -> primary
            fallback.digits.length > primary.digits.length -> fallback
            primary.rawText.isBlank() && fallback.rawText.isNotBlank() -> fallback
            else -> primary
        }
    }

    private fun scoreEngine(candidate: OcrCandidate?): Float {
        if (candidate == null) return -1f
        return (candidate.reading.length * 100f) + candidate.confidence
    }

    private fun buildMlKitVariants(source: Bitmap, includeThresholdVariant: Boolean): List<MlKitVariant> {
        val variants = mutableListOf<MlKitVariant>()
        variants.add(MlKitVariant(source, "RAW"))

        if (includeThresholdVariant) {
            val threshold = buildThresholdVariant(source)
            if (threshold != null) {
                variants.add(MlKitVariant(threshold, "THR"))
            }
        }

        return variants
    }

    private fun buildThresholdVariant(source: Bitmap): Bitmap? {
        val src = Mat()
        val gray = Mat()
        val blur = Mat()
        val thr = Mat()
        val rgba = Mat()
        return try {
            Utils.bitmapToMat(source, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)
            Imgproc.adaptiveThreshold(
                blur,
                thr,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                35,
                7.0
            )
            Imgproc.cvtColor(thr, rgba, Imgproc.COLOR_GRAY2RGBA)

            val out = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, out)
            out
        } catch (e: Exception) {
            Log.d("MeterAnalyzer", "Threshold variant failed", e)
            null
        } finally {
            src.release()
            gray.release()
            blur.release()
            thr.release()
            rgba.release()
        }
    }

    private fun scaleRect(box: AndroidRect, scaleX: Float, scaleY: Float, maxW: Int, maxH: Int): AndroidRect {
        val left = (box.left * scaleX).toInt().coerceIn(0, maxW - 1)
        val top = (box.top * scaleY).toInt().coerceIn(0, maxH - 1)
        val right = (box.right * scaleX).toInt().coerceIn(left + 1, maxW)
        val bottom = (box.bottom * scaleY).toInt().coerceIn(top + 1, maxH)
        return AndroidRect(left, top, right, bottom)
    }

    private fun extractSingleDigitCandidate(text: String): Char? {
        if (text.isBlank()) return null

        val normalized = text.uppercase(Locale.US)
            .replace('O', '0')
            .replace('Q', '0')
            .replace('D', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('S', '5')
            .replace('B', '8')

        return normalized.firstOrNull { it.isDigit() }
    }

    private fun buildEngineDisplayText(result: OcrEngineResult): String {
        return when {
            result.digits.isNotBlank() -> compactForDebug(result.digits)
            result.rawText == "NOT_INIT" -> "NOT_INIT"
            result.rawText == "ERR" -> "ERR"
            result.rawText.isBlank() -> "-"
            else -> compactForDebug(result.rawText)
        }
    }

    private fun compactForDebug(value: String): String {
        val compact = value.replace("\\s+".toRegex(), "")
        if (compact.isBlank()) return "-"
        return if (compact.length <= 18) compact else compact.take(18)
    }

    private fun resolveFinalReading(candidates: List<OcrCandidate>): ResolvedReading {
        if (candidates.isEmpty()) {
            return resolveFromPredictionOnly()
        }

        val lowerLimit = computeLowerLimit()
        val upperLimit = computeUpperLimit(lowerLimit)
        val strongestObservedDigits = candidates.maxOfOrNull { normalizeDigitsLoose(it.reading).length } ?: 0

        val completed = candidates.mapNotNull { candidate ->
            val completedReading = completeReading(candidate.reading, lowerLimit, upperLimit)
            val value = completedReading.toIntOrNull() ?: return@mapNotNull null
            if (value < lowerLimit || value > upperLimit) return@mapNotNull null
            candidate.copy(reading = completedReading)
        }

        if (completed.isEmpty()) {
            val predicted = predictReadingWithinBounds(lowerLimit, upperLimit, lowerLimit)
            return if (predicted.isBlank()) {
                ResolvedReading("", 0f, emptyList(), "N", false)
            } else {
                ResolvedReading(predicted, 62f, emptyList(), "X", strongestObservedDigits >= 4)
            }
        }

        val slotVotes = Array(5) { mutableMapOf<Char, Float>() }
        completed.forEach { candidate ->
            val padded = candidate.reading.padStart(5, '0').takeLast(5)
            padded.forEachIndexed { index, ch ->
                if (ch in '0'..'9') {
                    slotVotes[index][ch] = (slotVotes[index][ch] ?: 0f) + candidate.confidence
                }
            }
        }

        // Add a light history prior to stabilize partially unreadable slots.
        readingHistory.lastOrNull()?.padStart(5, '0')?.takeLast(5)?.forEachIndexed { index, ch ->
            slotVotes[index][ch] = (slotVotes[index][ch] ?: 0f) + 20f
        }

        val winner = StringBuilder()
        val slotConf = mutableListOf<Float>()
        for (i in 0 until 5) {
            val total = slotVotes[i].values.sum().coerceAtLeast(1f)
            val best = slotVotes[i].maxByOrNull { it.value }
            winner.append(best?.key ?: '0')
            slotConf.add(((best?.value ?: 0f) / total) * 100f)
        }

        var resolvedReading = winner.toString()
        resolvedReading = enforceRolloverAwareChoice(
            primary = resolvedReading,
            alternatives = completed.map { it.reading },
            lowerLimit = lowerLimit,
            upperLimit = upperLimit
        )

        val resolvedValue = resolvedReading.toIntOrNull()
        if (resolvedValue == null || resolvedValue < lowerLimit || resolvedValue > upperLimit) {
            resolvedReading = predictReadingWithinBounds(
                lowerLimit = lowerLimit,
                upperLimit = upperLimit,
                fallbackValue = lowerLimit
            )
        }

        val rangeWidth = (upperLimit - lowerLimit).coerceAtLeast(1)
        val rangePenalty = (rangeWidth / 25f).coerceAtMost(20f)
        val avgConf = (slotConf.average().toFloat() - rangePenalty).coerceIn(45f, 97f)

        val source = when {
            completed.size >= 2 && completed.map { it.reading }.distinct().size == 1 -> "V"
            completed.any { it.source == "Y" } && completed.any { it.source == "M" } -> "V"
            completed.any { it.source == "G" } && completed.any { it.source == "Y" || it.source == "M" } -> "V"
            completed.any { it.source == "Y" } -> "Y"
            completed.any { it.source == "G" } -> "G"
            else -> "M"
        }

        val rects = completed.maxByOrNull { it.confidence }?.slotRects ?: emptyList()
        return ResolvedReading(resolvedReading, avgConf, rects, source, strongestObservedDigits >= 4)
    }

    private fun resolveFromPredictionOnly(): ResolvedReading {
        val lowerLimit = computeLowerLimit()
        val upperLimit = computeUpperLimit(lowerLimit)
        val predicted = predictReadingWithinBounds(lowerLimit, upperLimit, lowerLimit)
        return if (predicted.isBlank()) {
            ResolvedReading("", 0f, emptyList(), "N", false)
        } else {
            ResolvedReading(predicted, 55f, emptyList(), "X", false)
        }
    }

    private fun computeLowerLimit(): Int {
        if (previousReading > 0) {
            return previousReading.coerceIn(0, ReadingValidator.MAX_READING_VALUE)
        }
        val historyFloor = readingHistory.lastOrNull()?.toIntOrNull() ?: 0
        return historyFloor.coerceIn(0, ReadingValidator.MAX_READING_VALUE)
    }

    private fun computeUpperLimit(lowerLimit: Int): Int {
        val computedUpper = computePredictedUpper(lowerLimit)

        lastLowerLimit = lowerLimit
        lastUpperLimit = computedUpper

        return computedUpper
    }

    private fun computePredictedUpper(lowerLimit: Int): Int {
        val linearProjection = computeLinearProjection(lowerLimit)
        val prediction = readingPredictor.predict(previousReading)
        val predictiveValue = prediction.predictedReading.toIntOrNull()
        val lastValue = readingHistory.lastOrNull()?.toIntOrNull() ?: lowerLimit
        val avgStep = estimateAverageStep()
        val stepProjection = (lastValue + avgStep).coerceAtLeast(lowerLimit)

        val hardUpper = (lowerLimit + ReadingValidator.MAX_MONTHLY_USAGE)
            .coerceAtMost(ReadingValidator.MAX_READING_VALUE)

        val candidates = mutableListOf<Int>()
        if (predictiveValue != null) candidates.add(predictiveValue)
        candidates.add(linearProjection)
        candidates.add(stepProjection)
        candidates.add(lowerLimit)

        val boundedCandidates = candidates
            .map { it.coerceIn(lowerLimit, hardUpper) }
            .sorted()

        val medianPrediction = boundedCandidates[boundedCandidates.size / 2]
        val computedUpper = medianPrediction.coerceIn(lowerLimit, hardUpper)

        lastPredictiveReading = if (prediction.predictedReading.isBlank()) {
            String.format("%05d", computedUpper)
        } else {
            prediction.predictedReading
        }

        return computedUpper
    }

    private fun computeLinearProjection(lowerLimit: Int): Int {
        val values = readingHistory.toList().mapNotNull { it.toIntOrNull() }.takeLast(8)
        if (values.isEmpty()) return lowerLimit
        if (values.size == 1) return maxOf(lowerLimit, values.last())

        val n = values.size.toFloat()
        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumX2 = 0f

        values.forEachIndexed { index, value ->
            val x = index.toFloat()
            val y = value.toFloat()
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }

        val denominator = (n * sumX2) - (sumX * sumX)
        val slope = if (abs(denominator) < 1e-5f) 0f else ((n * sumXY) - (sumX * sumY)) / denominator
        val intercept = (sumY - (slope * sumX)) / n
        val projected = (intercept + (slope * values.size.toFloat())).roundToInt()
        return projected.coerceAtLeast(lowerLimit)
    }

    private fun estimateAverageStep(): Int {
        val values = readingHistory.toList().mapNotNull { it.toIntOrNull() }.takeLast(8)
        if (values.size < 2) return 0

        val deltas = values.zipWithNext()
            .map { (previous, current) -> (current - previous).coerceAtLeast(0) }

        if (deltas.isEmpty()) return 0
        return deltas.average().roundToInt()
    }

    private fun predictReadingWithinBounds(lowerLimit: Int, upperLimit: Int, fallbackValue: Int): String {
        val prediction = readingPredictor.predict(previousReading)
        val ranked = mutableListOf<Pair<Int, Float>>()

        prediction.predictedReading.toIntOrNull()?.let { ranked.add(it to (prediction.confidence + 20f)) }
        prediction.alternativeCandidates.forEach { (candidate, score) ->
            candidate.toIntOrNull()?.let { ranked.add(it to score) }
        }
        readingHistory.lastOrNull()?.toIntOrNull()?.let { ranked.add(it to 30f) }
        ranked.add(fallbackValue to 10f)

        val boundedRanked = ranked.map { (value, score) ->
            value.coerceIn(lowerLimit, upperLimit) to score
        }

        val chosen = boundedRanked.maxByOrNull { it.second }?.first ?: lowerLimit
        return String.format("%05d", chosen.coerceIn(lowerLimit, upperLimit))
    }

    private fun completeReading(raw: String, lowerLimit: Int, upperLimit: Int): String {
        val digitsOnly = raw.filter { it.isDigit() }
        val predicted = predictReadingWithinBounds(lowerLimit, upperLimit, lowerLimit)
        if (digitsOnly.isEmpty()) return predicted
        if (digitsOnly.length >= 5) {
            val direct = digitsOnly.takeLast(5).toIntOrNull() ?: lowerLimit
            return String.format("%05d", direct.coerceIn(lowerLimit, upperLimit))
        }

        // For partial reads, fill unknown slots from prediction first (fast path).
        val patterns = mutableListOf<String>()
        if (digitsOnly.length == 4) {
            for (i in 0..4) {
                val p = StringBuilder(digitsOnly)
                p.insert(i, '?')
                patterns.add(p.toString())
            }
        } else {
            patterns.add(digitsOnly.padStart(5, '?').takeLast(5))
        }

        val predictionDigits = predicted.toCharArray()
        val predictedValue = predicted.toIntOrNull() ?: lowerLimit
        var bestReading = predicted
        var bestScore = Float.NEGATIVE_INFINITY

        for (pattern in patterns) {
            val candidateChars = pattern.toCharArray()
            candidateChars.forEachIndexed { idx, ch ->
                if (ch == '?') {
                    candidateChars[idx] = predictionDigits[idx]
                }
            }

            val candidate = String(candidateChars)
            val candidateInt = candidate.toIntOrNull() ?: continue
            if (candidateInt < lowerLimit || candidateInt > upperLimit) continue

            val validation = ReadingValidator.validateReading(candidate, previousReading)
            if (!validation.isValid && validation.errorCode != ReadingValidator.ErrorCode.HIGH_USAGE_WARNING) continue

            var score = 0f
            score -= abs(candidateInt - predictedValue).toFloat() * 0.08f
            score -= rolloverPenalty(String.format("%05d", lowerLimit), candidate)

            readingHistory.lastOrNull()?.let { last ->
                if (candidate.take(4) == last.take(4)) score += 10f
            }

            if (score > bestScore) {
                bestScore = score
                bestReading = candidate
            }
        }

        val bounded = bestReading.toIntOrNull()?.coerceIn(lowerLimit, upperLimit) ?: (predicted.toIntOrNull() ?: lowerLimit)
        return String.format("%05d", bounded)
    }

    private fun enforceRolloverAwareChoice(
        primary: String,
        alternatives: List<String>,
        lowerLimit: Int,
        upperLimit: Int
    ): String {
        val all = (listOf(primary) + alternatives).distinct()
        val lowerReading = String.format("%05d", lowerLimit)

        return all
            .filter {
                val value = it.toIntOrNull() ?: return@filter false
                it.length == 5 && value in lowerLimit..upperLimit
            }
            .minByOrNull {
                val value = it.toIntOrNull() ?: Int.MAX_VALUE
                val delta = value - lowerLimit
                val rollover = rolloverPenalty(lowerReading, it)
                delta + rollover.toInt()
            } ?: primary
    }

    private fun rolloverPenalty(previous: String, current: String): Float {
        var penalty = 0f
        for (i in 0..3) {
            val prevDigit = previous[i]
            val currDigit = current[i]
            if (currDigit > prevDigit) {
                val prevTail = previous.substring(i + 1)
                val currTail = current.substring(i + 1)
                val tailLooksLikeRollover = prevTail.all { it >= '7' } && currTail.all { it <= '2' }
                if (!tailLooksLikeRollover) penalty += 15f
            }
        }
        return penalty
    }

    private fun splitIntoSlotRects(
        boundingBox: AndroidRect?,
        digitCount: Int,
        cropW: Int,
        cropH: Int,
        cropRect: Rect
    ): List<Rect> {
        val box = boundingBox ?: AndroidRect(0, 0, cropW, cropH)
        val safeLeft = box.left.coerceIn(0, cropW - 1)
        val safeTop = box.top.coerceIn(0, cropH - 1)
        val safeRight = box.right.coerceIn(safeLeft + 1, cropW)
        val safeBottom = box.bottom.coerceIn(safeTop + 1, cropH)

        val safeWidth = safeRight - safeLeft
        val safeHeight = safeBottom - safeTop
        val slots = digitCount.coerceAtLeast(1)
        val slotW = (safeWidth / slots).coerceAtLeast(1)

        val rects = ArrayList<Rect>(slots)
        for (i in 0 until slots) {
            val x = safeLeft + i * slotW
            val w = if (i == slots - 1) safeRight - x else slotW
            rects.add(Rect(cropRect.x + x, cropRect.y + safeTop, w, safeHeight))
        }
        return rects
    }

    private fun splitWholeCropToSlots(cropRect: Rect): List<Rect> {
        val slotW = (cropRect.width / 5).coerceAtLeast(1)
        val rects = ArrayList<Rect>(5)
        for (i in 0 until 5) {
            val x = cropRect.x + i * slotW
            val w = if (i == 4) cropRect.width - (slotW * i) else slotW
            rects.add(Rect(x, cropRect.y, w, cropRect.height))
        }
        return rects
    }

    private fun extractDigitCandidate(text: String): String? {
        if (text.isBlank()) return null

        val normalized = text.uppercase(Locale.US)
            .replace('O', '0')
            .replace('Q', '0')
            .replace('D', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('S', '5')
            .replace('B', '8')

        val digitsOnly = normalized.filter { it.isDigit() }
        if (digitsOnly.length >= 5) {
            return Regex("\\d{5}").findAll(digitsOnly).lastOrNull()?.value ?: digitsOnly.takeLast(5)
        }

        return if (digitsOnly.length >= 3) digitsOnly else null
    }

    private fun normalizeToFiveDigits(raw: String): String? {
        val digitsOnly = raw.filter { it.isDigit() }
        if (digitsOnly.length < 5) return null
        return digitsOnly.takeLast(5)
    }

    private fun candidateScore(digitLength: Int, hasBox: Boolean): Float {
        val lengthScore = when {
            digitLength >= 5 -> 300f
            digitLength == 4 -> 200f
            digitLength == 3 -> 100f
            else -> 0f
        }
        return lengthScore + if (hasBox) 20f else 0f
    }

    private fun rotateIfNeeded(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun saveDebugImage(bitmap: Bitmap, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bitmap, fileName)
            } else {
                saveLegacy(bitmap, fileName)
            }
        } catch (e: Exception) {
            Log.e("MeterAnalyzer", "Failed to save debug image", e)
        }
    }

    private fun saveViaMediaStore(bitmap: Bitmap, fileName: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, debugRelativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
    }

    private fun saveLegacy(bitmap: Bitmap, fileName: String) {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val debugDir = File(picturesDir, debugFolderName).apply { if (!exists()) mkdirs() }
        val outFile = File(debugDir, fileName)
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("image/png"), null)
    }

    private data class ResolvedReading(
        val reading: String,
        val confidence: Float,
        val rects: List<Rect>,
        val source: String,
        val hasFreshEvidence: Boolean
    )
}

