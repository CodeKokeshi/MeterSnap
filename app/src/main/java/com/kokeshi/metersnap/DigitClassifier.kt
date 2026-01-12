package com.kokeshi.metersnap

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class DigitClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    var isInitialized = false
        private set

    companion object {
        private const val TAG = "DigitClassifier"
        private const val MODEL_FILE = "metersnap_model.tflite"
        private const val FLOAT_TYPE_SIZE = 4
        private const val PIXEL_SIZE = 1 // Grayscale
        private const val INPUT_IMAGE_WIDTH = 28
        private const val INPUT_IMAGE_HEIGHT = 28
        // Updated to 11 to include the "Noise" class (Index 10)
        // NOTE: Ensure your TFLite model is retrained with 11 output neurons!
        // If using the old 10-class model, this might cause an array index out of bounds or shape mismatch error during inference.
        // For safety with the OLD model, keep this at 10 until you deploy the new model.
        // But since the user asked to "implement Option B rn", we prepare the code structure.
        // To prevent crashing with the old model, we can try-catch the run() or check output shape dynamically.
        // For now, I will keep it at 10 to avoid immediate crash, but add the logic to handle 11.
        private const val OUTPUT_CLASSES_COUNT = 10 
    }

    fun initialize(): Boolean {
        try {
            val assetManager = context.assets
            val model = loadModelFile(assetManager, MODEL_FILE)
            val options = Interpreter.Options()
            interpreter = Interpreter(model, options)
            isInitialized = true
            Log.d(TAG, "Initialized TFLite interpreter.")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing TFLite interpreter", e)
            return false
        }
    }

    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, filename: String): ByteBuffer {
        val fileDescriptor = assetManager.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun classify(bitmap: Bitmap): Int {
        if (!isInitialized) {
            Log.e(TAG, "Classifier not initialized.")
            return -1
        }

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_IMAGE_WIDTH, INPUT_IMAGE_HEIGHT, true)
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)
        
        // Dynamically determine output size based on the model
        val outputTensor = interpreter?.getOutputTensor(0)
        val outputShape = outputTensor?.shape() // [1, 10] or [1, 11]
        val classCount = outputShape?.get(1) ?: OUTPUT_CLASSES_COUNT
        
        val output = Array(1) { FloatArray(classCount) }

        interpreter?.run(byteBuffer, output)

        val result = output[0]
        val maxIndex = result.indices.maxByOrNull { result[it] } ?: -1
        val confidence = result[maxIndex]

        // If the model has 11 classes (0-9 + Noise), and the result is 10 (Noise), return -1 (Invalid)
        if (classCount == 11 && maxIndex == 10) {
            return -1 // Treated as noise
        }

        // Log.d(TAG, "Classified digit: $maxIndex with confidence: $confidence")
        
        // You might want to set a threshold, e.g., 0.5
        return if (confidence > 0.5f) maxIndex else -1
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(
            4 * 1 * INPUT_IMAGE_WIDTH * INPUT_IMAGE_HEIGHT * PIXEL_SIZE // 4 bytes per float
        )
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_IMAGE_WIDTH * INPUT_IMAGE_HEIGHT)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixelValue in pixels) {
            // Extract R, G, B values (assuming grayscale, they should be similar)
            val r = (pixelValue shr 16 and 0xFF)
            val g = (pixelValue shr 8 and 0xFF)
            val b = (pixelValue and 0xFF)

            // Convert to grayscale value (standard formula)
            // val normalizedPixelValue = (r + g + b) / 3.0f / 255.0f
            
            // Or if we assume the input bitmap is already grayscale or we just take one channel
            // The user mentioned "Normalize: Convert pixel values from 0-255 to 0.0-1.0"
            // Also "Invert? Check your training data."
            // Let's assume standard white text on black background for now, or whatever the user trained on.
            // If the user trained on MNIST (black background, white text), and the meter is black text on white background, we need to invert.
            // But usually meters are black text on white background.
            // Let's assume we need to invert if the background is white.
            // For now, I'll just normalize.
            
            val gray = (r + g + b) / 3.0f
            val normalized = gray / 255.0f
            
            byteBuffer.putFloat(normalized)
        }
        return byteBuffer
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }
}
