package fi.lounassaa.app

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val db = Db.get(context)
        db.logEvent("boot_receiver", intent.action ?: "")
        Night.scheduleAll(context)
        if (Prefs.autostart(context) && ScanService.hasPermissions(context)) {
            try {
                ScanService.start(context)
            } catch (e: Exception) {
                db.logError("autostart_failed", e.javaClass.simpleName)
            }
        }
    }
}

/** Needed only for DevicePolicyManager.lockNow() (screen off at night). */
class AdminReceiver : DeviceAdminReceiver()

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Db.get(context).logEvent("wake_alarm")
        wakeScreen(context)
        Night.launch(context, DayActivity::class.java, wake = true)
    }
}
