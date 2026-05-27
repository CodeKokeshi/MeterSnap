package com.kokeshi.metersnap

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kokeshi.metersnap.ocr.AlgorithmicDigitDetector
import com.kokeshi.metersnap.utils.ScannerSettings

class ScannerTuningActivity : AppCompatActivity() {

    // Model
    private lateinit var spinnerModel: Spinner

    // Inference
    private lateinit var spinnerThreads: Spinner

    // Detection thresholds
    private lateinit var seekConf: SeekBar
    private lateinit var labelConf: TextView
    private lateinit var seekIou: SeekBar
    private lateinit var labelIou: TextView
    private lateinit var seekMaxDet: SeekBar
    private lateinit var labelMaxDet: TextView
    private lateinit var seekMinBox: SeekBar
    private lateinit var labelMinBox: TextView

    // Window scan
    private lateinit var seekMaxWindows: SeekBar
    private lateinit var labelMaxWindows: TextView
    private lateinit var seekParallel: SeekBar
    private lateinit var labelParallel: TextView

    // Actions
    private lateinit var btnSave: Button
    private lateinit var btnReset: Button
    private lateinit var tvCurrentModel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_scanner_tuning)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Scanner Tuning"

        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            v.setPadding(v.paddingLeft, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, v.paddingRight, v.paddingBottom)
            insets
        }

        bindViews()
        loadCurrentValues()
        setupListeners()
    }

    private fun bindViews() {
        spinnerModel     = findViewById(R.id.spinner_model)
        spinnerThreads   = findViewById(R.id.spinner_threads)
        tvCurrentModel   = findViewById(R.id.tv_current_model)

        seekConf         = findViewById(R.id.seek_conf)
        labelConf        = findViewById(R.id.label_conf)
        seekIou          = findViewById(R.id.seek_iou)
        labelIou         = findViewById(R.id.label_iou)
        seekMaxDet       = findViewById(R.id.seek_max_det)
        labelMaxDet      = findViewById(R.id.label_max_det)
        seekMinBox       = findViewById(R.id.seek_min_box)
        labelMinBox      = findViewById(R.id.label_min_box)

        seekMaxWindows   = findViewById(R.id.seek_max_windows)
        labelMaxWindows  = findViewById(R.id.label_max_windows)
        seekParallel     = findViewById(R.id.seek_parallel)
        labelParallel    = findViewById(R.id.label_parallel)

        btnSave          = findViewById(R.id.btn_save)
        btnReset         = findViewById(R.id.btn_reset)
    }

    private fun loadCurrentValues() {
        // Model spinner — show friendly names, save file paths
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ScannerSettings.MODEL_DISPLAY_NAMES)
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModel.adapter = modelAdapter
        val currentModel = ScannerSettings.getModelFile(this)
        val modelIdx = ScannerSettings.AVAILABLE_MODELS.indexOf(currentModel).takeIf { it >= 0 } ?: 0
        spinnerModel.setSelection(modelIdx)

        // Currently-loaded model info
        tvCurrentModel.text = "Loaded: ${ScannerSettings.displayNameForModel(currentModel)}"

        // Threads spinner (1, 2, 4, 8)
        val threadOptions = listOf("1", "2", "4", "8")
        val threadAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, threadOptions)
        threadAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerThreads.adapter = threadAdapter
        val curThreads = ScannerSettings.getInferenceThreads(this)
        spinnerThreads.setSelection(threadOptions.indexOfFirst { it.toInt() == curThreads }.takeIf { it >= 0 } ?: 2) // default index 2 = "4"

        // Conf threshold: 0.05–0.95, step 0.01 → seekbar 5–95
        seekConf.max = 90
        val confInt = ((ScannerSettings.getConfThreshold(this) * 100).toInt()).coerceIn(5, 95) - 5
        seekConf.progress = confInt
        labelConf.text = "Confidence Threshold: %.2f".format(confThresholdFromProgress(confInt))

        // IOU threshold: 0.05–0.95
        seekIou.max = 90
        val iouInt = ((ScannerSettings.getIouThreshold(this) * 100).toInt()).coerceIn(5, 95) - 5
        seekIou.progress = iouInt
        labelIou.text = "IOU Threshold: %.2f".format(iouThresholdFromProgress(iouInt))

        // Max detections: 5–100
        seekMaxDet.max = 95
        val maxDetProgress = (ScannerSettings.getMaxDetections(this) - 5).coerceIn(0, 95)
        seekMaxDet.progress = maxDetProgress
        labelMaxDet.text = "Max Detections: ${maxDetProgress + 5}"

        // Min box size: 1–40px
        seekMinBox.max = 39
        val minBoxProgress = (ScannerSettings.getMinBoxSizePx(this).toInt() - 1).coerceIn(0, 39)
        seekMinBox.progress = minBoxProgress
        labelMinBox.text = "Min Box Size: ${minBoxProgress + 1} px"

        // Max windows: 1–100
        seekMaxWindows.max = 99
        val maxWinProgress = (ScannerSettings.getMaxWindows(this) - 1).coerceIn(0, 99)
        seekMaxWindows.progress = maxWinProgress
        labelMaxWindows.text = "Max Windows: ${maxWinProgress + 1}"

        // Parallel windows: 1–8
        seekParallel.max = 7
        val parallelProgress = (ScannerSettings.getParallelWindows(this) - 1).coerceIn(0, 7)
        seekParallel.progress = parallelProgress
        labelParallel.text = "Parallel Windows: ${parallelProgress + 1}"
    }

    private fun setupListeners() {
        seekConf.setOnSeekBarChangeListener(simpleSeekListener { p ->
            labelConf.text = "Confidence Threshold: %.2f".format(confThresholdFromProgress(p))
        })
        seekIou.setOnSeekBarChangeListener(simpleSeekListener { p ->
            labelIou.text = "IOU Threshold: %.2f".format(iouThresholdFromProgress(p))
        })
        seekMaxDet.setOnSeekBarChangeListener(simpleSeekListener { p ->
            labelMaxDet.text = "Max Detections: ${p + 5}"
        })
        seekMinBox.setOnSeekBarChangeListener(simpleSeekListener { p ->
            labelMinBox.text = "Min Box Size: ${p + 1} px"
        })
        seekMaxWindows.setOnSeekBarChangeListener(simpleSeekListener { p ->
            labelMaxWindows.text = "Max Windows: ${p + 1}"
        })
        seekParallel.setOnSeekBarChangeListener(simpleSeekListener { p ->
            labelParallel.text = "Parallel Windows: ${p + 1}"
        })

        btnSave.setOnClickListener { saveAndPromptRestart() }
        btnReset.setOnClickListener { confirmReset() }
    }

    private fun saveAndPromptRestart() {
        val selectedModel = ScannerSettings.AVAILABLE_MODELS[spinnerModel.selectedItemPosition.coerceIn(0, ScannerSettings.AVAILABLE_MODELS.lastIndex)]
        val selectedThreads = listOf(1, 2, 4, 8)[spinnerThreads.selectedItemPosition]

        ScannerSettings.saveAll(
            context          = this,
            modelFile        = selectedModel,
            inferenceThreads = selectedThreads,
            confThreshold    = confThresholdFromProgress(seekConf.progress),
            iouThreshold     = iouThresholdFromProgress(seekIou.progress),
            maxDetections    = seekMaxDet.progress + 5,
            minBoxSizePx     = (seekMinBox.progress + 1).toFloat(),
            maxWindows       = seekMaxWindows.progress + 1,
            parallelWindows  = seekParallel.progress + 1
        )

        // Force detector to reinit with new model on next use
        AlgorithmicDigitDetector.close()

        AlertDialog.Builder(this)
            .setTitle("Settings Saved")
            .setMessage("Restart the app now to apply all changes?")
            .setPositiveButton("Restart Now") { _, _ -> restartApp() }
            .setNegativeButton("Later") { _, _ ->
                Toast.makeText(this, "Settings saved — restart when ready", Toast.LENGTH_SHORT).show()
                finish()
            }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset to Defaults")
            .setMessage("This will clear all scanner tuning settings and restart the app.")
            .setPositiveButton("Reset & Restart") { _, _ ->
                ScannerSettings.resetToDefaults(this)
                AlgorithmicDigitDetector.close()
                restartApp()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finishAffinity()
    }

    private fun confThresholdFromProgress(progress: Int): Float = (progress + 5) / 100f
    private fun iouThresholdFromProgress(progress: Int): Float = (progress + 5) / 100f

    private fun simpleSeekListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { onChanged(p) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
