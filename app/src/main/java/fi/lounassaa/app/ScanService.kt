package fi.lounassaa.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

@SuppressLint("MissingPermission")
class ScanService : Service() {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "scan"
        private const val TICK_MS = 10 * 60 * 1000L

        fun hasPermissions(c: Context): Boolean {
            val need = if (Build.VERSION.SDK_INT >= 31) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            return need.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }

        fun start(c: Context) {
            c.startForegroundService(Intent(c, ScanService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ScanService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: Db
    private var scanner: BluetoothLeScanner? = null
    private var resultsSinceTick = 0
    private var rejected = 0L
    private val lastStoredMinute = HashMap<String, Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> db.logEvent("screen_on")
                Intent.ACTION_SCREEN_OFF -> db.logEvent("screen_off")
                Intent.ACTION_POWER_CONNECTED -> db.logEvent("power_connected")
                Intent.ACTION_POWER_DISCONNECTED -> db.logEvent("power_disconnected")
            }
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            db.logError("scan_failed", errorCode.toString())
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            db.logEvent("tick", "adverts=$resultsSinceTick tags=${Live.latest.size}")
            resultsSinceTick = 0
            restartScan()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = Db.get(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Anturien seuranta", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Lounassää")
            .setContentText("Seuraa RuuviTag-antureita")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (!hasPermissions(this)) {
            db.logError("service_no_permission")
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }

        if (!running) {
            running = true
            db.logEvent("service_start")
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            registerReceiver(screenReceiver, f)
            startScan()
            handler.postDelayed(tick, TICK_MS)
        }
        return START_STICKY
    }

    private fun startScan() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            db.logError("bluetooth_off")
            return
        }
        scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setManufacturerData(RuuviParser.MANUFACTURER_ID, byteArrayOf(5), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        try {
            scanner?.startScan(listOf(filter), settings, callback)
        } catch (e: Exception) {
            db.logError("scan_start_error", e.javaClass.simpleName)
        }
    }

    private fun stopScan() {
        try {
            scanner?.stopScan(callback)
        } catch (e: Exception) {
            // Bluetooth may have been switched off
        }
    }

    private fun restartScan() {
        stopScan()
        startScan()
    }

    private val lastRejectLog = HashMap<String, Long>()

    private fun handle(result: ScanResult) {
        try {
            handleUnsafe(result)
        } catch (e: Exception) {
            db.logError("käsittely", e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun handleUnsafe(result: ScanResult) {
        val bytes = result.scanRecord?.getManufacturerSpecificData(RuuviParser.MANUFACTURER_ID) ?: return
        val r = RuuviParser.parseV5(bytes) ?: return
        val mac = result.device.address
        val now = System.currentTimeMillis()
        val reason = Plausible.check(r)
        if (reason != null) {
            rejected++
            val last = lastRejectLog[mac] ?: 0L
            if (now - last > 10 * 60 * 1000L) {
                lastRejectLog[mac] = now
                db.logError("hylätty_arvo", "$mac $reason (hylättyjä yht. $rejected)")
            }
            return
        }
        Live.latest[mac] = Seen(now, r, result.rssi)
        resultsSinceTick++
        val minute = now / 60000
        if (lastStoredMinute[mac] != minute) {
            lastStoredMinute[mac] = minute
            db.insertReading(minute * 60000, mac, r, result.rssi)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        if (running) {
            try { unregisterReceiver(screenReceiver) } catch (e: Exception) { }
            db.logEvent("service_destroy")
        }
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
