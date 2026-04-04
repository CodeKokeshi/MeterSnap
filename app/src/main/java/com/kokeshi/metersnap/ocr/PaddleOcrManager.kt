package com.kokeshi.metersnap.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Handles Paddle Lite OCR model prep and predictor initialization via reflection.
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
    @Volatile
    private var lastStatus = "IDLE"

    private var recPredictor: Any? = null
    private var recLabels: MutableList<String> = mutableListOf()

    @Synchronized
    fun init(context: Context, threads: Int = 4) {
        if (initialized) return
        lastStatus = "INIT_START"

        try {
            System.loadLibrary("paddle_light_api_shared")
            System.loadLibrary("paddle_lite_jni")
        } catch (e: UnsatisfiedLinkError) {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "NA"
            lastStatus = "INIT_LIB_FAIL_$abi"
            Log.e(TAG, "Failed to load Paddle native libraries", e)
            return
        }

        val appContext = context.applicationContext
        val filesDir = appContext.filesDir
        val clsAsset = if (assetExists(appContext, CLS_MODEL)) CLS_MODEL else CLS_MODEL_LEGACY

        val assets = arrayOf(DET_MODEL, REC_MODEL, clsAsset, OCR_KEYS, OCR_CONFIG)
        for (asset in assets) {
            val forceRefresh = true
            if (!copyAsset(appContext, asset, forceRefresh)) {
                lastStatus = "INIT_ASSET_FAIL"
                Log.e(TAG, "Failed to prepare asset: $asset")
                return
            }
        }

        val recPath = File(filesDir, REC_MODEL).absolutePath
        recPredictor = createPredictor(recPath, threads)
        recLabels = loadRecLabels(appContext)

        initialized = recPredictor != null
        lastStatus = if (initialized) "INIT_OK_L${recLabels.size}" else "INIT_PRED_FAIL"
        Log.i(TAG, "Paddle OCR initialized: $initialized, labels=${recLabels.size}")
    }

    fun isInitialized(): Boolean = initialized

    fun getDebugStatus(): String = lastStatus

    fun runRecognition(bitmap: Bitmap): String {
        if (!initialized) {
            lastStatus = "RUN_NO_INIT"
            return ""
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            lastStatus = "RUN_BAD_INPUT"
            return ""
        }

        val predictor = recPredictor ?: return ""
        return try {
            val out = PaddleRuntimeBridge.runRecognition(predictor, bitmap, recLabels)
            lastStatus = if (out.isBlank()) "RUN_EMPTY" else "RUN_OK_${out.length}"
            out
        } catch (e: Exception) {
            val kind = e::class.java.simpleName.take(10)
            lastStatus = "RUN_ERR_$kind"
            Log.d(TAG, "runRecognition failed", e)
            ""
        }
    }

    private fun createPredictor(modelPath: String, threads: Int): Any? {
        return PaddleRuntimeBridge.createPredictor(modelPath, threads)
    }

    private fun loadRecLabels(context: Context): MutableList<String> {
        val labels = mutableListOf<String>()
        try {
            context.assets.open(OCR_KEYS).bufferedReader().use { reader ->
                val rawText = reader.readText()
                val lines = rawText
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (lines.size == 1 && lines.first().length > 1) {
                    lines.first().forEach { ch ->
                        if (ch.isDigit() || ch == '.') {
                            labels.add(ch.toString())
                        }
                    }
                } else {
                    for (line in lines) {
                        if (line.length == 1) {
                            labels.add(line)
                        } else {
                            line.forEach { ch ->
                                if (ch.isDigit() || ch == '.') {
                                    labels.add(ch.toString())
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not load OCR keys table", e)
        }

        if (labels.isEmpty()) {
            for (digit in '0'..'9') labels.add(digit.toString())
            labels.add(".")
        }

        return labels
    }


    private fun assetExists(context: Context, assetName: String): Boolean {
        return try {
            context.assets.open(assetName).close()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun copyAsset(context: Context, assetName: String, overwrite: Boolean): Boolean {
        val target = File(context.filesDir, assetName)
        if (target.exists() && !overwrite) return true

        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(target).use { output ->
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
