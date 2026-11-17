package com.dafeiyu.paiguard.ui

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dafeiyu.paiguard.R
import com.dafeiyu.paiguard.core.PrefKeys
import com.dafeiyu.paiguard.data.Event
import com.dafeiyu.paiguard.data.EventStore
import com.dafeiyu.paiguard.data.Notifier
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页。所有行都是代码里拼的——六行开关加两个滑杆，写死在 XML 里
 * 会变成三百行重复标签，改一个文案要翻三处。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: EventStore
    private lateinit var adapter: EventAdapter

    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var list: RecyclerView

    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PrefKeys.FILE, MODE_PRIVATE)
        store = EventStore.get(this)

        statusText = findViewById(R.id.statusText)
        emptyText = findViewById(R.id.emptyText)
        list = findViewById(R.id.eventList)

        adapter = EventAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        buildSwitchRows(findViewById(R.id.switchContainer))
        buildThresholdRows(findViewById(R.id.thresholdContainer))

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            store.clear()
            refresh()
            Snackbar.make(findViewById(android.R.id.content), R.string.cleared, Snackbar.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener {
            if (!hasNotificationPermission()) {
                requestNotificationPermission()
                Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.test_denied,
                    Snackbar.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            Notifier.warn(
                this,
                "这是一条测试通知",
                "真正的拦截通知长这样：\n原因：本窗口公式 42 ≥ 24\n公式数：42，持续 10 秒后自动恢复"
            )
            Snackbar.make(findViewById(android.R.id.content), R.string.test_sent, Snackbar.LENGTH_SHORT).show()
        }

        if (!hasNotificationPermission()) requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshHandler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshTask)
    }

    // ---- 动态构建设置区 ----

    private fun buildSwitchRows(container: LinearLayout) {
        val rows = listOf(
            Row(PrefKeys.MASTER, R.string.sw_master, R.string.sw_master_desc, true),
            Row(PrefKeys.LATEX_GUARD, R.string.sw_latex, R.string.sw_latex_desc, true),
            Row(PrefKeys.KILL_PLUGIN, R.string.sw_kill, R.string.sw_kill_desc, false),
            Row(PrefKeys.BLOCK_MACROS, R.string.sw_macro, R.string.sw_macro_desc, true),
            Row(PrefKeys.TOAST, R.string.sw_toast, R.string.sw_toast_desc, true),
            Row(PrefKeys.NOTIFY, R.string.sw_notify, R.string.sw_notify_desc, true)
        )

        rows.forEachIndexed { index, row ->
            if (index > 0) container.addView(divider())
            container.addView(switchRow(row))
        }
    }

    private data class Row(val key: String, val title: Int, val desc: Int, val default: Boolean)

    private fun switchRow(row: Row): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(label(getString(row.title), 15f, R.color.on_surface, medium = true))
        texts.addView(label(getString(row.desc), 12.5f, R.color.on_surface_variant).apply {
            setPadding(0, dp(3), 0, 0)
        })

        val sw = MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(row.key, row.default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(row.key, checked).apply()
                refreshStatus()
            }
        }

        wrap.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        wrap.addView(sw)
        return wrap
    }

    private fun buildThresholdRows(container: LinearLayout) {
        container.addView(
            label(getString(R.string.section_threshold), 15f, R.color.on_surface, medium = true)
        )
        container.addView(
            label(getString(R.string.threshold_hint), 12.5f, R.color.on_surface_variant).apply {
                setPadding(0, dp(4), 0, dp(12))
            }
        )

        container.addView(
            sliderRow(
                titleRes = R.string.threshold_window,
                key = PrefKeys.MAX_FORMULAS,
                from = 4f,
                to = 80f,
                step = 2f,
                default = 24,
                unit = " 个/秒"
            )
        )
        container.addView(
            sliderRow(
                titleRes = R.string.threshold_chars,
                key = PrefKeys.MAX_CHARS,
                from = 200f,
                to = 3000f,
                step = 100f,
                default = 1200,
                unit = " 字符"
            )
        )
    }

    private fun sliderRow(
        titleRes: Int,
        key: String,
        from: Float,
        to: Float,
        step: Float,
        default: Int,
        unit: String
    ): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = label(getString(titleRes), 14f, R.color.on_surface)
        val valueLabel = label("", 14f, R.color.primary, medium = true).apply {
            gravity = android.view.Gravity.END
        }
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(valueLabel)

        val stored = prefs.getInt(key, default).toFloat().coerceIn(from, to)
        val slider = Slider(this).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            this.value = alignToStep(stored, from, step, to)
            addOnChangeListener { _, v, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val int = v.toInt()
                prefs.edit().putInt(key, int).apply()
                valueLabel.text = "$int$unit"
            }
        }
        valueLabel.text = "${slider.value.toInt()}$unit"

        wrap.addView(head)
        wrap.addView(slider)
        return wrap
    }

    private fun alignToStep(v: Float, from: Float, step: Float, to: Float): Float {
        val steps = Math.round((v - from) / step)
        return (from + steps * step).coerceIn(from, to)
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
    }

    private fun label(text: String, sizeSp: Float, colorRes: Int, medium: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
            if (medium) {
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL
                )
            }
            setLineSpacing(0f, 1.25f)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- 数据 ----

    private fun refresh() {
        val events = store.recent(120)
        adapter.submit(events)
        emptyText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        refreshStatus()
    }

    private fun refreshStatus() {
        val master = prefs.getBoolean(PrefKeys.MASTER, true)
        if (!master) {
            statusText.text = getString(R.string.status_idle)
            return
        }
        val summary = store.summary()
        statusText.text = getString(
            R.string.status_active,
            summary.blocked,
            summary.formulas
        )
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    // ---- 列表 ----

    private class EventAdapter : RecyclerView.Adapter<EventAdapter.VH>() {

        private var items: List<Event> = emptyList()
        private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        fun submit(list: List<Event>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_event, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], fmt)
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val time: TextView = view.findViewById(R.id.eventTime)
            private val badge: TextView = view.findViewById(R.id.eventBadge)
            private val title: TextView = view.findViewById(R.id.eventTitle)
            private val detail: TextView = view.findViewById(R.id.eventDetail)

            fun bind(e: Event, fmt: SimpleDateFormat) {
                time.text = fmt.format(Date(e.time))
                badge.text = when (e.kind) {
                    Event.KIND_FATAL -> "渲染崩溃已拦截"
                    Event.KIND_BLOCK -> "已拦截"
                    Event.KIND_TRIP -> "已熔断"
                    else -> "记录"
                }
                title.text = e.title
                detail.text = e.detail.ifBlank { "—" }
                detail.visibility = if (e.detail.isBlank()) View.GONE else View.VISIBLE
            }
        }
    }
}
