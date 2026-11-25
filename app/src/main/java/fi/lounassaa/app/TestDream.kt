package fi.lounassaa.app

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService

/** Interactive screensaver: the day view, touch works, "Poistu" ends it. */
class TestDream : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private var screen: DayScreen? = null
    private val tick = object : Runnable {
        override fun run() {
            screen?.update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        setContentView(ScaledRoot(this, false) { ui ->
            DayScreen(this, ui) { finish() }.also { screen = it }.root
        })
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Shown.dream = true
        Db.get(this).logEvent("dream_start")
        handler.post(tick)
        Night.onDreamStarted(this)
    }

    override fun onDreamingStopped() {
        handler.removeCallbacksAndMessages(null)
        Shown.dream = false
        Db.get(this).logEvent("dream_stop")
        super.onDreamingStopped()
    }
}
