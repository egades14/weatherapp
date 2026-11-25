package fi.lounassaa.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Full-screen day view outside the screensaver, for checking the layout on any tablet. */
class PreviewActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var screen: DayScreen? = null
    private val tick = object : Runnable {
        override fun run() {
            screen?.update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScaledRoot(this, false) { ui ->
            DayScreen(this, ui) { finish() }.also { screen = it }.root
        })
        hideSystemBars(window)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars(window)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }
}
