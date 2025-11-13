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
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BillResultActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var textUsage: TextView
    private lateinit var textRateType: TextView
    private lateinit var containerTierBreakdown: LinearLayout
    private lateinit var textWaterBill: TextView
    private lateinit var textESFFee: TextView
    private lateinit var textTotalBill: TextView
    private lateinit var btnSaveToGallery: Button
    private lateinit var btnHome: Button

    private val STORAGE_PERMISSION_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_result)

        initializeViews()
        setupToolbar()
        loadResults()
        setupListeners()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        textUsage = findViewById(R.id.text_usage)
        textRateType = findViewById(R.id.text_rate_type)
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

        // Get data from intent
        val usage = intent.getIntExtra("USAGE", 0)
        val currentReading = intent.getIntExtra("CURRENT_READING", 0)
        val previousReading = intent.getIntExtra("PREVIOUS_READING", 0)
        val rateType = intent.getStringExtra("RATE_TYPE") ?: "RESIDENTIAL"
        val waterBill = intent.getDoubleExtra("WATER_BILL", 0.0)
        val esfFee = intent.getDoubleExtra("ESF_FEE", 0.0)
        val totalBill = intent.getDoubleExtra("TOTAL_BILL", 0.0)

        // Usage Summary Card
        yPos = drawCard(canvas, paint, 40f, yPos, width - 80f, cardColor, textColor) {
            var cardY = it
            paint.textSize = 40f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Total Water Usage", width / 2f, cardY, paint)
            cardY += 60f

            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            paint.color = if (isDarkMode) Color.LTGRAY else Color.DKGRAY
            canvas.drawText("Previous: $previousReading → Current: $currentReading", width / 2f, cardY, paint)
            cardY += 60f

            paint.textSize = 64f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = textColor
            canvas.drawText("$usage usage", width / 2f, cardY, paint)
            cardY += 60f

            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            paint.color = if (isDarkMode) Color.LTGRAY else Color.DKGRAY
            val rateTypeFormatted = rateType.replace("_", " ").lowercase(Locale.US)
                .split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            canvas.drawText(rateTypeFormatted, width / 2f, cardY, paint)
            cardY + 40f
        }
        yPos += 60f

        // Water Bill Breakdown Card
        val tierData = listOf(
            Triple(intent.getIntExtra("TIER_1_USAGE", 0),
                   intent.getDoubleExtra("TIER_1_AMOUNT", 0.0),
                   "1-10 usage (Min)"),
            Triple(intent.getIntExtra("TIER_2_USAGE", 0),
                   intent.getDoubleExtra("TIER_2_AMOUNT", 0.0),
                   "11-20 usage"),
            Triple(intent.getIntExtra("TIER_3_USAGE", 0),
                   intent.getDoubleExtra("TIER_3_AMOUNT", 0.0),
                   "21-30 usage"),
            Triple(intent.getIntExtra("TIER_4_USAGE", 0),
                   intent.getDoubleExtra("TIER_4_AMOUNT", 0.0),
                   "31-40 usage"),
            Triple(intent.getIntExtra("TIER_5_USAGE", 0),
                   intent.getDoubleExtra("TIER_5_AMOUNT", 0.0),
                   "41+ usage")
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
                canvas.drawText(tierLabel, 80f, cardY, paint)
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
            val cardY = it
            paint.textSize = 52f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = Color.WHITE
            canvas.drawText("TOTAL DUE", 80f, cardY, paint)

            paint.textSize = 72f
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "₱%.2f", totalBill), width - 80f, cardY, paint)
            paint.textAlign = Paint.Align.LEFT
            cardY + 40f
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
        val usage = intent.getIntExtra("USAGE", 0)
        val currentReading = intent.getIntExtra("CURRENT_READING", 0)
        val previousReading = intent.getIntExtra("PREVIOUS_READING", 0)
        val rateType = intent.getStringExtra("RATE_TYPE") ?: "RESIDENTIAL"
        val waterBill = intent.getDoubleExtra("WATER_BILL", 0.0)
        val esfFee = intent.getDoubleExtra("ESF_FEE", 0.0)
        val totalBill = intent.getDoubleExtra("TOTAL_BILL", 0.0)

        // Display usage with proper formatting
        textUsage.text = "$usage usage"

        // Display readings
        textRateType.text = "Previous: $previousReading → Current: $currentReading"

        // Display rate type name
        val textRateTypeName = findViewById<TextView>(R.id.text_rate_type_name)
        val rateTypeFormatted = rateType.replace("_", " ").lowercase(Locale.US)
            .split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        textRateTypeName.text = rateTypeFormatted

        // Display tier breakdown
        displayTierBreakdown()

        // Display totals
        textWaterBill.text = String.format(Locale.US, "₱%.2f", waterBill)
        textESFFee.text = String.format(Locale.US, "₱%.2f", esfFee)
        textTotalBill.text = String.format(Locale.US, "₱%.2f", totalBill)
    }

    private fun displayTierBreakdown() {
        containerTierBreakdown.removeAllViews()

        val tierData = listOf(
            Triple(intent.getIntExtra("TIER_1_USAGE", 0),
                   intent.getDoubleExtra("TIER_1_AMOUNT", 0.0),
                   "1-10 usage (Minimum Charge)"),
            Triple(intent.getIntExtra("TIER_2_USAGE", 0),
                   intent.getDoubleExtra("TIER_2_AMOUNT", 0.0),
                   "11-20 usage"),
            Triple(intent.getIntExtra("TIER_3_USAGE", 0),
                   intent.getDoubleExtra("TIER_3_AMOUNT", 0.0),
                   "21-30 usage"),
            Triple(intent.getIntExtra("TIER_4_USAGE", 0),
                   intent.getDoubleExtra("TIER_4_AMOUNT", 0.0),
                   "31-40 usage"),
            Triple(intent.getIntExtra("TIER_5_USAGE", 0),
                   intent.getDoubleExtra("TIER_5_AMOUNT", 0.0),
                   "41+ usage")
        )

        android.util.Log.d("BillResult", "Tier breakdown data: $tierData")

        for ((usage, amount, label) in tierData) {
            android.util.Log.d("BillResult", "Processing tier: $label, usage: $usage, amount: $amount")
            if (usage > 0) {
                val tierView = createTierView(label, usage, amount)
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

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

