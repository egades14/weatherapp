package fi.lounassaa.app

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val db = Db.get(context)
        db.logEvent("boot_receiver", intent.action ?: "")
        if (Prefs.autostart(context) && ScanService.hasPermissions(context)) {
            try {
                ScanService.start(context)
            } catch (e: Exception) {
                db.logEvent("autostart_failed", e.javaClass.simpleName)
            }
        }
    }
}

/** Needed only for DevicePolicyManager.lockNow() (screen off at night). */
class AdminReceiver : DeviceAdminReceiver()

class WakeReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "lounassaa:wake"
        )
        wl.acquire(15_000)
        Db.get(context).logEvent("wake_alarm")
    }
}
