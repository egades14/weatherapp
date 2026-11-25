package fi.lounassaa.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Shown on top of the lock screen when the screen is turned on at night (double tap):
 * the day view at low brightness, screen off again after 30 s without touches.
 */
class NightActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var screen: DayScreen? = null

    private val timeout = Runnable {
        Night.lock(this, "yönäkymä 30 s")
        finish()
    }
    private val tick = object : Runnable {
        override fun run() {
            screen?.update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLock()
        window.attributes = window.attributes.apply { screenBrightness = 0.1f }
        setContentView(ScaledRoot(this, false) { ui ->
            DayScreen(this, ui) { finish() }.also { screen = it }.root
        })
        hideSystemBars(window)
    }

    override fun onResume() {
        super.onResume()
        Shown.nightView = WeakReference(this)
        Db.get(this).logEvent("yönäkymä")
        hideSystemBars(window)
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
        Shown.nightView = null
        super.onPause()
    }
}
