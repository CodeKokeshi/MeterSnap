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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.kokeshi.metersnap.data.BillCalculationResult
import com.kokeshi.metersnap.data.BillSource
import com.kokeshi.metersnap.data.DualBillComparison
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.data.TierBreakdownDisplay
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.utils.BillCalculator
import com.kokeshi.metersnap.utils.HistoryManager
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import com.kokeshi.metersnap.utils.RatePreferences
import com.kokeshi.metersnap.utils.RateTableManager
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Activity that displays dual bill breakdown - comparing CWD-based and App-predicted calculations.
 * 
 * Uses ViewPager2 with TabLayout to allow swiping between the two bill calculations.
 */
class BillComparisonActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BillComparisonActivity"
        private const val STORAGE_PERMISSION_CODE = 101
    }

    private lateinit var toolbar: Toolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var btnSave: Button
    private lateinit var btnHome: Button

    // Data
    private var currentReading: Int = 0
    private var cwdPreviousReading: Int = 0
    private var appPredictedPreviousReading: Int? = null
    private var rateType: String = "RESIDENTIAL"
    private var isFromHistory: Boolean = false
    
    // Session tracking - prevents duplicates
    private var sessionId: String = UUID.randomUUID().toString()
    private var historySaved: Boolean = false
    
    private lateinit var dualComparison: DualBillComparison
    private lateinit var billPagerAdapter: BillPagerAdapter
    
    // Managers
    private lateinit var ratePreferences: RatePreferences
    private lateinit var rateTableManager: RateTableManager
    private lateinit var projectedReadingManager: ProjectedReadingManager
    private lateinit var historyManager: HistoryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_bill_comparison)
        
        initializeManagers()
        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadIntentData()
        calculateDualBills()
        setupViewPager()
        setupButtons()
    }
    
    override fun onPause() {
        super.onPause()
        // Auto-save to history when leaving the activity (but only once)
        if (!historySaved && !isFromHistory) {
            saveBothBillsToHistory()
        }
    }
    
    private fun setupWindowInsets() {
        // Apply navigation bar inset to root only (toolbar handled in setupToolbar)
        val rootView = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            windowInsets
        }
    }
    
    private fun initializeManagers() {
        ratePreferences = RatePreferences(this)
        rateTableManager = RateTableManager(this)
        projectedReadingManager = ProjectedReadingManager(this)
        historyManager = HistoryManager(this)
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        tabLayout = findViewById(R.id.tab_layout_bill_source)
        viewPager = findViewById(R.id.view_pager_bills)
        
        // We'll add buttons programmatically at the bottom
        addActionButtons()
    }
    
    private fun addActionButtons() {
        // Create a button container at the bottom
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(32, 16, 32, 32)
            }
            weightSum = 2f
        }
        
        btnSave = Button(this).apply {
            text = "Save to Gallery"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 16
            }
        }
        
        btnHome = Button(this).apply {
            text = "Home"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = 16
            }
        }
        
        buttonContainer.addView(btnSave)
        buttonContainer.addView(btnHome)
        
        // Add to root layout
        val rootLayout = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
        rootLayout.addView(buttonContainer)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Bill Comparison"

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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadIntentData() {
        currentReading = intent.getIntExtra("CURRENT_READING", 0)
        cwdPreviousReading = intent.getIntExtra("PREVIOUS_READING", 0)
        rateType = intent.getStringExtra("RATE_TYPE") ?: "RESIDENTIAL"
        isFromHistory = intent.getBooleanExtra("FROM_HISTORY", false)
        
        if (isFromHistory) {
            // Use the existing history entry ID if provided
            sessionId = intent.getStringExtra("HISTORY_ID") ?: sessionId
            historySaved = true // Already in history, don't re-save
            
            // Check if this history entry has a comparison saved
            val hasComparison = intent.getBooleanExtra("HAS_COMPARISON", false)
            appPredictedPreviousReading = if (hasComparison) {
                intent.getIntExtra("APP_PREVIOUS_READING", 0).takeIf { it > 0 }
            } else {
                null
            }
        } else {
            // New calculation - generate fresh session ID
            sessionId = UUID.randomUUID().toString()
            historySaved = false
            
            // Get app-predicted previous reading for new calculations
            appPredictedPreviousReading = projectedReadingManager.getAppPredictedPreviousReading()
        }
        
        Log.d(TAG, "Loaded: current=$currentReading, cwdPrev=$cwdPreviousReading, appPrev=$appPredictedPreviousReading, fromHistory=$isFromHistory, sessionId=$sessionId")
    }

    private fun calculateDualBills() {
        if (isFromHistory) {
            // Load pre-calculated data from history
            loadBillsFromHistoryIntent()
        } else {
            // Calculate fresh bills
            calculateFreshBills()
        }
    }
    
    private fun loadBillsFromHistoryIntent() {
        // Load CWD bill from intent extras
        val cwdBill = BillCalculationResult(
            source = BillSource.CWD_READING,
            currentReading = currentReading,
            previousReading = cwdPreviousReading,
            usage = currentReading - cwdPreviousReading,
            rateType = rateType,
            waterBill = intent.getDoubleExtra("WATER_BILL", 0.0),
            esfFee = intent.getDoubleExtra("ESF_FEE", 0.0),
            totalBill = intent.getDoubleExtra("TOTAL_BILL", 0.0),
            tierBreakdown = buildTierBreakdownFromIntent(""),
            confidenceScore = null,
            sourceDescription = "Based on CWD's previous reading from your bill"
        )
        
        // Load App bill if comparison was available
        val hasComparison = intent.getBooleanExtra("HAS_COMPARISON", false)
        val appBill = if (hasComparison && appPredictedPreviousReading != null) {
            val appPrev = appPredictedPreviousReading!!
            BillCalculationResult(
                source = BillSource.APP_PREDICTED,
                currentReading = currentReading,
                previousReading = appPrev,
                usage = intent.getIntExtra("APP_USAGE", currentReading - appPrev),
                rateType = rateType,
                waterBill = intent.getDoubleExtra("APP_WATER_BILL", 0.0),
                esfFee = intent.getDoubleExtra("APP_ESF_FEE", 0.0),
                totalBill = intent.getDoubleExtra("APP_TOTAL_BILL", 0.0),
                tierBreakdown = buildTierBreakdownFromIntent("APP_"),
                confidenceScore = null,
                sourceDescription = "Based on app's end-of-month prediction"
            )
        } else {
            null
        }
        
        dualComparison = DualBillComparison.create(cwdBill, appBill)
        Log.d(TAG, "Loaded from history: hasComparison=$hasComparison")
    }
    
    private fun buildTierBreakdownFromIntent(prefix: String): List<TierBreakdownDisplay> {
        val tiers = mutableListOf<TierBreakdownDisplay>()
        
        val tier1Usage = intent.getIntExtra("${prefix}TIER_1_USAGE", 0)
        if (tier1Usage > 0 || prefix.isEmpty()) {
            tiers.add(TierBreakdownDisplay("1-10", tier1Usage, intent.getDoubleExtra("${prefix}TIER_1_AMOUNT", 0.0), true))
        }
        
        val tier2Usage = intent.getIntExtra("${prefix}TIER_2_USAGE", 0)
        if (tier2Usage > 0) {
            tiers.add(TierBreakdownDisplay("11-20", tier2Usage, intent.getDoubleExtra("${prefix}TIER_2_AMOUNT", 0.0), false))
        }
        
        val tier3Usage = intent.getIntExtra("${prefix}TIER_3_USAGE", 0)
        if (tier3Usage > 0) {
            tiers.add(TierBreakdownDisplay("21-30", tier3Usage, intent.getDoubleExtra("${prefix}TIER_3_AMOUNT", 0.0), false))
        }
        
        val tier4Usage = intent.getIntExtra("${prefix}TIER_4_USAGE", 0)
        if (tier4Usage > 0) {
            tiers.add(TierBreakdownDisplay("31-40", tier4Usage, intent.getDoubleExtra("${prefix}TIER_4_AMOUNT", 0.0), false))
        }
        
        val tier5Usage = intent.getIntExtra("${prefix}TIER_5_USAGE", 0)
        if (tier5Usage > 0) {
            tiers.add(TierBreakdownDisplay("41+", tier5Usage, intent.getDoubleExtra("${prefix}TIER_5_AMOUNT", 0.0), false))
        }
        
        return tiers
    }
    
    private fun calculateFreshBills() {
        // Calculate CWD-based bill
        val cwdBill = calculateBillForPreviousReading(
            previousReading = cwdPreviousReading,
            source = BillSource.CWD_READING,
            description = "Based on CWD's previous reading from your bill"
        )
        
        // Calculate App-predicted bill (if available)
        val appBill = appPredictedPreviousReading?.let { predictedPrev ->
            calculateBillForPreviousReading(
                previousReading = predictedPrev,
                source = BillSource.APP_PREDICTED,
                description = "Based on app's end-of-month prediction"
            )
        }
        
        dualComparison = DualBillComparison.create(cwdBill, appBill)
        
        Log.d(TAG, "Dual comparison: usageDiff=${dualComparison.usageDifference}, billDiff=${dualComparison.billDifference}")
    }

    private fun calculateBillForPreviousReading(
        previousReading: Int,
        source: BillSource,
        description: String
    ): BillCalculationResult {
        val usage = (currentReading - previousReading).coerceAtLeast(0)
        
        val rateTypeEnum = try {
            WaterRateType.valueOf(rateType)
        } catch (e: Exception) {
            WaterRateType.RESIDENTIAL
        }
        
        val billResult = if (rateTypeEnum == WaterRateType.CUSTOM) {
            val customRates = ratePreferences.getCustomRates()
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
                val esfRate = ratePreferences.getESFRate()
                val customRates = ratePreferences.getCustomRates()
                BillCalculator.calculateBill(
                    usage = usage,
                    rateType = rateTypeEnum,
                    customRates = customRates,
                    esfRate = esfRate
                )
            }
        }
        
        // Convert tier breakdown
        val tierBreakdown = billResult.tierBreakdown.map { tier ->
            TierBreakdownDisplay(
                tierLabel = tier.label,
                usageInTier = tier.usageInTier,
                amount = tier.amount,
                isMinimumCharge = tier.isMinimumCharge
            )
        }.ifEmpty {
            // Fallback to legacy tier data
            listOfNotNull(
                if (billResult.tier1Usage > 0) TierBreakdownDisplay("1-10", billResult.tier1Usage, billResult.tier1Amount, true) else null,
                if (billResult.tier2Usage > 0) TierBreakdownDisplay("11-20", billResult.tier2Usage, billResult.tier2Amount, false) else null,
                if (billResult.tier3Usage > 0) TierBreakdownDisplay("21-30", billResult.tier3Usage, billResult.tier3Amount, false) else null,
                if (billResult.tier4Usage > 0) TierBreakdownDisplay("31-40", billResult.tier4Usage, billResult.tier4Amount, false) else null,
                if (billResult.tier5Usage > 0) TierBreakdownDisplay("41+", billResult.tier5Usage, billResult.tier5Amount, false) else null
            )
        }
        
        val confidenceScore = if (source == BillSource.APP_PREDICTED) {
            projectedReadingManager.getPreviousMonthProjection()?.confidenceScore
        } else null
        
        return BillCalculationResult(
            source = source,
            currentReading = currentReading,
            previousReading = previousReading,
            usage = usage,
            rateType = rateType,
            waterBill = billResult.waterBill,
            esfFee = billResult.esfFee,
            totalBill = billResult.totalBill,
            tierBreakdown = tierBreakdown,
            confidenceScore = confidenceScore,
            sourceDescription = description
        )
    }

    private fun setupViewPager() {
        billPagerAdapter = BillPagerAdapter(
            cwdBill = dualComparison.cwdBill,
            appBill = dualComparison.appPredictedBill,
            comparison = dualComparison,
            onEditCurrentReading = { showEditCurrentReadingDialog() }
        )
        
        viewPager.adapter = billPagerAdapter
        
        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "CWD Reading"
                1 -> "App Predicted"
                else -> ""
            }
        }.attach()
        
        // Hide second tab if no prediction available
        if (!dualComparison.hasPrediction) {
            tabLayout.getTabAt(1)?.view?.visibility = View.GONE
            tabLayout.visibility = View.GONE // Hide entire tab layout
            toolbar.title = if (isFromHistory) "Bill Details" else "Bill Calculation"
        }
    }
    
    private fun setupButtons() {
        // Save button is always visible - allows saving to gallery anytime
        // When from history, it will re-generate the bill image
        btnSave.visibility = View.VISIBLE
        
        btnSave.setOnClickListener {
            if (checkStoragePermission()) {
                saveCurrentBillToGallery()
            } else {
                requestStoragePermission()
            }
        }
        
        btnHome.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }
    
    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                saveCurrentBillToGallery()
            } else {
                Toast.makeText(this, "Permission denied to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun saveCurrentBillToGallery() {
        try {
            // Determine which tab is active
            val currentPosition = viewPager.currentItem
            val isCwdTab = currentPosition == 0
            val bill = if (isCwdTab) dualComparison.cwdBill else dualComparison.appPredictedBill
            
            if (bill == null) {
                Toast.makeText(this, "No bill to save", Toast.LENGTH_SHORT).show()
                return
            }
            
            // ONLY save image to gallery - history is saved automatically on exit
            val bitmap = generateBillBitmap(bill, isCwdTab)
            val savedUri = saveBitmapToGallery(bitmap, isCwdTab)

            if (savedUri != null) {
                val tabName = if (isCwdTab) "CWD" else "App-Predicted"
                Toast.makeText(this, "$tabName bill saved to gallery!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to save bill", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bill", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun generateBillBitmap(bill: BillCalculationResult, isCwdTab: Boolean): Bitmap {
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isDarkMode) Color.parseColor("#121212") else Color.WHITE
        val textColor = if (isDarkMode) Color.WHITE else Color.BLACK
        val cardColor = if (isDarkMode) Color.parseColor("#1E1E1E") else Color.parseColor("#F5F5F5")
        
        // Different accent color based on source
        val accentColor = if (isCwdTab) {
            if (isDarkMode) Color.parseColor("#1565C0") else Color.parseColor("#2196F3") // Blue for CWD
        } else {
            if (isDarkMode) Color.parseColor("#2E7D32") else Color.parseColor("#4CAF50") // Green for App
        }

        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var yPos = 80f

        // Source badge
        paint.color = accentColor
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(40f, yPos, width - 40f, yPos + 100f, 16f, 16f, paint)
        
        paint.textSize = 40f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        val sourceText = if (isCwdTab) "CWD Previous Reading" else "App-Predicted Reading"
        canvas.drawText(sourceText, width / 2f, yPos + 65f, paint)
        yPos += 140f

        // Title
        paint.textSize = 60f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = textColor
        canvas.drawText("Bill Calculation", width / 2f, yPos, paint)
        yPos += 100f

        // Usage Summary Card
        yPos = drawBillCard(canvas, paint, 40f, yPos, width - 80f, cardColor, textColor, isDarkMode) {
            var cardY = it
            paint.textSize = 40f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Total Water Usage", width / 2f, cardY, paint)
            cardY += 70f

            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            paint.color = if (isDarkMode) Color.LTGRAY else Color.DKGRAY
            canvas.drawText("Previous: ${bill.previousReading} → Current: ${bill.currentReading}", width / 2f, cardY, paint)
            cardY += 80f

            paint.textSize = 64f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = textColor
            canvas.drawText("${bill.usage} cu.m.", width / 2f, cardY, paint)
            cardY += 80f

            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            paint.color = if (isDarkMode) Color.LTGRAY else Color.DKGRAY
            val rateTypeFormatted = bill.rateType.replace("_", " ").lowercase(Locale.US)
                .split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            canvas.drawText(rateTypeFormatted, width / 2f, cardY, paint)
            cardY + 40f
        }
        yPos += 60f

        // Water Bill Breakdown Card
        val tierData = bill.tierBreakdown.filter { it.usageInTier > 0 }

        yPos = drawBillCard(canvas, paint, 40f, yPos, width - 80f, cardColor, textColor, isDarkMode) {
            var cardY = it
            paint.textSize = 44f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.LEFT
            paint.color = textColor
            canvas.drawText("Water Bill Breakdown", 80f, cardY, paint)
            cardY += 60f

            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            for (tier in tierData) {
                val tierText = "${tier.tierLabel}${if (tier.isMinimumCharge) " (Min)" else ""}: ${tier.usageInTier} cu.m."
                canvas.drawText(tierText, 80f, cardY, paint)
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(String.format(Locale.US, "₱%.2f", tier.amount), width - 80f, cardY, paint)
                paint.textAlign = Paint.Align.LEFT
                cardY += 50f
            }

            cardY += 20f
            paint.strokeWidth = 2f
            canvas.drawLine(80f, cardY, width - 80f, cardY, paint)
            cardY += 40f

            paint.textSize = 36f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("Subtotal (Water)", 80f, cardY, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "₱%.2f", bill.waterBill), width - 80f, cardY, paint)
            paint.textAlign = Paint.Align.LEFT
            cardY + 40f
        }
        yPos += 60f

        // ESF Card
        yPos = drawBillCard(canvas, paint, 40f, yPos, width - 80f, cardColor, textColor, isDarkMode) {
            var cardY = it
            paint.textSize = 44f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("Environmental Service Fee", 80f, cardY, paint)
            cardY += 60f

            paint.textSize = 36f
            canvas.drawText("ESF", 80f, cardY, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "₱%.2f", bill.esfFee), width - 80f, cardY, paint)
            paint.textAlign = Paint.Align.LEFT
            cardY + 40f
        }
        yPos += 60f

        // Total Due Card (with accent color)
        drawBillCard(canvas, paint, 40f, yPos, width - 80f, accentColor, Color.WHITE, isDarkMode) {
            var cardY = it
            paint.textSize = 52f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = Color.WHITE
            canvas.drawText("TOTAL DUE", 80f, cardY, paint)
            cardY += 80f

            paint.textSize = 72f
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "₱%.2f", bill.totalBill), width - 80f, cardY, paint)
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

    private fun drawBillCard(
        canvas: Canvas,
        paint: Paint,
        x: Float,
        y: Float,
        width: Float,
        cardColor: Int,
        textColor: Int,
        isDarkMode: Boolean,
        drawContent: (Float) -> Float
    ): Float {
        val cornerRadius = 24f
        val padding = 50f

        val tempY = drawContent(y + padding)
        val cardHeight = tempY - y + padding

        paint.color = cardColor
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(x, y, x + width, y + cardHeight, cornerRadius, cornerRadius, paint)

        paint.color = textColor
        drawContent(y + padding)

        return y + cardHeight
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, isCwdTab: Boolean): Uri? {
        val sourceTag = if (isCwdTab) "CWD" else "AppPredicted"
        val filename = "MeterSnap_${sourceTag}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        var fos: OutputStream? = null
        var imageUri: Uri? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MeterSnap")
                }

                imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                fos = imageUri?.let { resolver.openOutputStream(it) }
            } else {
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
            Log.e(TAG, "Error saving bitmap", e)
            return null
        } finally {
            fos?.close()
        }
    }
    
    private fun saveBothBillsToHistory() {
        // Don't save if already from history (viewing existing entry)
        if (isFromHistory) {
            Log.d(TAG, "Skipping history save - viewing from history")
            return
        }
        
        // Create/update history entry with session ID
        val entry = createDualHistoryEntry()
        
        // Use updateOrAdd to prevent duplicates (uses sessionId)
        historyManager.updateOrAddEntry(entry)
        
        // Update projection for current month
        projectedReadingManager.recordActualMonthEndReading(currentReading)
        
        historySaved = true
        Log.d(TAG, "History saved with sessionId=$sessionId")
    }
    
    /**
     * Create a history entry containing both CWD and App calculations when available.
     * Uses the session ID to allow updates instead of duplicates.
     */
    private fun createDualHistoryEntry(): ScanHistoryEntry {
        val cwdBill = dualComparison.cwdBill
        val appBill = dualComparison.appPredictedBill
        val hasComparison = dualComparison.hasPrediction && appBill != null
        
        return ScanHistoryEntry(
            id = sessionId, // Use session ID for update tracking
            timestamp = System.currentTimeMillis(),
            currentReading = cwdBill.currentReading,
            
            // CWD calculation
            previousReading = cwdBill.previousReading,
            usage = cwdBill.usage,
            rateType = cwdBill.rateType,
            waterBill = cwdBill.waterBill,
            esfFee = cwdBill.esfFee,
            totalBill = cwdBill.totalBill,
            tier1Usage = cwdBill.tierBreakdown.getOrNull(0)?.usageInTier ?: 0,
            tier1Amount = cwdBill.tierBreakdown.getOrNull(0)?.amount ?: 0.0,
            tier2Usage = cwdBill.tierBreakdown.getOrNull(1)?.usageInTier ?: 0,
            tier2Amount = cwdBill.tierBreakdown.getOrNull(1)?.amount ?: 0.0,
            tier3Usage = cwdBill.tierBreakdown.getOrNull(2)?.usageInTier ?: 0,
            tier3Amount = cwdBill.tierBreakdown.getOrNull(2)?.amount ?: 0.0,
            tier4Usage = cwdBill.tierBreakdown.getOrNull(3)?.usageInTier ?: 0,
            tier4Amount = cwdBill.tierBreakdown.getOrNull(3)?.amount ?: 0.0,
            tier5Usage = cwdBill.tierBreakdown.getOrNull(4)?.usageInTier ?: 0,
            tier5Amount = cwdBill.tierBreakdown.getOrNull(4)?.amount ?: 0.0,
            
            // App calculation (if available)
            appPreviousReading = appBill?.previousReading,
            appUsage = appBill?.usage,
            appWaterBill = appBill?.waterBill,
            appEsfFee = appBill?.esfFee,
            appTotalBill = appBill?.totalBill,
            appTier1Usage = appBill?.tierBreakdown?.getOrNull(0)?.usageInTier,
            appTier1Amount = appBill?.tierBreakdown?.getOrNull(0)?.amount,
            appTier2Usage = appBill?.tierBreakdown?.getOrNull(1)?.usageInTier,
            appTier2Amount = appBill?.tierBreakdown?.getOrNull(1)?.amount,
            appTier3Usage = appBill?.tierBreakdown?.getOrNull(2)?.usageInTier,
            appTier3Amount = appBill?.tierBreakdown?.getOrNull(2)?.amount,
            appTier4Usage = appBill?.tierBreakdown?.getOrNull(3)?.usageInTier,
            appTier4Amount = appBill?.tierBreakdown?.getOrNull(3)?.amount,
            appTier5Usage = appBill?.tierBreakdown?.getOrNull(4)?.usageInTier,
            appTier5Amount = appBill?.tierBreakdown?.getOrNull(4)?.amount,
            
            hasComparison = hasComparison
        )
    }
    
    private fun createHistoryEntry(bill: BillCalculationResult): ScanHistoryEntry {
        return ScanHistoryEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            currentReading = bill.currentReading,
            previousReading = bill.previousReading,
            usage = bill.usage,
            rateType = bill.rateType,
            waterBill = bill.waterBill,
            esfFee = bill.esfFee,
            totalBill = bill.totalBill,
            tier1Usage = bill.tierBreakdown.getOrNull(0)?.usageInTier ?: 0,
            tier1Amount = bill.tierBreakdown.getOrNull(0)?.amount ?: 0.0,
            tier2Usage = bill.tierBreakdown.getOrNull(1)?.usageInTier ?: 0,
            tier2Amount = bill.tierBreakdown.getOrNull(1)?.amount ?: 0.0,
            tier3Usage = bill.tierBreakdown.getOrNull(2)?.usageInTier ?: 0,
            tier3Amount = bill.tierBreakdown.getOrNull(2)?.amount ?: 0.0,
            tier4Usage = bill.tierBreakdown.getOrNull(3)?.usageInTier ?: 0,
            tier4Amount = bill.tierBreakdown.getOrNull(3)?.amount ?: 0.0,
            tier5Usage = bill.tierBreakdown.getOrNull(4)?.usageInTier ?: 0,
            tier5Amount = bill.tierBreakdown.getOrNull(4)?.amount ?: 0.0
        )
    }
    
    private fun showEditCurrentReadingDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Edit Current Reading")
        builder.setMessage("Correct the meter reading if it was misread:")
        
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(currentReading.toString())
        input.setSelection(input.text.length)
        
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.leftMargin = margin
        params.rightMargin = margin
        params.topMargin = margin / 2
        input.layoutParams = params
        container.addView(input)
        builder.setView(container)
        
        builder.setPositiveButton("Update") { dialog, _ ->
            val newReading = input.text.toString().toIntOrNull()
            
            if (newReading == null) {
                Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            
            val minPrevious = minOf(cwdPreviousReading, appPredictedPreviousReading ?: cwdPreviousReading)
            if (newReading < minPrevious) {
                Toast.makeText(this, "Current reading must be ≥ previous ($minPrevious)", Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            
            currentReading = newReading
            calculateFreshBills() // Recalculate with new reading
            billPagerAdapter.updateData(dualComparison.cwdBill, dualComparison.appPredictedBill, dualComparison)
            
            // Mark as needing to save again (will update existing entry with same sessionId)
            historySaved = false
            
            Toast.makeText(this, "Bills recalculated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    /**
     * ViewPager adapter for the bill breakdown pages
     */
    inner class BillPagerAdapter(
        private var cwdBill: BillCalculationResult,
        private var appBill: BillCalculationResult?,
        private var comparison: DualBillComparison,
        private val onEditCurrentReading: () -> Unit
    ) : RecyclerView.Adapter<BillPagerAdapter.BillPageViewHolder>() {
        
        fun updateData(
            cwdBill: BillCalculationResult,
            appBill: BillCalculationResult?,
            comparison: DualBillComparison
        ) {
            this.cwdBill = cwdBill
            this.appBill = appBill
            this.comparison = comparison
            notifyDataSetChanged()
        }
        
        override fun getItemCount(): Int = if (appBill != null) 2 else 1
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BillPageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.page_bill_breakdown, parent, false)
            return BillPageViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: BillPageViewHolder, position: Int) {
            val bill = if (position == 0) cwdBill else appBill!!
            val showDifference = position == 1 && appBill != null
            holder.bind(bill, showDifference, comparison)
        }
        
        inner class BillPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cardSourceBadge: CardView = itemView.findViewById(R.id.card_source_badge)
            private val iconSource: ImageView = itemView.findViewById(R.id.icon_source)
            private val textSourceTitle: TextView = itemView.findViewById(R.id.text_source_title)
            private val textSourceDescription: TextView = itemView.findViewById(R.id.text_source_description)
            private val textConfidence: TextView = itemView.findViewById(R.id.text_confidence)
            private val textPreviousReading: TextView = itemView.findViewById(R.id.text_previous_reading)
            private val textCurrentReading: TextView = itemView.findViewById(R.id.text_current_reading)
            private val iconEditCurrent: ImageView = itemView.findViewById(R.id.icon_edit_current)
            private val textUsage: TextView = itemView.findViewById(R.id.text_usage)
            private val textRateTypeName: TextView = itemView.findViewById(R.id.text_rate_type_name)
            private val containerTierBreakdown: LinearLayout = itemView.findViewById(R.id.container_tier_breakdown)
            private val textWaterBill: TextView = itemView.findViewById(R.id.text_water_bill)
            private val textEsfFee: TextView = itemView.findViewById(R.id.text_esf_fee)
            private val textTotalBill: TextView = itemView.findViewById(R.id.text_total_bill)
            private val cardDifference: CardView = itemView.findViewById(R.id.card_difference)
            private val textUsageDifference: TextView = itemView.findViewById(R.id.text_usage_difference)
            private val textBillDifference: TextView = itemView.findViewById(R.id.text_bill_difference)
            
            fun bind(bill: BillCalculationResult, showDifference: Boolean, comparison: DualBillComparison) {
                // Source badge
                when (bill.source) {
                    BillSource.CWD_READING -> {
                        cardSourceBadge.setCardBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_blue_light))
                        textSourceTitle.text = "Using CWD Previous Reading"
                        textSourceDescription.text = bill.sourceDescription
                        textConfidence.visibility = View.GONE
                    }
                    BillSource.APP_PREDICTED -> {
                        cardSourceBadge.setCardBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_light))
                        textSourceTitle.text = "Using App-Predicted Reading"
                        textSourceDescription.text = bill.sourceDescription
                        bill.confidenceScore?.let { confidence ->
                            textConfidence.visibility = View.VISIBLE
                            textConfidence.text = String.format("%.0f%% confidence", confidence * 100)
                        } ?: run {
                            textConfidence.visibility = View.GONE
                        }
                    }
                    BillSource.USER_SCANNED -> {
                        cardSourceBadge.setCardBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_purple))
                        textSourceTitle.text = "Using Your Last-Day Scan"
                        textSourceDescription.text = "Actual reading from your end-of-month scan"
                        textConfidence.visibility = View.VISIBLE
                        textConfidence.text = "100% accurate"
                    }
                }
                
                // Readings
                textPreviousReading.text = bill.previousReading.toString()
                textCurrentReading.text = bill.currentReading.toString()
                textUsage.text = "${bill.usage} cu.m."
                
                // Rate type display name
                textRateTypeName.text = bill.rateType.replace("_", " ").lowercase()
                    .replaceFirstChar { it.uppercase() }
                
                // Edit button
                textCurrentReading.setOnClickListener { onEditCurrentReading() }
                iconEditCurrent.setOnClickListener { onEditCurrentReading() }
                
                // Tier breakdown
                containerTierBreakdown.removeAllViews()
                for (tier in bill.tierBreakdown) {
                    addTierRow(tier)
                }
                
                // Totals
                textWaterBill.text = String.format(Locale.US, "₱%.2f", bill.waterBill)
                textEsfFee.text = String.format(Locale.US, "₱%.2f", bill.esfFee)
                textTotalBill.text = String.format(Locale.US, "₱%.2f", bill.totalBill)
                
                // Difference card (only for predicted page)
                if (showDifference) {
                    cardDifference.visibility = View.VISIBLE
                    textUsageDifference.text = comparison.getFormattedUsageDifference()
                    textBillDifference.text = comparison.getFormattedBillDifference()
                    
                    // Color code based on difference
                    val diffColor = when {
                        comparison.billDifference > 0 -> ContextCompat.getColor(itemView.context, android.R.color.holo_red_light)
                        comparison.billDifference < 0 -> ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
                        else -> ContextCompat.getColor(itemView.context, android.R.color.darker_gray)
                    }
                    textBillDifference.setTextColor(diffColor)
                } else {
                    cardDifference.visibility = View.GONE
                }
            }
            
            private fun addTierRow(tier: TierBreakdownDisplay) {
                val row = LinearLayout(itemView.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 8
                    }
                }
                
                val labelText = TextView(itemView.context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = buildString {
                        append(tier.tierLabel)
                        if (tier.isMinimumCharge) append(" (Min)")
                        append(": ${tier.usageInTier} cu.m.")
                    }
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    textSize = 14f
                }
                
                val amountText = TextView(itemView.context).apply {
                    text = String.format(Locale.US, "₱%.2f", tier.amount)
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    textSize = 14f
                }
                
                row.addView(labelText)
                row.addView(amountText)
                containerTierBreakdown.addView(row)
            }
        }
    }
}
