package com.kokeshi.metersnap

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.FocusMeteringAction
import androidx.core.view.GestureDetectorCompat
import java.util.concurrent.TimeUnit
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kokeshi.metersnap.ocr.MeterReadingSnapProcessor
import com.kokeshi.metersnap.ocr.SnapProcessingOutcome
import com.kokeshi.metersnap.ocr.AlgorithmicDigitDetector
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
import com.kokeshi.metersnap.utils.SessionResetManager
import com.kokeshi.metersnap.notifications.EndOfMonthReminderScheduler
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private enum class CameraUiState {
        LIVE,
        PREVIEW_PROCESSING,
        NAVIGATING,
        RESETTING
    }

    private data class SnapSession(
        val sessionId: String,
        val canceled: AtomicBoolean,
        val startedAtElapsedMs: Long,
        val deadlineElapsedMs: Long,
        val timeoutRunnable: Runnable,
        var future: Future<*>? = null,
        var previewVisible: Boolean = false
    )

    private lateinit var cameraContainer: FrameLayout
    private lateinit var capturedPreviewOverlay: FrameLayout
    private lateinit var capturedPreviewImage: ImageView
    private lateinit var snapLoadingSpinner: android.widget.ProgressBar
    private lateinit var btnSnap: ImageButton
    private lateinit var btnLoadImage: ImageButton
    private lateinit var btnManualInput: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnTorch: ImageButton
    private lateinit var btnHelp: ImageButton
    private lateinit var btnDebugStrip: android.widget.Button
    private lateinit var btnDebugFakePrevMonth: android.widget.Button
    private lateinit var scanLine: android.view.View
    private var tutorialOverlay: TutorialOverlay? = null

    private var liveDetectExecutor: ExecutorService? = null
    private var frameBufferExecutor: ExecutorService? = null   // dedicated executor for frame-buffer updates only
    private var snapCoordinatorExecutor: ExecutorService? = null
    private var snapWindowExecutor: ExecutorService? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var activePreviewView: PreviewView? = null
    private var isTorchOn = false
    private var isTutorialActive = false
    private lateinit var projectedReadingManager: ProjectedReadingManager
    private var manualRedirectTriggered = false
    /**
     * Counts how many consecutive snap attempts produced a reading that fell
     * outside [previousReading, projectedMonthEndReading].
     * Reset to 0 on every onResume and on every in-bounds success.
     * When it reaches 2 the user is shown a "possible new session" dialog.
     */
    private var snapOutOfBoundsCount = 0
    private val liveFrameLock = Any()
    private var latestLiveFrameBitmap: Bitmap? = null
    private var capturedPreviewBitmap: Bitmap? = null
    private var lastStableResult = ""
    private var cameraUiState = CameraUiState.LIVE
    private var activeSnapSession: SnapSession? = null

    private val uiResetHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearScanUiRunnable = Runnable { clearScanUiForLiveCamera() }
    private val snapTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isProcessingSuccess = false
    private val cameraExecutor: ExecutorService?
        get() = snapCoordinatorExecutor
    private var isSnapInProgress: Boolean
        get() = cameraUiState == CameraUiState.PREVIEW_PROCESSING && activeSnapSession != null
        set(_) = Unit
    private var isGalleryProcessing = false

    //  Zoom toggle & gesture controls 
    private var is2xZoom = false
    private lateinit var tvZoom1x: TextView
    private lateinit var tvZoom2x: TextView
    private lateinit var gestureOverlay: View
    private var gestureDetector: GestureDetectorCompat? = null

    private enum class ScrollAxis { NONE, HORIZONTAL, VERTICAL }
    private var scrollAxis = ScrollAxis.NONE
    private var exposureAccum = 0f
    private val exposureStepPx = 80f

    //  Slider HUDs 
    private lateinit var zoomSliderContainer: android.widget.FrameLayout
    private lateinit var zoomSliderFill: View
    private lateinit var zoomSliderLabel: TextView
    private lateinit var brightnessSliderContainer: android.widget.FrameLayout
    private lateinit var brightnessSliderFill: View
    private val sliderHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sliderHideRunnable = Runnable { hideSliders() }

    //  Gallery image picker 
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        processGalleryUri(uri)
    }

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val PREFS_NAME = "MeterSnapPrefs"
        private const val PREF_TUTORIAL_COMPLETED = "tutorial_completed"
        private const val PREF_PREVIOUS_READING = "previous_reading"
        private const val PREF_PREVIOUS_READING_SET = "previous_reading_set"
        private const val PREF_PREVIOUS_READING_PERIOD_KEY = "previous_reading_period_key"
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
        capturedPreviewOverlay = findViewById(R.id.captured_preview_overlay)
        capturedPreviewImage = findViewById(R.id.captured_preview_image)
        snapLoadingSpinner = findViewById(R.id.snap_loading_spinner)
        btnSnap = findViewById(R.id.btn_snap)
        btnLoadImage = findViewById(R.id.btn_load_image)
        btnManualInput = findViewById(R.id.btn_manual_input)
        btnSettings = findViewById(R.id.btn_settings)
        btnTorch = findViewById(R.id.btn_torch)
        btnHelp = findViewById(R.id.btn_help)
        btnDebugStrip = findViewById(R.id.btn_debug_strip)
        btnDebugFakePrevMonth = findViewById(R.id.btn_debug_fake_prev_month)
        scanLine = findViewById(R.id.scan_line)
        tvZoom1x = findViewById(R.id.zoom_1x)
        tvZoom2x = findViewById(R.id.zoom_2x)
        gestureOverlay = findViewById(R.id.gesture_overlay)
        zoomSliderContainer = findViewById(R.id.zoom_slider_container)
        zoomSliderFill = findViewById(R.id.zoom_slider_fill)
        zoomSliderLabel = findViewById(R.id.zoom_slider_label)
        brightnessSliderContainer = findViewById(R.id.brightness_slider_container)
        brightnessSliderFill = findViewById(R.id.brightness_slider_fill)

        liveDetectExecutor = Executors.newSingleThreadExecutor()
        frameBufferExecutor = Executors.newSingleThreadExecutor()
        snapCoordinatorExecutor = Executors.newSingleThreadExecutor()
        snapWindowExecutor = Executors.newFixedThreadPool(3)

        // Initialize OpenCV
        if (OpenCVLoader.initDebug()) {
            android.util.Log.d("MainActivity", "OpenCV loaded successfully")
        } else {
            android.util.Log.e("MainActivity", "OpenCV initialization failed!")
        }

        liveDetectExecutor?.execute {
            AlgorithmicDigitDetector.init(applicationContext, threads = 4)
        }

        // Initialize projection manager and schedule reminders
        projectedReadingManager = ProjectedReadingManager(this)
        EndOfMonthReminderScheduler.scheduleReminders(this)

        // Add press animations to buttons
        addScaleAnimation(btnSnap)
        addScaleAnimation(btnLoadImage)
        addScaleAnimation(btnManualInput)
        addSpinAnimation(btnSettings)
        addScaleAnimation(btnTorch)
        addScaleAnimation(btnHelp)

        btnSnap.setOnClickListener {
            if (!isTutorialActive) {
                if (isSnapInProgress) {
                    cancelActiveSnapSessionAndReset()
                } else {
                    startSnapSessionCapture()
                }
            }
        }

        // Load Image: open gallery picker and process the selected photo
        btnLoadImage.setOnClickListener {
            if (!isTutorialActive) {
                if (isSnapInProgress) {
                    cancelActiveSnapSessionAndReset {
                        galleryLauncher.launch("image/*")
                    }
                } else if (!isGalleryProcessing) {
                    galleryLauncher.launch("image/*")
                }
            }
        }

        btnManualInput.setOnClickListener {
            if (!isTutorialActive) {
                if (isSnapInProgress) {
                    cancelActiveSnapSessionAndReset {
                        openManualInput()
                    }
                } else {
                    openManualInput()
                }
            }
        }

        btnSettings.setOnClickListener {
            if (!isTutorialActive) {
                if (isSnapInProgress) {
                    cancelActiveSnapSessionAndReset {
                        openSettings()
                    }
                } else {
                    openSettings()
                }
            }
        }

        btnTorch.setOnClickListener {
            if (!isTutorialActive) {
                toggleTorch()
            }
        }

        btnHelp.setOnClickListener {
            if (!isTutorialActive) {
                if (isSnapInProgress) {
                    cancelActiveSnapSessionAndReset {
                        startTutorial()
                    }
                } else {
                    startTutorial()
                }
            } else {
                Toast.makeText(this, "Complete the tutorial first", Toast.LENGTH_SHORT).show()
            }
        }

        btnDebugStrip.setOnClickListener {
            Toast.makeText(this, "Legacy strip debug is unavailable in snap mode", Toast.LENGTH_SHORT).show()
        }

        btnDebugFakePrevMonth.setOnClickListener {
            createFakePreviousMonthScan()
        }

        // Set up zoom toggle pill and invisible gesture layer
        setupZoomToggle()
        setupGestureOverlay()
        hideCameraStatusViews()

        onBackPressedDispatcher.addCallback(this) {
            if (isSnapInProgress) {
                cancelActiveSnapSessionAndReset()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Apply developer settings for debug buttons visibility
        applyDeveloperSettings()

        // Check if this is the first launch
        checkAndShowTutorial()
    }

    override fun onResume() {
        super.onResume()
        cancelPendingScanUiReset()
        cameraUiState = CameraUiState.LIVE

        // CRITICAL: Reset ALL UI and analyzer state
        lastStableResult = ""
        manualRedirectTriggered = false
        snapOutOfBoundsCount = 0
        isGalleryProcessing = false
        if (::btnLoadImage.isInitialized) btnLoadImage.isEnabled = true

        // Reset UI instructions
        val tvInstructions = findViewById<android.widget.TextView>(R.id.tv_instructions)
        tvInstructions?.text = "Make sure the meter numbers are visible, then press Snap"
        tvInstructions?.setTextColor(ContextCompat.getColor(this, R.color.main_camera_instruction_default))

        val tvLiveResult = findViewById<android.widget.TextView>(R.id.tv_live_result)
        tvLiveResult?.text = ""
        hideCameraStatusViews()

        android.util.Log.d("MainActivity", "onResume: Reset state")

        // Refresh developer settings (debug button visibility)
        applyDeveloperSettings()

        // Check billing period rollover on every resume, not just cold start.
        // If the user left the app in the background across a charge date, they need to
        // enter the new previous reading  otherwise every scan uses the old baseline.
        val resumePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (resumePrefs.getBoolean("charge_date_set", false) && needsBillingPeriodReadingUpdate(resumePrefs)) {
            showPreviousReadingDialog(isBillingPeriodRollover = true)
        }
    }

    override fun onPause() {
        super.onPause()
        cancelPendingScanUiReset()
        cancelActiveSnapSessionAndReset(stopDetector = false)
        // When user navigates away, discard the stale live frame
        synchronized(liveFrameLock) {
            latestLiveFrameBitmap?.recycle()
            latestLiveFrameBitmap = null
        }
        // Cancel pending hide and immediately dismiss sliders
        sliderHideHandler.removeCallbacks(sliderHideRunnable)
        zoomSliderContainer.visibility = View.GONE
        brightnessSliderContainer.visibility = View.GONE
        capturedPreviewOverlay.animate().cancel()
        capturedPreviewOverlay.visibility = View.GONE
        capturedPreviewImage.setImageDrawable(null)
        capturedPreviewBitmap?.recycle()
        capturedPreviewBitmap = null
        cameraUiState = CameraUiState.LIVE
        android.util.Log.d("MainActivity", "onPause: Cleared live frame")
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
            } else {
                Toast.makeText(this, "Camera and storage permissions are required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // 
    // Camera: simple live preview + frame buffer only
    // 

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                startLiveCamera(cameraProvider)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Starts a plain live preview that continuously buffers the latest camera frame.
     * No continuous OCR  the user triggers analysis manually by pressing Snap.
     */
    private fun startLiveCamera(cameraProvider: ProcessCameraProvider) {
        val previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }

        activePreviewView = previewView
        cameraContainer.removeAllViews()
        cameraContainer.addView(previewView)

        preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Image analysis: only kept to maintain a current frame for snapping
        val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1920, 1080))
            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                // (which can take 50-300 ms) never blocks latestLiveFrameBitmap from being
                // refreshed. This eliminates the "motion-blurred / stale image on Snap" bug.
                it.setAnalyzer(frameBufferExecutor!!, androidx.camera.core.ImageAnalysis.Analyzer { image ->
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
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
    }

    private fun startSnapSessionCapture() {
        if (cameraUiState != CameraUiState.LIVE || isSnapInProgress) return
        cancelPendingScanUiReset()

        val fullFrame = synchronized(liveFrameLock) {
            latestLiveFrameBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        } ?: run {
            Toast.makeText(this, "No camera frame yet. Hold still and try again.", Toast.LENGTH_SHORT).show()
            return
        }

        val cropBitmap: Bitmap
        val processingBitmap: Bitmap
        try {
            val shortSide = min(fullFrame.width, fullFrame.height)
            val cropSize = (shortSide * MeterGuideBoxView.FRACTION).toInt().coerceAtLeast(1)
            val cropX = (fullFrame.width - cropSize) / 2
            val cropY = (fullFrame.height - cropSize) / 2

            cropBitmap = Bitmap.createBitmap(fullFrame, cropX, cropY, cropSize, cropSize)
            processingBitmap = Bitmap.createBitmap(fullFrame, cropX, cropY, cropSize, cropSize)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not crop frame. Try again.", Toast.LENGTH_SHORT).show()
            android.util.Log.e("MainActivity", "Guidebox crop failed", e)
            return
        } finally {
            fullFrame.recycle()
        }

        val coordinatorExecutor = snapCoordinatorExecutor ?: run {
            cropBitmap.recycle()
            processingBitmap.recycle()
            return
        }
        val workerExecutor = snapWindowExecutor ?: run {
            cropBitmap.recycle()
            processingBitmap.recycle()
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + 15_000L
        val cancelFlag = AtomicBoolean(false)

        // Fires at 15s if the scan still hasn't finished  routes to "Nothing Detected"
        val timeoutRunnable = Runnable {
            handleSnapProcessingOutcome(sessionId, SnapProcessingOutcome.TimedOut)
        }

        val session = SnapSession(
            sessionId = sessionId,
            canceled = cancelFlag,
            startedAtElapsedMs = startedAt,
            deadlineElapsedMs = deadline,
            timeoutRunnable = timeoutRunnable,
            previewVisible = true
        )

        activeSnapSession = session
        cameraUiState = CameraUiState.PREVIEW_PROCESSING
        showCapturedPreview(cropBitmap)
        setInstructionText("Reading\u2026 (press Snap again to cancel)", android.graphics.Color.YELLOW)
        hideCameraStatusViews()

        // After 3s with no result, show spinner and nudge the user
        snapTimeoutHandler.postDelayed({
            if (cameraUiState == CameraUiState.PREVIEW_PROCESSING) {
                snapLoadingSpinner.visibility = View.VISIBLE
                setInstructionText("Please capture a clearer image", android.graphics.Color.YELLOW)
            }
        }, 3_000L)

        // Hard timeout at 15s
        snapTimeoutHandler.postDelayed(timeoutRunnable, 15_000L)
        session.future = coordinatorExecutor.submit {
            val outcome = try {
                MeterReadingSnapProcessor.process(
                    context = applicationContext,
                    source = processingBitmap,
                    workerExecutor = workerExecutor,
                    deadlineElapsedMs = deadline,
                    shouldCancel = {
                        val currentSession = activeSnapSession
                        cancelFlag.get() ||
                            currentSession?.sessionId != sessionId ||
                            cameraUiState != CameraUiState.PREVIEW_PROCESSING
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Snap scan failed", e)
                SnapProcessingOutcome.Invalid("Processing exception")
            } finally {
                processingBitmap.recycle()
            }

            runOnUiThread {
                handleSnapProcessingOutcome(sessionId, outcome)
            }
        }
    }

    private fun handleSnapProcessingOutcome(
        sessionId: String,
        outcome: SnapProcessingOutcome
    ) {
        val session = activeSnapSession ?: return
        if (session.sessionId != sessionId) return

        snapTimeoutHandler.removeCallbacksAndMessages(null)

        when (outcome) {
            is SnapProcessingOutcome.Success -> {
                lastStableResult = outcome.reading
                routeValidReadingToBillResult(outcome.reading)
            }
            is SnapProcessingOutcome.Invalid -> {
                routeInvalidReadingToManual(
                    partialReading = outcome.partialReading,
                    reason = outcome.reason
                )
            }
            SnapProcessingOutcome.TimedOut -> {
                // Only reachable if the 60-second safety deadline somehow triggers.
                // Treat it the same as an unreadable result.
                routeInvalidReadingToManual(
                    partialReading = null,
                    reason = "Scan could not complete in time"
                )
            }
            SnapProcessingOutcome.Canceled -> {
                cancelActiveSnapSessionAndReset()
            }
        }
    }

    private fun routeValidReadingToBillResult(reading: String) {
        cancelActiveSnapSessionAndReset(stopDetector = false) {
            cameraUiState = CameraUiState.NAVIGATING
            handleScanSuccess(reading)
        }
    }

    /**
     * Called whenever the snap pipeline cannot produce a confirmed reading.
     * Shows a dialog so the user can decide how to proceed rather than being
     * silently pushed into Manual Input.
     */
    private fun routeInvalidReadingToManual(
        partialReading: String? = null,
        reason: String = ""
    ) {
        if (manualRedirectTriggered) return
        manualRedirectTriggered = true

        // Keep the raw partial (with ?/X) for display and smart fill
        val rawPartial = partialReading?.takeIf { it.isNotEmpty() }

        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val previousReading = prefs.getInt(PREF_PREVIOUS_READING, -1).let { if (it == -1) 0 else it }
        val projectedUpperLimit = projectedReadingManager.getCurrentMonthProjection()
            ?.projectedReading?.toInt()

        cancelActiveSnapSessionAndReset(stopDetector = false) {

            if (rawPartial != null && rawPartial.any { it == '?' || it == 'X' } && rawPartial.any { it.isDigit() }) {
                //  Partial read: has some real digits AND some unknowns  try to fill gaps 
                val smartFilled = smartFillPartialReading(rawPartial, previousReading, projectedUpperLimit)
                // Replace unknowns with '0' to keep the full 5-digit structure (filtering drops
                // the position entirely, producing e.g. "0004" from "000?4" which parses as 4
                //  almost always below previousReading  and hides "Use this reading" in the dialog)
                showUnreadableReadingDialog(
                    partialReading = rawPartial,
                    reason = reason,
                    manualPrefill = rawPartial.filter { it.isDigit() }.takeIf { it.isNotEmpty() }
                )

            } else if (rawPartial != null && rawPartial.any { it.isDigit() }) {
                //  Got some digits but pipeline still rejected (e.g. wrong digit count) 
                showUnreadableReadingDialog(
                    partialReading = rawPartial,
                    reason = reason,
                    manualPrefill = rawPartial
                )

            } else {
                //  No readable digits at all (no strip / timeout) 
                showUnreadableReadingDialog(
                    partialReading = null,
                    reason = reason,
                    manualPrefill = null
                )
            }
        }
    }

    private fun showUnreadableReadingDialog(
        partialReading: String?,
        reason: String,
        manualPrefill: String?
    ) {
        val contextMsg = buildString {
            when {
                reason.contains("Detector init failed", ignoreCase = true) -> {
                    append("The scanner model failed to load, so the app could not start image processing.\n\n")
                    append("This is not a bad-photo result. Reopen the app and try again.")
                }
                reason.contains("No strip", ignoreCase = true) -> {
                    append("We couldn't find the meter display in the frame.\n\n")
                    append("Make sure the numbers are clearly visible inside the guide box and try again.")
                }
                reason.contains("timed out", ignoreCase = true) -> {
                    append("It took too long to get a clear reading.\n\n")
                    append("Try holding the phone steadier, or move to better lighting.")
                }
                partialReading != null && partialReading.any { it == '?' || it == 'X' } -> {
                    append("We only got part of the reading: $partialReading\n\n")
                    append("Some digits are still unclear, so it's better to scan again or type it manually.")
                }
                partialReading != null -> {
                    append("We got an incomplete reading: $partialReading\n\n")
                    append("Try centering the meter again, or type the reading in manually.")
                }
                else -> {
                    append("The numbers on the meter weren't clear enough to read.\n\n")
                    append("Try centering the meter again, or type the reading in manually.")
                }
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Couldn't Get a Reading")
            .setMessage(contextMsg)
            .setPositiveButton("Scan Again") { _, _ -> resetToLiveCamera() }
            .setNeutralButton("Enter Manually") { _, _ ->
                cameraUiState = CameraUiState.NAVIGATING
                openManualInput(prefilledReading = manualPrefill)
            }
            .setCancelable(false)
            .show()
    }

    private fun cancelActiveSnapSessionAndReset(
        stopDetector: Boolean = true,
        afterReset: (() -> Unit)? = null
    ) {
        cameraUiState = CameraUiState.RESETTING

        activeSnapSession?.let { session ->
            session.canceled.set(true)
            snapTimeoutHandler.removeCallbacksAndMessages(null) // clears timeout + slow-scan nudge
            session.future?.cancel(true)
        }
        activeSnapSession = null

        cancelPendingScanUiReset()
        dismissCapturedPreviewImmediately()
        hideCameraStatusViews()
        btnSnap.isEnabled = true
        btnLoadImage.isEnabled = true
        btnManualInput.isEnabled = true
        btnSettings.isEnabled = true
        clearScanUiForLiveCamera()

        cameraUiState = CameraUiState.LIVE
        afterReset?.invoke()
    }

    private fun dismissCapturedPreviewImmediately() {
        capturedPreviewOverlay.animate().cancel()
        capturedPreviewOverlay.visibility = View.GONE
        capturedPreviewOverlay.alpha = 1f
        capturedPreviewOverlay.scaleX = 1f
        capturedPreviewOverlay.scaleY = 1f
        capturedPreviewImage.setImageDrawable(null)
        capturedPreviewBitmap?.recycle()
        capturedPreviewBitmap = null
        snapLoadingSpinner.visibility = View.GONE
    }

    private fun openManualInput(prefilledReading: String? = null) {
        cameraUiState = CameraUiState.NAVIGATING
        val intent = Intent(this, ManualInputActivity::class.java)
        if (!prefilledReading.isNullOrBlank()) {
            intent.putExtra("SNAP_READING", prefilledReading)
        }
        startActivity(intent)
    }

    private fun openSettings() {
        cameraUiState = CameraUiState.NAVIGATING
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    //  Gallery / Load Image 

    private fun processGalleryUri(uri: Uri) {
        if (isGalleryProcessing || isSnapInProgress) {
            Toast.makeText(this, "Please wait for the current scan to finish.", Toast.LENGTH_SHORT).show()
            return
        }
        cancelPendingScanUiReset()

        val bitmap = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(contentResolver, uri)
                ) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to decode gallery image", e)
            Toast.makeText(this, "Could not open image. Please try another.", Toast.LENGTH_SHORT).show()
            return
        }

        showGalleryCropDialog(bitmap)
    }

    private fun showGalleryCropDialog(sourceBitmap: Bitmap) {
        var workingBitmap = sourceBitmap
        val density = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }

        val title = TextView(this).apply {
            text = "Rotate and crop the meter area"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        root.addView(title)

        val previewFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val previewImage = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(workingBitmap)
        }
        val cropOverlay = CropSelectionView(this, null).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setActive(true)
        }
        previewFrame.addView(previewImage)
        previewFrame.addView(cropOverlay)
        root.addView(previewFrame)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val iconSize  = (44 * density).toInt()
        val iconPad   = (10 * density).toInt()
        val iconBg    = getDrawable(R.drawable.bg_circle_icon_button)

        fun makeIconBtn(drawableRes: Int): android.widget.ImageButton =
            android.widget.ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                background = iconBg?.constantState?.newDrawable()?.mutate()
                setImageResource(drawableRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(iconPad, iconPad, iconPad, iconPad)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            }

        val rotateLeft  = makeIconBtn(R.drawable.ic_rotate_left)
        val rotateRight = makeIconBtn(R.drawable.ic_rotate_right)
        val cancelButton = makeIconBtn(R.drawable.ic_x_button)
        val scanButton   = makeIconBtn(R.drawable.ic_check)

        buttonRow.addView(rotateLeft)
        buttonRow.addView(View(this).apply {           // spacer between rotate and action buttons
            layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
        })
        buttonRow.addView(rotateRight)
        buttonRow.addView(View(this).apply {           // flex spacer
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        buttonRow.addView(cancelButton)
        buttonRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
        })
        buttonRow.addView(scanButton)
        root.addView(buttonRow)

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .setCancelable(false)
            .create()

        fun resetSelection() {
            cropOverlay.post {
                val side = (min(cropOverlay.width, cropOverlay.height) * 0.8f).coerceAtLeast(120f)
                val left = (cropOverlay.width - side) / 2f
                val top = (cropOverlay.height - side) / 2f
                cropOverlay.setSelection(RectF(left, top, left + side, top + side))
            }
        }

        fun refreshPreview() {
            previewImage.setImageBitmap(workingBitmap)
            cropOverlay.clearSelection()
            resetSelection()
        }

        rotateLeft.setOnClickListener {
            val rotated = rotateBitmap(workingBitmap, -90f)
            if (rotated !== workingBitmap) {
                workingBitmap.recycle()
                workingBitmap = rotated
            }
            refreshPreview()
        }

        rotateRight.setOnClickListener {
            val rotated = rotateBitmap(workingBitmap, 90f)
            if (rotated !== workingBitmap) {
                workingBitmap.recycle()
                workingBitmap = rotated
            }
            refreshPreview()
        }

        cancelButton.setOnClickListener {
            workingBitmap.recycle()
            dialog.dismiss()
        }

        scanButton.setOnClickListener {
            val selection = cropOverlay.getSelection()
            if (selection == null) {
                Toast.makeText(this, "Select a square crop first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val bitmapRect = mapFitCenterRectToBitmapRect(selection, cropOverlay, workingBitmap)
            if (bitmapRect == null) {
                Toast.makeText(this, "Could not crop that area. Try again.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val croppedBitmap = try {
                Bitmap.createBitmap(
                    workingBitmap,
                    bitmapRect.left,
                    bitmapRect.top,
                    bitmapRect.width(),
                    bitmapRect.height()
                )
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to crop gallery image", e)
                Toast.makeText(this, "Could not crop that area. Try again.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            workingBitmap.recycle()
            dialog.dismiss()
            processGalleryBitmap(croppedBitmap)
        }

        dialog.setOnDismissListener {
            cropOverlay.setActive(false)
        }

        dialog.show()
        resetSelection()
    }

    private fun processGalleryBitmap(bitmap: Bitmap) {
        isGalleryProcessing = true
        btnLoadImage.isEnabled = false
        btnSnap.isEnabled = false

        val previewBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        showCapturedPreview(previewBitmap)
        setInstructionText("Reading gallery image\u2026", android.graphics.Color.YELLOW)
        hideCameraStatusViews()

        Thread {
            val result = try {
                MeterReadingSnapProcessor.process(applicationContext, bitmap)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Gallery scan failed", e)
                null
            } finally {
                bitmap.recycle()
            }

            runOnUiThread {
                isGalleryProcessing = false
                btnLoadImage.isEnabled = true
                btnSnap.isEnabled = true

                val tvLiveResult = findViewById<TextView>(R.id.tv_live_result)

                if (result == null) {
                    tvLiveResult?.text = ""
                    setInstructionText("Gallery scan failed. Try a different image.", android.graphics.Color.RED)
                    hideCapturedPreview(delayMs = 180L)
                    scheduleScanUiReset(1200L)
                    return@runOnUiThread
                }

                lastStableResult = result.readingText

                when {
                    result.isComplete -> {
                        tvLiveResult?.text = result.readingText
                        setInstructionText("Read complete: ${result.readingText}", android.graphics.Color.GREEN)
                        hideCapturedPreview(delayMs = 220L)
                        handleScanSuccess(result.readingText)
                    }
                    result.readingText.any { it == 'X' || it == '?' } && result.readingText != "?????" -> {
                        tvLiveResult?.text = result.readingText
                        setInstructionText("Best match shown. Review and correct if needed.", android.graphics.Color.YELLOW)
                        hideCapturedPreview(delayMs = 220L)
                        handleScanSuccess(result.readingText)
                    }
                    else -> {
                        tvLiveResult?.text = ""
                        setInstructionText("Nothing readable found. Try a different image.", android.graphics.Color.RED)
                        scheduleScanUiReset(1200L)
                    }
                }
            }
        }.start()
    }

    private fun hideCameraStatusViews() {
        findViewById<TextView>(R.id.tv_live_result)?.visibility = View.GONE
    }

    private fun setInstructionText(text: String, color: Int) {
        findViewById<TextView>(R.id.tv_instructions)?.apply {
            this.text = text
            setTextColor(color)
        }
    }

    private fun showCapturedPreview(bitmap: Bitmap) {
        capturedPreviewBitmap?.recycle()
        capturedPreviewBitmap = bitmap
        capturedPreviewImage.setImageBitmap(bitmap)
        capturedPreviewOverlay.visibility = View.VISIBLE
        capturedPreviewOverlay.alpha = 0f
        capturedPreviewOverlay.scaleX = 0.96f
        capturedPreviewOverlay.scaleY = 0.96f
        capturedPreviewOverlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140)
            .start()
    }

    private fun hideCapturedPreview(delayMs: Long = 0L) {
        capturedPreviewOverlay.postDelayed({
            capturedPreviewOverlay.animate()
                .alpha(0f)
                .setDuration(140)
                .withEndAction {
                    capturedPreviewOverlay.visibility = View.GONE
                    capturedPreviewOverlay.alpha = 1f
                    capturedPreviewOverlay.scaleX = 1f
                    capturedPreviewOverlay.scaleY = 1f
                    capturedPreviewImage.setImageDrawable(null)
                    capturedPreviewBitmap?.recycle()
                    capturedPreviewBitmap = null
                }
                .start()
        }, delayMs)
    }

    private fun scheduleScanUiReset(delayMs: Long) {
        uiResetHandler.removeCallbacks(clearScanUiRunnable)
        uiResetHandler.postDelayed(clearScanUiRunnable, delayMs)
    }

    private fun cancelPendingScanUiReset() {
        uiResetHandler.removeCallbacks(clearScanUiRunnable)
    }

    private fun clearScanUiForLiveCamera() {
        if (isSnapInProgress) return

        hideCameraStatusViews()
    }

    private fun mapFitCenterRectToBitmapRect(
        viewRect: RectF,
        sourceView: View,
        bitmap: Bitmap
    ): Rect? {
        if (sourceView.width <= 0 || sourceView.height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }

        val viewW = sourceView.width.toFloat()
        val viewH = sourceView.height.toFloat()
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
            viewRect.left.coerceIn(drawLeft, drawRight),
            viewRect.top.coerceIn(drawTop, drawBottom),
            viewRect.right.coerceIn(drawLeft, drawRight),
            viewRect.bottom.coerceIn(drawTop, drawBottom)
        )
        if (clipped.width() <= 1f || clipped.height() <= 1f) return null

        val leftPx = ((clipped.left - drawLeft) / scale).toInt().coerceIn(0, bitmap.width - 1)
        val topPx = ((clipped.top - drawTop) / scale).toInt().coerceIn(0, bitmap.height - 1)
        val rightPx = ((clipped.right - drawLeft) / scale).toInt().coerceIn(leftPx + 1, bitmap.width)
        val bottomPx = ((clipped.bottom - drawTop) / scale).toInt().coerceIn(topPx + 1, bitmap.height)
        return Rect(leftPx, topPx, rightPx, bottomPx)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun rotateFrameToUpright(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    // 
    // UI helpers
    // 

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

    // 
    // Zoom toggle pill
    // 

    private fun setupZoomToggle() {
        updateZoomToggleUI(false)

        tvZoom1x.setOnClickListener {
            if (is2xZoom) {
                is2xZoom = false
                updateZoomToggleUI(false)
                camera?.cameraControl?.setZoomRatio(1f)
            }
        }

        tvZoom2x.setOnClickListener {
            if (!is2xZoom) {
                is2xZoom = true
                updateZoomToggleUI(true)
                val maxRatio = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 2f
                camera?.cameraControl?.setZoomRatio(minOf(2f, maxRatio))
            }
        }
    }

    private fun updateZoomToggleUI(is2x: Boolean) {
        val params1x = tvZoom1x.layoutParams as LinearLayout.LayoutParams
        val params2x = tvZoom2x.layoutParams as LinearLayout.LayoutParams

        if (is2x) {
            params1x.weight = 1f
            params2x.weight = 2f
            tvZoom1x.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            tvZoom2x.setBackgroundResource(R.drawable.bg_zoom_active)
            tvZoom1x.alpha = 0.55f
            tvZoom2x.alpha = 1f
        } else {
            params1x.weight = 2f
            params2x.weight = 1f
            tvZoom1x.setBackgroundResource(R.drawable.bg_zoom_active)
            tvZoom2x.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            tvZoom1x.alpha = 1f
            tvZoom2x.alpha = 0.55f
        }

        tvZoom1x.layoutParams = params1x
        tvZoom2x.layoutParams = params2x
    }

    // 
    // Invisible gesture overlay: tap-focus / swipe-zoom / swipe-exposure
    // 

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupGestureOverlay() {
        val listener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Reset scroll lock for every new touch
                scrollAxis = ScrollAxis.NONE
                exposureAccum = 0f
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                performTapToFocus(e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Lock to the dominant axis for this gesture
                if (scrollAxis == ScrollAxis.NONE) {
                    scrollAxis = if (abs(distanceX) >= abs(distanceY))
                        ScrollAxis.HORIZONTAL else ScrollAxis.VERTICAL
                }
                when (scrollAxis) {
                    ScrollAxis.HORIZONTAL -> handleZoomScroll(distanceX)
                    ScrollAxis.VERTICAL -> handleExposureScroll(distanceY)
                    ScrollAxis.NONE -> {}
                }
                return true
            }
        }

        gestureDetector = GestureDetectorCompat(this, listener)

        gestureOverlay.setOnTouchListener { _, event ->
            if (isTutorialActive) return@setOnTouchListener false
            gestureDetector?.onTouchEvent(event)
            true
        }
    }

    private fun handleZoomScroll(distanceX: Float) {
        val previewView = activePreviewView ?: return
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return

        val currentRatio = zoomState.zoomRatio
        val minRatio = zoomState.minZoomRatio
        val maxRatio = zoomState.maxZoomRatio

        // distanceX = previousX - currentX; negative when swiping right (zoom in)
        val delta = -distanceX / previewView.width.toFloat() * (maxRatio - minRatio) * 2f
        val newRatio = (currentRatio + delta).coerceIn(minRatio, maxRatio)
        cam.cameraControl.setZoomRatio(newRatio)

        // Keep toggle UI in sync with swipe-controlled zoom
        val shouldBe2x = newRatio >= 1.5f
        if (shouldBe2x != is2xZoom) {
            is2xZoom = shouldBe2x
            updateZoomToggleUI(is2xZoom)
        }

        // Show zoom slider HUD
        showZoomSlider(newRatio, minRatio, maxRatio)
    }

    private fun handleExposureScroll(distanceY: Float) {
        // distanceY = previousY - currentY; positive when swiping up (brighter)
        exposureAccum += distanceY
        val steps = (exposureAccum / exposureStepPx).toInt()
        if (steps == 0) return

        exposureAccum -= steps * exposureStepPx

        val cam = camera ?: return
        val exposureState = cam.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) return

        val current = exposureState.exposureCompensationIndex
        val range = exposureState.exposureCompensationRange
        val newIdx = (current + steps).coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(newIdx)

        // Show brightness slider HUD
        showBrightnessSlider(newIdx, range.lower, range.upper)
    }

    private fun performTapToFocus(x: Float, y: Float) {
        val previewView = activePreviewView ?: return
        val cam = camera ?: return

        // Convert gesture_overlay-local coords ™ absolute screen coords first,
        // then subtract each target view's screen origin. This makes the
        // intermediate step explicit and avoids the downward-bias that occurs
        // when previewView and gestureOverlay have different screen Y origins
        // (e.g. previewView sits behind the status bar at y=0 while the overlay
        // starts below the top bar).
        val overlayLoc = IntArray(2)
        gestureOverlay.getLocationOnScreen(overlayLoc)
        val screenX = x + overlayLoc[0]
        val screenY = y + overlayLoc[1]

        // ™ previewView-local coords for the metering factory
        val previewLoc = IntArray(2)
        previewView.getLocationOnScreen(previewLoc)
        val previewX = (screenX - previewLoc[0]).coerceIn(0f, previewView.width.toFloat())
        val previewY = (screenY - previewLoc[1]).coerceIn(0f, previewView.height.toFloat())

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(previewX, previewY)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)

        // Show gold focus ring at tap point in root-view coords
        val root = findViewById<android.view.ViewGroup>(R.id.main)
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val rootX = screenX - rootLoc[0]
        val rootY = screenY - rootLoc[1]
        showFocusRingAt(rootX, rootY, root)
    }

    private fun showFocusRingAt(x: Float, y: Float, root: android.view.ViewGroup) {
        val sizePx = (56f * resources.displayMetrics.density).toInt()
        val borderPx = (2f * resources.displayMetrics.density).toInt()

        val ring = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(borderPx, 0xFFFFD700.toInt())
                setColor(android.graphics.Color.TRANSPARENT)
            }
            elevation = 20f
        }

        root.addView(ring, android.view.ViewGroup.LayoutParams(sizePx, sizePx))
        ring.x = x - sizePx / 2f
        ring.y = y - sizePx / 2f
        ring.scaleX = 1.5f
        ring.scaleY = 1.5f
        ring.alpha = 1f

        ring.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                ring.animate()
                    .alpha(0f)
                    .setStartDelay(500)
                    .setDuration(300)
                    .withEndAction { root.removeView(ring) }
                    .start()
            }
            .start()
    }

    // 
    // Slider HUDs (zoom + brightness)
    // 

    private fun showZoomSlider(ratio: Float, minRatio: Float, maxRatio: Float) {
        val fraction = ((ratio - minRatio) / (maxRatio - minRatio).coerceAtLeast(0.001f)).coerceIn(0f, 1f)

        if (zoomSliderContainer.visibility != View.VISIBLE) {
            zoomSliderContainer.alpha = 0f
            zoomSliderContainer.visibility = View.VISIBLE
            zoomSliderContainer.animate().alpha(1f).setDuration(180).start()
        }

        zoomSliderLabel.text = String.format("%.1f\u00D7", ratio)

        // Update fill width after container is laid out
        zoomSliderContainer.post {
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            val trackWidth = (zoomSliderContainer.width - 2 * paddingPx).coerceAtLeast(1)
            val fillWidth = (trackWidth * fraction).toInt().coerceAtLeast(0)
            val lp = zoomSliderFill.layoutParams
            lp.width = fillWidth
            zoomSliderFill.layoutParams = lp
        }

        scheduleSliderHide()
    }

    private fun showBrightnessSlider(current: Int, lower: Int, upper: Int) {
        val range = (upper - lower).coerceAtLeast(1).toFloat()
        // Center (0 EV) = 50 %; higher index = brighter = larger fill from bottom
        val fraction = ((current - lower) / range).coerceIn(0f, 1f)

        if (brightnessSliderContainer.visibility != View.VISIBLE) {
            brightnessSliderContainer.alpha = 0f
            brightnessSliderContainer.visibility = View.VISIBLE
            brightnessSliderContainer.animate().alpha(1f).setDuration(180).start()
        }

        // Update fill height after container is laid out
        brightnessSliderContainer.post {
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            val trackHeight = (brightnessSliderContainer.height - 2 * paddingPx).coerceAtLeast(1)
            val fillHeight = (trackHeight * fraction).toInt().coerceAtLeast(0)
            val lp = brightnessSliderFill.layoutParams
            lp.height = fillHeight
            brightnessSliderFill.layoutParams = lp
        }

        scheduleSliderHide()
    }

    private fun scheduleSliderHide() {
        sliderHideHandler.removeCallbacks(sliderHideRunnable)
        sliderHideHandler.postDelayed(sliderHideRunnable, 1500)
    }

    private fun hideSliders() {
        fun fadeOut(v: View) {
            if (v.visibility != View.VISIBLE) return
            v.animate().alpha(0f).setDuration(250).withEndAction {
                v.visibility = View.GONE
                v.alpha = 1f
            }.start()
        }
        fadeOut(zoomSliderContainer)
        fadeOut(brightnessSliderContainer)
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

    // 
    // Tutorial & first-run setup
    // 

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
        val historyManager = HistoryManager(this)
        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (isBillingPeriodRollover) "New Billing Period Detected" else "Welcome to METEREAD")
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
        builder.setPositiveButton("Save", null)
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
        builder.setCancelable(true)
        val dialog = builder.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString().trim()
            when {
                text.isEmpty() -> {
                    Toast.makeText(this, "Please enter a reading", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val reading = text.toIntOrNull()
                    when {
                        reading == null -> {
                            Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                        }
                        reading !in 0..99999 -> {
                            Toast.makeText(this, "Reading must be between 0 and 99999", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            val latestSavedReading = historyManager.getLatestEntry()?.currentReading
                            if (latestSavedReading != null && reading < latestSavedReading) {
                                dialog.dismiss()
                                showBaselineInconsistencyDialog(reading, latestSavedReading)
                                return@setOnClickListener
                            }

                            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            val chargeDate = ChargeDateHelper.getChargeDate(this)
                            val currentPeriodKey = ChargeDateHelper.getCurrentBillingPeriodKey(chargeDate)
                            prefs.edit()
                                .putInt(PREF_PREVIOUS_READING, reading)
                                .putBoolean(PREF_PREVIOUS_READING_SET, true)
                                .putString(PREF_PREVIOUS_READING_PERIOD_KEY, currentPeriodKey)
                                .apply()

                            Toast.makeText(this, "Previous reading saved for current billing period", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()

                            if (!prefs.getBoolean(PREF_TUTORIAL_COMPLETED, false)) {
                                startTutorial()
                            } else {
                                if (hasAllRequiredPermissions()) {
                                    startCamera()
                                } else {
                                    requestAllRequiredPermissions()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showBaselineInconsistencyDialog(enteredReading: Int, latestSavedReading: Int) {
        snapOutOfBoundsCount = 0
        AlertDialog.Builder(this)
            .setTitle("Inconsistencies Detected")
            .setMessage(
                "The reading you entered ($enteredReading) is lower than your latest saved reading ($latestSavedReading).\n\n" +
                    "This usually means your current history belongs to an older meter session. Start a new session to reset history and projections before using this lower baseline."
            )
            .setPositiveButton("Start New Session") { _, _ ->
                startActivity(Intent(this, HistorySettingsActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTutorial() {
        // Keep the camera running so the guide box step shows the live feed.
        // (If triggered from the help button while camera is already up this is a no-op;
        //  if it's the very first launch the camera hasn't started yet  handled below.)
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

            // Only start the camera if it wasn't already running (first-launch path).
            // When the help button triggers the tutorial the camera stays live throughout.
            if (camera == null) {
                if (hasAllRequiredPermissions()) {
                    startCamera()
                } else {
                    requestAllRequiredPermissions()
                }
            }
        }
    }

    // 
    // Developer / debug helpers
    // 

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
        builder.setTitle("Welcome to METEREAD")
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
                    "\u2022 Scan Reading: $fakeReading m\u00B3\n" +
                    "\u2022 Projected Month-End: $fakeReading m\u00B3\n" +
                    "\u2022 CWD Previous: $cwdPrevReading m\u00B3\n\n" +
                    "Difference: ${cwdPrevReading - fakeReading} m\u00B3\n\n" +
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
                    "Created fake data for $prevMonthName:\n\u2022 Scan: $fakeReading m\u00B3\n\u2022 Projection: $fakeReading m\u00B3",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // 
    // Scan success: bill calculation and navigation
    // 

    /**
     * Reset all snap-related state so the user can take a fresh snap.
     * Called from any "Scan Again" action inside a post-snap dialog.
     */
    private fun resetToLiveCamera() {
        manualRedirectTriggered = false
        snapOutOfBoundsCount = 0
        cameraUiState = CameraUiState.LIVE
    }

    /**
     * Attempts to fill in unreadable digit placeholders ('?' or 'X') in [partial]
     * by enumerating all digit combinations and keeping only those that fall within
     * the valid range [prevReading, upperLimit].
     *
     * - 1 valid completion  ™ return it (high confidence)
     * - Multiple completions ™ return the one closest to prevReading (conservative)
     * - 0 completions        ™ return null (can't infer)
     *
     * Example: partial="0007?", prevReading=66, upperLimit=80
     *   Tries 0007000079, all fall in [66,80] ™ picks 00070 (closest to prev)
     */
    private fun smartFillPartialReading(
        partial: String,
        prevReading: Int,
        upperLimit: Int?
    ): String? {
        val unknownPos = partial.indices.filter { partial[it] == '?' || partial[it] == 'X' }
        if (unknownPos.isEmpty()) {
            val v = partial.toIntOrNull() ?: return null
            return if (v >= prevReading && (upperLimit == null || v <= upperLimit)) partial else null
        }

        val combinations = Math.pow(10.0, unknownPos.size.toDouble()).toInt()
        val valid = mutableListOf<String>()

        for (combo in 0 until combinations) {
            val chars = partial.toCharArray()
            var temp = combo
            for (pos in unknownPos.reversed()) {
                chars[pos] = '0' + (temp % 10)
                temp /= 10
            }
            val candidate = String(chars)
            val v = candidate.toIntOrNull() ?: continue
            if (v >= prevReading && (upperLimit == null || v <= upperLimit)) {
                valid.add(candidate)
            }
        }

        return when {
            valid.isEmpty() -> null
            valid.size == 1 -> valid.first()
            // Multiple: pick closest to prevReading (most conservative / likely reading)
            else -> valid.minByOrNull { it.toInt() - prevReading }
        }
    }

    /**
     * Shows an in-app dialog that lets the user confirm or fix a reading without
     * navigating away to ManualInputActivity. On confirm, goes straight to bill calculation.
     *
     * This is the primary correction surface for partial / misread results  the same
     * concept as the edit-pencil in BillResultActivity but triggered right after the snap.
     *
     * @param scannedDisplay  What the OCR actually produced (shown as context, may have ?/X).
     * @param prefill         Best-guess value to pre-fill the EditText with.
     * @param previousReading Lower bound for validation.
     * @param projectedUpperLimit Upper bound (if available). Readings above this go to calculator mode.
     */
    /**
     * Shows a decision dialog after a partial/ambiguous read.
     * No EditText  the user either uses the inferred reading as-is and corrects
     * on the Bill Result screen via the pencil, scans again, or uses Calculator mode.
     *
     * @param scannedDisplay  Raw OCR output (may contain ?/X), shown as context.
     * @param prefill         Smart-filled best guess (or clean partial digits).
     * @param previousReading Lower bound for a normal session.
     * @param projectedUpperLimit Upper bound (null = no projection yet).
     */
    private fun showInAppReadingConfirmDialog(
        scannedDisplay: String,
        prefill: String,
        previousReading: Int,
        projectedUpperLimit: Int?
    ) {
        val hasBestGuess = prefill.isNotEmpty() && prefill != scannedDisplay
            && scannedDisplay.any { it == '?' || it == 'X' }

        val msgBuilder = StringBuilder()
        msgBuilder.append("What we picked up: $scannedDisplay")
        if (hasBestGuess) msgBuilder.append("\nMost likely reading: $prefill")
        msgBuilder.append(
            "\n\nYou'll see the reading on the next screen. " +
            "Tap the pencil icon there if you need to correct it."
        )

        val readingToUse = prefill.toIntOrNull()

        // For partial reads (scanned display still has ?/X), always offer "Use this reading"
        // even if the value is below previousReading  OCR was uncertain so the user should
        // be able to proceed and correct via the pencil on the Bill Result screen.
        val hasPartialUnknowns = scannedDisplay.any { it == '?' || it == 'X' }
        val hasUseThis = readingToUse != null && (readingToUse >= previousReading || hasPartialUnknowns)

        val items = buildList {
            if (hasUseThis) {
                add("Use this reading  ($readingToUse)")
            }
            add("Use as Calculator  (choose any previous reading)")
            add("Scan Again")
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Confirm Reading")
            .setMessage(msgBuilder.toString())
            .setItems(items) { _, which ->
                var idx = which
                if (hasUseThis) {
                    when (idx) {
                        0 -> {
                            cameraUiState = CameraUiState.NAVIGATING
                            if (readingToUse!! < previousReading) {
                                // Partial fill produced an implausibly low number (OCR filled '0'
                                // into unknown slots). Sending this to BillCalculator gives negative
                                // usage ™  bill screen. Route to Manual Input instead so the user
                                // can type the actual reading without confusion.
                                openManualInput(prefilledReading = readingToUse.toString())
                            } else {
                                val calcMode = projectedUpperLimit != null && readingToUse > projectedUpperLimit
                                proceedToBillFromSnap(readingToUse, previousReading, calculatorMode = calcMode)
                            }
                        }
                        1 -> showCalculatorPreviousReadingDialog(readingToUse ?: 0)
                        2 -> resetToLiveCamera()
                    }
                } else {
                    when (idx) {
                        0 -> showCalculatorPreviousReadingDialog(readingToUse ?: 0)
                        1 -> resetToLiveCamera()
                    }
                }
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Shown when the user picks "Use as Calculator". Asks what previous reading
     * to subtract from  useful when calculating for someone else's meter or
     * when the stored previous doesn't apply to this reading.
     */
    private fun showCalculatorPreviousReadingDialog(scannedReading: Int) {
        val suggestedPrev = (scannedReading - 1).coerceAtLeast(0)

        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "e.g. $suggestedPrev"
            setText(suggestedPrev.toString())
            setSelection(text.length)
        }
        val margin = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            addView(input, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.leftMargin = margin; it.rightMargin = margin; it.topMargin = margin / 2 })
        }

        val readingSpan = android.text.SpannableStringBuilder()
            .append("Reading we got: ")
            .also { sb ->
                val start = sb.length
                sb.append(scannedReading.toString())
                sb.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, sb.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            .append("\n\nWhat was the reading at the start of this billing period?")

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Quick Bill Estimate")
            .setMessage(readingSpan)
            .setView(container)
            .setPositiveButton("Calculate", null)
            .setNegativeButton("Cancel") { _, _ -> resetToLiveCamera() }
            .setCancelable(false)
            .show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = input.text.toString().trim()
            val prevReading = entered.toIntOrNull()
            when {
                prevReading == null ->
                    Toast.makeText(this, "Please enter a number", Toast.LENGTH_SHORT).show()
                prevReading > scannedReading ->
                    Toast.makeText(
                        this,
                        "The starting reading can't be higher than the current one",
                        Toast.LENGTH_LONG
                    ).show()
                else -> {
                    dialog.dismiss()
                    cameraUiState = CameraUiState.NAVIGATING
                    proceedToBillFromSnap(scannedReading, prevReading, calculatorMode = true)
                }
            }
        }
    }

    /**
     * Calculate the bill for [currentReading] and navigate to the result screen.
     * Shared by the normal snap path, all override dialogs, and calculator mode.
     *
     * @param calculatorMode  When true, history is not saved and bill comparison is skipped.
     */
    private fun proceedToBillFromSnap(
        currentReading: Int,
        previousReading: Int,
        calculatorMode: Boolean = false,
        usageOverride: Int? = null
    ) {
        if (!calculatorMode) {
            val latestSavedCurrentReading = HistoryManager(this).getLatestEntry()?.currentReading
            val isPossibleMeterRollover =
                latestSavedCurrentReading != null &&
                    latestSavedCurrentReading > 80000 &&
                    currentReading < 20000

            if (latestSavedCurrentReading != null &&
                currentReading < latestSavedCurrentReading &&
                !isPossibleMeterRollover
            ) {
                showCurrentReadingRegressionDialog(
                    currentReading = currentReading,
                    previousReading = previousReading,
                    latestSavedReading = latestSavedCurrentReading
                )
                return
            }
        }

        val ratePreferences = RatePreferences(this)
        val rateType = ratePreferences.getRateType()
        val customRates = ratePreferences.getCustomRates()
        val usage = usageOverride ?: (currentReading - previousReading)
        val rateTableManager = RateTableManager(this)
        val billResult: BillResult = if (rateType == WaterRateType.CUSTOM) {
            val esfRate = ratePreferences.getESFRate()
            BillCalculator.calculateBill(
                usage = usage,
                rateType = rateType,
                customRates = customRates,
                esfRate = esfRate
            )
        } else {
            val serviceType = rateTableManager.getServiceType(rateType.name)
            if (serviceType != null) {
                BillCalculator.calculateBillDynamic(
                    usage = usage,
                    serviceType = serviceType,
                    esfRate = rateTableManager.getEsfRate()
                )
            } else {
                val esfRate = WaterRates.getESFForType(rateType)
                BillCalculator.calculateBill(
                    usage = usage,
                    rateType = rateType,
                    customRates = customRates,
                    esfRate = esfRate
                )
            }
        }

        if (calculatorMode) {
            // Calculator mode: skip comparison and history  go straight to BillResultActivity
            startActivity(android.content.Intent(this, BillResultActivity::class.java).apply {
                putExtra("USAGE", usage)
                putExtra("CURRENT_READING", currentReading)
                putExtra("PREVIOUS_READING", previousReading)
                putExtra("RATE_TYPE", rateType.name)
                putExtra("WATER_BILL", billResult.waterBill)
                putExtra("ESF_FEE", billResult.esfFee)
                putExtra("TOTAL_BILL", billResult.totalBill)
                putExtra("CALCULATOR_MODE", true)
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
            })
            return
        }

        // Normal mode: apply dual-billing comparison logic if applicable
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
            startActivity(android.content.Intent(this, BillComparisonActivity::class.java).apply {
                putExtra("CURRENT_READING", currentReading)
                putExtra("PREVIOUS_READING", previousReading)
                putExtra("RATE_TYPE", rateType.name)
                putExtra(DeveloperOptions.EXTRA_DEV_FORCED_COMPARISON, isForcedDualBillingEnabled)
                putExtra(DeveloperOptions.EXTRA_FORCED_APP_PREVIOUS_READING, comparisonPreviousReading)
            })
        } else {
            if (isForcedDualBillingEnabled) {
                Toast.makeText(
                    this,
                    "Developer preview could not create a valid discrepancy for this reading",
                    Toast.LENGTH_LONG
                ).show()
            }
            startActivity(android.content.Intent(this, BillResultActivity::class.java).apply {
                putExtra("USAGE", usage)
                putExtra("CURRENT_READING", currentReading)
                putExtra("PREVIOUS_READING", previousReading)
                putExtra("RATE_TYPE", rateType.name)
                putExtra("WATER_BILL", billResult.waterBill)
                putExtra("ESF_FEE", billResult.esfFee)
                putExtra("TOTAL_BILL", billResult.totalBill)
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
            })
        }
    }

    private fun showCurrentReadingRegressionDialog(
        currentReading: Int,
        previousReading: Int,
        latestSavedReading: Int
    ) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Inconsistencies Detected")
            .setMessage(
                "The new reading ($currentReading) is lower than your latest saved reading " +
                    "($latestSavedReading).\n\n" +
                    "Water usage should not go backward within the same session.\n\n" +
                    "If this is from an older meter session, start a new session first. " +
                    "Otherwise, use Calculator so history and projections stay unchanged."
            )
            .setPositiveButton("Use as Calculator") { _, _ ->
                proceedToBillFromSnap(
                    currentReading = currentReading,
                    previousReading = previousReading,
                    calculatorMode = true
                )
            }
            .setNeutralButton("Scan Again") { _, _ -> resetToLiveCamera() }
            .setNegativeButton("Start New Session") { _, _ ->
                cameraUiState = CameraUiState.NAVIGATING
                startActivity(Intent(this, HistorySettingsActivity::class.java))
            }
            .setCancelable(false)
            .show()
    }

    private fun handleScanSuccess(result: String) {
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val previousReading = prefs.getInt(PREF_PREVIOUS_READING, -1)
        val projectedUpperLimit = projectedReadingManager.getCurrentMonthProjection()
            ?.projectedReading?.toInt()

        val currentReading = result.toIntOrNull()
        if (currentReading == null) {
            val cleanDigits = result.filter { it.isDigit() }

            // No digits at all  camera caught nothing useful
            if (cleanDigits.isEmpty()) {
                showUnreadableReadingDialog(
                    partialReading = null,
                    reason = "No strip / no digits detected",
                    manualPrefill = null
                )
                return
            }

            showUnreadableReadingDialog(
                partialReading = result,
                reason = "Partial reading with unknown digits",
                manualPrefill = cleanDigits
            )
            return
        }

        if (previousReading == -1) {
            Toast.makeText(this, "Please set Previous Reading in Settings", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            return
        }

        //  Zero-usage guard: same reading as previous means nothing changed 
        if (currentReading == previousReading) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Same as Previous")
                .setMessage(
                    "We got $currentReading, which matches your stored previous reading.\n\n" +
                    "Has the meter not changed yet, or did we misread it?"
                )
                .setPositiveButton("Scan Again") { _, _ -> resetToLiveCamera() }
                .setNegativeButton("Enter Manually") { _, _ ->
                    cameraUiState = CameraUiState.NAVIGATING
                    openManualInput(prefilledReading = currentReading.toString())
                }
                .setCancelable(false)
                .show()
            return
        }

        //  Lower limit: reading cannot be below the previous reading 
        if (currentReading < previousReading) {
            // Meter rollover: digits physically wrap from 99999 ™ 00000
            if (previousReading > 80000 && currentReading < 20000) {
                val rolloverUsage = (99999 - previousReading) + currentReading + 1
                android.app.AlertDialog.Builder(this)
                    .setTitle("Meter Rolled Over?")
                    .setMessage(
                        "We got $currentReading, which is lower than your previous reading ($previousReading).\n\n" +
                        "If your meter just rolled over from 99999 back to 00000, the usage would be:\n" +
                        "$rolloverUsage m\u00B3\n\n" +
                        "Is that correct?"
                    )
                    .setPositiveButton("Yes, Meter Rolled Over") { _, _ ->
                        proceedToBillFromSnap(currentReading, previousReading, usageOverride = rolloverUsage)
                    }
                    .setNeutralButton("Use as Calculator") { _, _ ->
                        showCalculatorPreviousReadingDialog(scannedReading = currentReading)
                    }
                    .setNegativeButton("Scan Again") { _, _ -> resetToLiveCamera() }
                    .setCancelable(false)
                    .show()
                return
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Double-Check This")
                .setMessage("We got $currentReading. Is this correct, or should we try again?")
                .setPositiveButton("Scan Again") { _, _ -> resetToLiveCamera() }
                .setNegativeButton("Use as Calculator") { _, _ ->
                    showCalculatorPreviousReadingDialog(scannedReading = currentReading)
                }
                .setCancelable(false)
                .show()
            return
        }

        //  Impossible usage guard 
        // Fires when no upper projection exists OR even when one does, since a 200 m³ spike
        // is physically unreasonable regardless of what the projection says.
        val currentUsage = currentReading - previousReading
        run {
            val predictor = com.kokeshi.metersnap.utils.ReadingPredictor(this)
            val (dynamicThreshold, hardCap) = predictor.getUsagePlausibilityThresholds()

            val isHardImpossible = currentUsage > hardCap
            val isDynamicAnomaly = dynamicThreshold != null && currentUsage > dynamicThreshold && !isHardImpossible

            if (isHardImpossible) {
                // > 200 m³: almost certainly a misread  block and explain
                android.app.AlertDialog.Builder(this)
                    .setTitle("Reading Looks Impossible")
                    .setMessage(
                        "Usage of $currentUsage m\u00B3 in one billing period is beyond a realistic residential amount.\n\n" +
                        "This is almost always a misread. Try scanning again with better lighting."
                    )
                    .setPositiveButton("Scan Again") { _, _ -> resetToLiveCamera() }
                    .setNegativeButton("Enter Manually") { _, _ ->
                        cameraUiState = CameraUiState.NAVIGATING
                        openManualInput(prefilledReading = null)
                    }
                    .setCancelable(false)
                    .show()
                return
            }

            if (isDynamicAnomaly) {
                // > your own historical average: flag as suspicious but allow proceeding
                val avgMonthly = dynamicThreshold!! / 3
                android.app.AlertDialog.Builder(this)
                    .setTitle("Unusually High Usage")
                    .setMessage(
                        "We got $currentUsage m\u00B3 for this period \u2014 that's more than 3\u00D7 your usual ~$avgMonthly m\u00B3/month.\n\n" +
                        "Possible misread? Or maybe a leak or unusual usage?"
                    )
                    .setPositiveButton("Use This Reading") { _, _ ->
                        proceedToBillFromSnap(currentReading, previousReading, calculatorMode = false)
                    }
                    .setNeutralButton("Use as Calculator") { _, _ ->
                        proceedToBillFromSnap(currentReading, previousReading, calculatorMode = true)
                    }
                    .setNegativeButton("Scan Again") { _, _ -> resetToLiveCamera() }
                    .setCancelable(false)
                    .show()
                return
            }
        }

        //  Upper limit: reading should not exceed the projected month-end 
        if (projectedUpperLimit != null
            && projectedUpperLimit > previousReading
            && currentReading > projectedUpperLimit
        ) {
            snapOutOfBoundsCount++ // Track consecutive out-of-bounds  counter shows new-session hint at 2+
            val isRepeatedOutOfBounds = snapOutOfBoundsCount >= 2
            val choices = if (isRepeatedOutOfBounds) {
                arrayOf(
                    "Use As-Is  (save as a normal session)",
                    "Use for Calculator  (no history saved)",
                    "Scan Again",
                    "Enter Manually",
                    "Go to Settings  (start new session)"
                )
            } else {
                arrayOf(
                    "Use As-Is  (save as a normal session)",
                    "Use for Calculator  (no history saved)",
                    "Scan Again",
                    "Enter Manually"
                )
            }
            val dialogMessage = if (isRepeatedOutOfBounds) {
                "The number we got ($currentReading) is higher than expected again.\n\n" +
                "This may mean your billing cycle already started from a new baseline.\n\n" +
                "You can start a new session to reset the projection, or proceed as-is."
            } else {
                "The number we got looks higher than expected for this billing period.\n\n" +
                "It might be a misread - or your new billing cycle may have already started."
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Reading Seems High")
                .setMessage(dialogMessage)
                .setItems(choices) { _, which ->
                    when (which) {
                        0 -> proceedToBillFromSnap(currentReading, previousReading, calculatorMode = false)
                        1 -> proceedToBillFromSnap(currentReading, previousReading, calculatorMode = true)
                        2 -> { /* Scan Again  leave snapOutOfBoundsCount as-is so it accumulates */ resetToLiveCamera() }
                        3 -> {
                            cameraUiState = CameraUiState.NAVIGATING
                            openManualInput(prefilledReading = currentReading.toString())
                        }
                        4 -> {
                            // "Go to Settings"  the HistorySettingsActivity already
                            // has the complete Start New Session flow via
                            // "Edit CWD Previous Reading" ™ "Start New Session" button.
                            cameraUiState = CameraUiState.NAVIGATING
                            startActivity(android.content.Intent(this, HistorySettingsActivity::class.java))
                        }
                    }
                }
                .setCancelable(false)
                .show()
            return
        }

        //  Reading is valid  proceed to bill 
        snapOutOfBoundsCount = 0
        proceedToBillFromSnap(currentReading, previousReading, calculatorMode = false)
    }

    // saveToHistory removed - now handled by destination activities

    override fun onDestroy() {
        super.onDestroy()
        synchronized(liveFrameLock) {
            latestLiveFrameBitmap?.recycle()
            latestLiveFrameBitmap = null
        }
        capturedPreviewBitmap?.recycle()
        capturedPreviewBitmap = null
        AlgorithmicDigitDetector.close()
        liveDetectExecutor?.shutdown()
        frameBufferExecutor?.shutdown()
        snapCoordinatorExecutor?.shutdown()
        snapWindowExecutor?.shutdown()
        // LeNet disabled: no classifier teardown needed.
    }
}


