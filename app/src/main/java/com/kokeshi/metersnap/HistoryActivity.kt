package com.kokeshi.metersnap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.utils.ChargeDateHelper
import com.kokeshi.metersnap.utils.HistoryManager
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var btnSort: ImageButton
    private lateinit var historyManager: HistoryManager
    private lateinit var projectedReadingManager: ProjectedReadingManager
    private lateinit var adapter: GroupedHistoryAdapter
    
    // true = most recent month first (default), false = oldest month first
    private var sortDescending: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)

        historyManager = HistoryManager(this)
        projectedReadingManager = ProjectedReadingManager(this)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadHistory()
        setupListeners()
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
        recyclerView = findViewById(R.id.recycler_history)
        emptyView = findViewById(R.id.text_empty_history)
        btnClearHistory = findViewById(R.id.btn_clear_history)
        btnSort = findViewById(R.id.btn_sort)

        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "History Logs"

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
            btnClearHistory.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            btnClearHistory.visibility = View.VISIBLE

            // Group history by month
            val groupedItems = groupHistoryByMonth(history)
            
            adapter = GroupedHistoryAdapter(
                items = groupedItems,
                onEntryClick = { entry -> openBillBreakdown(entry) },
                onClearMonthClick = { monthKey -> showClearMonthDialog(monthKey) }
            )
            recyclerView.adapter = adapter
        }
    }
    
    /**
     * Group history entries by billing period (using charge date) and create display items.
     * If charge date is set, groups by billing cycle; otherwise falls back to calendar month.
     */
    private fun groupHistoryByMonth(history: List<ScanHistoryEntry>): List<HistoryListItem> {
        val items = mutableListOf<HistoryListItem>()
        val chargeDate = ChargeDateHelper.getChargeDate(this)
        val chargeDateSet = ChargeDateHelper.isChargeDateSet(this)
        
        // Group entries by billing period
        val groupedByMonth = history.groupBy { entry ->
            if (chargeDateSet) {
                ChargeDateHelper.getBillingPeriodKey(entry.timestamp, chargeDate)
            } else {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = entry.timestamp
                SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time)
            }
        }
        
        // Convert to list items with headers, sorted by month key
        val sortedKeys = if (sortDescending) {
            groupedByMonth.keys.sortedDescending()
        } else {
            groupedByMonth.keys.sorted()
        }
        
        for (monthKey in sortedKeys) {
            val entries = groupedByMonth[monthKey] ?: continue
            val monthDisplay = if (chargeDateSet) {
                ChargeDateHelper.getBillingPeriodDisplay(monthKey, chargeDate)
            } else {
                val displayFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                val firstEntry = entries.first()
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = firstEntry.timestamp
                displayFormat.format(calendar.time)
            }
            
            // Calculate month summary
            val totalUsage = entries.sumOf { it.usage }
            val scanCount = entries.size
            
            items.add(HistoryListItem.Header(
                monthKey = monthKey,
                monthDisplay = monthDisplay,
                scanCount = scanCount,
                totalUsage = totalUsage
            ))
            
            // Add all entries for this month
            for (entry in entries) {
                items.add(HistoryListItem.Entry(entry))
            }
        }
        
        return items
    }
    
    private fun setupListeners() {
        btnClearHistory.setOnClickListener {
            showClearHistoryDialog()
        }
        
        btnSort.setOnClickListener {
            sortDescending = !sortDescending
            loadHistory()
        }
    }
    
    private fun showClearHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear All History?")
            .setMessage("This will permanently delete all scan history and projected readings. This action cannot be undone.\n\nAre you sure?")
            .setPositiveButton("Clear All") { _, _ ->
                clearAllHistory()
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
    
    private fun clearAllHistory() {
        historyManager.clearAllHistory()
        projectedReadingManager.clearAllProjections()
        loadHistory()
        Toast.makeText(this, "All history cleared successfully", Toast.LENGTH_SHORT).show()
    }
    
    private fun showClearMonthDialog(monthKey: String) {
        val chargeDateSet = ChargeDateHelper.isChargeDateSet(this)
        val chargeDate = ChargeDateHelper.getChargeDate(this)
        
        val monthDisplay = if (chargeDateSet) {
            ChargeDateHelper.getMonthDisplayName(monthKey)
        } else {
            val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
            val displayFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            try {
                val date = monthFormat.parse(monthKey)
                if (date != null) displayFormat.format(date) else monthKey
            } catch (e: Exception) {
                monthKey
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("Clear $monthDisplay?")
            .setMessage("This will delete all scan entries for $monthDisplay. This action cannot be undone.")
            .setPositiveButton("Clear Month") { _, _ ->
                clearMonthHistory(monthKey)
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
    
    private fun clearMonthHistory(monthKey: String) {
        val chargeDateSet = ChargeDateHelper.isChargeDateSet(this)
        if (chargeDateSet) {
            val chargeDate = ChargeDateHelper.getChargeDate(this)
            historyManager.clearBillingPeriodHistory(monthKey, chargeDate)
        } else {
            historyManager.clearMonthHistory(monthKey)
        }
        loadHistory()
        Toast.makeText(this, "Period history cleared", Toast.LENGTH_SHORT).show()
    }

    private fun openBillBreakdown(entry: ScanHistoryEntry) {
        // Route to correct activity based on whether comparison data exists
        // No Comparison = BillResultActivity (Bill Details)
        // With Comparison = BillComparisonActivity (Bill Comparison with tabs)
        
        if (entry.hasComparison) {
            // Open BillComparisonActivity with both CWD and App data
            val intent = Intent(this, BillComparisonActivity::class.java).apply {
                putExtra("HISTORY_ID", entry.id) // Pass the history entry ID
                putExtra("CURRENT_READING", entry.currentReading)
                putExtra("PREVIOUS_READING", entry.previousReading)
                putExtra("RATE_TYPE", entry.rateType)
                putExtra("FROM_HISTORY", true)
                
                // Pass CWD pre-calculated bill data
                putExtra("WATER_BILL", entry.waterBill)
                putExtra("ESF_FEE", entry.esfFee)
                putExtra("TOTAL_BILL", entry.totalBill)
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
                
                // Pass App data for comparison
                putExtra("HAS_COMPARISON", true)
                putExtra("APP_PREVIOUS_READING", entry.appPreviousReading)
                putExtra("APP_USAGE", entry.appUsage)
                putExtra("APP_WATER_BILL", entry.appWaterBill)
                putExtra("APP_ESF_FEE", entry.appEsfFee)
                putExtra("APP_TOTAL_BILL", entry.appTotalBill)
                putExtra("APP_TIER_1_USAGE", entry.appTier1Usage)
                putExtra("APP_TIER_1_AMOUNT", entry.appTier1Amount)
                putExtra("APP_TIER_2_USAGE", entry.appTier2Usage)
                putExtra("APP_TIER_2_AMOUNT", entry.appTier2Amount)
                putExtra("APP_TIER_3_USAGE", entry.appTier3Usage)
                putExtra("APP_TIER_3_AMOUNT", entry.appTier3Amount)
                putExtra("APP_TIER_4_USAGE", entry.appTier4Usage)
                putExtra("APP_TIER_4_AMOUNT", entry.appTier4Amount)
                putExtra("APP_TIER_5_USAGE", entry.appTier5Usage)
                putExtra("APP_TIER_5_AMOUNT", entry.appTier5Amount)
            }
            startActivity(intent)
        } else {
            // Open BillResultActivity for single bill (Bill Details)
            val intent = Intent(this, BillResultActivity::class.java).apply {
                putExtra("HISTORY_ID", entry.id) // Pass the history entry ID
                putExtra("CURRENT_READING", entry.currentReading)
                putExtra("PREVIOUS_READING", entry.previousReading)
                putExtra("RATE_TYPE", entry.rateType)
                putExtra("FROM_HISTORY", true)
                
                // Pass pre-calculated bill data
                putExtra("WATER_BILL", entry.waterBill)
                putExtra("ESF_FEE", entry.esfFee)
                putExtra("TOTAL_BILL", entry.totalBill)
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
    }

    // Sealed class for list items (header or entry)
    sealed class HistoryListItem {
        data class Header(
            val monthKey: String,
            val monthDisplay: String,
            val scanCount: Int,
            val totalUsage: Int
        ) : HistoryListItem()
        
        data class Entry(val entry: ScanHistoryEntry) : HistoryListItem()
    }

    // RecyclerView Adapter with multiple view types
    private class GroupedHistoryAdapter(
        private val items: List<HistoryListItem>,
        private val onEntryClick: (ScanHistoryEntry) -> Unit,
        private val onClearMonthClick: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_ENTRY = 1
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is HistoryListItem.Header -> TYPE_HEADER
                is HistoryListItem.Entry -> TYPE_ENTRY
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_HEADER -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_history_month_header, parent, false)
                    HeaderViewHolder(view)
                }
                else -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_history, parent, false)
                    EntryViewHolder(view)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HistoryListItem.Header -> (holder as HeaderViewHolder).bind(item, onClearMonthClick)
                is HistoryListItem.Entry -> (holder as EntryViewHolder).bind(item.entry, onEntryClick)
            }
        }

        override fun getItemCount(): Int = items.size

        class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textMonthHeader: TextView = itemView.findViewById(R.id.text_month_header)
            private val textMonthSummary: TextView = itemView.findViewById(R.id.text_month_summary)
            private val btnClearMonth: ImageButton = itemView.findViewById(R.id.btn_clear_month)

            fun bind(header: HistoryListItem.Header, onClearClick: (String) -> Unit) {
                textMonthHeader.text = header.monthDisplay
                val scanText = if (header.scanCount == 1) "1 scan" else "${header.scanCount} scans"
                textMonthSummary.text = "$scanText • Total usage: ${header.totalUsage} m³"
                
                btnClearMonth.setOnClickListener {
                    onClearClick(header.monthKey)
                }
            }
        }

        class EntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cardView: CardView = itemView.findViewById(R.id.card_history_item)
            private val textDate: TextView = itemView.findViewById(R.id.text_date)
            private val textUsage: TextView = itemView.findViewById(R.id.text_usage)
            private val textReading: TextView = itemView.findViewById(R.id.text_reading)
            private val textTotalBill: TextView = itemView.findViewById(R.id.text_total_bill)

            fun bind(entry: ScanHistoryEntry, onClick: (ScanHistoryEntry) -> Unit) {
                // Show just date and time (month is in header)
                val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                textDate.text = dateFormat.format(Date(entry.timestamp))
                
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
