package fi.lounassaa.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val trace = android.util.Log.getStackTraceString(e).take(1500)
                Db.get(this).logError("kaatuminen", "${thread.name}: $trace")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
