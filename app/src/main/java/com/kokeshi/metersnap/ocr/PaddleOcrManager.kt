package com.kokeshi.metersnap.ocr

import android.content.Context
import android.util.Log
import com.baidu.paddle.lite.MobileConfig
import com.baidu.paddle.lite.PaddlePredictor
import com.baidu.paddle.lite.PowerMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Handles Paddle Lite OCR model file prep and predictor initialization.
 */
object PaddleOcrManager {
    private const val TAG = "PaddleOcrManager"
    private const val DET_MODEL = "detection_model.nb"
    private const val REC_MODEL = "recognition_model.nb"
    private const val CLS_MODEL = "classification_model.nb"
    private const val CLS_MODEL_LEGACY = "classifcation_model.nb"
    private const val OCR_KEYS = "keys.txt"
    private const val OCR_CONFIG = "config.txt"

    @Volatile
    private var initialized = false

    var detPredictor: PaddlePredictor? = null
        private set
    var clsPredictor: PaddlePredictor? = null
        private set
    var recPredictor: PaddlePredictor? = null
        private set

    @Synchronized
    fun init(context: Context, threads: Int = 4) {
        if (initialized) return

        try {
            System.loadLibrary("paddle_light_api_shared")
            System.loadLibrary("paddle_lite_jni")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load Paddle native libraries", e)
            return
        }

        val appContext = context.applicationContext
        val modelDir = appContext.filesDir

        val clsAssetName = if (assetExists(appContext, CLS_MODEL)) CLS_MODEL else CLS_MODEL_LEGACY
        val requiredAssets = listOf(DET_MODEL, REC_MODEL, clsAssetName, OCR_KEYS, OCR_CONFIG)

        requiredAssets.forEach { assetName ->
            if (!copyAssetIfMissing(appContext, assetName)) {
                Log.e(TAG, "Failed to prepare asset: $assetName")
                return
            }
        }

        val detPath = File(modelDir, DET_MODEL).absolutePath
        val recPath = File(modelDir, REC_MODEL).absolutePath
        val clsPath = File(modelDir, clsAssetName).absolutePath

        detPredictor = createPredictor(detPath, threads)
        clsPredictor = createPredictor(clsPath, threads)
        recPredictor = createPredictor(recPath, threads)

        initialized = detPredictor != null && clsPredictor != null && recPredictor != null
        Log.i(TAG, "Paddle OCR initialized: $initialized")
    }

    fun isInitialized(): Boolean = initialized

    @Synchronized
    fun release() {
        detPredictor = null
        clsPredictor = null
        recPredictor = null
        initialized = false
    }

    private fun createPredictor(modelPath: String, threads: Int): PaddlePredictor? {
        return try {
            val config = MobileConfig().apply {
                setModelFromFile(modelPath)
                setPowerMode(PowerMode.LITE_POWER_HIGH)
                setThreads(threads)
            }
            PaddlePredictor.createPaddlePredictor(config)
        } catch (e: Exception) {
            Log.e(TAG, "Predictor init failed for model: $modelPath", e)
            null
        }
    }

    private fun assetExists(context: Context, assetName: String): Boolean {
        return try {
            context.assets.open(assetName).close()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun copyAssetIfMissing(context: Context, assetName: String): Boolean {
        val targetFile = File(context.filesDir, assetName)
        if (targetFile.exists()) return true

        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Asset copy failed: $assetName", e)
            false
        }
    }
}

