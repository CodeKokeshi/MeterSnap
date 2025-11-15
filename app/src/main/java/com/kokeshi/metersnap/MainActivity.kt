package com.kokeshi.metersnap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var cameraContainer: FrameLayout
    private lateinit var btnSnap: ImageButton
    private lateinit var btnManualInput: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnTorch: ImageButton
    private lateinit var btnHelp: ImageButton
    private lateinit var scanLine: android.view.View
    private var tutorialOverlay: TutorialOverlay? = null

    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var isTorchOn = false
    private var isTutorialActive = false

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val PREF_TUTORIAL_COMPLETED = "tutorial_completed"
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
        scanLine = findViewById(R.id.scan_line)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Start scanning animation
        startScanAnimation()

        if (checkCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }

        // Add press animations to buttons
        addSpinAnimation(btnSnap)
        addScaleAnimation(btnManualInput)
        addSpinAnimation(btnSettings)
        addScaleAnimation(btnTorch)
        addScaleAnimation(btnHelp)

        btnSnap.setOnClickListener {
            if (!isTutorialActive) {
                // TODO: Implement photo capture and OCR processing
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

        // Check if this is the first launch
        checkAndShowTutorial()
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
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
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
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

                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind camera to lifecycle
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview
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
        val tutorialCompleted = prefs.getBoolean(PREF_TUTORIAL_COMPLETED, false)
        
        if (!tutorialCompleted) {
            // Wait a bit for the layout to be ready
            findViewById<android.view.View>(R.id.main).postDelayed({
                startTutorial()
            }, 500)
        }
    }

    private fun startTutorial() {
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
    }
}