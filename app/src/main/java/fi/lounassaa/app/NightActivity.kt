package fi.lounassaa.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dim night view shown on top of the lock screen when the screen is turned on at night. Off after 30 s. */
class NightActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val fi = Locale("fi", "FI")
    private var clock: TextView? = null
    private var values: TextView? = null

    private val timeout = Runnable {
        Night.lock(this, "yönäkymä 30 s")
        finish()
    }
    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLock()
        window.attributes = window.attributes.apply { screenBrightness = 0.02f }
        setContentView(ScaledRoot(this, false) { ui -> build(ui) })
        hideSystemBars(window)
    }

    private fun build(ui: Ui): View {
        val dim = Color.parseColor("#7d8790")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        clock = TextView(this).apply {
            ui.text(this, 120f)
            setTextColor(dim)
            gravity = Gravity.CENTER
        }
        values = TextView(this).apply {
            ui.text(this, 34f)
            setTextColor(dim)
            gravity = Gravity.CENTER
            setPadding(0, ui.px(12f), 0, ui.px(28f))
        }
        val hint = TextView(this).apply {
            ui.text(this, 16f)
            setTextColor(Color.parseColor("#4a5259"))
            gravity = Gravity.CENTER
            text = "Näyttö sammuu 30 s kuluttua · Poistu avaa lukitusnäytön"
        }
        val exit = Button(this).apply {
            text = "Poistu"
            isAllCaps = false
            ui.text(this, 18f)
            setOnClickListener { finish() }
        }
        col.addView(clock)
        col.addView(values)
        col.addView(hint)
        col.addView(exit, LinearLayout.LayoutParams(ui.px(180f), ui.px(60f)).apply { topMargin = ui.px(24f) })
        update()
        return col
    }

    private fun deg(v: Double) = String.format(fi, "%.1f°", v).replace('-', '−')

    private fun update() {
        clock?.text = SimpleDateFormat("HH.mm", fi).format(Date())
        val o = Prefs.outdoor(this)?.let { Live.latest[it] }?.let { deg(it.r.tempC) } ?: "–"
        val i = Prefs.indoor(this)?.let { Live.latest[it] }?.let { deg(it.r.tempC) } ?: "–"
        values?.text = "Ulkona $o   ·   Sisällä $i"
    }

    override fun onResume() {
        super.onResume()
        Shown.night = true
        Db.get(this).logEvent("yönäkymä")
        handler.post(tick)
        handler.postDelayed(timeout, 30_000)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 30_000)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        Shown.night = false
        super.onPause()
    }
}
