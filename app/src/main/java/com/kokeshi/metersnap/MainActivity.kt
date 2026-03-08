package com.kokeshi.metersnap

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
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
import com.kokeshi.metersnap.notifications.EndOfMonthReminderScheduler
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.UUID

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

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            // Image loaded, but we mock the result as requested
            handleScanSuccess("1575")
        }
    }

    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var isTorchOn = false
    private var isTutorialActive = false
    private lateinit var digitClassifier: DigitClassifier
    private var meterAnalyzer: MeterAnalyzer? = null
    private lateinit var projectedReadingManager: ProjectedReadingManager
    
    // Stability Check Variables
    private var stableFrames = 0
    private var lastStableResult = ""
    private var isProcessingSuccess = false

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
        btnSnap = findViewById(R.id.btn_snap)
        btnManualInput = findViewById(R.id.btn_manual_input)
        btnSettings = findViewById(R.id.btn_settings)
        btnTorch = findViewById(R.id.btn_torch)
        btnHelp = findViewById(R.id.btn_help)
        btnDebugStrip = findViewById(R.id.btn_debug_strip)
        btnDebugFakePrevMonth = findViewById(R.id.btn_debug_fake_prev_month)
        scanLine = findViewById(R.id.scan_line)

        cameraExecutor = Executors.newSingleThreadExecutor()



        // Initialize OpenCV
        if (OpenCVLoader.initDebug()) {
            android.util.Log.d("MainActivity", "OpenCV loaded successfully")
        } else {
            android.util.Log.e("MainActivity", "OpenCV initialization failed!")
        }

        // Initialize DigitClassifier
        digitClassifier = DigitClassifier(this)
        digitClassifier.initialize()
        
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
        stableFrames = 0
        lastStableResult = ""

        // Update analyzer with current previousReading from settings
        // This is critical when user changes value in Settings and returns
        // setPreviousReading() will call reset() if the value changed
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val currentPreviousReading = prefs.getInt(PREF_PREVIOUS_READING, 0)
        meterAnalyzer?.setPreviousReading(currentPreviousReading)
        
        // Reset UI instructions
        val tvInstructions = findViewById<android.widget.TextView>(R.id.tv_instructions)
        tvInstructions?.text = "Align meter in box"
        tvInstructions?.setTextColor(ContextCompat.getColor(this, R.color.main_camera_instruction_default))
        
        android.util.Log.d("MainActivity", "onResume: Reset state, previous reading = $currentPreviousReading")
        
        // Refresh developer settings (debug button visibility)
        applyDeveloperSettings()
    }
    
    override fun onPause() {
        super.onPause()
        // When user navigates away (e.g., to Settings), pause scanning
        // This prevents the analyzer from accumulating stale state
        stableFrames = 0
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
                scanLine.visibility = android.view.View.VISIBLE
                startScanAnimation()
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

                // Create PreviewView and add it to the container
                val previewView = PreviewView(this)
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
                meterAnalyzer = MeterAnalyzer(applicationContext, digitClassifier, previousReading) { analyzerResult ->
                    runOnUiThread {
                        // Update Text
                        val tvLiveResult = findViewById<android.widget.TextView>(R.id.tv_live_result)
                        val tvInstructions = findViewById<android.widget.TextView>(R.id.tv_instructions)
                        
                        val result = analyzerResult.reading
                        val confidence = analyzerResult.confidence
                        val source = analyzerResult.source
                        val validation = analyzerResult.validationResult
                        
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
                                        stableFrames = 0
                                    }
                                    com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.READING_TOO_LOW -> {
                                        tvInstructions.text = "Reading lower than previous?"
                                        tvInstructions.setTextColor(android.graphics.Color.RED)
                                        stableFrames = 0
                                    }
                                    com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.TOO_MANY_DIGITS -> {
                                        tvInstructions.text = "Too many digits - noise detected"
                                        tvInstructions.setTextColor(android.graphics.Color.YELLOW)
                                        stableFrames = 0
                                    }
                                    com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.TOO_FEW_DIGITS -> {
                                        tvInstructions.text = "Move closer to meter"
                                        tvInstructions.setTextColor(android.graphics.Color.YELLOW)
                                        stableFrames = 0
                                    }
                                    else -> {
                                        tvInstructions.text = validation.message.take(30) + "..."
                                        tvInstructions.setTextColor(android.graphics.Color.YELLOW)
                                        stableFrames = 0
                                    }
                                }
                            }
                            // Special Case: 4 Digits (Partial Read)
                            else if (result.length == 4 && confidence > 80f) {
                                tvInstructions.text = "Move Closer / Adjust Angle"
                                tvInstructions.setTextColor(android.graphics.Color.YELLOW)
                                stableFrames = 0 // Reset stability
                            }
                            // High Usage Warning - Allow but confirm
                            else if (validation != null && validation.errorCode == com.kokeshi.metersnap.utils.ReadingValidator.ErrorCode.HIGH_USAGE_WARNING && confidence >= 90f) {
                                stableFrames++
                                tvInstructions.text = "High usage detected. Confirm? ($stableFrames)"
                                tvInstructions.setTextColor(android.graphics.Color.parseColor("#FFA500")) // Orange
                                
                                // Require more frames for high usage confirmation
                                val requiredFrames = if (source == "H" || source == "M" || source == "V") 10 else 30
                                
                                if (stableFrames >= requiredFrames) {
                                    isProcessingSuccess = true
                                    lastStableResult = result
                                    tvInstructions.text = "Processing..."
                                    handleScanSuccess(result)
                                }
                            }
                            // Normal high confidence - valid reading
                            else if (confidence >= 90f && (validation == null || validation.isValid)) {
                                stableFrames++
                                tvInstructions.text = "Hold Steady... ($stableFrames)"
                                tvInstructions.setTextColor(android.graphics.Color.GREEN)
                                
                                // If we have high confidence from Voted/Hybrid/ML Kit, we can be faster (3 frames ~ 0.15s)
                                // If it's just LeNet, we wait longer (20 frames ~ 1.0s) to allow ML Kit to validate/override
                                val requiredFrames = if (source == "H" || source == "M" || source == "V") 3 else 20
                                
                                if (stableFrames >= requiredFrames) {
                                    isProcessingSuccess = true
                                    lastStableResult = result
                                    tvInstructions.text = "Processing..."
                                    handleScanSuccess(result)
                                }
                            } else {
                                stableFrames = 0
                                tvInstructions.text = "Align meter in box"
                                tvInstructions.setTextColor(ContextCompat.getColor(this, R.color.main_camera_instruction_default))
                            }
                        }
                    }
                }

                val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720)) // Request higher resolution (HD)
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
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
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
                scanLine.visibility = android.view.View.VISIBLE
                startScanAnimation()
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
                            scanLine.visibility = android.view.View.VISIBLE
                            startScanAnimation()
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
                scanLine.visibility = android.view.View.VISIBLE
                startScanAnimation()
            } else {
                requestAllRequiredPermissions()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
        if (::digitClassifier.isInitialized) {
            digitClassifier.close()
        }
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