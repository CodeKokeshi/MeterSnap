package com.kokeshi.metersnap

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.utils.BillCalculator
import com.kokeshi.metersnap.utils.DeveloperOptions
import com.kokeshi.metersnap.utils.RatePreferences
import com.kokeshi.metersnap.utils.HistoryManager
import com.kokeshi.metersnap.utils.BillCalculator.BillResult
import com.kokeshi.metersnap.utils.ChargeDateHelper
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import com.kokeshi.metersnap.utils.RateTableManager
import com.kokeshi.metersnap.utils.ReadingValidator
import com.kokeshi.metersnap.notifications.EndOfMonthReminderScheduler
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var cameraContainer: FrameLayout
    private lateinit var btnSnap: ImageButton
    private lateinit var btnManualInput: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnTorch: ImageButton
    private lateinit var btnHelp: ImageButton
    private lateinit var btnDebugStrip: android.widget.Button
    private lateinit var btnDebugFakePrevMonth: android.widget.Button
    private lateinit var scanLine: android.view.View
    private var tutorialOverlay: TutorialOverlay? = null
    private lateinit var pausedCaptureLayer: FrameLayout
    private lateinit var pausedFrameImage: ImageView
    private lateinit var cropSelectionView: CropSelectionView
    private lateinit var tvCropInstruction: android.widget.TextView
    private lateinit var btnResumeCapture: Button

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            // Image loaded, but we mock the result as requested
            handleScanSuccess("1575")
        }
    }

    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var activePreviewView: PreviewView? = null
    private var isTorchOn = false
    private var isTutorialActive = false
    private var meterAnalyzer: MeterAnalyzer? = null
    private var autoPauseAnalyzer: AutoPauseCaptureAnalyzer? = null
    private lateinit var projectedReadingManager: ProjectedReadingManager
    private var cropOcrInProgress = false
    private var manualRedirectTriggered = false
    private val liveFrameLock = Any()
    private var latestLiveFrameBitmap: Bitmap? = null
    private var lastCropDecisionDebug = ""
    private val cropTextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val useAutoPauseCropFlow = true
    
    // Stability Check Variables
    private var stableFrames = 0
    private var lastStableResult = ""
    private var isProcessingSuccess = false
    private var steadyStartMs = 0L
    private var steadyLastGoodMs = 0L
    private var steadyAnchorReading = ""

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val PREFS_NAME = "MeterSnapPrefs"
        private const val PREF_TUTORIAL_COMPLETED = "tutorial_completed"
        private const val PREF_PREVIOUS_READING = "previous_reading"
        private const val PREF_PREVIOUS_READING_SET = "previous_reading_set"
        private const val PREF_PREVIOUS_READING_PERIOD_KEY = "previous_reading_period_key"
        private const val STEADY_REQUIRED_MS = 3000L
        private const val STEADY_GAP_TOLERANCE_MS = 900L
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cameraContainer = findViewById(R.id.camera_container)
        btnSnap = findViewById(R.id.btn_snap)
        btnManualInput = findViewById(R.id.btn_manual_input)
        btnSettings = findViewById(R.id.btn_settings)
        btnTorch = findViewById(R.id.btn_torch)
        btnHelp = findViewById(R.id.btn_help)
        btnDebugStrip = findViewById(R.id.btn_debug_strip)
        btnDebugFakePrevMonth = findViewById(R.id.btn_debug_fake_prev_month)
        scanLine = findViewById(R.id.scan_line)
        pausedCaptureLayer = findViewById(R.id.paused_capture_layer)
        pausedFrameImage = findViewById(R.id.iv_paused_frame)
        cropSelectionView = findViewById(R.id.crop_selection_view)
        tvCropInstruction = findViewById(R.id.tv_crop_instruction)
        btnResumeCapture = findViewById(R.id.btn_resume_capture)

        cropSelectionView.setListener(object : CropSelectionView.Listener {
            override fun onSelectionCommitted(selectionInView: RectF) {
                processCroppedSelection(selectionInView)
            }
        })

        cropSelectionView.setActive(false)
        pausedCaptureLayer.visibility = android.view.View.GONE
        pausedFrameImage.visibility = android.view.View.GONE
        btnResumeCapture.visibility = android.view.View.GONE

        btnResumeCapture.setOnClickListener {
            resumeLiveCapture()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()



        // Initialize OpenCV
        if (OpenCVLoader.initDebug()) {
            android.util.Log.d("MainActivity", "OpenCV loaded successfully")
        } else {
            android.util.Log.e("MainActivity", "OpenCV initialization failed!")
        }

        // Initialize projection manager and schedule reminders
        projectedReadingManager = ProjectedReadingManager(this)
        EndOfMonthReminderScheduler.scheduleReminders(this)

        // Start scanning animation
        // startScanAnimation() // Moved to after tutorial/permissions

        // Add press animations to buttons
        addScaleAnimation(btnSnap)
        addScaleAnimation(btnManualInput)
        addSpinAnimation(btnSettings)
        addScaleAnimation(btnTorch)
        addScaleAnimation(btnHelp)

        btnSnap.setOnClickListener {
            if (!isTutorialActive) {
                pickImageLauncher.launch("image/*")
            }
        }

        btnManualInput.setOnClickListener {
            if (!isTutorialActive) {
                // Navigate to manual input screen
                val intent = android.content.Intent(this, ManualInputActivity::class.java)
                startActivity(intent)
            }
        }

        btnSettings.setOnClickListener {
            if (!isTutorialActive) {
                // Navigate to settings screen
                val intent = android.content.Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
        }

        btnTorch.setOnClickListener {
            if (!isTutorialActive) {
                toggleTorch()
            }
        }

        btnHelp.setOnClickListener {
            if (!isTutorialActive) {
                // Show tutorial again
                startTutorial()
            } else {
                Toast.makeText(this, "Complete the tutorial first", Toast.LENGTH_SHORT).show()
            }
        }

        btnDebugStrip.setOnClickListener {
            meterAnalyzer?.captureDebugNextFrame = true
            Toast.makeText(this, "Capturing debug strip...", Toast.LENGTH_SHORT).show()
        }
        
        btnDebugFakePrevMonth.setOnClickListener {
            createFakePreviousMonthScan()
        }

        // Apply developer settings for debug buttons visibility
        applyDeveloperSettings()

        // Check if this is the first launch
        checkAndShowTutorial()
    }
    override fun onResume() {
        super.onResume()
        
        // CRITICAL: Reset ALL UI and analyzer state
        // This ensures when user changes meters or previous reading in Settings,
        // we start completely fresh
        isProcessingSuccess = false
        lastStableResult = ""
        manualRedirectTriggered = false
        resetSteadyWindow()

        // Update analyzer with current previousReading from settings
        // This is critical when user changes value in Settings and returns
        // setPreviousReading() will call reset() if the value changed
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val currentPreviousReading = prefs.getInt(PREF_PREVIOUS_READING, 0)
        meterAnalyzer?.setPreviousReading(currentPreviousReading)
        
        // Reset UI instructions
        val tvInstructions = findViewById<android.widget.TextView>(R.id.tv_instructions)
        tvInstructions?.text = if (useAutoPauseCropFlow) {
            "Drag to draw your own crop box over digits anytime."
        } else {
            "Align meter in box"
        }
        tvInstructions?.setTextColor(ContextCompat.getColor(this, R.color.main_camera_instruction_default))

        val tvMlKitLive = findViewById<android.widget.TextView>(R.id.tv_mlkit_live)
        tvMlKitLive?.text = ""

        val tvPaddleLive = findViewById<android.widget.TextView>(R.id.tv_paddle_live)
        tvPaddleLive?.text = ""

        val tvOcrDebug = findViewById<android.widget.TextView>(R.id.tv_ocr_debug)
        tvOcrDebug?.text = ""

        if (useAutoPauseCropFlow) {
            findViewById<android.view.View>(R.id.guide_box).visibility = android.view.View.GONE
            findViewById<OverlayView>(R.id.overlay_view).visibility = android.view.View.GONE
            if (camera != null) {
                showLiveCropLayer()
            }
        }
        
        android.util.Log.d("MainActivity", "onResume: Reset state, previous reading = $currentPreviousReading")
        
        // Refresh developer settings (debug button visibility)
        applyDeveloperSettings()
    }
    
    override fun onPause() {
        super.onPause()
        // When user navigates away (e.g., to Settings), pause scanning
        // This prevents the analyzer from accumulating stale state
        resetSteadyWindow()
        hidePausedCaptureLayer(clearBitmap = true)
        synchronized(liveFrameLock) {
            latestLiveFrameBitmap?.recycle()
            latestLiveFrameBitmap = null
        }
        android.util.Log.d("MainActivity", "onPause: Paused scanning")
    }
    
    private fun hasAllRequiredPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        return cameraGranted && storageGranted
    }

    private fun requestAllRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (hasAllRequiredPermissions()) {
                startCamera()
                configureScanAnimationForMode()
            } else {
                Toast.makeText(this, "Camera and storage permissions are required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                if (useAutoPauseCropFlow) {
                    startLiveCropCamera(cameraProvider)
                    return@addListener
                }

                // Create PreviewView and add it to the container
                val previewView = PreviewView(this).apply {
                    // Keep preview fit-stable to reduce guidebox/crop mismatch.
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
                cameraContainer.removeAllViews()
                cameraContainer.addView(previewView)

                preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                // Get previous reading for validation
                val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
                val previousReading = prefs.getInt(PREF_PREVIOUS_READING, 0)

                // Image Analysis with Decision Algorithm
                meterAnalyzer = MeterAnalyzer(applicationContext, previousReading) { analyzerResult ->
                    runOnUiThread {
                        // Update Text
                        val tvLiveResult = findViewById<android.widget.TextView>(R.id.tv_live_result)
                        val tvMlKitLive = findViewById<android.widget.TextView>(R.id.tv_mlkit_live)
                        val tvPaddleLive = findViewById<android.widget.TextView>(R.id.tv_paddle_live)
                        val tvOcrDebug = findViewById<android.widget.TextView>(R.id.tv_ocr_debug)
                        val tvInstructions = findViewById<android.widget.TextView>(R.id.tv_instructions)
                        
                        val result = analyzerResult.reading
                        val confidence = analyzerResult.confidence
                        val source = analyzerResult.source
                        val hasFreshEvidence = analyzerResult.hasFreshEvidence
                        val validation = analyzerResult.validationResult
                        val mlKitDisplayText = analyzerResult.mlKitDisplayText
                        val paddleDisplayText = analyzerResult.paddleDisplayText
                        val ocrDebug = analyzerResult.ocrDebug

                        tvMlKitLive.text = "MLKit: $mlKitDisplayText"
                        tvMlKitLive.setTextColor(android.graphics.Color.parseColor("#FFA726"))

                        tvPaddleLive.text = "Guide ROI: $paddleDisplayText"
                        tvPaddleLive.setTextColor(android.graphics.Color.parseColor("#42A5F5"))

                        tvOcrDebug.text = ocrDebug
                        tvOcrDebug.setTextColor(android.graphics.Color.parseColor("#FF5252"))
                        
                        if (result.isNotEmpty()) {
                            // Show validation status in display
                            val validationTag = when {
                                validation == null -> ""
                                validation.errorCode == com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.VALID -> "✓"
                                validation.errorCode == com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.HIGH_USAGE_WARNING -> "⚠"
                                else -> "✗"
                            }
                            tvLiveResult.text = "$result (${confidence.toInt()}%) [$source]$validationTag"
                        } else {
                            tvLiveResult.text = ""
                        }
                        
                        // Update Overlay
                        val overlayView = findViewById<OverlayView>(R.id.overlay_view)
                        val scaleX = overlayView.width.toFloat() / analyzerResult.imageWidth
                        val scaleY = overlayView.height.toFloat() / analyzerResult.imageHeight

                        // Only show accepted rects (Green), hide all rects (Red)
                        val scaledAccepted = analyzerResult.acceptedRects.map { r ->
                            android.graphics.RectF(
                                r.x * scaleX,
                                r.y * scaleY,
                                (r.x + r.width) * scaleX,
                                (r.y + r.height) * scaleY
                            )
                        }
                        overlayView.setRects(emptyList(), scaledAccepted)

                        // Stability Check & Instruction Update
                        if (!isProcessingSuccess && !isTutorialActive) {
                            val nowMs = System.currentTimeMillis()
                            val hasFiveDigitInGuideBox = hasFreshEvidence && result.length == 5

                            // Primary scan success rule: require a tolerant steady window (~3s).
                            if (hasFiveDigitInGuideBox && confidence >= 65f) {
                                val currentReading = result

                                if (steadyStartMs == 0L || steadyAnchorReading.isEmpty()) {
                                    steadyStartMs = nowMs
                                    steadyLastGoodMs = nowMs
                                    steadyAnchorReading = currentReading
                                } else {
                                    val nearAnchor = isReadingNearAnchor(currentReading, steadyAnchorReading)
                                    if (nearAnchor) {
                                        steadyLastGoodMs = nowMs
                                        if (confidence >= 88f) {
                                            steadyAnchorReading = currentReading
                                        }
                                    } else if (nowMs - steadyLastGoodMs > STEADY_GAP_TOLERANCE_MS) {
                                        // Drifted too far for too long: restart steady timer, don't fail hard.
                                        steadyStartMs = nowMs
                                        steadyLastGoodMs = nowMs
                                        steadyAnchorReading = currentReading
                                    }
                                }

                                val elapsed = nowMs - steadyStartMs
                                val remainingMs = (STEADY_REQUIRED_MS - elapsed).coerceAtLeast(0L)
                                val remainingSec = ((remainingMs + 999L) / 1000L).toInt()

                                tvInstructions.text = if (remainingSec > 0) {
                                    "Hold Steady... ${remainingSec}s"
                                } else {
                                    "Processing..."
                                }
                                tvInstructions.setTextColor(android.graphics.Color.GREEN)

                                if (elapsed >= STEADY_REQUIRED_MS) {
                                    isProcessingSuccess = true
                                    lastStableResult = result
                                    tvInstructions.text = "Processing..."
                                    handleScanSuccess(result)
                                }
                                return@runOnUiThread
                            }

                            // Briefly tolerate OCR jitter without forcing statue-level stillness.
                            if (steadyStartMs != 0L && (nowMs - steadyLastGoodMs) <= STEADY_GAP_TOLERANCE_MS) {
                                val elapsed = nowMs - steadyStartMs
                                val remainingMs = (STEADY_REQUIRED_MS - elapsed).coerceAtLeast(0L)
                                val remainingSec = ((remainingMs + 999L) / 1000L).toInt()
                                tvInstructions.text = "Keep meter in box... ${remainingSec}s"
                                tvInstructions.setTextColor(android.graphics.Color.YELLOW)
                                return@runOnUiThread
                            }

                            resetSteadyWindow()
                            
                            // === DECISION ALGORITHM: Manual Input Suggestion ===
                            if (analyzerResult.shouldSuggestManualInput) {
                                tvInstructions.text = "Having trouble? Try Manual Input"
                                tvInstructions.setTextColor(android.graphics.Color.parseColor("#FFA500")) // Orange
                                // Optional: Could auto-redirect to ManualInputActivity here
                            }
                            // === DECISION ALGORITHM: Validation Feedback ===
                            else if (validation != null && !validation.isValid) {
                                when (validation.errorCode) {
                                    com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.IMPOSSIBLE_USAGE -> {
                                        tvInstructions.text = "Reading too high! Check alignment"
                                        tvInstructions.setTextColor(android.graphics.Color.RED)
                                    }
                                    com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.READING_TOO_LOW -> {
                                        tvInstructions.text = "Reading lower than previous?"
                                        tvInstructions.setTextColor(android.graphics.Color.RED)
                                    }
                                    com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.TOO_MANY_DIGITS -> {
                                        tvInstructions.text = "Too many digits - noise detected"
                                        tvInstructions.setTextColor(android.graphics.Color.YELLOW)
                                    }
                                    com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.TOO_FEW_DIGITS -> {
                                        tvInstructions.text = "Move closer to meter"
                                        tvInstructions.setTextColor(android.graphics.Color.YELLOW)
                                    }
                                    else -> {
                                        tvInstructions.text = validation.message.take(30) + "..."
                                        tvInstructions.setTextColor(android.graphics.Color.YELLOW)
                                    }
                                }
                            }
                            // Special Case: 4 Digits (Partial Read)
                            else if (result.length == 4 && confidence > 80f) {
                                tvInstructions.text = "Move Closer / Adjust Angle"
                                tvInstructions.setTextColor(android.graphics.Color.YELLOW)
                            }
                            else {
                                tvInstructions.text = "Align meter in box"
                                tvInstructions.setTextColor(ContextCompat.getColor(this, R.color.main_camera_instruction_default))
                            }
                        }
                    }
                }

                bindGuideBoxToAnalyzer(previewView)

                val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1920, 1080)) // Request higher resolution for OCR clarity
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor!!, meterAnalyzer!!)
                    }

                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind camera to lifecycle
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                updateAnalyzerGuideBox(previewView)
                requestGuideBoxAutoFocus(previewView)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLiveCropCamera(cameraProvider: ProcessCameraProvider) {
        val previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }

        activePreviewView = previewView

        cameraContainer.removeAllViews()
        cameraContainer.addView(previewView)

        preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        autoPauseAnalyzer?.shutdown()
        autoPauseAnalyzer = null

        val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1280, 720))
            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor!!, androidx.camera.core.ImageAnalysis.Analyzer { image ->
                    try {
                        val frame = image.toBitmapCompat() ?: return@Analyzer
                        val upright = rotateFrameToUpright(frame, image.imageInfo.rotationDegrees)

                        synchronized(liveFrameLock) {
                            latestLiveFrameBitmap?.recycle()
                            latestLiveFrameBitmap = upright
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("MainActivity", "Live frame update failed", e)
                    } finally {
                        image.close()
                    }
                })
            }

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            this,
            cameraSelector,
            preview,
            imageAnalysis
        )

        findViewById<android.view.View>(R.id.guide_box).visibility = android.view.View.GONE
        findViewById<OverlayView>(R.id.overlay_view).visibility = android.view.View.GONE

        val tvInstructions = findViewById<android.widget.TextView>(R.id.tv_instructions)
        tvInstructions.text = "Drag to draw your own crop box over digits anytime."
        tvInstructions.setTextColor(ContextCompat.getColor(this, R.color.main_camera_instruction_default))

        findViewById<android.widget.TextView>(R.id.tv_live_result).text = ""
        findViewById<android.widget.TextView>(R.id.tv_mlkit_live).text = ""
        findViewById<android.widget.TextView>(R.id.tv_paddle_live).text = ""
        findViewById<android.widget.TextView>(R.id.tv_ocr_debug).text = ""

        showLiveCropLayer()
    }

    private fun processCroppedSelection(selectionInView: RectF) {
        if (cropOcrInProgress || manualRedirectTriggered) return
        val previewView = activePreviewView ?: return

        val bitmap = synchronized(liveFrameLock) {
            latestLiveFrameBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        } ?: run {
            tvCropInstruction.text = "No live frame yet. Hold still and drag again."
            return
        }

        val bitmapRect = mapSelectionToBitmapRectOnPreview(
            selectionInView = selectionInView,
            selectionView = cropSelectionView,
            previewView = previewView,
            bitmap = bitmap
        )
        if (bitmapRect == null || bitmapRect.width() < 32 || bitmapRect.height() < 32) {
            bitmap.recycle()
            tvCropInstruction.text = "Selection too small. Drag a bigger crop over digits."
            return
        }

        val cropped = Bitmap.createBitmap(bitmap, bitmapRect.left, bitmapRect.top, bitmapRect.width(), bitmapRect.height())
        bitmap.recycle()
        val executor = cameraExecutor ?: run {
            cropped.recycle()
            return
        }

        cropOcrInProgress = true
        tvCropInstruction.text = "Processing crop..."

        executor.execute {
            val detected = try {
                runCropOcr(cropped)
            } catch (_: Exception) {
                null
            }

            cropped.recycle()

            runOnUiThread {
                cropOcrInProgress = false
                findViewById<android.widget.TextView>(R.id.tv_ocr_debug).text = lastCropDecisionDebug
                if (!detected.isNullOrBlank()) {
                    isProcessingSuccess = true
                    lastStableResult = detected
                    handleScanSuccess(detected)
                } else {
                    tvCropInstruction.text = "Image not readable. Redirecting to Manual Input..."
                    redirectToManualInputUnreadable()
                }
            }
        }
    }

    private data class CropOcrVariant(
        val bitmap: Bitmap,
        val weight: Float,
        val tag: String
    )

    private fun runCropOcr(cropped: Bitmap): String? {
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val previousReading = prefs.getInt(PREF_PREVIOUS_READING, 0).coerceAtLeast(0)
        val lowerBound = previousReading
        val upperBound = computePredictedUpperBoundForCrop(previousReading).coerceAtLeast(lowerBound)

        val variants = buildCropOcrVariants(cropped)
        val weightedCandidates = linkedMapOf<String, Float>()

        for (variant in variants) {
            try {
                val input = InputImage.fromBitmap(variant.bitmap, 0)
                val result = Tasks.await(cropTextRecognizer.process(input))

                collectCandidatesFromText(result.text ?: "", variant.weight, weightedCandidates)
                for (block in result.textBlocks) {
                    collectCandidatesFromText(block.text, variant.weight + 0.06f, weightedCandidates)
                    for (line in block.lines) {
                        collectCandidatesFromText(line.text, variant.weight + 0.08f, weightedCandidates)
                        for (element in line.elements) {
                            collectCandidatesFromText(element.text, variant.weight + 0.1f, weightedCandidates)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("MainActivity", "Crop OCR variant failed: ${variant.tag}", e)
            } finally {
                if (variant.bitmap !== cropped) {
                    variant.bitmap.recycle()
                }
            }
        }

        val detected = chooseBoundedCropReading(
            weightedCandidates = weightedCandidates,
            lowerBound = lowerBound,
            upperBound = upperBound,
            previousReading = previousReading
        )

        lastCropDecisionDebug = "CROP LB:${String.format("%05d", lowerBound)} UB:${String.format("%05d", upperBound)} C:${weightedCandidates.size} PICK:${detected ?: "-"}"
        return detected
    }

    private fun collectCandidatesFromText(raw: String, weight: Float, out: MutableMap<String, Float>) {
        if (raw.isBlank()) return

        val normalized = normalizeCropText(raw)
        val digitsOnly = normalized.filter { it.isDigit() }
        if (digitsOnly.isBlank()) return

        if (digitsOnly.length >= 5) {
            for (i in 0..(digitsOnly.length - 5)) {
                addWeightedCandidate(digitsOnly.substring(i, i + 5), weight, out)
            }
        }

        if (digitsOnly.length == 4) {
            addWeightedCandidate("0$digitsOnly", weight * 0.9f, out)
        } else if (digitsOnly.length == 3) {
            addWeightedCandidate("00$digitsOnly", weight * 0.55f, out)
        }
    }

    private fun addWeightedCandidate(candidate: String, weight: Float, out: MutableMap<String, Float>) {
        val digits = candidate.filter { it.isDigit() }
        if (digits.isEmpty()) return

        val normalized = digits.padStart(5, '0').takeLast(5)
        val previous = out[normalized] ?: 0f
        out[normalized] = previous + weight.coerceAtLeast(0.05f)
    }

    private fun normalizeCropText(raw: String): String {
        return raw.uppercase()
            .replace('O', '0')
            .replace('Q', '0')
            .replace('D', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('S', '5')
            .replace('B', '8')
    }

    private fun chooseBoundedCropReading(
        weightedCandidates: Map<String, Float>,
        lowerBound: Int,
        upperBound: Int,
        previousReading: Int
    ): String? {
        if (weightedCandidates.isEmpty()) return null

        var bestReading: String? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for ((rawCandidate, weight) in weightedCandidates) {
            val generated = generateSingleDigitCorrections(rawCandidate)

            for (candidate in generated) {
                val value = candidate.toIntOrNull() ?: continue
                if (value < lowerBound || value > upperBound) continue

                val edits = hammingDistance(rawCandidate, candidate).toFloat()
                var score = weight * 100f
                score -= edits * 9f
                score -= abs(value - upperBound).toFloat() * 0.22f

                val validation = ReadingValidator.validateReading(candidate, previousReading)
                score += when {
                    validation.isValid && validation.errorCode == ReadingValidator.ErrorCode.VALID -> 25f
                    validation.isValid -> 14f
                    else -> -8f
                }

                if (previousReading > 0) {
                    val previousFormatted = String.format("%05d", previousReading)
                    if (candidate.take(3) == previousFormatted.take(3)) {
                        score += 6f
                    }
                }

                if (score > bestScore) {
                    bestScore = score
                    bestReading = candidate
                }
            }
        }

        return bestReading
    }

    private fun generateSingleDigitCorrections(base: String): Set<String> {
        val digits = base.filter { it.isDigit() }.padStart(5, '0').takeLast(5)
        val results = linkedSetOf<String>()
        results.add(digits)

        val chars = digits.toCharArray()
        for (index in chars.indices) {
            val original = chars[index]
            for (digit in '0'..'9') {
                if (digit == original) continue
                val copy = chars.copyOf()
                copy[index] = digit
                results.add(String(copy))
            }
        }

        return results
    }

    private fun hammingDistance(a: String, b: String): Int {
        val left = a.padStart(5, '0').takeLast(5)
        val right = b.padStart(5, '0').takeLast(5)
        var distance = 0
        for (i in 0 until 5) {
            if (left[i] != right[i]) distance++
        }
        return distance
    }

    private fun computePredictedUpperBoundForCrop(previousReading: Int): Int {
        val historyValues = HistoryManager(this)
            .getAllHistory()
            .map { it.currentReading }
            .filter { it >= 0 }
            .take(8)
            .reversed()

        val predictor = ReadingValidator.ReadingPredictor()
        if (previousReading > 0) {
            predictor.recordObservation(String.format("%05d", previousReading))
        }
        historyValues.forEach { value ->
            predictor.recordObservation(String.format("%05d", value))
        }

        val predictedFromModel = predictor.predict(previousReading).predictedReading.toIntOrNull()
        val avgStep = estimateAverageStepForCrop(previousReading, historyValues)
        val predictedFromStep = (historyValues.lastOrNull() ?: previousReading) + avgStep
        val fallbackPredicted = previousReading + ReadingValidator.ReadingPredictor.TYPICAL_MAX_USAGE

        val merged = mutableListOf<Int>()
        predictedFromModel?.let { merged.add(it) }
        merged.add(predictedFromStep)
        merged.add(fallbackPredicted)
        merged.add(previousReading)

        val robust = merged
            .map { it.coerceAtLeast(previousReading) }
            .sorted()[merged.size / 2]

        return robust.coerceAtMost(ReadingValidator.MAX_READING_VALUE)
    }

    private fun estimateAverageStepForCrop(previousReading: Int, historyValues: List<Int>): Int {
        val sequence = mutableListOf<Int>()
        if (previousReading > 0) sequence.add(previousReading)
        sequence.addAll(historyValues.takeLast(6))

        if (sequence.size < 2) {
            return ReadingValidator.ReadingPredictor.TYPICAL_MIN_USAGE
        }

        val deltas = sequence.zipWithNext()
            .map { (a, b) -> (b - a).coerceAtLeast(0) }
            .filter { it > 0 }

        if (deltas.isEmpty()) {
            return ReadingValidator.ReadingPredictor.TYPICAL_MIN_USAGE
        }

        return deltas.average().roundToInt()
            .coerceAtLeast(ReadingValidator.ReadingPredictor.TYPICAL_MIN_USAGE)
            .coerceAtMost(ReadingValidator.ReadingPredictor.TYPICAL_MAX_USAGE)
    }

    private fun buildCropOcrVariants(source: Bitmap): List<CropOcrVariant> {
        val variants = mutableListOf<CropOcrVariant>()
        variants.add(CropOcrVariant(source, 1.0f, "RAW"))

        variants.add(CropOcrVariant(applyColorAdjust(source, contrast = 1.2f, brightness = 18f, saturation = 1.05f), 0.92f, "BRC1"))
        variants.add(CropOcrVariant(applyColorAdjust(source, contrast = 1.35f, brightness = 0f, saturation = 0.0f), 0.95f, "GRAYHC"))
        variants.add(CropOcrVariant(applyColorAdjust(source, contrast = 1.08f, brightness = -10f, saturation = 1.3f), 0.85f, "SAT"))

        buildAdaptiveThresholdVariant(source, invert = false)?.let {
            variants.add(CropOcrVariant(it, 0.88f, "THR"))
        }
        buildAdaptiveThresholdVariant(source, invert = true)?.let {
            variants.add(CropOcrVariant(it, 0.82f, "THR_INV"))
        }

        return variants
    }

    private fun applyColorAdjust(source: Bitmap, contrast: Float, brightness: Float, saturation: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val saturationMatrix = ColorMatrix().apply {
            setSaturation(saturation)
        }
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        saturationMatrix.postConcat(contrastMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(saturationMatrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun buildAdaptiveThresholdVariant(source: Bitmap, invert: Boolean): Bitmap? {
        val src = Mat()
        val gray = Mat()
        val blur = Mat()
        val thr = Mat()
        val rgba = Mat()

        return try {
            Utils.bitmapToMat(source, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)
            Imgproc.adaptiveThreshold(
                blur,
                thr,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                if (invert) Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY,
                33,
                5.0
            )
            Imgproc.cvtColor(thr, rgba, Imgproc.COLOR_GRAY2RGBA)

            val out = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, out)
            out
        } catch (e: Exception) {
            android.util.Log.d("MainActivity", "Threshold variant failed", e)
            null
        } finally {
            src.release()
            gray.release()
            blur.release()
            thr.release()
            rgba.release()
        }

        val normalized = raw.uppercase()
            .replace('O', '0')
            .replace('Q', '0')
            .replace('D', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('S', '5')
            .replace('B', '8')

        val digitsOnly = normalized.filter { it.isDigit() }
        if (digitsOnly.length >= 5) {
            return Regex("\\d{5}").findAll(digitsOnly).lastOrNull()?.value ?: digitsOnly.takeLast(5)
        }
        return if (digitsOnly.length >= 4) digitsOnly else null
    }

    private fun mapSelectionToBitmapRectOnPreview(
        selectionInView: RectF,
        selectionView: android.view.View,
        previewView: PreviewView,
        bitmap: Bitmap
    ): Rect? {
        if (selectionView.width <= 0 || selectionView.height <= 0 ||
            previewView.width <= 0 || previewView.height <= 0 ||
            bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }

        val selectionLoc = IntArray(2)
        val previewLoc = IntArray(2)
        selectionView.getLocationOnScreen(selectionLoc)
        previewView.getLocationOnScreen(previewLoc)

        val localLeftRaw = selectionInView.left + (selectionLoc[0] - previewLoc[0])
        val localTopRaw = selectionInView.top + (selectionLoc[1] - previewLoc[1])
        val localRightRaw = selectionInView.right + (selectionLoc[0] - previewLoc[0])
        val localBottomRaw = selectionInView.bottom + (selectionLoc[1] - previewLoc[1])

        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()

        val local = RectF(
            localLeftRaw.coerceIn(0f, viewW),
            localTopRaw.coerceIn(0f, viewH),
            localRightRaw.coerceIn(0f, viewW),
            localBottomRaw.coerceIn(0f, viewH)
        )

        if (local.width() <= 1f || local.height() <= 1f) return null

        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()

        val scale = minOf(viewW / bmpW, viewH / bmpH)
        val drawW = bmpW * scale
        val drawH = bmpH * scale
        val drawLeft = (viewW - drawW) / 2f
        val drawTop = (viewH - drawH) / 2f
        val drawRight = drawLeft + drawW
        val drawBottom = drawTop + drawH

        val clipped = RectF(
            local.left.coerceIn(drawLeft, drawRight),
            local.top.coerceIn(drawTop, drawBottom),
            local.right.coerceIn(drawLeft, drawRight),
            local.bottom.coerceIn(drawTop, drawBottom)
        )

        if (clipped.width() <= 1f || clipped.height() <= 1f) return null

        val leftPx = (((clipped.left - drawLeft) / scale)).toInt().coerceIn(0, bitmap.width - 1)
        val topPx = (((clipped.top - drawTop) / scale)).toInt().coerceIn(0, bitmap.height - 1)
        val rightPx = (((clipped.right - drawLeft) / scale)).toInt().coerceIn(leftPx + 1, bitmap.width)
        val bottomPx = (((clipped.bottom - drawTop) / scale)).toInt().coerceIn(topPx + 1, bitmap.height)

        return Rect(leftPx, topPx, rightPx, bottomPx)
    }

    private fun rotateFrameToUpright(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun redirectToManualInputUnreadable() {
        if (manualRedirectTriggered) return
        manualRedirectTriggered = true
        Toast.makeText(this, "Image not readable. Redirecting to Manual Input.", Toast.LENGTH_LONG).show()
        val intent = android.content.Intent(this, ManualInputActivity::class.java)
        startActivity(intent)
    }

    private fun resumeLiveCapture() {
        cropOcrInProgress = false
        manualRedirectTriggered = false
        cropSelectionView.clearSelection()

        if (useAutoPauseCropFlow) {
            val tvInstructions = findViewById<android.widget.TextView>(R.id.tv_instructions)
            tvInstructions.text = "Drag to draw your own crop box over digits anytime."
            tvInstructions.setTextColor(ContextCompat.getColor(this, R.color.main_camera_instruction_default))
        }
    }

    private fun showLiveCropLayer() {
        pausedCaptureLayer.visibility = android.view.View.VISIBLE
        pausedCaptureLayer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        pausedCaptureLayer.isClickable = false
        pausedCaptureLayer.isFocusable = false

        pausedFrameImage.visibility = android.view.View.GONE
        btnResumeCapture.visibility = android.view.View.GONE
        tvCropInstruction.visibility = android.view.View.VISIBLE
        tvCropInstruction.text = "Drag to draw your own crop box over digits."

        cropSelectionView.setActive(true)
        cropSelectionView.clearSelection()
    }

    private fun hidePausedCaptureLayer(clearBitmap: Boolean) {
        pausedCaptureLayer.visibility = android.view.View.GONE
        cropSelectionView.setActive(false)
        cropSelectionView.clearSelection()

        if (clearBitmap) {
            pausedFrameImage.setImageBitmap(null)
        }
    }

    private fun configureScanAnimationForMode() {
        if (useAutoPauseCropFlow) {
            scanLine.animate().cancel()
            scanLine.visibility = android.view.View.GONE
            return
        }

        scanLine.visibility = android.view.View.VISIBLE
        startScanAnimation()
    }

    private fun bindGuideBoxToAnalyzer(previewView: PreviewView) {
        val guideBox = findViewById<android.view.View>(R.id.guide_box)

        val sync = {
            updateAnalyzerGuideBox(previewView)
        }

        previewView.post(sync)
        guideBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
    }

    private fun updateAnalyzerGuideBox(previewView: PreviewView) {
        val analyzer = meterAnalyzer ?: return
        val guideBox = findViewById<android.view.View>(R.id.guide_box)
        if (previewView.width <= 0 || previewView.height <= 0) return
        if (guideBox.width <= 0 || guideBox.height <= 0) return

        val guideLoc = IntArray(2)
        val previewLoc = IntArray(2)
        guideBox.getLocationOnScreen(guideLoc)
        previewView.getLocationOnScreen(previewLoc)

        val localLeftRaw = (guideLoc[0] - previewLoc[0]).toFloat()
        val localTopRaw = (guideLoc[1] - previewLoc[1]).toFloat()
        val localRightRaw = localLeftRaw + guideBox.width.toFloat()
        val localBottomRaw = localTopRaw + guideBox.height.toFloat()

        if (localRightRaw <= 0f || localBottomRaw <= 0f ||
            localLeftRaw >= previewView.width.toFloat() || localTopRaw >= previewView.height.toFloat()) {
            return
        }

        val leftPx = localLeftRaw.coerceIn(0f, (previewView.width - 1).toFloat())
        val topPx = localTopRaw.coerceIn(0f, (previewView.height - 1).toFloat())
        val rightPx = localRightRaw.coerceIn(leftPx + 1f, previewView.width.toFloat())
        val bottomPx = localBottomRaw.coerceIn(topPx + 1f, previewView.height.toFloat())

        val left = (leftPx / previewView.width.toFloat()).coerceIn(0f, 1f)
        val top = (topPx / previewView.height.toFloat()).coerceIn(0f, 1f)
        val right = (rightPx / previewView.width.toFloat()).coerceIn(left + 0.01f, 1f)
        val bottom = (bottomPx / previewView.height.toFloat()).coerceIn(top + 0.01f, 1f)

        analyzer.setGuideBoxNormalized(left, top, right, bottom)
    }

    private fun resetSteadyWindow() {
        stableFrames = 0
        steadyStartMs = 0L
        steadyLastGoodMs = 0L
        steadyAnchorReading = ""
    }

    private fun isReadingNearAnchor(reading: String, anchor: String): Boolean {
        if (reading == anchor) return true
        val readingValue = reading.toIntOrNull() ?: return false
        val anchorValue = anchor.toIntOrNull() ?: return false
        return abs(readingValue - anchorValue) <= 1
    }

    private fun requestGuideBoxAutoFocus(previewView: PreviewView) {
        val guideBox = findViewById<android.view.View>(R.id.guide_box)
        val activeCamera = camera ?: return

        previewView.post {
            if (previewView.width <= 0 || previewView.height <= 0) return@post

            val centerX = guideBox.x + (guideBox.width / 2f)
            val centerY = guideBox.y + (guideBox.height / 2f)

            val pointFactory = SurfaceOrientedMeteringPointFactory(
                previewView.width.toFloat(),
                previewView.height.toFloat()
            )
            val point = pointFactory.createPoint(centerX, centerY)

            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build()

            try {
                activeCamera.cameraControl.startFocusAndMetering(action)
            } catch (e: Exception) {
                android.util.Log.d("MainActivity", "Guide-box autofocus request failed", e)
            }
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun addSpinAnimation(button: ImageButton) {
        button.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Spin 180 degrees AND scale down when pressed
                    view.animate()
                        .rotation(180f)
                        .scaleX(0.7f)
                        .scaleY(0.7f)
                        .alpha(0.7f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // Reset rotation and scale when released
                    view.animate()
                        .rotation(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2f))
                        .start()
                }
            }
            false // Return false to allow the click listener to also fire
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun addScaleAnimation(button: ImageButton) {
        button.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Scale down more dramatically when pressed
                    view.animate()
                        .scaleX(0.7f)
                        .scaleY(0.7f)
                        .alpha(0.7f)
                        .setDuration(100)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // Bounce back to normal with overshoot
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2f))
                        .start()
                }
            }
            false // Return false to allow the click listener to also fire
        }
    }

    private fun startScanAnimation() {
        // Get the guide box height to calculate scan range
        val guideBox = findViewById<android.view.View>(R.id.guide_box)
        guideBox.post {
            val scanHeight = guideBox.height

            // Animate the scan line from top to bottom continuously
            scanLine.animate()
                .translationY(-scanHeight / 2f)
                .setDuration(0)
                .withEndAction {
                    animateScanLine(scanHeight)
                }
                .start()
        }
    }

    private fun animateScanLine(scanHeight: Int) {
        scanLine.animate()
            .translationY(scanHeight / 2f)
            .setDuration(2000)
            .setInterpolator(android.view.animation.LinearInterpolator())
            .withEndAction {
                // Reset to top and repeat
                scanLine.animate()
                    .translationY(-scanHeight / 2f)
                    .setDuration(0)
                    .withEndAction {
                        animateScanLine(scanHeight)
                    }
                    .start()
            }
            .start()
    }


    private fun toggleTorch() {
        camera?.let { cam ->
            if (cam.cameraInfo.hasFlashUnit()) {
                isTorchOn = !isTorchOn
                cam.cameraControl.enableTorch(isTorchOn)

                // Update icon
                if (isTorchOn) {
                    btnTorch.setImageResource(R.drawable.ic_flashlight_on)
                    Toast.makeText(this, "Torch may cause glare on glass cover", Toast.LENGTH_LONG).show()
                } else {
                    btnTorch.setImageResource(R.drawable.ic_flashlight_off)
                }
            } else {
                Toast.makeText(this, "No flash available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndShowTutorial() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previousReadingSet = prefs.getBoolean(PREF_PREVIOUS_READING_SET, false)
        val tutorialCompleted = prefs.getBoolean(PREF_TUTORIAL_COMPLETED, false)
        val chargeDateSet = prefs.getBoolean("charge_date_set", false)
        val requiresNewBillingPeriodReading = chargeDateSet && needsBillingPeriodReadingUpdate(prefs)
        
        if (!chargeDateSet) {
            // First run: Ask for charge date first
            findViewById<android.view.View>(R.id.main).postDelayed({
                showChargeDateDialog()
            }, 500)
        } else if (!previousReadingSet || requiresNewBillingPeriodReading) {
            // First run or new billing period: ask for previous reading (receipt present reading)
            findViewById<android.view.View>(R.id.main).postDelayed({
                showPreviousReadingDialog(requiresNewBillingPeriodReading)
            }, 500)
        } else if (!tutorialCompleted) {
            // Second step: Show tutorial
            findViewById<android.view.View>(R.id.main).postDelayed({
                startTutorial()
            }, 500)
        } else {
            // Normal run: Start camera
            if (hasAllRequiredPermissions()) {
                startCamera()
                configureScanAnimationForMode()
            } else {
                requestAllRequiredPermissions()
            }
        }
    }

    private fun needsBillingPeriodReadingUpdate(prefs: android.content.SharedPreferences): Boolean {
        val chargeDate = ChargeDateHelper.getChargeDate(this)
        val currentPeriodKey = ChargeDateHelper.getCurrentBillingPeriodKey(chargeDate)
        val savedPeriodKey = prefs.getString(PREF_PREVIOUS_READING_PERIOD_KEY, null)
        return currentPeriodKey != savedPeriodKey
    }

    private fun showPreviousReadingDialog(isBillingPeriodRollover: Boolean = false) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (isBillingPeriodRollover) "New Billing Period Detected" else "Welcome to Mete:Read")
        builder.setMessage(
            if (isBillingPeriodRollover) {
                "A new billing period started after your charge date.\n\n" +
                    "Please enter the receipt's Present Reading (not the old Previous Reading).\n\n" +
                    "Example: If receipt covers Dec 27 - Jan 28 and today is Jan 29, use Jan 28 Present Reading."
            } else {
                "To calculate your bill accurately, enter the receipt's Present Reading as your baseline Previous Reading.\n\n" +
                    "Important: Do not use the old Previous Reading printed on the receipt."
            }
        )

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.leftMargin = margin
        params.rightMargin = margin
        input.layoutParams = params
        container.addView(input)
        builder.setView(container)

        builder.setPositiveButton("Save") { dialog, _ ->
            val text = input.text.toString()
            if (text.isNotEmpty()) {
                val reading = text.toIntOrNull()
                if (reading != null) {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val chargeDate = ChargeDateHelper.getChargeDate(this)
                    val currentPeriodKey = ChargeDateHelper.getCurrentBillingPeriodKey(chargeDate)
                    prefs.edit()
                        .putInt(PREF_PREVIOUS_READING, reading)
                        .putBoolean(PREF_PREVIOUS_READING_SET, true)
                        .putString(PREF_PREVIOUS_READING_PERIOD_KEY, currentPeriodKey)
                        .apply()
                    
                    // Update analyzer if it exists
                    meterAnalyzer?.setPreviousReading(reading)
                    
                    Toast.makeText(this, "Previous reading saved for current billing period", Toast.LENGTH_SHORT).show()

                    if (!prefs.getBoolean(PREF_TUTORIAL_COMPLETED, false)) {
                        startTutorial()
                    } else {
                        if (hasAllRequiredPermissions()) {
                            startCamera()
                            configureScanAnimationForMode()
                        } else {
                            requestAllRequiredPermissions()
                        }
                    }
                } else {
                    Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show()
                    showPreviousReadingDialog(isBillingPeriodRollover) // Retry
                }
            } else {
                Toast.makeText(this, "Please enter a reading", Toast.LENGTH_SHORT).show()
                showPreviousReadingDialog(isBillingPeriodRollover) // Retry
            }
        }
        
        builder.setCancelable(false) // Force user to input
        builder.show()
    }

    private fun startTutorial() {
        // Stop camera and scanning animation
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            // Ignore
        }
        scanLine.animate().cancel()
        scanLine.visibility = android.view.View.INVISIBLE

        // Remove existing overlay if any
        tutorialOverlay?.let {
            val rootView = findViewById<android.view.ViewGroup>(R.id.main)
            rootView.removeView(it)
        }
        
        // Create fresh tutorial overlay
        tutorialOverlay = TutorialOverlay(this)
        val rootView = findViewById<android.view.ViewGroup>(R.id.main)
        rootView.addView(tutorialOverlay, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))

        isTutorialActive = true
        tutorialOverlay?.startTutorial {
            isTutorialActive = false
            // Mark tutorial as completed
            val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_TUTORIAL_COMPLETED, true).apply()
            
            // Start camera after tutorial
            if (hasAllRequiredPermissions()) {
                startCamera()
                configureScanAnimationForMode()
            } else {
                requestAllRequiredPermissions()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        meterAnalyzer?.shutdown()
        autoPauseAnalyzer?.shutdown()
        hidePausedCaptureLayer(clearBitmap = true)
        synchronized(liveFrameLock) {
            latestLiveFrameBitmap?.recycle()
            latestLiveFrameBitmap = null
        }
        try {
            cropTextRecognizer.close()
        } catch (_: Exception) {
            // no-op
        }
        cameraExecutor?.shutdown()
        // LeNet disabled: no classifier teardown needed.
    }

    /**
     * Apply developer settings to control debug button visibility.
     * Reads from SharedPreferences set in OtherOptionsActivity's Developer Settings.
     */
    private fun applyDeveloperSettings() {
        val devPrefs = getSharedPreferences("developer_settings", Context.MODE_PRIVATE)
        val debugStripEnabled = devPrefs.getBoolean("debug_strip_enabled", false)
        val fakePrevEnabled = devPrefs.getBoolean("fake_prev_enabled", false)
        
        btnDebugStrip.visibility = if (debugStripEnabled) android.view.View.VISIBLE else android.view.View.GONE
        btnDebugStrip.isClickable = debugStripEnabled
        
        btnDebugFakePrevMonth.visibility = if (fakePrevEnabled) android.view.View.VISIBLE else android.view.View.GONE
        btnDebugFakePrevMonth.isClickable = fakePrevEnabled
    }

    /**
     * Show dialog to select CWD charge date (1-28) during initial setup.
     */
    private fun showChargeDateDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Welcome to Mete:Read")
        builder.setMessage("First, let's set up your CWD billing cycle.\n\nWhat day of the month does your CWD bill charge date fall on?\n\nThis is the date shown on your CWD receipt (e.g., if your receipt says \"January 21\", enter 21).")
        
        val container = FrameLayout(this)
        val innerLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val margin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = margin
                rightMargin = margin
            }
        }
        
        val numberPicker = android.widget.NumberPicker(this).apply {
            minValue = 1
            maxValue = 28
            value = 28
            wrapSelectorWheel = true
        }
        innerLayout.addView(numberPicker)
        
        val helpText = android.widget.TextView(this).apply {
            text = "Select a day between 1 and 28"
            textSize = 12f
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
            gravity = android.view.Gravity.CENTER
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        innerLayout.addView(helpText)
        container.addView(innerLayout)
        builder.setView(container)
        
        builder.setPositiveButton("Save") { _, _ ->
            val chargeDate = numberPicker.value
            val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("charge_date", chargeDate)
                .putBoolean("charge_date_set", true)
                .apply()
            Toast.makeText(this, "Charge date set to day $chargeDate of each month", Toast.LENGTH_SHORT).show()
            
            // Continue to previous reading dialog
            showPreviousReadingDialog()
        }
        
        builder.setCancelable(false)
        builder.show()
    }

    // onResume is already defined above
    
    /**
     * DEBUG: Creates a fake scan history entry AND projection for the previous month.
     * This allows testing bill comparison without waiting a real month.
     */
    private fun createFakePreviousMonthScan() {
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val cwdPrevReading = prefs.getInt("previous_reading", 2096)
        
        // Create a fake reading 4 units below CWD reading
        val fakeReading = cwdPrevReading - 4
        
        // Calculate timestamp for end of previous month
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1) // Go to 1st of current month
        calendar.add(java.util.Calendar.DAY_OF_MONTH, -1) // Go back to last day of prev month
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 18)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        val prevMonthTimestamp = calendar.timeInMillis
        val prevYear = calendar.get(java.util.Calendar.YEAR)
        val prevMonth = calendar.get(java.util.Calendar.MONTH) + 1
        
        val monthFormat = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
        val prevMonthName = monthFormat.format(java.util.Date(prevMonthTimestamp))
        
        AlertDialog.Builder(this)
            .setTitle("Create Fake Previous Month Data")
            .setMessage("This will create fake data for $prevMonthName:\n\n" +
                    "• Scan Reading: $fakeReading cu.m\n" +
                    "• Projected Month-End: $fakeReading cu.m\n" +
                    "• CWD Previous: $cwdPrevReading cu.m\n\n" +
                    "Difference: ${cwdPrevReading - fakeReading} cu.m\n\n" +
                    "This enables bill comparison testing.")
            .setPositiveButton("Create") { _, _ ->
                // 1. Create fake scan entry with all required fields
                val historyManager = HistoryManager(this)
                val fakeEntry = ScanHistoryEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = prevMonthTimestamp,
                    currentReading = fakeReading,
                    previousReading = fakeReading - 10,
                    usage = 10,
                    rateType = "RESIDENTIAL_A",
                    waterBill = 50.0,
                    esfFee = 3.50,
                    totalBill = 53.50
                )
                historyManager.addScanEntry(fakeEntry)
                
                // 2. Create fake projection for previous month
                val fakeProjection = com.kokeshi.metersnap.data.ProjectedReading(
                    monthYear = String.format("%04d-%02d", prevYear, prevMonth),
                    projectedReading = fakeReading.toDouble(),
                    lastActualReading = fakeReading,
                    lastActualTimestamp = prevMonthTimestamp,
                    confidenceScore = 1.0,
                    dailyUsageRate = 0.5,
                    dataPointsUsed = 1,
                    rSquared = 1.0,
                    source = com.kokeshi.metersnap.data.ProjectionSource.MANUAL_SCAN,
                    userVerified = true,
                    userActualReading = fakeReading
                )
                projectedReadingManager.saveProjection(fakeProjection)
                
                Toast.makeText(this, 
                    "Created fake data for $prevMonthName:\n• Scan: $fakeReading\n• Projection: $fakeReading",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleScanSuccess(result: String) {
        val currentReading = result.toIntOrNull()
        if (currentReading == null) {
            Toast.makeText(this, "Invalid reading captured", Toast.LENGTH_SHORT).show()
            isProcessingSuccess = false
            return
        }

        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val previousReading = prefs.getInt(PREF_PREVIOUS_READING, -1)

        if (previousReading == -1) {
            // Should not happen if flow is correct, but fallback
            Toast.makeText(this, "Please set Previous Reading in Settings", Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            return
        }

        if (currentReading < previousReading) {
            Toast.makeText(this, "Current reading ($currentReading) is less than previous ($previousReading)!", Toast.LENGTH_LONG).show()
            // Maybe allow them to edit? For now, just reset
            isProcessingSuccess = false
            return
        }

        val usage = currentReading - previousReading
        
        // Calculate Bill
        val ratePreferences = RatePreferences(this)
        val rateType = ratePreferences.getRateType()
        val customRates = ratePreferences.getCustomRates()
        
        val esfRate = if (rateType == WaterRateType.CUSTOM) {
            ratePreferences.getESFRate()
        } else {
            WaterRates.getESFForType(rateType)
        }

        val billResult = BillCalculator.calculateBill(
            usage = usage,
            rateType = rateType,
            customRates = customRates,
            esfRate = esfRate
        )
        
        // DON'T save to history here - let destination activity handle it to prevent duplicates
        
        val isForcedDualBillingEnabled = DeveloperOptions.isForceDualBillingEnabled(this)
        val appPredictedPreviousReading = projectedReadingManager.getAppPredictedPreviousReading()
        val comparisonPreviousReading = if (isForcedDualBillingEnabled) {
            DeveloperOptions.buildForcedAppPreviousReading(currentReading, previousReading)
                ?: appPredictedPreviousReading?.takeIf { it != previousReading }
        } else {
            appPredictedPreviousReading?.takeIf { it != previousReading }
        }

        if (comparisonPreviousReading != null) {
            if (isForcedDualBillingEnabled) {
                Toast.makeText(
                    this,
                    "Developer preview: forced dual billing will not be saved to history",
                    Toast.LENGTH_LONG
                ).show()
            }

            // Navigate to BillComparisonActivity (will save to history on exit unless dev preview is forced)
            val intent = android.content.Intent(this, BillComparisonActivity::class.java).apply {
                putExtra("CURRENT_READING", currentReading)
                putExtra("PREVIOUS_READING", previousReading)
                putExtra("RATE_TYPE", rateType.name)
                putExtra(DeveloperOptions.EXTRA_DEV_FORCED_COMPARISON, isForcedDualBillingEnabled)
                putExtra(DeveloperOptions.EXTRA_FORCED_APP_PREVIOUS_READING, comparisonPreviousReading)
            }
            startActivity(intent)
        } else {
            if (isForcedDualBillingEnabled) {
                Toast.makeText(
                    this,
                    "Developer preview could not create a valid discrepancy for this reading",
                    Toast.LENGTH_LONG
                ).show()
            }

            // Launch BillResultActivity (will save to history on exit)
            val intent = android.content.Intent(this, BillResultActivity::class.java).apply {
                putExtra("USAGE", usage)
                putExtra("CURRENT_READING", currentReading)
                putExtra("PREVIOUS_READING", previousReading)
                putExtra("RATE_TYPE", rateType.name)
                putExtra("WATER_BILL", billResult.waterBill)
                putExtra("ESF_FEE", billResult.esfFee)
                putExtra("TOTAL_BILL", billResult.totalBill)
                
                // Add tier details
                putExtra("TIER_1_USAGE", billResult.tier1Usage)
                putExtra("TIER_1_AMOUNT", billResult.tier1Amount)
                putExtra("TIER_2_USAGE", billResult.tier2Usage)
                putExtra("TIER_2_AMOUNT", billResult.tier2Amount)
                putExtra("TIER_3_USAGE", billResult.tier3Usage)
                putExtra("TIER_3_AMOUNT", billResult.tier3Amount)
                putExtra("TIER_4_USAGE", billResult.tier4Usage)
                putExtra("TIER_4_AMOUNT", billResult.tier4Amount)
                putExtra("TIER_5_USAGE", billResult.tier5Usage)
                putExtra("TIER_5_AMOUNT", billResult.tier5Amount)
            }
            startActivity(intent)
        }
    }
    
    // saveToHistory removed - now handled by destination activities
}