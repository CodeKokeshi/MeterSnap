package com.kokeshi.metersnap

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.CvType
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import com.kokeshi.metersnap.utils.ReadingValidator

/**
 * Result from the MeterAnalyzer containing detection info and validation status.
 */
data class AnalyzerResult(
    val reading: String,
    val confidence: Float,
    val allRects: List<Rect>,
    val acceptedRects: List<Rect>,
    val imageWidth: Int,
    val imageHeight: Int,
    val source: String,  // "L" = LeNet, "M" = MLKit, "H" = Hybrid, "V" = Voted
    val validationResult: ReadingValidator.ValidationResult?,
    val shouldSuggestManualInput: Boolean = false
)

class MeterAnalyzer(
    private val context: Context,
    private val classifier: DigitClassifier,
    private var previousReading: Int = 0,
    private val onResult: (AnalyzerResult) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedTimestamp = 0L
    private val interval = 50L // Super fast (20fps) for snappier feel
    private val dateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private var debugCounter = 0
    private val debugFolderName = "MeterSnapDigits"
    private val debugRelativePath = "Pictures/$debugFolderName"

    // Voting Buffer
    private val resultHistory = ArrayDeque<String>()
    private val historySize = 5 // Reduced from 10 for faster consensus
    private var consecutiveFailures = 0 // Track consecutive failures for ML Kit trigger
    
    // ML Kit Fallback
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mlKitExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var mlKitInProgress = false
    private var cachedMLKitResult = ""
    private var cachedMLKitRects = ArrayList<Rect>()
    private var firstGlimpseTime = 0L
    
    // MSER Instance (Reused to avoid memory leaks)
    private val mser = org.opencv.features2d.MSER.create(
        4,      // delta: Reduced to 4 to catch lower contrast red digits
        60,     // min_area
        14400,  // max_area
        0.5,    // max_variation: Increased to 0.5 to allow more variation (rolling digits)
        0.2,    // min_diversity
        200,    // max_evolution
        1.01,   // area_threshold
        0.003,  // min_margin
        5       // edge_blur_size
    )
    
    // === DECISION ALGORITHM STATE ===
    // Failure tracker for suggesting manual input
    private val failureTracker = ReadingValidator.FailureTracker()
    
    // Cached LeNet result for voting
    private var cachedLenetResult = ""
    
    // Cached Hybrid result (MLKit localization + LeNet classification)
    private var cachedHybridResult = ""
    
    // === SMART CONFIDENCE SYSTEM ===
    // The "locked in" reading we're confident about (normalized)
    private var lockedReading = ""
    
    // How many consecutive frames we've seen the locked reading
    private var lockedFrameCount = 0
    
    // The current confidence level (0-100) - with momentum
    private var currentConfidence = 0f
    
    // Timestamp when we LAST saw the locked reading (for timeout)
    private var lastSeenLockedTime = 0L
    
    // How many frames since we last saw ANY valid reading
    private var framesSinceValidReading = 0
    
    // Confidence thresholds
    companion object {
        // Once we hit this confidence, we "lock in" and resist changes
        const val LOCK_IN_THRESHOLD = 80f
        
        // Minimum frames needed before we consider locking
        const val MIN_FRAMES_TO_LOCK = 3
        
        // How fast confidence grows per consistent frame (additive)
        const val CONFIDENCE_GROWTH_PER_FRAME = 15f  // Faster growth!
        
        // How fast confidence decays when we see different readings (multiplicative)
        const val CONFIDENCE_DECAY_FACTOR = 0.5f  // Faster decay!
        
        // How fast confidence decays when we see NOTHING
        const val NO_DETECTION_DECAY = 0.4f  // Very fast decay when nothing detected!
        
        // Minimum confidence floor when locked (doesn't drop below this easily)
        const val LOCKED_CONFIDENCE_FLOOR = 50f
        
        // Maximum time (ms) to hold a lock WITHOUT seeing the reading
        const val MAX_LOCK_HOLD_TIME = 500L  // Very short! Release fast
        
        // Frames of nothing before we completely release the lock
        const val FRAMES_TO_RELEASE_LOCK = 4
    }

    var captureDebugNextFrame = false
    
    /**
     * Update the previous reading used for validation.
     * Call this when the user confirms a reading or updates settings.
     */
    fun setPreviousReading(reading: Int) {
        previousReading = reading
        Log.d("MeterAnalyzer", "Previous reading updated to: $reading")
    }

    fun reset() {
        resultHistory.clear()
        consecutiveFailures = 0
        firstGlimpseTime = 0L
        cachedMLKitResult = ""
        cachedMLKitRects.clear()
        cachedLenetResult = ""
        cachedHybridResult = ""
        failureTracker.reset()
        // Reset smart confidence
        lockedReading = ""
        lockedFrameCount = 0
        currentConfidence = 0f
        lastSeenLockedTime = 0L
        framesSinceValidReading = 0
    }
    
    /**
     * Call this when we detect NOTHING valid this frame.
     * Rapidly decays confidence and releases lock if sustained.
     */
    private fun handleNoDetection() {
        framesSinceValidReading++
        
        // Rapid confidence decay when seeing nothing
        currentConfidence *= NO_DETECTION_DECAY
        
        // If we've seen nothing for too long, release the lock entirely
        if (framesSinceValidReading >= FRAMES_TO_RELEASE_LOCK) {
            if (lockedReading.isNotEmpty()) {
                Log.d("MeterAnalyzer", "Releasing lock on '$lockedReading' - no valid detection for $framesSinceValidReading frames")
            }
            lockedReading = ""
            lockedFrameCount = 0
            currentConfidence = 0f
            resultHistory.clear()
        }
        
        // Also check time-based timeout
        val now = System.currentTimeMillis()
        if (lockedReading.isNotEmpty() && (now - lastSeenLockedTime) > MAX_LOCK_HOLD_TIME) {
            Log.d("MeterAnalyzer", "Lock timeout on '$lockedReading' - not seen for ${now - lastSeenLockedTime}ms")
            lockedReading = ""
            lockedFrameCount = 0
            currentConfidence = 0f
        }
    }
    
    /**
     * Smart confidence calculation that considers:
     * 1. How many frames we've seen this reading (temporal persistence)
     * 2. Previous confidence level (momentum)
     * 3. Lock-in behavior (resistance to noise once confident)
     * 4. FAST RELEASE when seeing nothing or different reading
     * 
     * @param detectedReading The normalized reading from this frame
     * @param historyAgreement What percentage of history buffer agrees (0-100)
     * @return The smart confidence level (0-100)
     */
    private fun calculateSmartConfidence(detectedReading: String, historyAgreement: Float): Float {
        val normalizedDetected = ReadingValidator.normalizeReading(detectedReading)
        val normalizedLocked = ReadingValidator.normalizeReading(lockedReading)
        val now = System.currentTimeMillis()
        
        // Reset the "no valid reading" counter since we have something
        framesSinceValidReading = 0
        
        // Case 1: Same reading as our lock (or we have no lock yet)
        if (normalizedDetected == normalizedLocked || lockedReading.isEmpty()) {
            if (lockedReading.isEmpty() && normalizedDetected.isNotEmpty()) {
                // First time seeing a valid reading - start tracking
                lockedReading = detectedReading
                lastSeenLockedTime = now
                lockedFrameCount = 1
                currentConfidence = Math.max(historyAgreement, 40f) // Start at 40% minimum
            } else if (normalizedDetected == normalizedLocked) {
                // Same reading! Increase confidence FAST
                lockedFrameCount++
                lastSeenLockedTime = now  // Update last seen time!
                
                // Confidence grows quickly
                val growth = CONFIDENCE_GROWTH_PER_FRAME * (1f - currentConfidence / 120f)
                currentConfidence = Math.min(100f, currentConfidence + growth)
                
                // History agreement boost
                if (historyAgreement > currentConfidence) {
                    currentConfidence = (currentConfidence * 0.6f + historyAgreement * 0.4f)
                }
            }
        }
        // Case 2: DIFFERENT reading detected
        else {
            val timeSinceSeen = now - lastSeenLockedTime
            
            // If we're locked in AND we just saw it recently, resist briefly
            if (currentConfidence >= LOCK_IN_THRESHOLD && 
                lockedFrameCount >= MIN_FRAMES_TO_LOCK &&
                timeSinceSeen < MAX_LOCK_HOLD_TIME) {
                // Decay confidence but hold the lock
                currentConfidence = Math.max(LOCKED_CONFIDENCE_FLOOR, currentConfidence * 0.9f)
                Log.d("MeterAnalyzer", "Lock holding: '$lockedReading' (conf: ${currentConfidence.toInt()}%), saw '$normalizedDetected'")
                return currentConfidence
            }
            
            // Lock expired or not strong enough - SWITCH to new reading
            Log.d("MeterAnalyzer", "Switching: '$lockedReading' -> '$normalizedDetected' (old conf: ${currentConfidence.toInt()}%)")
            
            // Decay the old confidence
            currentConfidence *= CONFIDENCE_DECAY_FACTOR
            
            // Switch to new reading
            lockedReading = detectedReading
            lastSeenLockedTime = now
            lockedFrameCount = 1
            
            // Start the new reading with reasonable confidence
            currentConfidence = Math.max(currentConfidence, Math.max(historyAgreement, 35f))
        }
        
        return currentConfidence
    }
    
    /**
     * Gets the current best reading (might be the locked one, not the latest frame)
     */
    private fun getBestReading(frameReading: String): String {
        val normalizedFrame = ReadingValidator.normalizeReading(frameReading)
        val normalizedLocked = ReadingValidator.normalizeReading(lockedReading)
        val now = System.currentTimeMillis()
        val timeSinceSeen = now - lastSeenLockedTime
        
        // Only use locked reading if:
        // 1. We're confident
        // 2. We've seen it enough times  
        // 3. We saw it RECENTLY (within timeout)
        if (lockedReading.isNotEmpty() && 
            currentConfidence >= LOCK_IN_THRESHOLD && 
            lockedFrameCount >= MIN_FRAMES_TO_LOCK &&
            timeSinceSeen < MAX_LOCK_HOLD_TIME &&
            normalizedFrame != normalizedLocked) {
            
            Log.d("MeterAnalyzer", "Using locked '$lockedReading' instead of frame '$frameReading'")
            return ReadingValidator.formatReading(lockedReading)
        }
        
        return ReadingValidator.formatReading(frameReading)
    }
    
    /**
     * Helper function to emit validated results.
     * This centralizes validation and failure tracking logic.
     */
    private fun emitResult(
        reading: String,
        confidence: Float,
        allRects: List<Rect>,
        acceptedRects: List<Rect>,
        imageWidth: Int,
        imageHeight: Int,
        source: String
    ) {
        // Perform validation if we have a previous reading
        val validationResult = if (reading.isNotEmpty() && previousReading > 0) {
            ReadingValidator.validateReading(reading, previousReading)
        } else {
            null
        }
        
        // Track failures for suggesting manual input
        if (validationResult != null) {
            if (validationResult.isValid) {
                failureTracker.recordSuccess()
            } else {
                failureTracker.recordFailure(validationResult.errorCode)
            }
        } else if (reading.length < 4) {
            failureTracker.recordFailure(ReadingValidator.ErrorCode.TOO_FEW_DIGITS)
        }
        
        // Determine if we should suggest manual input
        val shouldSuggestManual = failureTracker.shouldSuggestManualInput()
        
        // If validation failed but we can auto-correct, use the cleaned reading
        val finalReading = if (validationResult != null && 
                               validationResult.cleanedReading != reading && 
                               validationResult.isValid) {
            Log.d("MeterAnalyzer", "Auto-corrected: $reading → ${validationResult.cleanedReading}")
            validationResult.cleanedReading
        } else {
            reading
        }
        
        // Adjust confidence based on validation
        val finalConfidence = when {
            validationResult == null -> confidence
            validationResult.errorCode == ReadingValidator.ErrorCode.VALID -> confidence
            validationResult.errorCode == ReadingValidator.ErrorCode.HIGH_USAGE_WARNING -> confidence * 0.8f
            validationResult.isValid -> confidence * 0.9f
            else -> confidence * 0.5f // Invalid readings get halved confidence
        }
        
        val result = AnalyzerResult(
            reading = finalReading,
            confidence = finalConfidence,
            allRects = allRects,
            acceptedRects = acceptedRects,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            source = source,
            validationResult = validationResult,
            shouldSuggestManualInput = shouldSuggestManual
        )
        
        onResult(result)
        mlKitInProgress = false
    }

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
            
            val imageWidth = mat.width()
            val imageHeight = mat.height()

            // 2. Preprocessing & ROI (Area Masking)
            // We define a specific "Area Mask" or Region of Interest (ROI) to avoid hallucinations from the background.
            // The visual guide box is roughly 2:1 aspect ratio (300dp x 150dp).
            // We adjust the crop to match this tighter area so we don't scan pipes or other meter parts.
            
            // Height: Reduced from 1/3 (33%) to 1/5 (20%) to match the visual guide box height better.
            val cropHeight = mat.height() / 5
            val cropCenterY = mat.height() * 0.4
            val cropY = (cropCenterY - cropHeight / 2).toInt().coerceAtLeast(0)
            
            // Width: Reduced to 70% to focus more on the center.
            val cropWidth = (mat.width() * 0.70).toInt()
            val cropX = (mat.width() - cropWidth) / 2
            
            val cropRect = Rect(cropX, cropY, cropWidth, cropHeight)
            val croppedMat = Mat(mat, cropRect)

            // --- 0. BACKGROUND ML KIT (Parallel Execution) ---
            // We fire this off every frame (if not busy) to have a "second opinion" ready.
            if (!mlKitInProgress) {
                mlKitInProgress = true
                // Create a copy for ML Kit so we don't block or race on 'mat'
                // We use the CROPPED image for ML Kit to strictly follow the guide box
                val mlKitBitmap = Bitmap.createBitmap(croppedMat.width(), croppedMat.height(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(croppedMat, mlKitBitmap)
                
                // Capture crop offsets for coordinate mapping
                val offsetX = cropX
                val offsetY = cropY

                mlKitExecutor.execute {
                    try {
                        val inputImage = InputImage.fromBitmap(mlKitBitmap, 0)
                        
                        textRecognizer.process(inputImage)
                            .addOnSuccessListener { mlKitResult ->
                                var bestText = ""
                                val newRects = ArrayList<Rect>()
                                
                                // Parse ML Kit result
                                for (block in mlKitResult.textBlocks) {
                                    val text = block.text.replace(" ", "").replace("\n", "")
                                    
                                    // Regex: Look for 4 or 5 consecutive digits
                                    // We allow 4 digits to catch the stable prefix even if the rolling digit is missed.
                                    val match = Regex("\\d{4,5}").find(text)
                                    
                                    if (match != null) {
                                        bestText = match.value
                                        val androidBox = block.boundingBox
                                        if (androidBox != null) {
                                            // Create the "Green Text" slices for visualization
                                            val safeBox = Rect(
                                                Math.max(0, androidBox.left),
                                                Math.max(0, androidBox.top),
                                                Math.min(mlKitBitmap.width - androidBox.left, androidBox.width()),
                                                Math.min(mlKitBitmap.height - androidBox.top, androidBox.height())
                                            )
                                            
                                            // Slice into N equal parts (Trojan Horse Strategy)
                                            val len = bestText.length
                                            val digitWidth = safeBox.width / len
                                            val digitHeight = safeBox.height
                                            
                                            for (i in 0 until len) {
                                                newRects.add(Rect(
                                                    safeBox.x + (i * digitWidth) + offsetX, // Add crop offset
                                                    safeBox.y + offsetY,                   // Add crop offset
                                                    digitWidth,
                                                    digitHeight
                                                ))
                                            }
                                        }
                                        break // Take first valid block
                                    }
                                }
                                
                                if (bestText.isNotEmpty()) {
                                    cachedMLKitResult = bestText
                                    cachedMLKitRects = newRects
                                }
                                mlKitInProgress = false
                            }
                            .addOnFailureListener { 
                                mlKitInProgress = false 
                            }
                    } catch (e: Exception) {
                        mlKitInProgress = false
                    }
                }
            }

            val gray = Mat()
            Imgproc.cvtColor(croppedMat, gray, Imgproc.COLOR_RGB2GRAY)

            // Blur to reduce noise
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            // Thresholding to find digits
            val binary = Mat()
            
            // --- OPTION B: HYBRID MSER + ADAPTIVE THRESHOLD ---
            // 1. Get crisp pixels using Adaptive Threshold (good for details, bad for noise)
            val adaptiveBinary = Mat()
            Imgproc.adaptiveThreshold(
                gray, adaptiveBinary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, // Invert: White text on Black
                45, 5.0 // Block 45 (Smoother), C 5.0 (Thicker digits)
            )
            
            // 2. Get robust regions using MSER (good for finding text, ignores lighting)
            // (Using class-level mser instance)
            
            val mserContours = ArrayList<MatOfPoint>()
            val mserBboxes = MatOfRect()
            mser.detectRegions(gray, mserContours, mserBboxes)
            
            // 3. Create a mask from MSER regions
            val mserMask = Mat.zeros(gray.size(), CvType.CV_8UC1)
            for (c in mserContours) {
                val rect = Imgproc.boundingRect(c)
                val ratio = rect.width.toDouble() / rect.height.toDouble()
                // Relaxed filter for the mask
                if (ratio > 0.1 && ratio < 1.5) { 
                     Imgproc.drawContours(mserMask, listOf(c), -1, Scalar(255.0), Imgproc.FILLED)
                }
            }
            
            // Dilate the mask slightly to ensure we don't clip the edges of the digits
            val maskKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.dilate(mserMask, mserMask, maskKernel)
            
            // 4. Combine: Keep pixels that are High Contrast (Adaptive) AND inside a Stable Region (MSER)
            // This fixes the "rounded/blurred" look of raw MSER blobs while keeping the noise rejection.
            binary.create(gray.size(), CvType.CV_8UC1)
            Core.bitwise_and(adaptiveBinary, mserMask, binary)
            
            // Clean up salt-and-pepper noise from the adaptive threshold
            Imgproc.medianBlur(binary, binary, 3)
            
            // Clean up intermediate Mats
            adaptiveBinary.release()
            mserMask.release()

            // --- DYNAMIC WINDOW DETECTION (Digit Holder Finder) ---
            // Instead of processing the whole crop, we look for the specific rectangular "window"
            // that holds the digits. This helps ignore noise outside the actual meter display.
            
            var workingBinary = binary
            var workingBinaryThin: Mat? = null
            var windowOffsetX = 0
            var windowOffsetY = 0
            
            // 1. Morphological Close to connect digits into a single blob
            val morphMat = Mat()
            val kernelWindow = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(30.0, 5.0)) // Wide kernel
            Imgproc.morphologyEx(binary, morphMat, Imgproc.MORPH_CLOSE, kernelWindow)
            
            // 2. Find contours of these blobs
            val windowContours = ArrayList<MatOfPoint>()
            Imgproc.findContours(morphMat, windowContours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            
            // 3. Find the best candidate for the "Digit Window"
            var bestWindowRect: Rect? = null
            var maxArea = 0.0
            
            for (c in windowContours) {
                val r = Imgproc.boundingRect(c)
                val area = r.area()
                val ratio = r.width.toDouble() / r.height.toDouble()
                
                // Filter: Must be wide (strip-like) and have significant size
                if (ratio > 2.0 && area > (binary.width() * binary.height() * 0.05)) {
                    if (area > maxArea) {
                        maxArea = area
                        bestWindowRect = r
                    }
                }
            }
            
            morphMat.release()
            // kernelWindow.release() // Mat returned by getStructuringElement needs release? In Java OpenCV it's a Mat.
            
            // 4. If found, crop to this window!
            if (bestWindowRect != null) {
                // Add padding
                val pad = 15
                val x = (bestWindowRect.x - pad).coerceAtLeast(0)
                val y = (bestWindowRect.y - pad).coerceAtLeast(0)
                val w = (bestWindowRect.width + 2 * pad).coerceAtMost(binary.width() - x)
                val h = (bestWindowRect.height + 2 * pad).coerceAtMost(binary.height() - y)
                
                val paddedRect = Rect(x, y, w, h)
                
                // Update offsets for final coordinate mapping
                windowOffsetX = x
                windowOffsetY = y
                
                // Create sub-mats (Views)
                workingBinary = Mat(binary, paddedRect)
                
                // We need to clone binary for 'thin' BEFORE we overwrite 'workingBinary' logic if we were reusing vars
                // But here we just take a submat of the original binary for the thin pass later.
                // Wait, binaryThin hasn't been created yet. We should create it from the cropped binary.
            }

            // Create a copy for the "Thin" pass (Before Dilation)
            // We use workingBinary which is either the full crop or the focused window
            val binaryThin = workingBinary.clone()

            // Thicken digits (Dilate) for the "Thick" pass.
            // This helps "1" survive and closes gaps in "0".
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_CROSS, Size(3.0, 3.0))
            Imgproc.dilate(workingBinary, workingBinary, kernel)

            // Find contours
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                workingBinary, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            // Debug: Prepare a color image to draw rectangles on for visualization
            var debugMat: Mat? = null
            if (captureDebugNextFrame) {
                debugMat = Mat()
                Imgproc.cvtColor(workingBinary, debugMat, Imgproc.COLOR_GRAY2RGB)
            }

            // Filter contours to find potential digits
            val digitRects = ArrayList<Rect>()
            val allRects = ArrayList<Rect>()
            val acceptedRects = ArrayList<Rect>()
            val badContours = ArrayList<MatOfPoint>()
            
            // Update cropHeight reference for filtering logic
            val activeHeight = workingBinary.height()

            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
                
                // Convert to Rect for Overlay (Offset by cropX + windowOffsetX, etc)
                val opencvRect = Rect(
                    rect.x + cropX + windowOffsetX, 
                    rect.y + cropY + windowOffsetY, 
                    rect.width, 
                    rect.height
                )
                allRects.add(opencvRect)

                // Draw ALL detected contours in RED (for debug)
                if (debugMat != null) {
                    Imgproc.rectangle(debugMat, rect, Scalar(255.0, 0.0, 0.0), 1)
                }

                // Filter based on size and aspect ratio (digits are usually tall)
                // Relaxed constraints:
                // Height: > 15% of active window height
                // Aspect Ratio: > 0.08 (was 0.15) to catch very thin "1"s.
                // Border Check: Reject if height > 85% of active window (likely a vertical separator line)
                val isBorder = rect.height > activeHeight * 0.85
                
                if (!isBorder &&
                    rect.height > activeHeight * 0.15 && 
                    rect.height < activeHeight * 0.95 &&
                    aspectRatio > 0.08 && aspectRatio < 1.0) {
                    
                    digitRects.add(rect)
                    acceptedRects.add(opencvRect)
                    // Draw ACCEPTED digits in GREEN (for debug)
                    if (debugMat != null) {
                        Imgproc.rectangle(debugMat, rect, Scalar(0.0, 255.0, 0.0), 2)
                    }
                } else {
                    // Only mark as BAD (paint black) if it's definitely noise (too small)
                    // If it's just a border or weird shape, don't paint it black, just ignore it.
                    // This prevents us from accidentally erasing parts of valid digits that overlap with "bad" regions.
                    if (rect.height < activeHeight * 0.15 || rect.width < 5) {
                         badContours.add(contour)
                    }
                }
            }

            // NOISE REMOVAL: Paint all "Small Noise" contours BLACK on the binary image.
            if (badContours.isNotEmpty()) {
                Imgproc.drawContours(workingBinary, badContours, -1, Scalar(0.0), Imgproc.FILLED)
            }

            // --- CONSENSUS FILTERING (Height & Alignment) ---
            // Instead of simple averages or single anchors which can be skewed by noise,
            // we use a voting system to find the "Dominant Group" of rectangles.
            // Valid digits will share similar Height and Y-Position.
            
            if (digitRects.isNotEmpty()) {
                // 1. Vote: Each rect counts how many "friends" it has (similar height & y-align)
                val scores = digitRects.mapIndexed { index, ref ->
                    var count = 0
                    for (other in digitRects) {
                        if (ref === other) continue
                        val hRatio = other.height.toDouble() / ref.height.toDouble()
                        val yDiff = Math.abs((other.y + other.height/2.0) - (ref.y + ref.height/2.0))
                        
                        // Strict criteria for "Friendship"
                        // Height within 15%
                        // Y-Center aligned within 30% of height
                        if (hRatio > 0.85 && hRatio < 1.15 && yDiff < (ref.height * 0.3)) {
                            count++
                        }
                    }
                    // Tie-breaker: Prefer rects closer to the center of the image
                    val cropCenterX = workingBinary.width() / 2
                    val dist = Math.abs((ref.x + ref.width/2) - cropCenterX)
                    val centrality = 1.0 - (dist.toDouble() / workingBinary.width())
                    
                    // Score = Count + small bonus for centrality
                    Pair(index, count + (centrality * 0.1))
                }
                
                // Find the winner (Best Reference Digit)
                val best = scores.maxByOrNull { it.second }
                
                if (best != null) {
                    val ref = digitRects[best.first]
                    val refHeight = ref.height.toDouble()
                    val refCenterY = ref.y + ref.height / 2.0
                    
                    val filteredDigitRects = ArrayList<Rect>()
                    val filteredAcceptedRects = ArrayList<Rect>()
                    
                    // 2. Filter: Keep everyone who fits with the Winner
                    for (i in digitRects.indices) {
                        val rect = digitRects[i]
                        val hRatio = rect.height.toDouble() / refHeight
                        val yDiff = Math.abs((rect.y + rect.height/2.0) - refCenterY)
                        
                        // STRICTER inclusion criteria to reject noise (m3, pipes)
                        // Height within 15% (was 20%)
                        // Y-Center aligned within 20% of height (was 50%)
                        // RELAXED for Rolling Digits: Allow height to be 60% - 140% of reference
                        // This catches "halved" digits that are rolling in/out.
                        if (hRatio > 0.60 && hRatio < 1.40 && yDiff < (refHeight * 0.3)) {
                            filteredDigitRects.add(rect)
                            filteredAcceptedRects.add(acceptedRects[i])
                        }
                    }
                    
                    // 3. Sort Left-to-Right
                    // We need to zip them to sort together, then unzip
                    val combined = filteredDigitRects.zip(filteredAcceptedRects)
                    val sorted = combined.sortedBy { (r, _) -> r.x }
                    
                    // 4. Limit to EXACTLY 5 Digits (Water Meter Standard)
                    // If we have > 5, we assume the extras are noise.
                    // Strategy: Find the "Best 5" that form a tight chain.
                    val finalSelection = if (sorted.size > 5) {
                        // If we have 6 or 7, usually the noise is on the far right (m3) or far left.
                        // We calculate the "Gap Variance" for sliding windows of 5.
                        // Real digits have consistent spacing.
                        
                        var bestWindow = sorted.take(5)
                        var minVariance = Double.MAX_VALUE
                        
                        for (i in 0..sorted.size - 5) {
                            val window = sorted.subList(i, i + 5)
                            // Calculate gaps
                            val gaps = window.zipWithNext { a, b -> (b.first.x - (a.first.x + a.first.width)).toDouble() }
                            val avgGap = gaps.average()
                            val variance = gaps.sumOf { Math.pow(it - avgGap, 2.0) }
                            
                            if (variance < minVariance) {
                                minVariance = variance
                                bestWindow = window
                            }
                        }
                        bestWindow
                    } else {
                        sorted
                    }
                    
                    digitRects.clear()
                    acceptedRects.clear()
                    finalSelection.forEach { (r, ar) ->
                        digitRects.add(r)
                        acceptedRects.add(ar)
                    }
                }
            }

            // Save the full debug strip with rectangles
            if (captureDebugNextFrame && debugMat != null) {
                val debugStripBitmap = Bitmap.createBitmap(debugMat.width(), debugMat.height(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(debugMat, debugStripBitmap)
                saveDebugImage(debugStripBitmap, "debug_strip_${dateFormatter.format(Date())}_${debugCounter}.png")
                debugMat.release()
                captureDebugNextFrame = false // Reset immediately, we only want the strip
            }

            // Sort rects from left to right (Already done above, but safe to keep if logic changes)
            // digitRects.sortBy { it.x }

            // Limit to 5 digits (or whatever logic fits)
            // If we have too many, maybe we only take the ones in the center?
            // For now, let's just process up to 5 valid looking ones.
            
            val resultBuilder = StringBuilder()
            
            // If we found nothing, maybe try the center crop?
            // But let's stick to the contours for now.
            
            var count = 0
            for (rect in digitRects) {
                if (count >= 5) break // Strict limit to 5 digits
                
                // --- DUAL PASS CLASSIFICATION ---
                
                // Pass 1: Thick (Dilated) - Good for '1', '7', '5'
                val digitMatThick = Mat(workingBinary, rect)
                val digitBitmapThick = processMatForModel(digitMatThick, rect)
                val predThick = classifier.classify(digitBitmapThick)
                digitMatThick.release()

                // Pass 2: Thin (Original) - Good for '6', '8', '9', '0' (Preserves holes)
                val digitMatThin = Mat(binaryThin, rect)
                val digitBitmapThin = processMatForModel(digitMatThin, rect)
                val predThin = classifier.classify(digitBitmapThin)
                
                // GEOMETRIC ANALYSIS: Check Center Density
                // '0' is hollow in the middle. '6', '8', '9' have strokes crossing the center.
                val centerDensity = calculateCenterDensity(digitMatThin)
                
                // GEOMETRIC ANALYSIS: Aspect Ratio for '1'
                // '1' is significantly taller than it is wide.
                val rectAspectRatio = rect.width.toDouble() / rect.height.toDouble()
                
                digitMatThin.release()

                // JUDGEMENT LOGIC
                var finalDigit = predThick

                // 0. Geometric Override for '1'
                // If the digit is very thin (Aspect Ratio < 0.5), it is almost certainly a '1'.
                // This fixes the "1 detected as 2" issue where noise makes the 1 look like a curve.
                if (rectAspectRatio < 0.5) {
                    finalDigit = 1
                }
                // 1. Trust '1' from Thick (Thin often breaks 1s)
                else if (predThick == 1) {
                    finalDigit = 1
                } 
                // 2. Trust Thin for '6', '8', '9' if Thick sees '0' (Blob vs Detail)
                else if (predThick == 0 && (predThin == 6 || predThin == 8 || predThin == 9)) {
                    finalDigit = predThin
                }
                // 3. Ambiguity: Both think it's '0', or Thick='8'/Thin='0'
                else if (predThick == 0 || predThin == 0) {
                    // Check the center. 
                    // A real '0' is hollow (Density ~0.0 - 0.1).
                    // A '6' or '8' has a middle stroke (Density > 0.15).
                    // We lowered the threshold from 0.35 to 0.12 because thin lines have low density.
                    if (centerDensity > 0.12) { 
                        // Center is NOT empty. It cannot be a clean 0.
                        if (predThin == 6 || predThin == 8 || predThin == 9) {
                            finalDigit = predThin
                        } else {
                            // Both models failed to see the structure, but geometry confirms a middle stroke.
                            // "Locked in the middle" -> Likely a 6.
                            finalDigit = 6
                        }
                    } else {
                        // Center is hollow. It's a valid 0.
                        finalDigit = 0
                    }
                } 
                // 4. Noise Check: Thick='8' but Thin='0' -> Likely noise closed the 0
                else if (predThick == 8 && predThin == 0) {
                     if (centerDensity < 0.12) {
                        finalDigit = 0
                     } else {
                        finalDigit = 8
                     }
                }
                // 5. 2 vs 8 Confusion (User Report: 02102 -> 02108)
                // '2' has a tail and is open at the top-left and bottom-right. '8' is closed.
                // If Thin says 2, it's likely a 2 (thin lines preserve the opening).
                // If Thick says 8, it might be a 2 that got bloated.
                else if (predThick == 8 && predThin == 2) {
                    finalDigit = 2
                }
                // 6. Fallback
                else if (predThick == -1 && predThin != -1) {
                    finalDigit = predThin
                }

                /*
                if (captureDebugNextFrame) {
                    // Save both for analysis
                    saveDigitBitmap(digitBitmapThick, predThick, rect, "thick")
                    saveDigitBitmap(digitBitmapThin, predThin, rect, "thin_d${String.format("%.2f", centerDensity)}")
                }
                */
                
                resultBuilder.append(if (finalDigit != -1) finalDigit.toString() else "?")
                count++
            }

            var currentResult = resultBuilder.toString()
            
            // === TRAILING NOISE DETECTION ===
            // If we got more than 5 digits, we have noise at the end (e.g., "045251" -> "04525")
            // This happens when a small artifact after the 5th digit gets detected
            if (currentResult.length > 5) {
                Log.d("MeterAnalyzer", "Detected ${currentResult.length} digits: '$currentResult'. Trimming trailing noise.")
                // Keep only the first 5 digits (the real meter reading)
                currentResult = currentResult.take(5)
            }

            // --- PARTIAL MATCH RECOVERY ---
            // If we only found 4 digits (e.g. 0161), check if we recently saw a 5-digit version (0161x).
            // This handles cases where the last digit is rolling or obscured but was briefly visible.
            // Use NORMALIZED comparison (so "4525" can match with history entries like "04525")
            val normalizedCurrent = ReadingValidator.normalizeReading(currentResult)
            if (normalizedCurrent.length == 4 || (currentResult.length == 4 && normalizedCurrent.length <= 4)) {
                // Look for history entries that START with the same prefix
                val potentialMatches = resultHistory.filter { historyEntry ->
                    val normalizedHistory = ReadingValidator.normalizeReading(historyEntry)
                    normalizedHistory.length >= 5 && normalizedHistory.startsWith(normalizedCurrent)
                }
                if (potentialMatches.isNotEmpty()) {
                    // Find the most frequent 5th digit (use normalized grouping)
                    val bestMatch = potentialMatches
                        .groupingBy { ReadingValidator.normalizeReading(it) }
                        .eachCount()
                        .maxByOrNull { it.value }?.key
                    if (bestMatch != null) {
                        currentResult = ReadingValidator.formatReading(bestMatch)
                    }
                }
            }

            // --- TIMER & OVERRIDE LOGIC ---
            // Track when we first started seeing *something* (at least 3 digits)
            if (currentResult.length >= 3) {
                if (firstGlimpseTime == 0L) firstGlimpseTime = System.currentTimeMillis()
            } else {
                // If we lose track, reset the timer (unless ML Kit has a lock)
                if (cachedMLKitResult.isEmpty()) {
                    firstGlimpseTime = 0L
                }
            }
            
            val timeSinceGlimpse = System.currentTimeMillis() - firstGlimpseTime
            val isTakingTooLong = firstGlimpseTime > 0 && timeSinceGlimpse > 2000 // 2 seconds timeout

            // --- INTEGRATION: ML KIT "JUDGE" ---
            if (cachedMLKitResult.isNotEmpty()) {
                // Case A: ML Kit found 5 digits. It is the authority.
                if (cachedMLKitResult.length == 5 || ReadingValidator.normalizeReading(cachedMLKitResult).length >= 4) {
                    // 1. Confidence Boost (Instant Win)
                    // If LeNet agrees with ML Kit (using NORMALIZED comparison!), we trust it 100% immediately.
                    // "04525" == "4525" should be considered a match!
                    if (ReadingValidator.areReadingsEquivalent(currentResult, cachedMLKitResult)) {
                        // Use the formatted (5-digit with leading zeros) version
                        val formattedResult = ReadingValidator.formatReading(currentResult)
                        // We will handle this in the voting section by forcing history
                        resultHistory.clear()
                        repeat(historySize) { resultHistory.add(formattedResult) }
                        // Cache results for voting
                        cachedLenetResult = formattedResult
                        cachedHybridResult = formattedResult
                        
                        // === SMART CONFIDENCE: Lock in immediately with max confidence ===
                        lockedReading = formattedResult
                        lockedFrameCount = historySize // Treat as if we've seen it many times
                        currentConfidence = 100f
                        lastSeenLockedTime = System.currentTimeMillis()
                        
                        // Mark as Hybrid success with validation
                        emitResult(formattedResult, 100f, allRects, acceptedRects, imageWidth, imageHeight, "H")
                        mat.release()
                        croppedMat.release()
                        gray.release()
                        binary.release()
                        binaryThin.release()
                        hierarchy.release()
                        return
                    }
                    
                    // 2. Override (Time's Up!)
                    // If LeNet is struggling (taking too long) and ML Kit has a solid answer,
                    // ML Kit takes over.
                    // Reduced timeout to 1000ms (1s) to let ML Kit take over faster if LeNet struggles.
                    val overrideTimeout = 1000L 
                    val isTakingTooLong = firstGlimpseTime > 0 && (System.currentTimeMillis() - firstGlimpseTime) > overrideTimeout
                    
                    if (isTakingTooLong) {
                        currentResult = cachedMLKitResult
                        
                        // ML Kit manipulates the Green Text (Visuals)
                        // We replace the LeNet rects with the ML Kit rects
                        allRects.clear()
                        allRects.addAll(cachedMLKitRects)
                        acceptedRects.clear()
                        acceptedRects.addAll(cachedMLKitRects)
                        
                        // Force history to lock this result
                        resultHistory.clear()
                        repeat(historySize) { resultHistory.add(currentResult) }
                        
                        // === VOTING: Before accepting MLKit, cross-check with validation ===
                        val votingResult = ReadingValidator.voteOnReading(
                            lenetReading = cachedLenetResult,
                            mlkitReading = cachedMLKitResult,
                            hybridReading = cachedHybridResult,
                            previousReading = previousReading
                        )
                        
                        val finalReading = ReadingValidator.formatReading(votingResult.consensusReading ?: currentResult)
                        val sourceTag = if (votingResult.agreementLevel == ReadingValidator.AgreementLevel.UNANIMOUS ||
                                           votingResult.agreementLevel == ReadingValidator.AgreementLevel.MAJORITY) "V" else "M"
                        
                        // === SMART CONFIDENCE: Lock in the MLKit result ===
                        lockedReading = finalReading
                        lockedFrameCount = historySize
                        currentConfidence = Math.max(votingResult.confidence, 90f)
                        lastSeenLockedTime = System.currentTimeMillis()
                        
                        emitResult(finalReading, currentConfidence, allRects, acceptedRects, imageWidth, imageHeight, sourceTag)
                        mat.release()
                        croppedMat.release()
                        gray.release()
                        binary.release()
                        binaryThin.release()
                        hierarchy.release()
                        return
                    }
                }
                // Case B: ML Kit found 4 digits (Prefix).
                // We trust ML Kit for the first 4, and LeNet for the 5th.
                else if (cachedMLKitResult.length == 4 && currentResult.length == 5) {
                    // Construct the Hybrid Result
                    // Example: ML=0161, LeNet=02616 -> Result=01616
                    val hybridResult = cachedMLKitResult + currentResult.last()
                    
                    // Cache for voting
                    cachedHybridResult = hybridResult
                    
                    // We adopt this result immediately as the "Current Best Guess"
                    // This corrects LeNet's prefix errors (e.g. 02616 -> 01616)
                    currentResult = hybridResult
                    
                    // Note: We don't force an immediate "Override" return here because we want to 
                    // let the history buffer fill up with this corrected result to ensure stability.
                    // But if LeNet is unstable on the last digit, this might fluctuate.
                    // That's acceptable/desired (we want to read the rolling digit).
                }
            }


            // Track failures (relaxed definition of failure: anything less than 4 digits is suspicious for a 5-digit meter)
            if (currentResult.length < 4) {
                consecutiveFailures++
            } else {
                consecutiveFailures = 0
            }

            // --- FALLBACK: GOOGLE ML KIT (TROJAN HORSE) ---
            // If OpenCV failed to find at least 3 digits, or found nothing, we call in the "Trojan Horse".
            // Strategy: Use ML Kit to FIND the meter (Localization), but use LeNet to READ it (Classification).
            // Trigger if:
            // 1. Result is very poor (length < 3) - Immediate Trigger
            // 2. Result is suspicious (length < 4) AND we've been failing for a while (consecutiveFailures > 10)
            // 3. We don't have a cached result yet (if we do, we trust the background process instead of blocking here)
            val shouldTriggerMLKit = (currentResult.length < 3 || (currentResult.length < 4 && consecutiveFailures > 10)) && image.image != null && cachedMLKitResult.isEmpty()

            if (shouldTriggerMLKit) {
                try {
                    val inputImage = InputImage.fromMediaImage(image.image!!, image.imageInfo.rotationDegrees)
                    val mlKitTask = textRecognizer.process(inputImage)
                    val mlKitResult = Tasks.await(mlKitTask) // Block since we are on background thread
                    
                    // Parse ML Kit result
                    // Look for a block that resembles a meter reading (4-6 digits)
                    for (block in mlKitResult.textBlocks) {
                        val text = block.text.replace(" ", "").replace("\n", "")
                        
                        // Regex: Look for 4 to 6 consecutive digits
                        if (text.matches(Regex("\\d{4,6}"))) {
                            val androidBox = block.boundingBox
                            if (androidBox != null) {
                                // 1. Crop the meter area from the original image (mat is full RGB/ARGB)
                                // Ensure box is within image bounds
                                val safeBox = Rect(
                                    Math.max(0, androidBox.left),
                                    Math.max(0, androidBox.top),
                                    Math.min(mat.cols() - androidBox.left, androidBox.width()),
                                    Math.min(mat.rows() - androidBox.top, androidBox.height())
                                )
                                
                                if (safeBox.width > 0 && safeBox.height > 0) {
                                    val meterMatRGB = Mat(mat, safeBox)
                                    val meterMatGray = Mat()
                                    Imgproc.cvtColor(meterMatRGB, meterMatGray, Imgproc.COLOR_RGB2GRAY)
                                    
                                    // 2. Slice into 5 equal parts (Trojan Horse Strategy)
                                    // We assume the box covers exactly the 5 digits.
                                    val digitWidth = meterMatGray.cols() / 5
                                    val digitHeight = meterMatGray.rows()
                                    
                                    val leNetBuilder = StringBuilder()
                                    
                                    // Update rects for UI visualization (Green Texts)
                                    allRects.clear()
                                    acceptedRects.clear()

                                    for (i in 0 until 5) {
                                        val digitRect = Rect(i * digitWidth, 0, digitWidth, digitHeight)
                                        val digitMat = Mat(meterMatGray, digitRect)
                                        
                                        // 3. Feed to LeNet
                                        // We reuse processMatForModel which handles resizing and thresholding
                                        val digitBitmap = processMatForModel(digitMat, digitRect)
                                        val digit = classifier.classify(digitBitmap)
                                        
                                        leNetBuilder.append(if (digit != -1) digit.toString() else "?")
                                        
                                        // Add the slice to acceptedRects so it shows up as a "Green Text" box
                                        // Convert back to global coordinates
                                        val globalSliceRect = Rect(
                                            safeBox.x + digitRect.x,
                                            safeBox.y + digitRect.y,
                                            digitRect.width,
                                            digitRect.height
                                        )
                                        acceptedRects.add(globalSliceRect)
                                        allRects.add(globalSliceRect)

                                        digitMat.release()
                                    }
                                    
                                    currentResult = leNetBuilder.toString()
                                    consecutiveFailures = 0 // Reset failures on success
                                    
                                    meterMatRGB.release()
                                    meterMatGray.release()
                                    break // Stop after first valid meter found
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MeterAnalyzer", "ML Kit Fallback Failed", e)
                }
            }

            if (captureDebugNextFrame) {
                // Show toast with the captured result
                val finalRes = currentResult
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "Captured: $finalRes", android.widget.Toast.LENGTH_LONG).show()
                }
                captureDebugNextFrame = false
            }
            
            // === NORMALIZE the result before adding to history ===
            // This ensures "04525" and "4525" are treated as the same reading!
            val normalizedResult = if (currentResult.isNotEmpty()) {
                ReadingValidator.formatReading(currentResult) // Converts to "04525" format
            } else {
                currentResult
            }
            
            // Voting Logic - using NORMALIZED results
            if (normalizedResult.isNotEmpty() && normalizedResult.length >= 3) { // Only consider results with at least 3 digits
                resultHistory.addLast(normalizedResult)
                if (resultHistory.size > historySize) {
                    resultHistory.removeFirst()
                }
            } else {
                // If we see nothing (or garbage) this frame, we should degrade confidence.
                // If we see NOTHING (empty), clear history immediately to prevent "stuck" results.
                if (normalizedResult.isEmpty()) {
                    handleNoDetection()  // <-- RAPID RELEASE when camera sees nothing!
                    resultHistory.clear()
                }
            }

            // Calculate most frequent result using NORMALIZED comparison
            if (resultHistory.isNotEmpty()) {
                // Group by NORMALIZED value (so "04525", "4525", "004525" all count together)
                val frequencyMap = resultHistory
                    .groupingBy { ReadingValidator.normalizeReading(it) }
                    .eachCount()
                val mostFrequent = frequencyMap.maxByOrNull { it.value }
                
                if (mostFrequent != null) {
                    // Format the result properly (with leading zeros)
                    val formattedResult = ReadingValidator.formatReading(mostFrequent.key)
                    
                    // === SMART CONFIDENCE SYSTEM ===
                    // Calculate base history agreement (old method)
                    val historyAgreement = (mostFrequent.value.toFloat() / resultHistory.size) * 100
                    
                    // Calculate SMART confidence (with temporal persistence & momentum)
                    var confidence = calculateSmartConfidence(formattedResult, historyAgreement)
                    
                    // Get the best reading (might be locked reading if we're confident)
                    val bestReading = getBestReading(formattedResult)

                    // --- 4-DIGIT TRAP ---
                    // If we are stuck on 4 digits (e.g. 0161) and ML Kit isn't helping,
                    // we must NOT submit this as a success. It's a partial read.
                    // Check the NORMALIZED length (without leading zeros)
                    val normalizedLength = ReadingValidator.normalizeReading(bestReading).length
                    if (normalizedLength == 4 && ReadingValidator.normalizeReading(bestReading).toIntOrNull() ?: 0 >= 1000) {
                        // It's actually a 4-digit number (1000-9999), not a 5-digit with leading zero
                        // Cap confidence at 85% so it never triggers "Hold Steady" (which needs 90%)
                        // This forces the user to keep adjusting until we find the 5th digit.
                        confidence = Math.min(confidence, 85f)
                    }
                    
                    // === DECISION ALGORITHM: FINAL VOTING ===
                    // Cache the LeNet result for voting (use formatted version)
                    cachedLenetResult = bestReading
                    
                    // If we have results from multiple detectors, vote before emitting
                    val finalResult: String
                    val finalSource: String
                    val finalConfidence: Float
                    
                    if (cachedMLKitResult.isNotEmpty() || cachedHybridResult.isNotEmpty()) {
                        val votingResult = ReadingValidator.voteOnReading(
                            lenetReading = cachedLenetResult,
                            mlkitReading = cachedMLKitResult,
                            hybridReading = cachedHybridResult,
                            previousReading = previousReading
                        )
                        
                        if (votingResult.consensusReading != null) {
                            finalResult = votingResult.consensusReading
                            // Use the HIGHER of smart confidence or voting confidence
                            finalConfidence = Math.max(confidence, votingResult.confidence)
                            finalSource = when (votingResult.agreementLevel) {
                                ReadingValidator.AgreementLevel.UNANIMOUS -> "V"  // Voted
                                ReadingValidator.AgreementLevel.MAJORITY -> "V"
                                ReadingValidator.AgreementLevel.PREFIX_MATCH -> "H" // Hybrid agreement
                                else -> "L" // Fallback to LeNet
                            }
                            Log.d("MeterAnalyzer", "Voting result: ${votingResult.details}")
                            
                            // If voting agrees with our lock, boost confidence further
                            if (ReadingValidator.areReadingsEquivalent(finalResult, lockedReading)) {
                                // Voting confirmed our lock! Max confidence.
                                currentConfidence = Math.min(100f, currentConfidence + 10f)
                            }
                        } else {
                            finalResult = bestReading
                            finalConfidence = confidence
                            finalSource = "L"
                        }
                    } else {
                        finalResult = bestReading
                        finalConfidence = confidence
                        finalSource = "L"
                    }
                    
                    // Log for debugging
                    Log.d("MeterAnalyzer", "Smart Confidence: locked='$lockedReading', frames=$lockedFrameCount, conf=$currentConfidence -> emit '$finalResult' @ $finalConfidence%")

                    emitResult(finalResult, finalConfidence, allRects, acceptedRects, imageWidth, imageHeight, finalSource)
                }
            } else {
                 // Pass empty/current if no history yet - also handle decay
                 if (normalizedResult.isNotEmpty()) {
                     calculateSmartConfidence(normalizedResult, 0f)
                 } else {
                     // Nothing detected this frame - decay!
                     handleNoDetection()
                 }
                 emitResult(normalizedResult, currentConfidence, allRects, acceptedRects, imageWidth, imageHeight, "L")
            }

            // Cleanup
            mat.release()
            croppedMat.release()
            gray.release()
            binary.release()
            binaryThin.release() // Release the copy
            hierarchy.release()
            
        } catch (e: Exception) {
            Log.e("MeterAnalyzer", "Error analyzing image", e)
        } finally {
            image.close()
        }
    }

    private fun processMatForModel(digitMat: Mat, rect: Rect): Bitmap {
        // --- ROLLING DIGIT FIX ---
        // Mechanical meters often show "halved" numbers (e.g., 5 going out, 6 coming in).
        // To avoid confusion, we focus on the CENTER 60% of the vertical height.
        // This cuts off the "leaving" digit at the top and "entering" digit at the bottom (or vice versa),
        // focusing on the dominant digit in the window.
        
        val cropHeight = (digitMat.rows() * 0.60).toInt()
        val cropY = (digitMat.rows() - cropHeight) / 2
        val centerRect = Rect(0, cropY, digitMat.cols(), cropHeight)
        val centerMat = Mat(digitMat, centerRect)

        // Resize to 28x28 with Padding (Center the digit)
        // We use the cropped height for calculations now
        val maxDim = Math.max(centerMat.cols(), centerMat.rows())
        val squareMat = Mat.zeros(maxDim, maxDim, CvType.CV_8UC1)

        val offsetX = (maxDim - centerMat.cols()) / 2
        val offsetY = (maxDim - centerMat.rows()) / 2
        val roi = Rect(offsetX, offsetY, centerMat.cols(), centerMat.rows())
        
        centerMat.copyTo(squareMat.submat(roi))

        val resizedMat = Mat()
        Imgproc.resize(squareMat, resizedMat, Size(20.0, 20.0), 0.0, 0.0, Imgproc.INTER_NEAREST)

        val finalMat = Mat()
        Core.copyMakeBorder(resizedMat, finalMat, 4, 4, 4, 4, Core.BORDER_CONSTANT, Scalar(0.0))
        Imgproc.threshold(finalMat, finalMat, 127.0, 255.0, Imgproc.THRESH_BINARY)

        // Ensure digits remain white on black background
        // REMOVED: Auto-inversion logic was flipping thick digits (mean > 127) causing low confidence
        // val meanIntensity = Core.mean(finalMat).`val`[0]
        // if (meanIntensity > 127) {
        //    Core.bitwise_not(finalMat, finalMat)
        // }

        val digitBitmap = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalMat, digitBitmap)
        
        // centerMat is a submat, no need to release explicitly if we release parent, 
        // but here digitMat is passed in. centerMat header needs release? 
        // Actually Mat(Mat, Rect) creates a header. 
        centerMat.release()
        squareMat.release()
        resizedMat.release()
        finalMat.release()
        
        return digitBitmap
    }

    private fun saveDebugImage(bitmap: Bitmap, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bitmap, fileName)
            } else {
                saveLegacy(bitmap, fileName)
            }
        } catch (e: Exception) {
            Log.e("MeterAnalyzer", "Failed to save debug image", e)
        }
    }

    private fun saveDigitBitmap(bitmap: Bitmap, predictedDigit: Int, rect: Rect, suffix: String = "") {
        try {
            val timestamp = dateFormatter.format(Date())
            val predictedLabel = if (predictedDigit >= 0) predictedDigit.toString() else "unknown"
            val fileName = "${timestamp}_${debugCounter++}_pred-${predictedLabel}_${rect.width}x${rect.height}_$suffix.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bitmap, fileName)
            } else {
                saveLegacy(bitmap, fileName)
            }
        } catch (e: Exception) {
            Log.e("MeterAnalyzer", "Failed to save digit bitmap", e)
        }
    }

    private fun saveViaMediaStore(bitmap: Bitmap, fileName: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, debugRelativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            Log.d("MeterAnalyzer", "Saved digit debug image to MediaStore: $uri")
        } else {
            Log.e("MeterAnalyzer", "Failed to insert MediaStore record for $fileName")
        }
    }

    private fun saveLegacy(bitmap: Bitmap, fileName: String) {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val debugDir = File(picturesDir, debugFolderName).apply { if (!exists()) mkdirs() }
        val outFile = File(debugDir, fileName)
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("image/png"), null)
        Log.d("MeterAnalyzer", "Saved digit debug image: ${outFile.absolutePath}")
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

    private fun calculateCenterDensity(mat: Mat): Double {
        if (mat.empty()) return 0.0
        
        val cx = mat.cols() / 2
        val cy = mat.rows() / 2
        val w = mat.cols() / 3 // Check middle 1/3rd
        val h = mat.rows() / 3
        
        val roi = Rect(cx - w/2, cy - h/2, w, h)
        val submat = mat.submat(roi)
        val nonZero = Core.countNonZero(submat)
        val total = w * h
        
        submat.release()
        
        return if (total > 0) nonZero.toDouble() / total else 0.0
    }
}
