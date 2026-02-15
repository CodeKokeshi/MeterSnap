package com.kokeshi.metersnap

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.utils.BillCalculator
import com.kokeshi.metersnap.utils.HistoryManager
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import com.kokeshi.metersnap.utils.RatePreferences
import com.kokeshi.metersnap.utils.RateTableManager
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.Date
import java.util.Locale

class BillResultActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var textUsage: TextView
    private lateinit var textRateType: TextView
    private lateinit var textPreviousReading: TextView
    private lateinit var textCurrentReading: TextView
    private lateinit var iconEditCurrent: ImageView
    private lateinit var containerTierBreakdown: LinearLayout
    private lateinit var textWaterBill: TextView
    private lateinit var textESFFee: TextView
    private lateinit var textTotalBill: TextView
    private lateinit var btnSaveToGallery: Button
    private lateinit var btnHome: Button
    
    // Bill data that can be recalculated
    private var currentReading: Int = 0
    private var previousReading: Int = 0
    private var usage: Int = 0
    private var rateType: String = "RESIDENTIAL"
    private var waterBill: Double = 0.0
    private var esfFee: Double = 0.0
    private var totalBill: Double = 0.0
    
    // Tier data
    private var tier1Usage: Int = 0
    private var tier1Amount: Double = 0.0
    private var tier2Usage: Int = 0
    private var tier2Amount: Double = 0.0
    private var tier3Usage: Int = 0
    private var tier3Amount: Double = 0.0
    private var tier4Usage: Int = 0
    private var tier4Amount: Double = 0.0
    private var tier5Usage: Int = 0
    private var tier5Amount: Double = 0.0
    
    // Flag to indicate if viewing from history
    private var isFromHistory: Boolean = false
    
    // Session tracking for history - prevents duplicates
    private var sessionId: String = UUID.randomUUID().toString()
    private var historySaved: Boolean = false
    
    // Managers
    private lateinit var historyManager: HistoryManager
    private lateinit var projectedReadingManager: ProjectedReadingManager

    private val STORAGE_PERMISSION_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_bill_result)

        historyManager = HistoryManager(this)
        projectedReadingManager = ProjectedReadingManager(this)
        
        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadResults()
        setupListeners()
    }
    
    override fun onPause() {
        super.onPause()
        // Auto-save to history when leaving (but only once, and not if from history)
        if (!historySaved && !isFromHistory) {
            saveToHistory()
        }
    }
    
    private fun setupWindowInsets() {
        // Apply status bar inset to toolbar only
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                statusBarInsets.top,
                view.paddingRight,
                view.paddingBottom
            )
            windowInsets
        }

        // Apply navigation bar inset to root
        val rootView = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            windowInsets
        }
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        textUsage = findViewById(R.id.text_usage)
        textRateType = findViewById(R.id.text_rate_type)
        textPreviousReading = findViewById(R.id.text_previous_reading)
        textCurrentReading = findViewById(R.id.text_current_reading)
        iconEditCurrent = findViewById(R.id.icon_edit_current)
        containerTierBreakdown = findViewById(R.id.container_tier_breakdown)
        textWaterBill = findViewById(R.id.text_water_bill)
        textESFFee = findViewById(R.id.text_esf_fee)
        textTotalBill = findViewById(R.id.text_total_bill)
        btnSaveToGallery = findViewById(R.id.btn_save_to_gallery)
        btnHome = findViewById(R.id.btn_home)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Bill Calculation"

        // Apply status bar insets to toolbar using modern WindowInsets API
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                statusBarInsets.top,
                view.paddingRight,
                view.paddingBottom
            )
            windowInsets
        }
    }

    private fun setupListeners() {
        btnSaveToGallery.setOnClickListener {
            if (checkStoragePermission()) {
                saveBillToGallery()
            } else {
                requestStoragePermission()
            }
        }

        btnHome.setOnClickListener {
            // Navigate to main activity
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
        
        // HITL: Make current reading editable
        val editClickListener = {
            showEditCurrentReadingDialog()
        }
        textCurrentReading.setOnClickListener { editClickListener() }
        iconEditCurrent.setOnClickListener { editClickListener() }
    }
    
    /**
     * HITL (Human in the Loop) - Show dialog to edit the current reading
     * in case the AI misread the meter.
     */
    private fun showEditCurrentReadingDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Edit Current Reading")
        builder.setMessage("Correct the meter reading if it was misread:")
        
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(currentReading.toString())
        input.setSelection(input.text.length) // Cursor at end
        
        // Add padding to the EditText
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.leftMargin = margin
        params.rightMargin = margin
        params.topMargin = margin / 2
        input.layoutParams = params
        container.addView(input)
        builder.setView(container)
        
        builder.setPositiveButton("Update") { dialog, _ ->
            val newReadingText = input.text.toString()
            val newReading = newReadingText.toIntOrNull()
            
            if (newReading == null) {
                Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            
            if (newReading < previousReading) {
                Toast.makeText(this, "Current reading must be ≥ previous ($previousReading)", Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            
            if (newReading == currentReading) {
                // No change
                dialog.dismiss()
                return@setPositiveButton
            }
            
            // Update and recalculate
            currentReading = newReading
            recalculateBill()
            
            Toast.makeText(this, "Bill recalculated with corrected reading", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }
        
        builder.show()
    }
    
    /**
     * Recalculate the bill based on the new current reading.
     * Updates all UI elements in real-time.
     */
    private fun recalculateBill() {
        // Calculate new usage
        usage = currentReading - previousReading
        
        // Recalculate bill using the appropriate method
        val ratePreferences = RatePreferences(this)
        val rateTableManager = RateTableManager(this)
        val rateTypeEnum = try {
            WaterRateType.valueOf(rateType)
        } catch (e: Exception) {
            WaterRateType.RESIDENTIAL
        }
        
        val customRates = ratePreferences.getCustomRates()
        
        // Calculate bill using dynamic method if not custom
        val billResult: BillCalculator.BillResult = if (rateTypeEnum == WaterRateType.CUSTOM) {
            val esfRate = ratePreferences.getESFRate()
            BillCalculator.calculateBill(
                usage = usage,
                rateType = rateTypeEnum,
                customRates = customRates,
                esfRate = esfRate
            )
        } else {
            val serviceType = rateTableManager.getServiceType(rateTypeEnum.name)
            if (serviceType != null) {
                BillCalculator.calculateBillDynamic(
                    usage = usage,
                    serviceType = serviceType,
                    esfRate = rateTableManager.getEsfRate()
                )
            } else {
                val esfRate = WaterRates.getESFForType(rateTypeEnum)
                BillCalculator.calculateBill(
                    usage = usage,
                    rateType = rateTypeEnum,
                    customRates = customRates,
                    esfRate = esfRate
                )
            }
        }
        
        // Update stored values
        waterBill = billResult.waterBill
        esfFee = billResult.esfFee
        totalBill = billResult.totalBill
        tier1Usage = billResult.tier1Usage
        tier1Amount = billResult.tier1Amount
        tier2Usage = billResult.tier2Usage
        tier2Amount = billResult.tier2Amount
        tier3Usage = billResult.tier3Usage
        tier3Amount = billResult.tier3Amount
        tier4Usage = billResult.tier4Usage
        tier4Amount = billResult.tier4Amount
        tier5Usage = billResult.tier5Usage
        tier5Amount = billResult.tier5Amount
        
        // Update the UI
        updateDisplay()
        
        Log.d("BillResult", "HITL: Recalculated bill - usage: $usage, total: $totalBill")
    }
    
    /**
     * Update all display elements with current values.
     */
    private fun updateDisplay() {
        // Update readings display
        textPreviousReading.text = previousReading.toString()
        textCurrentReading.text = currentReading.toString()
        
        // Update usage
        textUsage.text = "$usage usage"
        
        // Update tier breakdown
        displayTierBreakdown()
        
        // Update totals
        textWaterBill.text = String.format(Locale.US, "₱%.2f", waterBill)
        textESFFee.text = String.format(Locale.US, "₱%.2f", esfFee)
        textTotalBill.text = String.format(Locale.US, "₱%.2f", totalBill)
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ doesn't need WRITE_EXTERNAL_STORAGE for MediaStore
            true
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveBillToGallery()
            } else {
                Toast.makeText(this, "Permission denied to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveBillToGallery() {
        try {
            val bitmap = generateBillBitmap()
            val savedUri = saveBitmapToGallery(bitmap)

            if (savedUri != null) {
                Toast.makeText(this, "Bill saved to gallery!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to save bill", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("BillResult", "Error saving bill", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateBillBitmap(): Bitmap {
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isDarkMode) Color.parseColor("#121212") else Color.WHITE
        val textColor = if (isDarkMode) Color.WHITE else Color.BLACK
        val cardColor = if (isDarkMode) Color.parseColor("#1E1E1E") else Color.parseColor("#F5F5F5")

        // Create bitmap with proper dimensions
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var yPos = 80f

        // Title
        paint.textSize = 60f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = textColor
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Bill Calculation", width / 2f, yPos, paint)
        yPos += 100f

        // Use class variables (which may have been updated via HITL)
        // Note: usage, currentReading, previousReading, rateType, waterBill, esfFee, totalBill are class fields

        // Usage Summary Card
        yPos = drawCard(canvas, paint, 40f, yPos, width - 80f, cardColor, textColor) {
            var cardY = it
            paint.textSize = 40f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Total Water Usage", width / 2f, cardY, paint)
            cardY += 70f

            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            paint.color = if (isDarkMode) Color.LTGRAY else Color.DKGRAY
            canvas.drawText("Previous: $previousReading → Current: $currentReading", width / 2f, cardY, paint)
            cardY += 80f

            paint.textSize = 64f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = textColor
            canvas.drawText("$usage usage", width / 2f, cardY, paint)
            cardY += 80f

            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            paint.color = if (isDarkMode) Color.LTGRAY else Color.DKGRAY
            val rateTypeFormatted = rateType.replace("_", " ").lowercase(Locale.US)
                .split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            canvas.drawText(rateTypeFormatted, width / 2f, cardY, paint)
            cardY + 40f
        }
        yPos += 60f

        // Water Bill Breakdown Card (use class tier variables)
        val tierData = listOf(
            Triple(tier1Usage, tier1Amount, "1-10 usage (Min)"),
            Triple(tier2Usage, tier2Amount, "11-20 usage"),
            Triple(tier3Usage, tier3Amount, "21-30 usage"),
            Triple(tier4Usage, tier4Amount, "31-40 usage"),
            Triple(tier5Usage, tier5Amount, "41+ usage")
        ).filter { it.first > 0 }

        yPos = drawCard(canvas, paint, 40f, yPos, width - 80f, cardColor, textColor) {
            var cardY = it
            paint.textSize = 44f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.LEFT
            paint.color = textColor
            canvas.drawText("Water Bill Breakdown", 80f, cardY, paint)
            cardY += 60f

            // Tier breakdown
            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            for ((tierUsage, tierAmount, tierLabel) in tierData) {
                val tierText = "$tierLabel ($tierUsage usage used)"
                canvas.drawText(tierText, 80f, cardY, paint)
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(String.format(Locale.US, "₱%.2f", tierAmount), width - 80f, cardY, paint)
                paint.textAlign = Paint.Align.LEFT
                cardY += 50f
            }

            cardY += 20f
            // Draw line
            paint.strokeWidth = 2f
            canvas.drawLine(80f, cardY, width - 80f, cardY, paint)
            cardY += 40f

            // Subtotal
            paint.textSize = 36f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("Subtotal (Water)", 80f, cardY, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "₱%.2f", waterBill), width - 80f, cardY, paint)
            paint.textAlign = Paint.Align.LEFT
            cardY + 40f
        }
        yPos += 60f

        // ESF Card
        yPos = drawCard(canvas, paint, 40f, yPos, width - 80f, cardColor, textColor) {
            var cardY = it
            paint.textSize = 44f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("Environmental Service Fee", 80f, cardY, paint)
            cardY += 60f

            paint.textSize = 36f
            canvas.drawText("ESF", 80f, cardY, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "₱%.2f", esfFee), width - 80f, cardY, paint)
            paint.textAlign = Paint.Align.LEFT
            cardY + 40f
        }
        yPos += 60f

        // Total Due Card (Emphasized)
        val totalCardColor = if (isDarkMode) Color.parseColor("#2E7D32") else Color.parseColor("#4CAF50")
        drawCard(canvas, paint, 40f, yPos, width - 80f, totalCardColor, Color.WHITE) {
            var cardY = it
            paint.textSize = 52f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = Color.WHITE
            canvas.drawText("TOTAL DUE", 80f, cardY, paint)
            cardY += 80f

            paint.textSize = 72f
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "₱%.2f", totalBill), width - 80f, cardY, paint)
            paint.textAlign = Paint.Align.LEFT
            cardY + 50f
        }

        // Timestamp at bottom
        paint.textSize = 28f
        paint.typeface = Typeface.DEFAULT
        paint.color = if (isDarkMode) Color.LTGRAY else Color.DKGRAY
        paint.textAlign = Paint.Align.CENTER
        val timestamp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US).format(Date())
        canvas.drawText("Generated on $timestamp", width / 2f, height - 60f, paint)

        return bitmap
    }

    private fun drawCard(
        canvas: Canvas,
        paint: Paint,
        x: Float,
        y: Float,
        width: Float,
        cardColor: Int,
        textColor: Int,
        drawContent: (Float) -> Float
    ): Float {
        val cornerRadius = 24f
        val padding = 50f

        // Calculate content height by drawing off-screen first
        val tempY = drawContent(y + padding)
        val cardHeight = tempY - y + padding

        // Draw card background with rounded corners
        paint.color = cardColor
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(x, y, x + width, y + cardHeight, cornerRadius, cornerRadius, paint)

        // Draw content
        paint.color = textColor
        drawContent(y + padding)

        return y + cardHeight
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val filename = "MeterSnap_Bill_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        var fos: OutputStream? = null
        var imageUri: Uri? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MeterSnap")
                }

                imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                fos = imageUri?.let { resolver.openOutputStream(it) }
            } else {
                // Android 9 and below
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val meterSnapDir = File(imagesDir, "MeterSnap")
                if (!meterSnapDir.exists()) {
                    meterSnapDir.mkdirs()
                }

                val image = File(meterSnapDir, filename)
                fos = FileOutputStream(image)
                imageUri = Uri.fromFile(image)
            }

            fos?.let {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                it.flush()
            }

            return imageUri
        } catch (e: Exception) {
            Log.e("BillResult", "Error saving bitmap", e)
            return null
        } finally {
            fos?.close()
        }
    }

    private fun loadResults() {
        // Load all data from intent into class variables
        usage = intent.getIntExtra("USAGE", 0)
        currentReading = intent.getIntExtra("CURRENT_READING", 0)
        previousReading = intent.getIntExtra("PREVIOUS_READING", 0)
        rateType = intent.getStringExtra("RATE_TYPE") ?: "RESIDENTIAL"
        waterBill = intent.getDoubleExtra("WATER_BILL", 0.0)
        esfFee = intent.getDoubleExtra("ESF_FEE", 0.0)
        totalBill = intent.getDoubleExtra("TOTAL_BILL", 0.0)
        isFromHistory = intent.getBooleanExtra("FROM_HISTORY", false)
        
        // Handle session ID for history tracking
        if (isFromHistory) {
            sessionId = intent.getStringExtra("HISTORY_ID") ?: sessionId
            historySaved = true // Already in history
        } else {
            sessionId = UUID.randomUUID().toString()
            historySaved = false
        }
        
        // Calculate usage if not provided
        if (usage == 0 && currentReading > 0 && previousReading > 0) {
            usage = currentReading - previousReading
        }
        
        // Load tier data
        tier1Usage = intent.getIntExtra("TIER_1_USAGE", 0)
        tier1Amount = intent.getDoubleExtra("TIER_1_AMOUNT", 0.0)
        tier2Usage = intent.getIntExtra("TIER_2_USAGE", 0)
        tier2Amount = intent.getDoubleExtra("TIER_2_AMOUNT", 0.0)
        tier3Usage = intent.getIntExtra("TIER_3_USAGE", 0)
        tier3Amount = intent.getDoubleExtra("TIER_3_AMOUNT", 0.0)
        tier4Usage = intent.getIntExtra("TIER_4_USAGE", 0)
        tier4Amount = intent.getDoubleExtra("TIER_4_AMOUNT", 0.0)
        tier5Usage = intent.getIntExtra("TIER_5_USAGE", 0)
        tier5Amount = intent.getDoubleExtra("TIER_5_AMOUNT", 0.0)
        
        // Update toolbar title based on source
        supportActionBar?.title = if (isFromHistory) "Bill Details" else "Bill Calculation"

        // Display rate type name
        val textRateTypeName = findViewById<TextView>(R.id.text_rate_type_name)
        val rateTypeFormatted = rateType.replace("_", " ").lowercase(Locale.US)
            .split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        textRateTypeName.text = rateTypeFormatted

        // Update all display elements
        updateDisplay()
    }

    private fun displayTierBreakdown() {
        containerTierBreakdown.removeAllViews()

        val tierData = listOf(
            Triple(tier1Usage, tier1Amount, "1-10 usage (Minimum Charge)"),
            Triple(tier2Usage, tier2Amount, "11-20 usage"),
            Triple(tier3Usage, tier3Amount, "21-30 usage"),
            Triple(tier4Usage, tier4Amount, "31-40 usage"),
            Triple(tier5Usage, tier5Amount, "41+ usage")
        )

        android.util.Log.d("BillResult", "Tier breakdown data: $tierData")

        for ((tierUsage, amount, label) in tierData) {
            android.util.Log.d("BillResult", "Processing tier: $label, usage: $tierUsage, amount: $amount")
            if (tierUsage > 0) {
                val tierView = createTierView(label, tierUsage, amount)
                containerTierBreakdown.addView(tierView)
                android.util.Log.d("BillResult", "Added tier view for $label")
            }
        }

        android.util.Log.d("BillResult", "Total tier views: ${containerTierBreakdown.childCount}")
    }

    private fun createTierView(label: String, usage: Int, amount: Double): CardView {
        // Get proper colors based on current theme
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val cardBgColor = if (isDarkMode) 0xFF2C2C2E.toInt() else android.graphics.Color.WHITE
        val textColor = if (isDarkMode) android.graphics.Color.WHITE else android.graphics.Color.BLACK

        val cardView = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            radius = 8f
            cardElevation = 4f
            setCardBackgroundColor(cardBgColor)
        }

        val linearLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 24, 32, 24)
        }

        val textLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = "$label\n$usage usage"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textColor)
        }

        val textAmount = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = String.format(Locale.US, "₱%.2f", amount)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(textColor)
            android.graphics.Typeface.DEFAULT_BOLD.let { typeface = it }
        }

        linearLayout.addView(textLabel)
        linearLayout.addView(textAmount)
        cardView.addView(linearLayout)

        return cardView
    }
    
    /**
     * Save the current bill to history.
     * Uses sessionId to prevent duplicates (update if exists).
     */
    private fun saveToHistory() {
        if (isFromHistory) {
            Log.d("BillResult", "Skipping history save - viewing from history")
            return
        }
        
        val entry = ScanHistoryEntry(
            id = sessionId,
            timestamp = System.currentTimeMillis(),
            currentReading = currentReading,
            previousReading = previousReading,
            usage = usage,
            rateType = rateType,
            waterBill = waterBill,
            esfFee = esfFee,
            totalBill = totalBill,
            tier1Usage = tier1Usage,
            tier1Amount = tier1Amount,
            tier2Usage = tier2Usage,
            tier2Amount = tier2Amount,
            tier3Usage = tier3Usage,
            tier3Amount = tier3Amount,
            tier4Usage = tier4Usage,
            tier4Amount = tier4Amount,
            tier5Usage = tier5Usage,
            tier5Amount = tier5Amount,
            hasComparison = false // BillResultActivity is only used when no comparison
        )
        
        historyManager.updateOrAddEntry(entry)
        projectedReadingManager.recordActualMonthEndReading(currentReading)
        
        historySaved = true
        Log.d("BillResult", "History saved with sessionId=$sessionId")
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}