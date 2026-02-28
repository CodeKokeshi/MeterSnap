package com.kokeshi.metersnap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kokeshi.metersnap.data.SessionArchive
import com.kokeshi.metersnap.utils.SessionLogManager

class SessionLogsActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var recyclerSessions: RecyclerView
    private lateinit var textEmptySessions: TextView
    private lateinit var sessionLogManager: SessionLogManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_session_logs)

        sessionLogManager = SessionLogManager(this)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadSessions()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerSessions = findViewById(R.id.recycler_sessions)
        textEmptySessions = findViewById(R.id.text_empty_sessions)
        recyclerSessions.layoutManager = LinearLayoutManager(this)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupWindowInsets() {
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

        val rootView = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            windowInsets
        }
    }

    private fun loadSessions() {
        val archives = sessionLogManager.getAllArchives()
        if (archives.isEmpty()) {
            recyclerSessions.visibility = View.GONE
            textEmptySessions.visibility = View.VISIBLE
            return
        }

        recyclerSessions.visibility = View.VISIBLE
        textEmptySessions.visibility = View.GONE
        recyclerSessions.adapter = SessionArchiveAdapter(archives) { archive ->
            val intent = Intent(this, HistoryActivity::class.java).apply {
                putExtra("SESSION_ARCHIVE_ID", archive.id)
            }
            startActivity(intent)
        }
    }

    private class SessionArchiveAdapter(
        private val archives: List<SessionArchive>,
        private val onClick: (SessionArchive) -> Unit
    ) : RecyclerView.Adapter<SessionArchiveAdapter.SessionViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_session_archive, parent, false)
            return SessionViewHolder(view)
        }

        override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
            val archive = archives[position]
            holder.bind(archive, position + 1, onClick)
        }

        override fun getItemCount(): Int = archives.size

        class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textSessionTitle: TextView = itemView.findViewById(R.id.text_session_title)
            private val textSessionNumber: TextView = itemView.findViewById(R.id.text_session_number)
            private val textPrevUsed: TextView = itemView.findViewById(R.id.text_prev_used)
            private val textLastCurrent: TextView = itemView.findViewById(R.id.text_last_current)

            fun bind(archive: SessionArchive, sequence: Int, onClick: (SessionArchive) -> Unit) {
                textSessionTitle.text = archive.label
                textSessionNumber.text = "Session #$sequence"
                textPrevUsed.text = "Previous Reading Used: ${archive.previousReadingUsed}"
                textLastCurrent.text = "Last Current Reading Used: ${archive.lastCurrentReadingUsed}"
                itemView.setOnClickListener { onClick(archive) }
            }
        }
    }
}
