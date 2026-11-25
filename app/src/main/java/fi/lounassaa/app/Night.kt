package fi.lounassaa.app

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.Locale

/** What the app currently has on screen. */
object Shown {
    @Volatile var dream = false
    @Volatile var day: WeakReference<Activity>? = null
    @Volatile var night = false
    fun displayMode(): Boolean = dream || day?.get() != null || night
}

/** Night period: screen off from start to end, double tap shows a dim view, wake in the morning. */
object Night {
    const val ACTION_START = "fi.lounassaa.app.NIGHT_START"
    const val ACTION_END = "fi.lounassaa.app.NIGHT_END"
    private const val RECHECK_MS = 5 * 60 * 1000L

    fun isNightMinute(minute: Int, start: Int, end: Int): Boolean =
        if (start == end) false
        else if (start < end) minute in start until end
        else minute >= start || minute < end

    fun isNight(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (now >= Prefs.testNightStart(c) && now < Prefs.testNightEnd(c)) return true
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return isNightMinute(m, Prefs.nightStart(c), Prefs.nightEnd(c))
    }

    fun hhmm(minute: Int): String = String.format(Locale("fi", "FI"), "%02d.%02d", minute / 60, minute % 60)

    private fun next(minute: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun pi(c: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        c, code, Intent(c, NightReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun setExact(c: Context, at: Long, op: PendingIntent, wake: Boolean) {
        val am = c.getSystemService(AlarmManager::class.java)
        try {
            if (wake) {
                val show = PendingIntent.getActivity(
                    c, 20, Intent(c, DayActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            }
        } catch (e: SecurityException) {
            Db.get(c).logError("tarkka_ajastus", e.message ?: "")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    /** (Re)schedules the next regular start and end, and the test period if one is pending. */
    fun scheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        setExact(c, next(Prefs.nightStart(c), now), pi(c, ACTION_START, 10), wake = false)
        setExact(c, next(Prefs.nightEnd(c), now), pi(c, ACTION_END, 11), wake = true)
        val ts = Prefs.testNightStart(c)
        val te = Prefs.testNightEnd(c)
        if (ts > now) setExact(c, ts, pi(c, ACTION_START, 12), wake = false)
        if (te > now) setExact(c, te, pi(c, ACTION_END, 13), wake = true)
    }

    fun startTest(c: Context) {
        val now = System.currentTimeMillis()
        Prefs.setTestNight(c, now + 60_000L, now + 6 * 60_000L)
        Db.get(c).logEvent("yötesti_ajastettu", "alkaa 1 min, päättyy 6 min")
        scheduleAll(c)
    }

    fun recheckLater(c: Context) {
        setExact(c, System.currentTimeMillis() + RECHECK_MS, pi(c, ACTION_START, 14), wake = false)
    }

    fun adminActive(c: Context): Boolean =
        c.getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(c, AdminReceiver::class.java))

    fun canShowOverLock(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun lock(c: Context, reason: String) {
        if (!adminActive(c)) {
            Db.get(c).logError("ei_sammutuslupaa", reason)
            return
        }
        Db.get(c).logEvent("näyttö_sammutetaan", reason)
        c.getSystemService(DevicePolicyManager::class.java).lockNow()
    }

    /** Opens an activity from the background. Android allows it with the "display over other apps" permission. */
    fun launch(c: Context, cls: Class<out Activity>, wake: Boolean): Boolean {
        if (!canShowOverLock(c)) {
            Db.get(c).logError("ei_päällepiirtolupaa", cls.simpleName)
            return false
        }
        c.startActivity(
            Intent(c, cls)
                .putExtra("wake", wake)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        return true
    }

    fun onScreenOn(c: Context) {
        if (!isNight(c) || Shown.night) return
        Db.get(c).logEvent("yö_näyttö_päälle")
        launch(c, NightActivity::class.java, wake = false)
    }

    /** Called when the screensaver starts: at night it is not wanted, the screen goes off instead. */
    fun onDreamStarted(c: Context) {
        if (isNight(c)) lock(c, "näytönsäästäjä yöllä")
    }
}

class NightReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val db = Db.get(c)
        try {
            when (intent.action) {
                Night.ACTION_START -> {
                    if (!Night.isNight(c)) return
                    val pm = c.getSystemService(PowerManager::class.java)
                    when {
                        !pm.isInteractive -> db.logEvent("yö_alkoi", "näyttö jo pois")
                        Shown.displayMode() -> {
                            db.logEvent("yö_alkoi")
                            Shown.day?.get()?.finish()
                            Night.lock(c, "yöjakso alkoi")
                        }
                        else -> {
                            db.logEvent("yö_siirretty", "tabletti käytössä, uusi yritys 5 min")
                            Night.recheckLater(c)
                        }
                    }
                }
                Night.ACTION_END -> {
                    db.logEvent("aamuherätys")
                    wakeScreen(c)
                    Night.launch(c, DayActivity::class.java, wake = true)
                }
            }
        } catch (e: Exception) {
            db.logError("yöajastus", e.javaClass.simpleName + ": " + e.message)
        } finally {
            Night.scheduleAll(c)
        }
    }
}

@Suppress("DEPRECATION")
fun wakeScreen(c: Context) {
    val pm = c.getSystemService(PowerManager::class.java)
    pm.newWakeLock(
        PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
        "lounassaa:wake"
    ).acquire(10_000)
}

/** Lets an activity appear on top of the lock screen without unlocking it. */
@Suppress("DEPRECATION")
fun Activity.showOverLock() {
    if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    } else {
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
}
