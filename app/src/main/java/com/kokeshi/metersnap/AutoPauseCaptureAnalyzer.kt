package com.kokeshi.metersnap

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class AutoPauseCaptureAnalyzer(
    private val context: Context,
    private val onAutoPaused: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastAnalyzeMs = 0L
    private val analyzeIntervalMs = 90L

    private var lastDigitCheckMs = 0L
    private val digitCheckIntervalMs = 420L

    private var stableStartMs = 0L
    private val stableRequiredMs = 900L

    private var autoPaused = false
    private var lastPausedMs = 0L
    private val pauseCooldownMs = 1600L

    private var cachedHasDigits = false

    private var previousSmallGray: Mat? = null

    fun reset() {
        stableStartMs = 0L
        autoPaused = false
        cachedHasDigits = false
        previousSmallGray?.release()
        previousSmallGray = null
    }

    fun setPaused(paused: Boolean) {
        autoPaused = paused
    }

    fun shutdown() {
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Log.d("AutoPauseAnalyzer", "Failed to close ML Kit recognizer", e)
        }
        previousSmallGray?.release()
        previousSmallGray = null
    }

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (autoPaused || (now - lastPausedMs < pauseCooldownMs)) {
            image.close()
            return
        }
        if (now - lastAnalyzeMs < analyzeIntervalMs) {
            image.close()
            return
        }
        lastAnalyzeMs = now

        val bitmap = try {
            image.toBitmapCompat()
        } catch (e: Exception) {
            null
        }

        if (bitmap == null) {
            image.close()
            return
        }

        val rotated = rotate(bitmap, image.imageInfo.rotationDegrees)
        image.close()

        try {
            val (motionScore, blurScore) = analyzeMotionAndBlur(rotated)
            val looksStable = motionScore <= 2.9
            val looksSharp = blurScore >= 85.0

            if (looksStable && looksSharp) {
                if (now - lastDigitCheckMs >= digitCheckIntervalMs) {
                    lastDigitCheckMs = now
                    cachedHasDigits = detectDigitsQuick(rotated)
                }

                if (cachedHasDigits) {
                    if (stableStartMs == 0L) {
                        stableStartMs = now
                    }
                    if (now - stableStartMs >= stableRequiredMs) {
                        autoPaused = true
                        lastPausedMs = now
                        onAutoPaused(rotated.copy(Bitmap.Config.ARGB_8888, false))
                    }
                } else {
                    stableStartMs = 0L
                }
            } else {
                stableStartMs = 0L
            }
        } catch (e: Exception) {
            Log.d("AutoPauseAnalyzer", "Analyzer frame failed", e)
            stableStartMs = 0L
        }
    }

    private fun analyzeMotionAndBlur(bitmap: Bitmap): Pair<Double, Double> {
        val src = Mat()
        val gray = Mat()
        val small = Mat()
        val lap = Mat()

        return try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, small, Size(320.0, 180.0), 0.0, 0.0, Imgproc.INTER_AREA)

            Imgproc.Laplacian(small, lap, CvType.CV_64F)
            val mean = org.opencv.core.MatOfDouble()
            val stddev = org.opencv.core.MatOfDouble()
            Core.meanStdDev(lap, mean, stddev)
            val std = stddev.toArray().firstOrNull() ?: 0.0
            val blurScore = std * std
            mean.release()
            stddev.release()

            val motionScore = if (previousSmallGray == null) {
                0.0
            } else {
                val diff = Mat()
                Core.absdiff(small, previousSmallGray, diff)
                val diffMean = Core.mean(diff).`val`[0]
                diff.release()
                diffMean
            }

            previousSmallGray?.release()
            previousSmallGray = small.clone()

            motionScore to blurScore
        } finally {
            src.release()
            gray.release()
            small.release()
            lap.release()
        }
    }

    private fun detectDigitsQuick(bitmap: Bitmap): Boolean {
        return try {
            val maxW = 960
            val scale = if (bitmap.width > maxW) maxW.toFloat() / bitmap.width.toFloat() else 1f
            val sample = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }

            val inputImage = InputImage.fromBitmap(sample, 0)
            val result = Tasks.await(textRecognizer.process(inputImage))
            val digits = result.text.replace(" ", "").replace("\n", "").filter { it.isDigit() }
            val hasDigits = digits.length >= 3

            if (sample !== bitmap) {
                sample.recycle()
            }
            hasDigits
        } catch (e: Exception) {
            Log.d("AutoPauseAnalyzer", "Digit quick-check failed", e)
            false
        }
    }

    private fun rotate(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
