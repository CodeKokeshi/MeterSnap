package com.kokeshi.metersnap.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class WindowSpec(
    val x: Int,
    val y: Int,
    val size: Int
) {
    val centerX: Float get() = x + (size / 2f)
    val centerY: Float get() = y + (size / 2f)
}

data class SnapDetection(
    val classId: Int,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class WindowReading(
    val readingText: String,
    val isComplete: Boolean,
    val hasUnreadable: Boolean,
    val confidence: Float,
    val slotCount: Int,
    val windowIndex: Int,
    val window: WindowSpec,
    val strip: SnapDetection?,
    val acceptedDigits: List<SnapDetection>
)

data class LegacySnapReadingResult(
    val readingText: String,
    val isComplete: Boolean,
    val debugText: String,
    val windowsProcessed: Int
)

sealed class SnapProcessingOutcome {
    data class Success(val reading: String) : SnapProcessingOutcome()
    /**
     * @param partialReading Best partial reading found (may contain '?' or 'X' slots).
     *                       Null when nothing at all was detected.
     */
    data class Invalid(val reason: String, val partialReading: String? = null) : SnapProcessingOutcome()
    data object Canceled : SnapProcessingOutcome()
    data object TimedOut : SnapProcessingOutcome()
}

object MeterReadingSnapProcessor {
    private const val TAG = "MeterReadingSnap"
    private const val MAX_WINDOWS = 24
    private const val SLOT_COUNT = 5

    /**
     * Zoom fractions used for window tiling (hardcoded — not exposed to the UI).
     * Full image first, then a 75% center crop (the main workhorse), then 55% crops for
     * finer coverage. Lower values = smaller crop = model sees more magnified view.
     */
    private val ZOOM_LEVELS = floatArrayOf(1.00f, 0.75f, 0.55f)

    fun process(
        context: Context,
        source: Bitmap,
        workerExecutor: ExecutorService,
        deadlineElapsedMs: Long,
        shouldCancel: () -> Boolean
    ): SnapProcessingOutcome {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            return SnapProcessingOutcome.Invalid("Captured frame was unavailable")
        }

        if (!AlgorithmicDigitDetector.isInitialized()) {
            AlgorithmicDigitDetector.init(context)
        }
        if (!AlgorithmicDigitDetector.isInitialized()) {
            return SnapProcessingOutcome.Invalid(
                "Detector init failed: ${AlgorithmicDigitDetector.getDebugStatus()}"
            )
        }

        // Read all tunable params from ScannerSettings
        val confThreshold  = com.kokeshi.metersnap.utils.ScannerSettings.getConfThreshold(context)
        val iouThreshold   = com.kokeshi.metersnap.utils.ScannerSettings.getIouThreshold(context)
        val maxDetections  = com.kokeshi.metersnap.utils.ScannerSettings.getMaxDetections(context)
        val minBoxSizePx   = com.kokeshi.metersnap.utils.ScannerSettings.getMinBoxSizePx(context)
        val maxWindowCount = com.kokeshi.metersnap.utils.ScannerSettings.getMaxWindows(context)
        val parallelCount  = com.kokeshi.metersnap.utils.ScannerSettings.getParallelWindows(context)

        val canceled = AtomicBoolean(false)

        fun isExpired(): Boolean = SystemClock.elapsedRealtime() >= deadlineElapsedMs
        fun isStopped(): Boolean = canceled.get() || shouldCancel() || isExpired()

        if (isStopped()) {
            return if (shouldCancel()) SnapProcessingOutcome.Canceled else SnapProcessingOutcome.TimedOut
        }

        val windows = buildWindows(source.width, source.height, maxWindowCount)
            .mapIndexedNotNull { index, window ->
                clampWindowToBitmap(window, source.width, source.height)?.let { index to it }
            }

        if (windows.isEmpty()) return SnapProcessingOutcome.Invalid("No valid scan windows")

        val completionService = ExecutorCompletionService<WindowReading?>(workerExecutor)
        val submitted = ArrayList<Future<WindowReading?>>()
        val concurrency = parallelCount.coerceIn(1, 8)
        var nextWindowCursor = 0
        var inFlight = 0
        var bestReading: WindowReading? = null
        var sawStrip = false

        fun submitNextWindow() {
            if (nextWindowCursor >= windows.size || isStopped()) return
            val (windowIndex, safeWindow) = windows[nextWindowCursor++]
            val future = completionService.submit(Callable {
                if (isStopped()) return@Callable null

                val cropped = try {
                    Bitmap.createBitmap(source, safeWindow.x, safeWindow.y, safeWindow.size, safeWindow.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Window crop failed for $safeWindow", e)
                    return@Callable null
                }

                try {
                    if (isStopped()) return@Callable null

                    val prepared = prepareWindowBitmap(cropped)
                    try {
                        if (isStopped()) return@Callable null

                        val detections = AlgorithmicDigitDetector.runPreparedDetection(
                            preparedBitmap = prepared,
                            confThreshold = confThreshold,
                            iouThreshold = iouThreshold,
                            maxDetections = maxDetections,
                            minBoxSizePx = minBoxSizePx,
                            classAgnosticNms = false
                        )
                        if (detections.any { it.classId == AlgorithmicDigitDetector.STRIP_CLASS_ID }) {
                            sawStrip = true
                        }

                        val remapped = detections.map { d ->
                            remapDetection(
                                d.classId,
                                d.score,
                                d.box.left,
                                d.box.top,
                                d.box.right,
                                d.box.bottom,
                                safeWindow
                            )
                        }
                        assembleWindowReading(remapped, safeWindow, windowIndex)
                    } finally {
                        prepared.recycle()
                    }
                } finally {
                    cropped.recycle()
                }
            })
            submitted += future
            inFlight += 1
        }

        repeat(min(concurrency, windows.size)) {
            submitNextWindow()
        }

        try {
            while (inFlight > 0) {
                if (shouldCancel()) {
                    canceled.set(true)
                    return SnapProcessingOutcome.Canceled
                }
                if (isExpired()) {
                    canceled.set(true)
                    return SnapProcessingOutcome.TimedOut
                }

                val remainingMs = (deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                val completed = completionService.poll(remainingMs, TimeUnit.MILLISECONDS)
                    ?: return SnapProcessingOutcome.TimedOut
                inFlight -= 1

                val reading = try {
                    completed.get()
                } catch (_: CancellationException) {
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Window processing failed", e)
                    null
                }

                if (reading != null) {
                    if (bestReading == null || scoreWindowReading(reading) > scoreWindowReading(bestReading!!)) {
                        bestReading = reading
                    }

                    if (reading.isComplete && !reading.hasUnreadable && !reading.readingText.contains('?')) {
                        canceled.set(true)
                        return SnapProcessingOutcome.Success(reading.readingText)
                    }
                }

                while (inFlight < concurrency && nextWindowCursor < windows.size && !isStopped()) {
                    submitNextWindow()
                }
            }
        } finally {
            canceled.set(true)
            submitted.forEach { it.cancel(true) }
        }

        if (shouldCancel()) return SnapProcessingOutcome.Canceled
        if (isExpired()) return SnapProcessingOutcome.TimedOut

        return when {
            bestReading == null && !sawStrip -> SnapProcessingOutcome.Invalid("No strip found")
            bestReading == null               -> SnapProcessingOutcome.Invalid("No readable digits found")
            !bestReading.isComplete           -> SnapProcessingOutcome.Invalid(
                "Reading incomplete", bestReading.readingText
            )
            bestReading.hasUnreadable         -> SnapProcessingOutcome.Invalid(
                "Reading contains unreadable digits", bestReading.readingText
            )
            bestReading.readingText.contains('?') -> SnapProcessingOutcome.Invalid(
                "Reading incomplete", bestReading.readingText
            )
            else -> SnapProcessingOutcome.Success(bestReading.readingText)
        }
    }

    fun process(
        context: Context,
        source: Bitmap
    ): LegacySnapReadingResult {
        val workerExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
        return try {
            when (
                val outcome = process(
                    context = context,
                    source = source,
                    workerExecutor = workerExecutor,
                    deadlineElapsedMs = SystemClock.elapsedRealtime() + 3_000L,
                    shouldCancel = { false }
                )
            ) {
                is SnapProcessingOutcome.Success -> LegacySnapReadingResult(
                    readingText = outcome.reading,
                    isComplete = true,
                    debugText = "success",
                    windowsProcessed = 0
                )
                is SnapProcessingOutcome.Invalid -> LegacySnapReadingResult(
                    readingText = "?????",
                    isComplete = false,
                    debugText = outcome.reason,
                    windowsProcessed = 0
                )
                SnapProcessingOutcome.Canceled -> LegacySnapReadingResult(
                    readingText = "?????",
                    isComplete = false,
                    debugText = "canceled",
                    windowsProcessed = 0
                )
                SnapProcessingOutcome.TimedOut -> LegacySnapReadingResult(
                    readingText = "?????",
                    isComplete = false,
                    debugText = "timed_out",
                    windowsProcessed = 0
                )
            }
        } finally {
            workerExecutor.shutdownNow()
        }
    }

    /**
     * Runs a single inference pass on [window] from [source] synchronously on the calling thread.
     * Used by the Phase 1 full-guidebox fast path before the parallel windowing scan starts.
     */
    private fun runSingleWindowSync(source: Bitmap, window: WindowSpec, windowIndex: Int): WindowReading? {
        val cropped = try {
            Bitmap.createBitmap(source, window.x, window.y, window.size, window.size)
        } catch (e: Exception) {
            Log.e(TAG, "Phase 1 full-guidebox crop failed", e)
            return null
        }
        return try {
            val prepared = prepareWindowBitmap(cropped)
            try {
                val detections = AlgorithmicDigitDetector.runPreparedDetection(
                    preparedBitmap = prepared,
                    classAgnosticNms = false
                )
                val remapped = detections.map { d ->
                    remapDetection(
                        d.classId, d.score,
                        d.box.left, d.box.top, d.box.right, d.box.bottom,
                        window
                    )
                }
                assembleWindowReading(remapped, window, windowIndex)
            } finally {
                prepared.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Phase 1 full-guidebox inference failed", e)
            null
        } finally {
            cropped.recycle()
        }
    }

    private fun clampWindowToBitmap(window: WindowSpec, bitmapWidth: Int, bitmapHeight: Int): WindowSpec? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val maxSize = min(bitmapWidth, bitmapHeight)
        if (maxSize <= 0) return null
        val size = window.size.coerceAtMost(maxSize).coerceAtLeast(1)
        val x = window.x.coerceIn(0, bitmapWidth - size)
        val y = window.y.coerceIn(0, bitmapHeight - size)
        if (x + size > bitmapWidth || y + size > bitmapHeight) return null
        return WindowSpec(x = x, y = y, size = size)
    }

    /**
     * Builds the window list using a center-biased zoom approach:
     *
     * For each zoom level a center-aligned crop is generated first, then a sliding grid
     * of crops at that same scale. All unique windows are sorted by distance from the image
     * center, so the most likely position (centered meter) is always tried first and triggers
     * an early exit. Corner/edge coverage is still guaranteed within [maxWindows].
     *
     * [ZOOM_LEVELS] are hardcoded — not exposed to the UI — so the behavior is predictable.
     */
    internal fun buildWindows(
        width: Int,
        height: Int,
        maxWindows: Int = MAX_WINDOWS
    ): List<WindowSpec> {
        require(width > 0 && height > 0) { "Image size must be positive" }

        val shortSide = min(width, height).toFloat()
        val imageCenterX = width / 2f
        val imageCenterY = height / 2f
        val all = mutableListOf<WindowSpec>()

        for (zoom in ZOOM_LEVELS) {
            val size = max(48, (shortSide * zoom).roundToInt())
            val maxX = max(0, width - size)
            val maxY = max(0, height - size)
            val step = max(1, (size * 0.35f).roundToInt())

            // Center-aligned crop for this zoom level
            val cx = ((width - size) / 2).coerceIn(0, maxX)
            val cy = ((height - size) / 2).coerceIn(0, maxY)
            all += WindowSpec(cx, cy, size)

            // Grid of crops at this scale for full coverage
            for (y in computeOrigins(maxY, step)) {
                for (x in computeOrigins(maxX, step)) {
                    all += WindowSpec(x, y, size)
                }
            }
        }

        val unique = all.distinctBy { "${it.x}:${it.y}:${it.size}" }
        val ordered = unique.sortedBy { distance(it.centerX, it.centerY, imageCenterX, imageCenterY) }
        if (ordered.size <= maxWindows) return ordered

        // Always keep at least one window near each corner so off-center meters are covered
        val corners = listOf(
            0f to 0f,
            width.toFloat() to 0f,
            0f to height.toFloat(),
            width.toFloat() to height.toFloat()
        )
        val anchors = corners.mapNotNull { (cx, cy) ->
            ordered.minByOrNull { distance(it.centerX, it.centerY, cx, cy) }
        }.distinct()

        val selected = LinkedHashSet<WindowSpec>().apply { addAll(anchors) }
        for (w in ordered) {
            if (selected.size >= maxWindows) break
            selected += w
        }
        return selected
            .sortedBy { distance(it.centerX, it.centerY, imageCenterX, imageCenterY) }
            .take(maxWindows)
    }

    private fun computeOrigins(maxOrigin: Int, step: Int): List<Int> {
        if (maxOrigin <= 0) return listOf(0)
        val values = mutableListOf<Int>()
        var current = 0
        while (current < maxOrigin) {
            values += current
            current += step
        }
        values += maxOrigin
        return values.distinct().sorted()
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot(abs(x1 - x2), abs(y1 - y2))

    internal fun assembleWindowReading(
        detections: List<SnapDetection>,
        window: WindowSpec,
        windowIndex: Int
    ): WindowReading? {
        val strip = detections
            .filter { it.classId == AlgorithmicDigitDetector.STRIP_CLASS_ID }
            .maxByOrNull { it.score } ?: return null

        val eligibleDigits = detections
            .filter { it.classId in AlgorithmicDigitDetector.DIGIT_ZERO_CLASS_ID..AlgorithmicDigitDetector.UNREADABLE_CLASS_ID }
            .filter { overlapsOrAligns(strip, it) }
            .sortedBy { it.centerX }

        if (eligibleDigits.isEmpty()) {
            return WindowReading(
                readingText = "?????",
                isComplete = false,
                hasUnreadable = false,
                confidence = strip.score,
                slotCount = 0,
                windowIndex = windowIndex,
                window = window,
                strip = strip,
                acceptedDigits = emptyList()
            )
        }

        val slots = arrayOfNulls<SnapDetection>(SLOT_COUNT)
        val slotWidth = strip.width / SLOT_COUNT.toFloat().coerceAtLeast(1f)
        for (digit in eligibleDigits) {
            val slotIndex = (((digit.centerX - strip.left) / slotWidth).toInt()).coerceIn(0, SLOT_COUNT - 1)
            val current = slots[slotIndex]
            if (current == null || digit.score > current.score) {
                slots[slotIndex] = digit
            }
        }

        val reading = buildString(SLOT_COUNT) {
            for (slot in slots) {
                append(
                    when {
                        slot == null -> '?'
                        slot.classId == AlgorithmicDigitDetector.UNREADABLE_CLASS_ID -> 'X'
                        slot.classId in AlgorithmicDigitDetector.DIGIT_ZERO_CLASS_ID..AlgorithmicDigitDetector.DIGIT_NINE_CLASS_ID ->
                            ('0'.code + (slot.classId - AlgorithmicDigitDetector.DIGIT_ZERO_CLASS_ID)).toChar()
                        else -> '?'
                    }
                )
            }
        }

        val accepted = slots.filterNotNull()
        return WindowReading(
            readingText = reading,
            isComplete = accepted.size == SLOT_COUNT && !reading.contains('?'),
            hasUnreadable = accepted.any { it.classId == AlgorithmicDigitDetector.UNREADABLE_CLASS_ID },
            confidence = strip.score + accepted.sumOf { it.score.toDouble() }.toFloat(),
            slotCount = accepted.size,
            windowIndex = windowIndex,
            window = window,
            strip = strip,
            acceptedDigits = accepted
        )
    }

    internal fun scoreWindowReading(reading: WindowReading): Float {
        val numericSlots = reading.readingText.count { it.isDigit() }
        val missingSlots = reading.readingText.count { it == '?' }
        val unreadableSlots = reading.readingText.count { it == 'X' }
        var score = reading.confidence
        score += numericSlots * 100f
        score += reading.slotCount * 40f
        score -= missingSlots * 35f
        score -= unreadableSlots * 20f
        if (reading.isComplete && !reading.hasUnreadable && !reading.readingText.contains('?')) {
            score += 1000f
        }
        return score
    }

    private fun remapDetection(
        classId: Int,
        score: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        window: WindowSpec
    ): SnapDetection {
        val scale = window.size / AlgorithmicDigitDetector.INPUT_SIZE.toFloat()
        return SnapDetection(
            classId,
            score,
            window.x + left * scale,
            window.y + top * scale,
            window.x + right * scale,
            window.y + bottom * scale
        )
    }

    private fun overlapsOrAligns(strip: SnapDetection, digit: SnapDetection): Boolean {
        val hPad = strip.width * 0.05f
        val vPad = strip.height * 0.35f
        val overlapsX = digit.right >= strip.left && digit.left <= strip.right
        val centerWithinStrip = digit.centerX in (strip.left - hPad)..(strip.right + hPad)
        val centerWithinBand = digit.centerY in (strip.top - vPad)..(strip.bottom + vPad)
        return (overlapsX || centerWithinStrip) && centerWithinBand
    }

    private fun prepareWindowBitmap(source: Bitmap): Bitmap {
        return try {
            if (source.width == AlgorithmicDigitDetector.INPUT_SIZE &&
                source.height == AlgorithmicDigitDetector.INPUT_SIZE
            ) {
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(
                    source,
                    AlgorithmicDigitDetector.INPUT_SIZE,
                    AlgorithmicDigitDetector.INPUT_SIZE,
                    source.width < AlgorithmicDigitDetector.INPUT_SIZE
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preprocess window bitmap", e)
            Bitmap.createScaledBitmap(source, AlgorithmicDigitDetector.INPUT_SIZE, AlgorithmicDigitDetector.INPUT_SIZE, true)
        }
    }

}
