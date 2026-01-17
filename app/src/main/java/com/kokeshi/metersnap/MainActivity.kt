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
import com.kokeshi.metersnap.utils.BillCalculator
import com.kokeshi.metersnap.utils.RatePreferences
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var debugVisible = false
    private lateinit var cameraContainer: FrameLayout
    private lateinit var btnSnap: ImageButton
    private lateinit var btnManualInput: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnTorch: ImageButton
    private lateinit var btnHelp: ImageButton
    private lateinit var btnDebugStrip: android.widget.Button
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
    
    // Stability Check Variables
    private var stableFrames = 0
    private var lastStableResult = ""
    private var isProcessingSuccess = false

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val PREF_TUTORIAL_COMPLETED = "tutorial_completed"
        private const val PREF_PREVIOUS_READING = "previous_reading"
        private const val PREF_PREVIOUS_READING_SET = "previous_reading_set"
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
        scanLine = findViewById(R.id.scan_line)

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnDebugStrip.visibility = android.view.View.GONE // Hides the goddamn debug strip


        // Initialize OpenCV
        if (OpenCVLoader.initDebug()) {
            android.util.Log.d("MainActivity", "OpenCV loaded successfully")
        } else {
            android.util.Log.e("MainActivity", "OpenCV initialization failed!")
        }

        // Initialize DigitClassifier
        digitClassifier = DigitClassifier(this)
        digitClassifier.initialize()

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
        btnDebugStrip.visibility = android.view.View.GONE // Hides the goddamn debug strip

        // Update analyzer with current previousReading from settings
        // This is critical when user changes value in Settings and returns
        // setPreviousReading() will call reset() if the value changed
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val currentPreviousReading = prefs.getInt(PREF_PREVIOUS_READING, 0)
        meterAnalyzer?.setPreviousReading(currentPreviousReading)
        
        // Reset UI instructions
        val tvInstructions = findViewById<android.widget.TextView>(R.id.tv_instructions)
        tvInstructions?.text = "Align meter in box"
        tvInstructions?.setTextColor(android.graphics.Color.WHITE)
        
        android.util.Log.d("MainActivity", "onResume: Reset state, previous reading = $currentPreviousReading")
    }
    
    override fun onPause() {
        super.onPause()
        // When user navigates away (e.g., to Settings), pause scanning
        // This prevents the analyzer from accumulating stale state
        isProcessingSuccess = false
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
                                tvInstructions.setTextColor(android.graphics.Color.WHITE)
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
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val previousReadingSet = prefs.getBoolean(PREF_PREVIOUS_READING_SET, false)
        val tutorialCompleted = prefs.getBoolean(PREF_TUTORIAL_COMPLETED, false)
        
        if (!previousReadingSet) {
            // First run: Ask for previous reading
            findViewById<android.view.View>(R.id.main).postDelayed({
                showPreviousReadingDialog()
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

    private fun showPreviousReadingDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Welcome to MeterSnap")
        builder.setMessage("To calculate your bill accurately, please enter the Previous Reading from your last water bill.\n\nYou can change this later in Settings.")

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
                    val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putInt(PREF_PREVIOUS_READING, reading)
                        .putBoolean(PREF_PREVIOUS_READING_SET, true)
                        .apply()
                    
                    // Update analyzer if it exists
                    meterAnalyzer?.setPreviousReading(reading)
                    
                    Toast.makeText(this, "Previous reading saved!", Toast.LENGTH_SHORT).show()
                    // Proceed to tutorial
                    startTutorial()
                } else {
                    Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show()
                    showPreviousReadingDialog() // Retry
                }
            } else {
                Toast.makeText(this, "Please enter a reading", Toast.LENGTH_SHORT).show()
                showPreviousReadingDialog() // Retry
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

    // onResume is already defined above

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

        // Launch Result Activity
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