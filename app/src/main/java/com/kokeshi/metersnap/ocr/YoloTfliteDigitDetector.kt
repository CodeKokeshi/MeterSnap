package com.kokeshi.metersnap.ocr

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
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

/**
 * INT8 YOLOv8 digit detector for the exported TFLite model.
 *
 * Assumptions (provided by project):
 * - Input: 640x640
 * - Classes: 0..9,N where N is class id 10
 * - Export has no built-in NMS
 */
object YoloTfliteDigitDetector {
    private const val TAG = "YoloTfliteDetector"
    private const val MODEL_FILE = "yolov8-watermeter.tflite"

    const val INPUT_SIZE = 640
    const val CLASS_COUNT = 11
    const val UNREADABLE_CLASS_ID = 10

    const val DEFAULT_CONF_THRESHOLD = 0.25f
    const val DEFAULT_IOU_THRESHOLD = 0.45f
    const val DEFAULT_MAX_DETECTIONS = 20
    const val DEFAULT_MIN_BOX_SIZE_PX = 8f

    data class Detection(
        val classId: Int,
        val score: Float,
        val box: RectF
    )

    private data class LetterboxInfo(
        val srcWidth: Int,
        val srcHeight: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    @Volatile
    private var initialized = false
    @Volatile
    private var lastStatus = "IDLE"

    private var interpreter: Interpreter? = null
    private var inputType: DataType = DataType.FLOAT32
    private var outputType: DataType = DataType.FLOAT32
    private var inputScale = 1f
    private var inputZeroPoint = 0
    private var outputScale = 1f
    private var outputZeroPoint = 0
    private var inputShape: IntArray = intArrayOf()
    private var outputShape: IntArray = intArrayOf()

    @Synchronized
    fun init(context: Context, threads: Int = 4) {
        if (initialized) return

        lastStatus = "INIT_START"
        try {
            val model = loadModelFile(context.applicationContext.assets, MODEL_FILE)
            val options = Interpreter.Options().apply {
                setNumThreads(threads)
            }

            val localInterpreter = Interpreter(model, options)
            val inTensor = localInterpreter.getInputTensor(0)
            val outTensor = localInterpreter.getOutputTensor(0)

            inputType = inTensor.dataType()
            outputType = outTensor.dataType()
            inputShape = inTensor.shape()
            outputShape = outTensor.shape()

            val inQuant = inTensor.quantizationParams()
            val outQuant = outTensor.quantizationParams()

            inputScale = if (inQuant.scale > 0f) inQuant.scale else 1f
            inputZeroPoint = inQuant.zeroPoint
            outputScale = if (outQuant.scale > 0f) outQuant.scale else 1f
            outputZeroPoint = outQuant.zeroPoint

            interpreter = localInterpreter
            initialized = true
            lastStatus = "INIT_OK"

            Log.i(
                TAG,
                "Initialized model. inShape=${inputShape.joinToString("x")}, outShape=${outputShape.joinToString("x")}, inType=$inputType, outType=$outputType, inQ=($inputScale,$inputZeroPoint), outQ=($outputScale,$outputZeroPoint)"
            )
        } catch (e: Exception) {
            initialized = false
            interpreter = null
            lastStatus = "INIT_ERR"
            Log.e(TAG, "Failed to initialize YOLO detector", e)
        }
    }

    fun isInitialized(): Boolean = initialized

    fun getDebugStatus(): String = lastStatus

    @Synchronized
    fun close() {
        interpreter?.close()
        interpreter = null
        initialized = false
        lastStatus = "CLOSED"
    }

    fun runDetection(
        source: Bitmap,
        confThreshold: Float = DEFAULT_CONF_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        maxDetections: Int = DEFAULT_MAX_DETECTIONS,
        classAgnosticNms: Boolean = true,
        minBoxSizePx: Float = DEFAULT_MIN_BOX_SIZE_PX
    ): List<Detection> {
        val localInterpreter = interpreter
        if (!initialized || localInterpreter == null) {
            lastStatus = "RUN_NO_INIT"
            return emptyList()
        }

        if (source.width <= 0 || source.height <= 0) {
            lastStatus = "RUN_BAD_INPUT"
            return emptyList()
        }

        return try {
            val (inputBitmap, letterbox) = createLetterboxedInput(source)
            val inputBuffer = buildInputBuffer(inputBitmap)

            val localOutputShape = localInterpreter.getOutputTensor(0).shape()
            val outElementCount = elementCount(localOutputShape)
            val outputBuffer = ByteBuffer
                .allocateDirect(localInterpreter.getOutputTensor(0).numBytes())
                .order(ByteOrder.nativeOrder())

            synchronized(this) {
                outputBuffer.rewind()
                localInterpreter.run(inputBuffer, outputBuffer)
            }
            outputBuffer.rewind()

            val dequantized = dequantizeOutput(outputBuffer, outputType, outElementCount)
            val predictions = parsePredictions(
                rawOutput = dequantized,
                shape = localOutputShape,
                confThreshold = confThreshold,
                minBoxSizePx = minBoxSizePx
            )

            val mapped = predictions.mapNotNull { mapBoxBackToSource(it, letterbox) }
            val afterNms = applyNms(mapped, iouThreshold, maxDetections, classAgnosticNms)

            lastStatus = "RUN_OK_${afterNms.size}"
            afterNms
        } catch (e: Exception) {
            lastStatus = "RUN_ERR"
            Log.e(TAG, "runDetection failed", e)
            emptyList()
        }
    }

    private fun createLetterboxedInput(source: Bitmap): Pair<Bitmap, LetterboxInfo> {
        val scale = min(INPUT_SIZE.toFloat() / source.width, INPUT_SIZE.toFloat() / source.height)
        val resizedW = max(1, (source.width * scale).roundToInt())
        val resizedH = max(1, (source.height * scale).roundToInt())

        val resized = Bitmap.createScaledBitmap(source, resizedW, resizedH, true)
        val letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val padX = (INPUT_SIZE - resizedW) / 2f
        val padY = (INPUT_SIZE - resizedH) / 2f

        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(resized, padX, padY, null)

        if (resized !== source) {
            resized.recycle()
        }

        return letterboxed to LetterboxInfo(
            srcWidth = source.width,
            srcHeight = source.height,
            scale = scale,
            padX = padX,
            padY = padY
        )
    }

    private fun buildInputBuffer(inputBitmap: Bitmap): ByteBuffer {
        val pixelCount = INPUT_SIZE * INPUT_SIZE
        val pixels = IntArray(pixelCount)
        inputBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val expectsUnitRange = inputScale <= 0.02f

        return when (inputType) {
            DataType.FLOAT32 -> {
                val buffer = ByteBuffer.allocateDirect(pixelCount * 3 * 4).order(ByteOrder.nativeOrder())
                for (pixel in pixels) {
                    val r = ((pixel shr 16) and 0xFF).toFloat() / 255f
                    val g = ((pixel shr 8) and 0xFF).toFloat() / 255f
                    val b = (pixel and 0xFF).toFloat() / 255f
                    buffer.putFloat(r)
                    buffer.putFloat(g)
                    buffer.putFloat(b)
                }
                buffer.rewind()
                buffer
            }

            DataType.INT8 -> {
                val buffer = ByteBuffer.allocateDirect(pixelCount * 3).order(ByteOrder.nativeOrder())
                for (pixel in pixels) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    val rReal = if (expectsUnitRange) r / 255f else r.toFloat()
                    val gReal = if (expectsUnitRange) g / 255f else g.toFloat()
                    val bReal = if (expectsUnitRange) b / 255f else b.toFloat()

                    buffer.put(quantizeInt8(rReal, inputScale, inputZeroPoint))
                    buffer.put(quantizeInt8(gReal, inputScale, inputZeroPoint))
                    buffer.put(quantizeInt8(bReal, inputScale, inputZeroPoint))
                }
                buffer.rewind()
                buffer
            }

            DataType.UINT8 -> {
                val buffer = ByteBuffer.allocateDirect(pixelCount * 3).order(ByteOrder.nativeOrder())
                for (pixel in pixels) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    val rReal = if (expectsUnitRange) r / 255f else r.toFloat()
                    val gReal = if (expectsUnitRange) g / 255f else g.toFloat()
                    val bReal = if (expectsUnitRange) b / 255f else b.toFloat()

                    buffer.put(quantizeUInt8(rReal, inputScale, inputZeroPoint))
                    buffer.put(quantizeUInt8(gReal, inputScale, inputZeroPoint))
                    buffer.put(quantizeUInt8(bReal, inputScale, inputZeroPoint))
                }
                buffer.rewind()
                buffer
            }

            else -> {
                // Fallback to float input path for uncommon tensor dtypes.
                val buffer = ByteBuffer.allocateDirect(pixelCount * 3 * 4).order(ByteOrder.nativeOrder())
                for (pixel in pixels) {
                    val r = ((pixel shr 16) and 0xFF).toFloat() / 255f
                    val g = ((pixel shr 8) and 0xFF).toFloat() / 255f
                    val b = (pixel and 0xFF).toFloat() / 255f
                    buffer.putFloat(r)
                    buffer.putFloat(g)
                    buffer.putFloat(b)
                }
                buffer.rewind()
                buffer
            }
        }
    }

    private fun dequantizeOutput(buffer: ByteBuffer, dtype: DataType, elementCount: Int): FloatArray {
        val out = FloatArray(elementCount)
        when (dtype) {
            DataType.FLOAT32 -> {
                for (i in 0 until elementCount) {
                    out[i] = buffer.float
                }
            }

            DataType.INT8 -> {
                for (i in 0 until elementCount) {
                    val q = buffer.get().toInt()
                    out[i] = (q - outputZeroPoint) * outputScale
                }
            }

            DataType.UINT8 -> {
                for (i in 0 until elementCount) {
                    val q = buffer.get().toInt() and 0xFF
                    out[i] = (q - outputZeroPoint) * outputScale
                }
            }

            else -> {
                for (i in 0 until elementCount) {
                    out[i] = 0f
                }
            }
        }
        return out
    }

    private fun parsePredictions(
        rawOutput: FloatArray,
        shape: IntArray,
        confThreshold: Float,
        minBoxSizePx: Float
    ): List<Detection> {
        if (shape.isEmpty()) return emptyList()

        val (attrCount, numPreds, channelsFirst) = resolveLayout(shape)
        if (attrCount <= 4 || numPreds <= 0) return emptyList()

        val classStart = when (attrCount) {
            4 + CLASS_COUNT -> 4
            5 + CLASS_COUNT -> 5
            else -> (attrCount - CLASS_COUNT).coerceAtLeast(4)
        }
        if (classStart + CLASS_COUNT > attrCount) return emptyList()

        val hasObjectness = classStart == 5
        val output = ArrayList<Detection>(numPreds.coerceAtMost(512))

        fun at(predIndex: Int, attrIndex: Int): Float {
            return if (channelsFirst) {
                rawOutput[attrIndex * numPreds + predIndex]
            } else {
                rawOutput[predIndex * attrCount + attrIndex]
            }
        }

        for (predIdx in 0 until numPreds) {
            val cx = at(predIdx, 0)
            val cy = at(predIdx, 1)
            val w = at(predIdx, 2)
            val h = at(predIdx, 3)

            var bestClass = -1
            var bestClassScore = Float.NEGATIVE_INFINITY
            for (c in 0 until CLASS_COUNT) {
                val s = maybeSigmoid(at(predIdx, classStart + c))
                if (s > bestClassScore) {
                    bestClassScore = s
                    bestClass = c
                }
            }

            if (bestClass !in 0 until CLASS_COUNT) continue

            val objectness = if (hasObjectness) maybeSigmoid(at(predIdx, 4)) else 1f
            val score = objectness * bestClassScore
            if (score < confThreshold) continue

            val box = toModelSpaceRect(cx, cy, w, h)
            val boxW = box.width()
            val boxH = box.height()
            if (boxW < minBoxSizePx || boxH < minBoxSizePx) continue

            output.add(
                Detection(
                    classId = bestClass,
                    score = score,
                    box = box
                )
            )
        }

        return output
    }

    private fun resolveLayout(shape: IntArray): Triple<Int, Int, Boolean> {
        if (shape.size == 3) {
            val d1 = shape[1]
            val d2 = shape[2]

            val expectedNoObj = 4 + CLASS_COUNT
            val expectedWithObj = 5 + CLASS_COUNT

            if ((d1 == expectedNoObj || d1 == expectedWithObj) && d2 > d1) {
                return Triple(d1, d2, true)
            }
            if ((d2 == expectedNoObj || d2 == expectedWithObj) && d1 > d2) {
                return Triple(d2, d1, false)
            }

            return if (d1 <= d2) {
                Triple(d1, d2, true)
            } else {
                Triple(d2, d1, false)
            }
        }

        if (shape.size == 2) {
            val d0 = shape[0]
            val d1 = shape[1]
            return if (d0 <= d1) {
                Triple(d0, d1, true)
            } else {
                Triple(d1, d0, false)
            }
        }

        return Triple(0, 0, true)
    }

    private fun toModelSpaceRect(cx: Float, cy: Float, w: Float, h: Float): RectF {
        val maxAbs = max(max(abs(cx), abs(cy)), max(abs(w), abs(h)))
        val normalized = maxAbs <= 2.5f

        val scale = if (normalized) INPUT_SIZE.toFloat() else 1f
        val boxCx = cx * scale
        val boxCy = cy * scale
        val boxW = w * scale
        val boxH = h * scale

        val left = (boxCx - boxW / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
        val top = (boxCy - boxH / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
        val right = (boxCx + boxW / 2f).coerceIn(left + 1f, INPUT_SIZE.toFloat())
        val bottom = (boxCy + boxH / 2f).coerceIn(top + 1f, INPUT_SIZE.toFloat())

        return RectF(left, top, right, bottom)
    }

    private fun mapBoxBackToSource(detection: Detection, info: LetterboxInfo): Detection? {
        val srcLeft = ((detection.box.left - info.padX) / info.scale).coerceIn(0f, info.srcWidth.toFloat())
        val srcTop = ((detection.box.top - info.padY) / info.scale).coerceIn(0f, info.srcHeight.toFloat())
        val srcRight = ((detection.box.right - info.padX) / info.scale).coerceIn(0f, info.srcWidth.toFloat())
        val srcBottom = ((detection.box.bottom - info.padY) / info.scale).coerceIn(0f, info.srcHeight.toFloat())

        val width = srcRight - srcLeft
        val height = srcBottom - srcTop
        if (width <= 1f || height <= 1f) return null

        return detection.copy(box = RectF(srcLeft, srcTop, srcRight, srcBottom))
    }

    private fun applyNms(
        detections: List<Detection>,
        iouThreshold: Float,
        maxDetections: Int,
        classAgnostic: Boolean
    ): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.score }
        val kept = ArrayList<Detection>(maxDetections)

        for (candidate in sorted) {
            var suppressed = false
            for (accepted in kept) {
                if (!classAgnostic && candidate.classId != accepted.classId) continue
                if (iou(candidate.box, accepted.box) > iouThreshold) {
                    suppressed = true
                    break
                }
            }

            if (!suppressed) {
                kept.add(candidate)
                if (kept.size >= maxDetections) break
            }
        }

        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        val interW = (right - left).coerceAtLeast(0f)
        val interH = (bottom - top).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f

        val areaA = (a.right - a.left).coerceAtLeast(0f) * (a.bottom - a.top).coerceAtLeast(0f)
        val areaB = (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)
        val union = areaA + areaB - interArea
        if (union <= 0f) return 0f

        return interArea / union
    }

    private fun maybeSigmoid(v: Float): Float {
        return if (v in 0f..1f) {
            v
        } else {
            val clipped = v.coerceIn(-20f, 20f)
            (1f / (1f + exp(-clipped))).coerceIn(0f, 1f)
        }
    }

    private fun quantizeInt8(realValue: Float, scale: Float, zeroPoint: Int): Byte {
        val q = (realValue / scale + zeroPoint).roundToInt().coerceIn(-128, 127)
        return q.toByte()
    }

    private fun quantizeUInt8(realValue: Float, scale: Float, zeroPoint: Int): Byte {
        val q = (realValue / scale + zeroPoint).roundToInt().coerceIn(0, 255)
        return q.toByte()
    }

    private fun elementCount(shape: IntArray): Int {
        var count = 1
        for (dim in shape) {
            count *= dim.coerceAtLeast(1)
        }
        return count
    }

    private fun loadModelFile(assetManager: AssetManager, filename: String): ByteBuffer {
        val fileDescriptor = assetManager.openFd(filename)
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }
}
