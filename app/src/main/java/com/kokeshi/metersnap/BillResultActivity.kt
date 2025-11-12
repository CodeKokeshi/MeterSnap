package com.kokeshi.metersnap

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import java.util.Locale

class BillResultActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var textUsage: TextView
    private lateinit var textRateType: TextView
    private lateinit var containerTierBreakdown: LinearLayout
    private lateinit var textWaterBill: TextView
    private lateinit var textESFFee: TextView
    private lateinit var textTotalBill: TextView
    private lateinit var btnDone: Button

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
        btnDone = findViewById(R.id.btn_done)
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
        btnDone.setOnClickListener {
            finish()
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
        val rateTypeFormatted = rateType.replace("_", " ").lowercase()
            .split(" ").joinToString(" ") { it.capitalize() }
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
        onBackPressed()
        return true
    }
}
