package fi.lounassaa.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * Full-screen day view. Opened from the test screen (preview) or by the morning wake, in which
 * case it appears on top of the lock screen so the lock screen does not flash first.
 */
class DayActivity : Activity() {
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
        if (intent.getBooleanExtra("wake", false)) showOverLock()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(ScaledRoot(this, false) { ui ->
            DayScreen(this, ui) { finish() }.also { screen = it }.root
        })
        hideSystemBars(window)
    }

    override fun onResume() {
        super.onResume()
        Shown.day = WeakReference(this)
        hideSystemBars(window)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        Shown.day = null
        super.onPause()
    }
}
