package com.kokeshi.metersnap

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class MeterAnalyzer(
    private val classifier: DigitClassifier,
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedTimestamp = 0L
    private val interval = 2000L // 2 seconds

    override fun analyze(image: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        if (currentTimestamp - lastAnalyzedTimestamp < interval) {
            image.close()
            return
        }
        lastAnalyzedTimestamp = currentTimestamp

        try {
            // 1. Convert ImageProxy to Mat
            val mat = imageProxyToMat(image)
            if (mat.empty()) {
                image.close()
                return
            }


            // 2. Preprocessing & ROI
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

            // Thresholding to find digits
            val binary = Mat()
            // Adaptive thresholding is usually better for varying lighting
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 11, 2.0
            )

            // Find contours
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                binary, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            // Filter contours to find potential digits
            val digitRects = ArrayList<Rect>()
            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
                
                // Filter based on size and aspect ratio (digits are usually tall)
                // These values might need tuning based on camera resolution and distance
                if (rect.height > 50 && rect.width > 20 && aspectRatio < 1.0 && aspectRatio > 0.2) {
                    digitRects.add(rect)
                }
            }

            // Sort rects from left to right
            digitRects.sortBy { it.x }

            // Limit to 5 digits (or whatever logic fits)
            // If we have too many, maybe we only take the ones in the center?
            // For now, let's just process up to 5 valid looking ones.
            
            val resultBuilder = StringBuilder()
            
            // If we found nothing, maybe try the center crop?
            // But let's stick to the contours for now.
            
            var count = 0
            for (rect in digitRects) {
                if (count >= 5) break
                
                // Crop
                val digitMat = Mat(gray, rect)
                
                // Resize to 28x28
                val resizedMat = Mat()
                Imgproc.resize(digitMat, resizedMat, Size(28.0, 28.0))
                
                // Convert back to Bitmap for the classifier
                val digitBitmap = matToGrayscaleBitmap(resizedMat)

                // Classify
                val digit = classifier.classify(digitBitmap)
                if (digit != -1) {
                    resultBuilder.append(digit)
                    count++
                }
                
                digitMat.release()
                resizedMat.release()
            }

            if (resultBuilder.isNotEmpty()) {
                onResult(resultBuilder.toString())
            }

            // Cleanup
            mat.release()
            gray.release()
            binary.release()
            hierarchy.release()
            
        } catch (e: Exception) {
            Log.e("MeterAnalyzer", "Error analyzing image", e)
        } finally {
            image.close()
        }
    }

    /**
     * Convert ImageProxy to OpenCV Mat
     * Handles YUV_420_888 format which is common in CameraX
     */
    private fun imageProxyToMat(image: ImageProxy): Mat {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // Copy V and U planes (interleaved to NV21 format)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvMat = Mat(image.height + image.height / 2, image.width, org.opencv.core.CvType.CV_8UC1)
        yuvMat.put(0, 0, nv21)

        val rgbMat = Mat()
        Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)

        // Handle rotation
        val rotationDegrees = image.imageInfo.rotationDegrees
        val rotatedMat = if (rotationDegrees != 0) {
            val center = org.opencv.core.Point(rgbMat.cols() / 2.0, rgbMat.rows() / 2.0)
            val rotationMatrix = Imgproc.getRotationMatrix2D(center, rotationDegrees.toDouble(), 1.0)
            val rotated = Mat()
            Imgproc.warpAffine(rgbMat, rotated, rotationMatrix, rgbMat.size())
            rgbMat.release()
            rotated
        } else {
            rgbMat
        }

        yuvMat.release()
        return rotatedMat
    }

    /**
     * Convert a grayscale Mat to Bitmap
     */
    private fun matToGrayscaleBitmap(mat: Mat): Bitmap {
        val bitmap = createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }
}
