package com.kokeshi.metersnap

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
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

        // Display usage
        textUsage.text = "$usage cu.m.\n($previousReading → $currentReading)"
        
        // Display rate type
        val rateTypeFormatted = rateType.replace("_", " ").lowercase()
            .split(" ").joinToString(" ") { it.capitalize() }
        textRateType.text = rateTypeFormatted

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
                   "1-10 cu.m. (Minimum Charge)"),
            Triple(intent.getIntExtra("TIER_2_USAGE", 0), 
                   intent.getDoubleExtra("TIER_2_AMOUNT", 0.0), 
                   "11-20 cu.m."),
            Triple(intent.getIntExtra("TIER_3_USAGE", 0), 
                   intent.getDoubleExtra("TIER_3_AMOUNT", 0.0), 
                   "21-30 cu.m."),
            Triple(intent.getIntExtra("TIER_4_USAGE", 0), 
                   intent.getDoubleExtra("TIER_4_AMOUNT", 0.0), 
                   "31-40 cu.m."),
            Triple(intent.getIntExtra("TIER_5_USAGE", 0), 
                   intent.getDoubleExtra("TIER_5_AMOUNT", 0.0), 
                   "41+ cu.m.")
        )

        for ((usage, amount, label) in tierData) {
            if (usage > 0) {
                val tierView = createTierView(label, usage, amount)
                containerTierBreakdown.addView(tierView)
            }
        }
    }

    private fun createTierView(label: String, usage: Int, amount: Double): CardView {
        val cardView = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 12)
            }
            radius = 8f
            cardElevation = 2f
            setContentPadding(16, 12, 16, 12)
        }

        val linearLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
        }

        val textLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = "$label\n$usage cu.m."
            textSize = 14f
            setTextColor(resources.getColor(android.R.color.black, theme))
        }

        val textAmount = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = String.format(Locale.US, "₱%.2f", amount)
            textSize = 16f
            setTextColor(resources.getColor(android.R.color.black, theme))
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
