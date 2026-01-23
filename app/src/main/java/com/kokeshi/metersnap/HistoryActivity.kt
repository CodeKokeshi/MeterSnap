package com.kokeshi.metersnap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.utils.HistoryManager

class HistoryActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var historyManager: HistoryManager
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyManager = HistoryManager(this)

        initializeViews()
        setupToolbar()
        loadHistory()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.recycler_history)
        emptyView = findViewById(R.id.text_empty_history)

        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "History Logs"

        // Apply status bar insets to toolbar
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

    private fun loadHistory() {
        val history = historyManager.getAllHistory()

        if (history.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE

            adapter = HistoryAdapter(history) { entry ->
                openBillBreakdown(entry)
            }
            recyclerView.adapter = adapter
        }
    }

    private fun openBillBreakdown(entry: ScanHistoryEntry) {
        // Open BillResultActivity with the history entry data
        val intent = Intent(this, BillResultActivity::class.java).apply {
            putExtra("USAGE", entry.usage)
            putExtra("CURRENT_READING", entry.currentReading)
            putExtra("PREVIOUS_READING", entry.previousReading)
            putExtra("RATE_TYPE", entry.rateType)
            putExtra("WATER_BILL", entry.waterBill)
            putExtra("ESF_FEE", entry.esfFee)
            putExtra("TOTAL_BILL", entry.totalBill)

            // Tier details
            putExtra("TIER_1_USAGE", entry.tier1Usage)
            putExtra("TIER_1_AMOUNT", entry.tier1Amount)
            putExtra("TIER_2_USAGE", entry.tier2Usage)
            putExtra("TIER_2_AMOUNT", entry.tier2Amount)
            putExtra("TIER_3_USAGE", entry.tier3Usage)
            putExtra("TIER_3_AMOUNT", entry.tier3Amount)
            putExtra("TIER_4_USAGE", entry.tier4Usage)
            putExtra("TIER_4_AMOUNT", entry.tier4Amount)
            putExtra("TIER_5_USAGE", entry.tier5Usage)
            putExtra("TIER_5_AMOUNT", entry.tier5Amount)
        }
        startActivity(intent)
    }

    // RecyclerView Adapter
    private class HistoryAdapter(
        private val history: List<ScanHistoryEntry>,
        private val onItemClick: (ScanHistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return HistoryViewHolder(view)
        }

        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            holder.bind(history[position], onItemClick)
        }

        override fun getItemCount(): Int = history.size

        class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cardView: CardView = itemView.findViewById(R.id.card_history_item)
            private val textDate: TextView = itemView.findViewById(R.id.text_date)
            private val textUsage: TextView = itemView.findViewById(R.id.text_usage)
            private val textReading: TextView = itemView.findViewById(R.id.text_reading)
            private val textTotalBill: TextView = itemView.findViewById(R.id.text_total_bill)

            fun bind(entry: ScanHistoryEntry, onClick: (ScanHistoryEntry) -> Unit) {
                textDate.text = entry.getFormattedDate()
                textUsage.text = "${entry.usage} m³"
                textReading.text = "${entry.previousReading} → ${entry.currentReading}"
                textTotalBill.text = entry.getFormattedTotalBill()

                cardView.setOnClickListener {
                    onClick(entry)
                }
            }
        }
    }
}
