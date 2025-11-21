package com.kokeshi.metersnap

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
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
            // Crop to the center strip (Guide Box approximation)
            // Assuming the guide box is roughly in the vertical center
            val cropHeight = mat.height() / 4
            val cropY = (mat.height() - cropHeight) / 2
            val cropRect = Rect(0, cropY, mat.width(), cropHeight)
            val croppedMat = Mat(mat, cropRect)

            val gray = Mat()
            Imgproc.cvtColor(croppedMat, gray, Imgproc.COLOR_RGB2GRAY)

            // Invert colors (Water meters are usually Black text on White background)
            // MNIST models are trained on White text on Black background.
            // So we invert: Black(0) -> White(255)
            Core.bitwise_not(gray, gray)

            // Thresholding to find digits
            val binary = Mat()
            // Adaptive thresholding is usually better for varying lighting
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 11, 2.0
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
                // Height: Must be significant enough (e.g., > 1/3 of the crop height)
                // Aspect Ratio: Digits are usually 0.3 to 0.8
                if (rect.height > cropHeight * 0.3 && 
                    rect.height < cropHeight * 0.9 &&
                    aspectRatio > 0.2 && aspectRatio < 0.9) {
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
                
                // Crop the digit from the GRAY image (which is already inverted)
                val digitMat = Mat(gray, rect)
                
                // Resize to 28x28 with Padding (Center the digit)
                // 1. Find max dimension to make it square
                val maxDim = Math.max(rect.width, rect.height)
                val squareMat = Mat.zeros(maxDim, maxDim, org.opencv.core.CvType.CV_8UC1)
                
                // 2. Copy digit into center of square
                val offsetX = (maxDim - rect.width) / 2
                val offsetY = (maxDim - rect.height) / 2
                
                val roi = Rect(offsetX, offsetY, rect.width, rect.height)
                digitMat.copyTo(squareMat.submat(roi))

                // 3. Resize to 20x20 (leaving 4px padding on all sides for 28x28)
                val resizedMat = Mat()
                Imgproc.resize(squareMat, resizedMat, Size(20.0, 20.0))
                
                // 4. Add padding to make it 28x28
                val finalMat = Mat()
                Core.copyMakeBorder(resizedMat, finalMat, 4, 4, 4, 4, Core.BORDER_CONSTANT, Scalar(0.0))
                
                // Convert back to Bitmap for the classifier
                val digitBitmap = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(finalMat, digitBitmap)

                // Classify
                val digit = classifier.classify(digitBitmap)
                if (digit != -1) {
                    resultBuilder.append(digit)
                    count++
                }
                
                digitMat.release()
                squareMat.release()
                resizedMat.release()
                finalMat.release()
            }

            if (resultBuilder.isNotEmpty()) {
                onResult(resultBuilder.toString())
            }

            // Cleanup
            mat.release()
            croppedMat.release()
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
        // For simplicity in this example, we'll use the Bitmap conversion provided by CameraX or Android
        // But since we need a Mat, and ImageProxy -> Bitmap -> Mat is easier than raw YUV handling for now:
        val bitmap = image.toBitmap()
        val mat = Mat()
        if (bitmap != null) {
            // Handle rotation
            val rotation = image.imageInfo.rotationDegrees
            val rotatedBitmap = if (rotation != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotation.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            Utils.bitmapToMat(rotatedBitmap, mat)
        }
        return mat
    }
}
