package com.kokeshi.metersnap.ocr

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.kokeshi.metersnap.utils.ScannerSettings
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LenetReadingResult(
    val reading: String,
    val digits: List<DigitCandidate>,
    val segmentedDigits: List<SegmentedDigit>,
    val stripGeometry: StripGeometry?,
    val preprocessDebugInfo: PreprocessDebugInfo,
    val isComplete: Boolean
)

data class DigitCandidate(
    val slotIndex: Int,
    val digit: Char,
    val confidence: Float,
    val margin: Float,
    val bounds: RectF
)

data class SegmentedDigit(
    val slotIndex: Int,
    val bounds: RectF,
    val foregroundRatio: Float,
    val usedFallbackSegmentation: Boolean,
    val normalizedSize: Int
)

data class StripGeometry(
    val sourceBounds: RectF,
    val rectifiedBounds: RectF,
    val dominantAngleDeg: Double,
    val usedPerspectiveRectification: Boolean,
    val rectifiedWidth: Int,
    val rectifiedHeight: Int
)

data class PreprocessDebugInfo(
    val bandTop: Int,
    val bandBottom: Int,
    val dominantAngleDeg: Double,
    val usedProjectionFallback: Boolean,
    val usedEqualSlotFallback: Boolean,
    val inverted: Boolean
)

object LenetDigitStripRecognizer {
    private const val TAG = "LenetRecognizer"
    private const val DIGIT_COUNT = 5
    private const val CANVAS_SIZE = 32
    private const val INNER_SIZE = 24
    private const val MIN_COMPONENT_AREA = 24.0
    private const val DEFAULT_MODEL_FILE = "models/best_small_float32.tflite"

    @Volatile
    private var initialized = false

    private var interpreter: Interpreter? = null
    private var inputType: DataType = DataType.FLOAT32
    private var outputType: DataType = DataType.FLOAT32
    private var inputShape: IntArray = intArrayOf()
    private var outputShape: IntArray = intArrayOf()
    private var inputScale = 1f
    private var inputZeroPoint = 0
    private var outputScale = 1f
    private var outputZeroPoint = 0
    private var outputElementCount = 0
    private var reusableInputBuffer: ByteBuffer? = null
    private var reusableOutputBuffer: ByteBuffer? = null

    // Runs the full OCR flow: locate the reading band, segment five digits, and decode them left to right.
    fun recognizeFiveDigits(context: Context, source: Bitmap): LenetReadingResult {
        if (source.width <= 0 || source.height <= 0 || source.isRecycled) {
            return emptyResult("?????", null, false, false, 0, 0, 0.0)
        }

        ensureInitialized(context)

        val gray = Mat()
        val binary = Mat()
        var rectifiedGray: Mat? = null
        var rectifiedBinary: Mat? = null

        return try {
            bitmapToGray(source, gray)
            buildDigitBinary(gray, binary)

            val band = locateDigitBand(binary)
            val strip = rectifyStrip(gray, binary, band)
            rectifiedGray = strip.gray
            rectifiedBinary = strip.binary

            val segmented = segmentDigits(strip.binary)
            val digits = segmented.map { slot ->
                classifyDigit(slot, strip.binary)
            }

            val reading = buildString(DIGIT_COUNT) { digits.forEach { append(it.digit) } }
            LenetReadingResult(
                reading = reading,
                digits = digits,
                segmentedDigits = segmented.map {
                    SegmentedDigit(
                        slotIndex = it.slotIndex,
                        bounds = rectToRectF(it.bounds),
                        foregroundRatio = it.foregroundRatio,
                        usedFallbackSegmentation = it.usedFallback,
                        normalizedSize = CANVAS_SIZE
                    )
                },
                stripGeometry = strip.geometry,
                preprocessDebugInfo = PreprocessDebugInfo(
                    bandTop = band.rect.y,
                    bandBottom = band.rect.y + band.rect.height,
                    dominantAngleDeg = strip.geometry.dominantAngleDeg,
                    usedProjectionFallback = band.usedProjectionFallback,
                    usedEqualSlotFallback = segmented.any { it.usedFallback },
                    inverted = true
                ),
                isComplete = digits.size == DIGIT_COUNT && digits.all { it.digit.isDigit() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Five-digit recognition failed", e)
            emptyResult("?????", null, false, false, 0, source.height, 0.0)
        } finally {
            gray.release()
            binary.release()
            rectifiedGray?.release()
            rectifiedBinary?.release()
        }
    }

    @Synchronized
    fun close() {
        interpreter?.close()
        interpreter = null
        initialized = false
        reusableInputBuffer = null
        reusableOutputBuffer = null
    }

    @Synchronized
    // Loads the TensorFlow Lite asset and caches tensor metadata for repeated per-digit inference.
    private fun ensureInitialized(context: Context) {
        if (initialized) return

        val modelPath = resolveModelPath(context)
        try {
            val mappedModel = loadModelFile(context.applicationContext.assets, modelPath)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            val localInterpreter = Interpreter(mappedModel, options)
            val inputTensor = localInterpreter.getInputTensor(0)
            val outputTensor = localInterpreter.getOutputTensor(0)

            inputType = inputTensor.dataType()
            outputType = outputTensor.dataType()
            inputShape = inputTensor.shape()
            outputShape = outputTensor.shape()
            outputElementCount = elementCount(outputShape)

            val inQuant = inputTensor.quantizationParams()
            val outQuant = outputTensor.quantizationParams()
            inputScale = if (inQuant.scale > 0f) inQuant.scale else 1f
            inputZeroPoint = inQuant.zeroPoint
            outputScale = if (outQuant.scale > 0f) outQuant.scale else 1f
            outputZeroPoint = outQuant.zeroPoint

            reusableInputBuffer = allocateInputBuffer()
            reusableOutputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
            interpreter = localInterpreter
            initialized = true
        } catch (e: Exception) {
            Log.w(TAG, "Classifier initialization did not complete", e)
            interpreter = null
            initialized = false
        }
    }

    // Converts the input bitmap into a grayscale matrix so the preprocessing pipeline works in one channel.
    private fun bitmapToGray(source: Bitmap, gray: Mat) {
        val rgba = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            if (rgba.channels() == 4) {
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            } else {
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGB2GRAY)
            }
        } finally {
            rgba.release()
        }
    }

    // Enhances digit strokes and produces a clean binary image for strip search and segmentation.
    private fun buildDigitBinary(gray: Mat, outBinary: Mat) {
        val normalized = Mat()
        val blurred = Mat()
        val blackhat = Mat()
        val adaptive = Mat()
        val otsu = Mat()
        val merged = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(21.0, 21.0))

        try {
            Core.normalize(gray, normalized, 0.0, 255.0, Core.NORM_MINMAX)
            Imgproc.GaussianBlur(normalized, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.morphologyEx(blurred, blackhat, Imgproc.MORPH_BLACKHAT, kernel)
            Imgproc.adaptiveThreshold(
                blackhat,
                adaptive,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                31,
                -8.0
            )
            Imgproc.threshold(blackhat, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Core.bitwise_or(adaptive, otsu, merged)
            Imgproc.morphologyEx(
                merged,
                outBinary,
                Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 3.0))
            )
        } finally {
            normalized.release()
            blurred.release()
            blackhat.release()
            adaptive.release()
            otsu.release()
            merged.release()
            kernel.release()
        }
    }

    // Scans horizontal pixel density to estimate the most likely meter reading band in the frame.
    private fun locateDigitBand(binary: Mat): BandSelection {
        val projection = IntArray(binary.rows())
        for (y in 0 until binary.rows()) {
            var rowSum = 0
            for (x in 0 until binary.cols()) {
                if (binary.get(y, x)[0] > 0.0) rowSum++
            }
            projection[y] = rowSum
        }

        val minBand = max(24, (binary.rows() * 0.14f).roundToInt())
        val maxBand = max(minBand + 1, (binary.rows() * 0.42f).roundToInt())

        var bestTop = 0
        var bestBottom = min(binary.rows(), maxBand)
        var bestScore = Int.MIN_VALUE
        var usedProjectionFallback = false

        val prefix = IntArray(projection.size + 1)
        for (i in projection.indices) {
            prefix[i + 1] = prefix[i] + projection[i]
        }

        for (bandHeight in minBand..maxBand step max(2, minBand / 8)) {
            for (top in 0..max(0, binary.rows() - bandHeight)) {
                val bottom = top + bandHeight
                val score = prefix[bottom] - prefix[top]
                if (score > bestScore) {
                    bestScore = score
                    bestTop = top
                    bestBottom = bottom
                }
            }
        }

        if (bestScore <= 0) {
            usedProjectionFallback = true
            bestTop = binary.rows() / 3
            bestBottom = min(binary.rows(), bestTop + max(minBand, binary.rows() / 4))
        }

        val margin = ((bestBottom - bestTop) * 0.18f).roundToInt()
        val rect = Rect(
            0,
            max(0, bestTop - margin),
            binary.cols(),
            min(binary.rows(), bestBottom + margin) - max(0, bestTop - margin)
        )
        return BandSelection(rect = rect, usedProjectionFallback = usedProjectionFallback)
    }

    // Straightens the digit strip using the dominant contour angle and crops it to a tighter working region.
    private fun rectifyStrip(gray: Mat, binary: Mat, band: BandSelection): RectifiedStrip {
        val bandGray = gray.submat(clampRect(band.rect, gray.cols(), gray.rows())).clone()
        val bandBinary = binary.submat(clampRect(band.rect, binary.cols(), binary.rows())).clone()

        val merged = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(19.0, 5.0))
        val dominantContourRect: Rect
        val dominantAngle: Double

        try {
            Imgproc.morphologyEx(bandBinary, merged, Imgproc.MORPH_CLOSE, kernel)
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            try {
                Imgproc.findContours(merged.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                val scored = contours.mapNotNull { contour ->
                    val rect = Imgproc.boundingRect(contour)
                    val area = Imgproc.contourArea(contour)
                    val widthScore = rect.width.toDouble() / max(1.0, rect.height.toDouble())
                    if (area < MIN_COMPONENT_AREA || rect.width < bandGray.cols() * 0.2f) {
                        null
                    } else {
                        Triple(contour, rect, area * widthScore)
                    }
                }
                val best = scored.maxByOrNull { it.third }
                if (best != null) {
                    dominantContourRect = best.second
                    dominantAngle = normalizedAngle(Imgproc.minAreaRect(MatOfPoint2f(*best.first.toArray())))
                } else {
                    dominantContourRect = Rect(0, 0, bandGray.cols(), bandGray.rows())
                    dominantAngle = 0.0
                }
            } finally {
                hierarchy.release()
                contours.forEach { it.release() }
            }
        } finally {
            merged.release()
            kernel.release()
        }

        val rotatedGray = rotateMat(bandGray, dominantAngle)
        val rotatedBinary = rotateMat(bandBinary, dominantAngle)
        bandGray.release()
        bandBinary.release()

        val finalRect = findTightStripRect(rotatedBinary)
        val croppedGray = rotatedGray.submat(finalRect).clone()
        val croppedBinary = rotatedBinary.submat(finalRect).clone()
        rotatedGray.release()
        rotatedBinary.release()

        val sourceBounds = Rect(
            dominantContourRect.x,
            band.rect.y + dominantContourRect.y,
            dominantContourRect.width,
            dominantContourRect.height
        )

        return RectifiedStrip(
            gray = croppedGray,
            binary = croppedBinary,
            geometry = StripGeometry(
                sourceBounds = rectToRectF(clampRect(sourceBounds, gray.cols(), gray.rows())),
                rectifiedBounds = rectToRectF(finalRect),
                dominantAngleDeg = dominantAngle,
                usedPerspectiveRectification = false,
                rectifiedWidth = croppedGray.cols(),
                rectifiedHeight = croppedGray.rows()
            )
        )
    }

    // Merges nearby strip contours and returns the tightest rectangle that still covers the digit band.
    private fun findTightStripRect(binary: Mat): Rect {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        return try {
            Imgproc.findContours(binary.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val mergedRect = contours
                .map { Imgproc.boundingRect(it) }
                .filter { it.area() > MIN_COMPONENT_AREA }
                .fold(null as Rect?) { acc, rect ->
                    if (acc == null) rect else unionRect(acc, rect)
                }
            clampRect(
                mergedRect ?: Rect(0, 0, binary.cols(), binary.rows()),
                binary.cols(),
                binary.rows()
            )
        } finally {
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    // Splits the rectified strip into exactly five slot candidates and extracts one glyph per slot.
    private fun segmentDigits(stripBinary: Mat): List<SlotCrop> {
        val boundaries = detectSlotBoundaries(stripBinary)
        return (0 until DIGIT_COUNT).map { slotIndex ->
            val left = boundaries.boundaries[slotIndex]
            val right = boundaries.boundaries[slotIndex + 1]
            val slotRect = clampRect(Rect(left, 0, max(1, right - left), stripBinary.rows()), stripBinary.cols(), stripBinary.rows())
            val isolated = isolateDigit(slotRect, stripBinary, slotIndex, boundaries.usedFallback)
            isolated
        }
    }

    // Uses vertical projection valleys to place slot boundaries, with equal-width recovery when spacing collapses.
    private fun detectSlotBoundaries(stripBinary: Mat): BoundarySelection {
        val projection = IntArray(stripBinary.cols())
        for (x in 0 until stripBinary.cols()) {
            var colSum = 0
            for (y in 0 until stripBinary.rows()) {
                if (stripBinary.get(y, x)[0] > 0.0) colSum++
            }
            projection[x] = colSum
        }

        val smoothed = IntArray(projection.size)
        for (i in projection.indices) {
            var sum = 0
            var count = 0
            for (j in max(0, i - 3)..min(projection.lastIndex, i + 3)) {
                sum += projection[j]
                count++
            }
            smoothed[i] = if (count > 0) sum / count else projection[i]
        }

        val slotWidth = stripBinary.cols() / DIGIT_COUNT.toFloat()
        val boundaries = IntArray(DIGIT_COUNT + 1)
        boundaries[0] = 0
        boundaries[DIGIT_COUNT] = stripBinary.cols()
        var usedFallback = false

        for (index in 1 until DIGIT_COUNT) {
            val ideal = (slotWidth * index).roundToInt()
            val radius = max(4, (slotWidth * 0.18f).roundToInt())
            var bestX = ideal
            var bestValue = Int.MAX_VALUE
            for (x in max(1, ideal - radius)..min(stripBinary.cols() - 2, ideal + radius)) {
                val value = smoothed[x]
                if (value < bestValue) {
                    bestValue = value
                    bestX = x
                }
            }
            boundaries[index] = bestX
        }

        for (i in 1 until boundaries.size) {
            if (boundaries[i] <= boundaries[i - 1] + 4) {
                usedFallback = true
                break
            }
        }

        if (usedFallback) {
            for (i in 0..DIGIT_COUNT) {
                boundaries[i] = ((stripBinary.cols() * i.toFloat()) / DIGIT_COUNT.toFloat()).roundToInt().coerceIn(0, stripBinary.cols())
            }
            boundaries[DIGIT_COUNT] = stripBinary.cols()
        }

        return BoundarySelection(boundaries = boundaries, usedFallback = usedFallback)
    }

    // Keeps the dominant connected component inside a slot and prepares it for centered 32x32 normalization.
    private fun isolateDigit(slotRect: Rect, stripBinary: Mat, slotIndex: Int, usedFallback: Boolean): SlotCrop {
        val slot = stripBinary.submat(slotRect).clone()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        return try {
            Imgproc.findContours(slot.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val chosenRect = contours
                .map { Imgproc.boundingRect(it) }
                .filter { it.area() > MIN_COMPONENT_AREA }
                .maxByOrNull { it.area() }
                ?: Rect(0, 0, slot.cols(), slot.rows())

            val normalized = normalizeDigit(slot, chosenRect)
            val foreground = countForeground(normalized).toFloat() / max(1f, (normalized.rows() * normalized.cols()).toFloat())

            SlotCrop(
                slotIndex = slotIndex,
                bounds = Rect(slotRect.x + chosenRect.x, chosenRect.y, chosenRect.width, chosenRect.height),
                normalized = normalized,
                foregroundRatio = foreground,
                usedFallback = usedFallback
            )
        } finally {
            slot.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    // Resizes the extracted glyph into a fixed canvas while preserving its proportions and stroke placement.
    private fun normalizeDigit(slotBinary: Mat, componentRect: Rect): Mat {
        val rect = clampRect(componentRect, slotBinary.cols(), slotBinary.rows())
        val digit = slotBinary.submat(rect).clone()
        val normalized = Mat.zeros(CANVAS_SIZE, CANVAS_SIZE, CvType.CV_8UC1)
        return try {
            val working = ensureForegroundPolarity(digit)
            val scale = min(INNER_SIZE.toDouble() / max(1.0, working.cols().toDouble()), INNER_SIZE.toDouble() / max(1.0, working.rows().toDouble()))
            val resizedWidth = max(1, (working.cols() * scale).roundToInt())
            val resizedHeight = max(1, (working.rows() * scale).roundToInt())
            val resized = Mat()
            try {
                Imgproc.resize(working, resized, Size(resizedWidth.toDouble(), resizedHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                val x = (CANVAS_SIZE - resizedWidth) / 2
                val y = (CANVAS_SIZE - resizedHeight) / 2
                val roi = normalized.submat(Rect(x, y, resizedWidth, resizedHeight))
                resized.copyTo(roi)
                roi.release()
                normalized
            } finally {
                resized.release()
                working.release()
            }
        } finally {
            digit.release()
        }
    }

    // Enforces a consistent foreground polarity before the digit is converted into the model input tensor.
    private fun ensureForegroundPolarity(input: Mat): Mat {
        val out = input.clone()
        val foreground = countForeground(out)
        val background = out.rows() * out.cols() - foreground
        if (foreground > background) {
            Core.bitwise_not(out, out)
        }
        return out
    }

    // Combines classifier output with structural digit cues to produce the final character and confidence.
    private fun classifyDigit(slot: SlotCrop, stripBinary: Mat): DigitCandidate {
        val modelScores = runModel(slot.normalized)
        val structuralScores = structuralScores(slot.normalized)
        val combined = FloatArray(10) { index ->
            val modelWeight = if (modelScores.any { it != 0f }) 0.75f else 0f
            val structuralWeight = 1f - modelWeight
            modelScores[index] * modelWeight + structuralScores[index] * structuralWeight
        }
        val probabilities = softmax(combined)
        val ranked = probabilities.withIndex().sortedByDescending { it.value }
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)
        val digitChar = ('0'.code + best.index).toChar()
        slot.normalized.release()

        val top = slot.bounds.y.toFloat().coerceAtLeast(0f)
        val bottom = (slot.bounds.y + slot.bounds.height).toFloat().coerceAtMost(stripBinary.rows().toFloat())

        return DigitCandidate(
            slotIndex = slot.slotIndex,
            digit = if (best.value >= 0.15f) digitChar else '?',
            confidence = best.value,
            margin = best.value - (runnerUp?.value ?: 0f),
            bounds = RectF(
                slot.bounds.x.toFloat(),
                top,
                (slot.bounds.x + slot.bounds.width).toFloat(),
                bottom
            )
        )
    }

    // Executes one digit classification pass using the cached interpreter and reusable buffers.
    private fun runModel(inputDigit: Mat): FloatArray {
        val localInterpreter = interpreter ?: return FloatArray(10)
        return try {
            val inputBuffer = buildInputBuffer(inputDigit)
            val outputBuffer = reusableOutputBuffer
                ?: ByteBuffer.allocateDirect(outputElementCount * 4).order(ByteOrder.nativeOrder()).also {
                    reusableOutputBuffer = it
                }
            outputBuffer.rewind()
            localInterpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            extractDigitScores(dequantizeOutput(outputBuffer))
        } catch (e: Exception) {
            Log.w(TAG, "Classifier pass fell back to structural scoring", e)
            FloatArray(10)
        }
    }

    // Packs the normalized 32x32 glyph into the tensor layout expected by the selected model.
    private fun buildInputBuffer(inputDigit: Mat): ByteBuffer {
        val buffer = reusableInputBuffer ?: allocateInputBuffer().also { reusableInputBuffer = it }
        buffer.rewind()

        val pixelCount = inputDigit.rows() * inputDigit.cols()
        val channels = resolveInputChannels()
        val floats = FloatArray(pixelCount) { idx ->
            val y = idx / inputDigit.cols()
            val x = idx % inputDigit.cols()
            (inputDigit.get(y, x)[0] / 255.0).toFloat()
        }
        val expectsUnitRange = inputScale <= 0.02f

        when (inputType) {
            DataType.FLOAT32 -> {
                for (value in floats) {
                    repeat(channels) {
                        buffer.putFloat(value)
                    }
                }
            }

            DataType.INT8 -> {
                for (value in floats) {
                    val real = if (expectsUnitRange) value else value * 255f
                    repeat(channels) {
                        buffer.put((real / inputScale + inputZeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                    }
                }
            }

            DataType.UINT8 -> {
                for (value in floats) {
                    val real = if (expectsUnitRange) value else value * 255f
                    repeat(channels) {
                        buffer.put((real / inputScale + inputZeroPoint).roundToInt().coerceIn(0, 255).toByte())
                    }
                }
            }

            else -> {
                for (value in floats) {
                    repeat(channels) {
                        buffer.putFloat(value)
                    }
                }
            }
        }

        buffer.rewind()
        return buffer
    }

    // Converts raw model output into floating-point scores regardless of quantized or float tensor type.
    private fun dequantizeOutput(buffer: ByteBuffer): FloatArray {
        val output = FloatArray(max(outputElementCount, 10))
        when (outputType) {
            DataType.FLOAT32 -> {
                for (i in 0 until min(outputElementCount, output.size)) {
                    output[i] = buffer.float
                }
            }

            DataType.INT8 -> {
                for (i in 0 until min(outputElementCount, output.size)) {
                    output[i] = (buffer.get().toInt() - outputZeroPoint) * outputScale
                }
            }

            DataType.UINT8 -> {
                for (i in 0 until min(outputElementCount, output.size)) {
                    output[i] = ((buffer.get().toInt() and 0xFF) - outputZeroPoint) * outputScale
                }
            }

            else -> {
                for (i in output.indices) output[i] = 0f
            }
        }
        return output
    }

    // Reduces the model output to ten digit scores so decoding always ranks classes 0 through 9.
    private fun extractDigitScores(raw: FloatArray): FloatArray {
        if (raw.size >= 10) {
            return FloatArray(10) { idx -> raw[idx] }
        }
        val scores = FloatArray(10)
        raw.forEachIndexed { index, value ->
            scores[index % 10] += value
        }
        return scores
    }

    // Derives shape-based cues from the glyph so scoring can still reflect loops, fill, and stroke balance.
    private fun structuralScores(inputDigit: Mat): FloatArray {
        val scores = FloatArray(10) { 0.05f }
        val fg = countForeground(inputDigit).toFloat()
        val total = max(1f, (inputDigit.rows() * inputDigit.cols()).toFloat())
        val fill = fg / total
        val bounds = findForegroundBounds(inputDigit)
        val aspect = bounds.width.toFloat() / max(1f, bounds.height.toFloat())
        val holeCount = countInnerHoles(inputDigit)
        val centerMass = computeCenterMassX(inputDigit)
        val lowerMass = computeLowerHalfMass(inputDigit)

        scores[1] += max(0f, 0.32f - fill) + max(0f, 0.75f - aspect)
        scores[0] += if (holeCount >= 1) 0.45f else 0.05f
        scores[8] += if (holeCount >= 2) 0.65f else 0.06f
        scores[6] += if (holeCount == 1 && lowerMass > 0.52f) 0.36f else 0.06f
        scores[9] += if (holeCount == 1 && lowerMass <= 0.52f) 0.36f else 0.06f
        scores[7] += if (fill < 0.24f && lowerMass < 0.52f) 0.30f else 0.05f
        scores[4] += if (aspect < 0.85f && centerMass > 0.52f) 0.22f else 0.04f
        scores[2] += if (centerMass > 0.50f && holeCount == 0) 0.18f else 0.04f
        scores[3] += if (centerMass > 0.54f && fill > 0.16f) 0.19f else 0.04f
        scores[5] += if (centerMass < 0.48f && fill > 0.16f) 0.19f else 0.04f
        return scores
    }

    // Converts logits into normalized probabilities that can be compared across all digit classes.
    private fun softmax(values: FloatArray): FloatArray {
        val maxValue = values.maxOrNull() ?: 0f
        val exps = FloatArray(values.size)
        var sum = 0.0
        for (i in values.indices) {
            val expValue = exp((values[i] - maxValue).toDouble())
            exps[i] = expValue.toFloat()
            sum += expValue
        }
        if (sum <= 0.0) return FloatArray(values.size)
        return FloatArray(values.size) { idx -> (exps[idx] / sum).toFloat() }
    }

    // Measures the occupied foreground box so later heuristics can use aspect ratio and coverage.
    private fun findForegroundBounds(input: Mat): Rect {
        var minX = input.cols()
        var minY = input.rows()
        var maxX = 0
        var maxY = 0
        var found = false

        for (y in 0 until input.rows()) {
            for (x in 0 until input.cols()) {
                if (input.get(y, x)[0] > 0.0) {
                    found = true
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
            }
        }

        return if (found) Rect(minX, minY, max(1, maxX - minX + 1), max(1, maxY - minY + 1)) else Rect(0, 0, input.cols(), input.rows())
    }

    // Counts enclosed voids inside the glyph, which helps separate digits with one or two loops.
    private fun countInnerHoles(input: Mat): Int {
        val inverted = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        return try {
            Core.bitwise_not(input, inverted)
            Imgproc.findContours(inverted.clone(), contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)
            var holes = 0
            for (i in contours.indices) {
                val parent = hierarchy.get(0, i)?.getOrNull(3)?.toInt() ?: -1
                if (parent >= 0 && Imgproc.contourArea(contours[i]) > 6.0) {
                    holes++
                }
            }
            holes
        } finally {
            inverted.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    // Estimates whether the digit mass leans left or right across the normalized canvas.
    private fun computeCenterMassX(input: Mat): Float {
        var weighted = 0.0
        var sum = 0.0
        for (y in 0 until input.rows()) {
            for (x in 0 until input.cols()) {
                val value = input.get(y, x)[0] / 255.0
                weighted += x * value
                sum += value
            }
        }
        return if (sum <= 0.0) 0.5f else (weighted / sum / max(1.0, (input.cols() - 1).toDouble())).toFloat()
    }

    // Measures how much of the foreground lies in the lower half of the glyph.
    private fun computeLowerHalfMass(input: Mat): Float {
        var lower = 0.0
        var total = 0.0
        for (y in 0 until input.rows()) {
            for (x in 0 until input.cols()) {
                val value = input.get(y, x)[0] / 255.0
                total += value
                if (y >= input.rows() / 2) lower += value
            }
        }
        return if (total <= 0.0) 0.5f else (lower / total).toFloat()
    }

    // Reads the input tensor shape to determine how many channels the digit buffer must provide.
    private fun resolveInputChannels(): Int {
        return when {
            inputShape.size >= 4 -> max(1, min(inputShape[inputShape.lastIndex], 3))
            inputShape.size == 3 -> max(1, min(inputShape[inputShape.lastIndex], 3))
            else -> 1
        }
    }

    // Allocates the reusable input tensor buffer using the active tensor type and channel count.
    private fun allocateInputBuffer(): ByteBuffer {
        val channels = resolveInputChannels()
        val bytesPerChannel = when (inputType) {
            DataType.FLOAT32 -> 4
            DataType.INT8, DataType.UINT8 -> 1
            else -> 4
        }
        val capacity = CANVAS_SIZE * CANVAS_SIZE * channels * bytesPerChannel
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
    }

    // Chooses an available model asset path, preferring the saved scanner setting and then bundled fallbacks.
    private fun resolveModelPath(context: Context): String {
        val candidate = ScannerSettings.getModelFile(context)
        return when {
            assetExists(context.applicationContext.assets, candidate) -> candidate
            assetExists(context.applicationContext.assets, DEFAULT_MODEL_FILE) -> DEFAULT_MODEL_FILE
            else -> ScannerSettings.AVAILABLE_MODELS.firstOrNull {
                assetExists(context.applicationContext.assets, it)
            } ?: DEFAULT_MODEL_FILE
        }
    }

    // Verifies that a candidate model asset exists before interpreter initialization begins.
    private fun assetExists(assetManager: AssetManager, path: String): Boolean {
        return try {
            assetManager.openFd(path).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    // Memory-maps the TensorFlow Lite file so the interpreter can initialize from the app asset efficiently.
    private fun loadModelFile(assetManager: AssetManager, filename: String): ByteBuffer {
        val descriptor = assetManager.openFd(filename)
        FileInputStream(descriptor.fileDescriptor).use { input ->
            return input.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
        }
    }

    // Rotates the strip around its center so downstream segmentation operates on a flatter digit row.
    private fun rotateMat(source: Mat, angleDeg: Double): Mat {
        if (abs(angleDeg) < 0.5) return source.clone()
        val center = Point(source.cols() / 2.0, source.rows() / 2.0)
        val matrix = Imgproc.getRotationMatrix2D(center, angleDeg, 1.0)
        val out = Mat()
        Imgproc.warpAffine(
            source,
            out,
            matrix,
            source.size(),
            Imgproc.INTER_LINEAR,
            Core.BORDER_CONSTANT,
            Scalar(0.0)
        )
        matrix.release()
        return out
    }

    // Normalizes OpenCV angle conventions into a stable band angle for strip rectification.
    private fun normalizedAngle(rotatedRect: RotatedRect): Double {
        var angle = rotatedRect.angle
        val size = rotatedRect.size
        if (size.width < size.height) {
            angle += 90.0
        }
        return angle.coerceIn(-30.0, 30.0)
    }

    // Counts foreground pixels in a binary image to derive fill and occupancy measurements.
    private fun countForeground(mat: Mat): Int {
        var count = 0
        for (y in 0 until mat.rows()) {
            for (x in 0 until mat.cols()) {
                if (mat.get(y, x)[0] > 0.0) count++
            }
        }
        return count
    }

    // Expands two rectangles into a single enclosing box when separate contours belong to the same strip.
    private fun unionRect(a: Rect, b: Rect): Rect {
        val left = min(a.x, b.x)
        val top = min(a.y, b.y)
        val right = max(a.x + a.width, b.x + b.width)
        val bottom = max(a.y + a.height, b.y + b.height)
        return Rect(left, top, right - left, bottom - top)
    }

    // Constrains a rectangle to valid image bounds before any cropping or submat operation.
    private fun clampRect(rect: Rect, maxWidth: Int, maxHeight: Int): Rect {
        val x = rect.x.coerceIn(0, max(0, maxWidth - 1))
        val y = rect.y.coerceIn(0, max(0, maxHeight - 1))
        val width = rect.width.coerceAtLeast(1).coerceAtMost(maxWidth - x)
        val height = rect.height.coerceAtLeast(1).coerceAtMost(maxHeight - y)
        return Rect(x, y, width, height)
    }

    // Converts OpenCV integer geometry into floating-point bounds for the returned OCR result.
    private fun rectToRectF(rect: Rect): RectF {
        return RectF(
            rect.x.toFloat(),
            rect.y.toFloat(),
            (rect.x + rect.width).toFloat(),
            (rect.y + rect.height).toFloat()
        )
    }

    // Multiplies tensor dimensions to determine the flattened output element count.
    private fun elementCount(shape: IntArray): Int {
        var count = 1
        for (dim in shape) {
            count *= dim.coerceAtLeast(1)
        }
        return count
    }

    // Produces a consistent fallback result when the strip or any digit stage cannot be resolved.
    private fun emptyResult(
        reading: String,
        geometry: StripGeometry?,
        usedProjectionFallback: Boolean,
        usedEqualSlotFallback: Boolean,
        bandTop: Int,
        bandBottom: Int,
        angle: Double
    ): LenetReadingResult {
        val digits = (0 until DIGIT_COUNT).map { index ->
            DigitCandidate(index, '?', 0f, 0f, RectF())
        }
        return LenetReadingResult(
            reading = reading,
            digits = digits,
            segmentedDigits = emptyList(),
            stripGeometry = geometry,
            preprocessDebugInfo = PreprocessDebugInfo(
                bandTop = bandTop,
                bandBottom = bandBottom,
                dominantAngleDeg = angle,
                usedProjectionFallback = usedProjectionFallback,
                usedEqualSlotFallback = usedEqualSlotFallback,
                inverted = true
            ),
            isComplete = false
        )
    }

    private data class BandSelection(
        val rect: Rect,
        val usedProjectionFallback: Boolean
    )

    private data class RectifiedStrip(
        val gray: Mat,
        val binary: Mat,
        val geometry: StripGeometry
    )

    private data class BoundarySelection(
        val boundaries: IntArray,
        val usedFallback: Boolean
    )

    private data class SlotCrop(
        val slotIndex: Int,
        val bounds: Rect,
        val normalized: Mat,
        val foregroundRatio: Float,
        val usedFallback: Boolean
    )
}
