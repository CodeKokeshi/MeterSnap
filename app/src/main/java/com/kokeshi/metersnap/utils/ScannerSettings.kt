package com.kokeshi.metersnap.utils

import android.content.Context

/**
 * Central store for all runtime-tweakable scanner parameters.
 * Values are persisted in SharedPreferences and read at init/call time,
 * so a full app restart is required after changes.
 */
object ScannerSettings {

    private const val PREFS_NAME = "scanner_settings"
    private const val LEGACY_DEFAULT_MODEL_FILE = "models/best_nano_float16.tflite"

    // ── Model ─────────────────────────────────────────────────────────────
    const val KEY_MODEL_FILE = "model_file"
    const val DEFAULT_MODEL_FILE = "models/best_nano_v2_float32.tflite"

    val AVAILABLE_MODELS = listOf(
        "models/best_nano_float16.tflite",
        "models/best_nano_float32.tflite",
        "models/best_nano_v2_float16.tflite",
        "models/best_nano_v2_float32.tflite",
        "models/best_small_float16.tflite",
        "models/best_small_float32.tflite",
    )

    /** Human-readable display names matching [AVAILABLE_MODELS] index-for-index. */
    val MODEL_DISPLAY_NAMES = listOf(
        "Nano Float 16",
        "Nano Float 32",
        "Nano v2 Float 16",
        "Nano v2 Float 32",
        "Small Float 16",
        "Small Float 32",
    )

    fun displayNameForModel(modelFile: String): String {
        val idx = AVAILABLE_MODELS.indexOf(modelFile)
        return if (idx >= 0) MODEL_DISPLAY_NAMES[idx] else modelFile
    }

    // ── Inference ─────────────────────────────────────────────────────────
    const val KEY_INFERENCE_THREADS = "inference_threads"
    const val DEFAULT_INFERENCE_THREADS = 4

    // ── Detection thresholds ──────────────────────────────────────────────
    const val KEY_CONF_THRESHOLD = "conf_threshold"
    const val DEFAULT_CONF_THRESHOLD = 0.25f

    const val KEY_IOU_THRESHOLD = "iou_threshold"
    const val DEFAULT_IOU_THRESHOLD = 0.45f

    const val KEY_MAX_DETECTIONS = "max_detections"
    const val DEFAULT_MAX_DETECTIONS = 20

    const val KEY_MIN_BOX_SIZE_PX = "min_box_size_px"
    const val DEFAULT_MIN_BOX_SIZE_PX = 8f

    // ── Window scan ───────────────────────────────────────────────────────
    const val KEY_MAX_WINDOWS = "max_windows"
    const val DEFAULT_MAX_WINDOWS = 8

    const val KEY_PARALLEL_WINDOWS = "parallel_windows"
    const val DEFAULT_PARALLEL_WINDOWS = 3

    // ─────────────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getModelFile(context: Context): String {
        val savedModel = prefs(context).getString(KEY_MODEL_FILE, DEFAULT_MODEL_FILE) ?: DEFAULT_MODEL_FILE
        val normalized = if (savedModel == LEGACY_DEFAULT_MODEL_FILE) DEFAULT_MODEL_FILE else savedModel
        return if (normalized in AVAILABLE_MODELS) normalized else DEFAULT_MODEL_FILE
    }

    fun saveModelFile(context: Context, modelFile: String) {
        prefs(context).edit()
            .putString(KEY_MODEL_FILE, modelFile)
            .apply()
    }

    fun getInferenceThreads(context: Context): Int =
        prefs(context).getInt(KEY_INFERENCE_THREADS, DEFAULT_INFERENCE_THREADS)

    fun getConfThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_CONF_THRESHOLD, DEFAULT_CONF_THRESHOLD)

    fun getIouThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_IOU_THRESHOLD, DEFAULT_IOU_THRESHOLD)

    fun getMaxDetections(context: Context): Int =
        prefs(context).getInt(KEY_MAX_DETECTIONS, DEFAULT_MAX_DETECTIONS)

    fun getMinBoxSizePx(context: Context): Float =
        prefs(context).getFloat(KEY_MIN_BOX_SIZE_PX, DEFAULT_MIN_BOX_SIZE_PX)

    fun getMaxWindows(context: Context): Int =
        prefs(context).getInt(KEY_MAX_WINDOWS, DEFAULT_MAX_WINDOWS)

    fun getParallelWindows(context: Context): Int =
        prefs(context).getInt(KEY_PARALLEL_WINDOWS, DEFAULT_PARALLEL_WINDOWS)

    fun saveAll(
        context: Context,
        modelFile: String,
        inferenceThreads: Int,
        confThreshold: Float,
        iouThreshold: Float,
        maxDetections: Int,
        minBoxSizePx: Float,
        maxWindows: Int,
        parallelWindows: Int
    ) {
        prefs(context).edit()
            .putString(KEY_MODEL_FILE, modelFile)
            .putInt(KEY_INFERENCE_THREADS, inferenceThreads)
            .putFloat(KEY_CONF_THRESHOLD, confThreshold)
            .putFloat(KEY_IOU_THRESHOLD, iouThreshold)
            .putInt(KEY_MAX_DETECTIONS, maxDetections)
            .putFloat(KEY_MIN_BOX_SIZE_PX, minBoxSizePx)
            .putInt(KEY_MAX_WINDOWS, maxWindows)
            .putInt(KEY_PARALLEL_WINDOWS, parallelWindows)
            .apply()
    }

    fun resetToDefaults(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
