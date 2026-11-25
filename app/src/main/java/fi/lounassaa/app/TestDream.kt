package fi.lounassaa.app

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Interactive screensaver test: live values and an exit button. */
class TestDream : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private val tick = object : Runnable {
        override fun run() {
            text.text = render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(48, 48, 48, 48)
        }
        text = TextView(this).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val exit = Button(this).apply {
            this.text = "Poistu"
            textSize = 24f
            setOnClickListener { finish() }
        }
        col.addView(text)
        col.addView(exit)
        setContentView(col)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Db.get(this).logEvent("dream_start")
        handler.post(tick)
    }

    override fun onDreamingStopped() {
        handler.removeCallbacksAndMessages(null)
        Db.get(this).logEvent("dream_stop")
        super.onDreamingStopped()
    }

    private fun render(): String {
        val now = System.currentTimeMillis()
        val out = Prefs.outdoor(this)
        val inn = Prefs.indoor(this)
        val sb = StringBuilder("Lounassää – näytönsäästäjätesti\n\n")
        if (Live.latest.isEmpty()) sb.append("Ei vastaanotettuja mittauksia\n")
        for ((mac, s) in Live.latest.toSortedMap()) {
            val role = when (mac) {
                out -> "Ulko"
                inn -> "Sisä"
                else -> mac
            }
            sb.append("$role  ${Fmt.temp(s.r.tempC)}  ${Fmt.hum(s.r.humidity)}  ${Fmt.age(now - s.ts)}\n")
        }
        sb.append("\nKosketus toimii. Poistu-painike lopettaa näytönsäästäjän.")
        return sb.toString()
    }
}
