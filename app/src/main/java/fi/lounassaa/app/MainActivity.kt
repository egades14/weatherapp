package fi.lounassaa.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Device test build: permissions, service control, tag roles, screensaver/night tests, event log. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var tagsBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var errorView: TextView
    private var tagSignature = ""

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val admin by lazy { ComponentName(this, AdminReceiver::class.java) }
    private val dpm by lazy { getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        col.addView(title("Lounassää – laitetestiversio"))

        statusView = TextView(this).apply { textSize = 18f }
        col.addView(statusView)

        col.addView(row(
            button("1. Myönnä luvat") { requestPerms() },
            button("2. Akun poikkeus") { batteryExemption() },
            button("3. Käynnistä seuranta") { startTracking() },
            button("Pysäytä") { stopTracking() },
        ))

        col.addView(title("Löydetyt anturit"))
        tagsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tagsBox)

        col.addView(title("Yösammutuksen testi"))
        col.addView(row(
            button("Ota näytön sammutus käyttöön") { enableAdmin() },
            button("Sammuta näyttö nyt") { lockNow() },
            button("Sammuta, herätä 2 min päästä") { lockAndWake() },
        ))
        col.addView(row(
            button("Avaa näytönsäästäjäasetukset") { openDreamSettings() },
            button("Käynnistä näytönsäästäjä nyt") { startDreamNow() },
        ))

        col.addView(title("Virheet"))
        errorView = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(android.graphics.Color.rgb(200, 40, 40))
        }
        col.addView(errorView)
        col.addView(row(button("Jaa loki") { shareLog() }))

        col.addView(title("Tapahtumaloki (uusin ensin)"))
        logView = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
        col.addView(logView)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 22f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 32, 0, 12)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun requestPerms() {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun batteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun startTracking() {
        if (!ScanService.hasPermissions(this)) {
            toast("Myönnä ensin luvat")
            return
        }
        Prefs.setAutostart(this, true)
        ScanService.start(this)
    }

    private fun stopTracking() {
        Prefs.setAutostart(this, false)
        ScanService.stop(this)
    }

    private fun enableAdmin() {
        if (dpm.isAdminActive(admin)) {
            toast("Jo käytössä")
            return
        }
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Tarvitaan näytön sammuttamiseen yöksi.")
        )
    }

    private fun lockNow() {
        if (!dpm.isAdminActive(admin)) {
            toast("Ota ensin sammutus käyttöön")
            return
        }
        Db.get(this).logEvent("test_lock_now")
        dpm.lockNow()
    }

    private fun lockAndWake() {
        if (!dpm.isAdminActive(admin)) {
            toast("Ota ensin sammutus käyttöön")
            return
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val wake = PendingIntent.getBroadcast(this, 1, Intent(this, WakeReceiver::class.java), flags)
        val show = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 120_000, show), wake)
        Db.get(this).logEvent("test_lock_wake_scheduled")
        dpm.lockNow()
    }

    private fun openDreamSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
    }

    private fun startDreamNow() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
            )
        } catch (e: Exception) {
            toast("Ei onnistunut. Valitse Lounassää näytönsäästäjäksi ja paina Esikatsele.")
        }
    }

    private fun shareLog() {
        val text = Db.get(this).recentEvents(300).joinToString("\n")
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "Jaa loki"
            )
        )
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun refresh() {
        val db = Db.get(this)
        val now = System.currentTimeMillis()
        statusView.text = buildString {
            append("Luvat: ${if (ScanService.hasPermissions(this@MainActivity)) "OK" else "puuttuu"}\n")
            append("Seuranta: ${if (ScanService.running) "käynnissä" else "pysäytetty"}\n")
            append("Näytön sammutus: ${if (dpm.isAdminActive(admin)) "käytössä" else "ei käytössä"}\n")
            append("Tallennettuja mittauksia: ${db.count()}\n")
            append("Virheitä 24 h: ${db.errorCountSince(now - 24 * 3600 * 1000L)}")
        }

        val stored = db.latestPerTag().associateBy { it.mac }
        val macs = (Live.latest.keys + stored.keys).toSortedSet().toList()
        val signature = macs.joinToString(",") + "|" + Prefs.outdoor(this) + "|" + Prefs.indoor(this)
        if (signature != tagSignature) {
            tagSignature = signature
            tagsBox.removeAllViews()
            if (macs.isEmpty()) {
                tagsBox.addView(TextView(this).apply { text = "Ei vielä antureita. Käynnistä seuranta." })
            }
            for (mac in macs) {
                val label = TextView(this).apply { textSize = 18f; tag = mac }
                val outBtn = button("Ulko") { Prefs.setOutdoor(this@MainActivity, mac); tagSignature = "" }
                val inBtn = button("Sisä") { Prefs.setIndoor(this@MainActivity, mac); tagSignature = "" }
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
                    addView(outBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(inBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                tagsBox.addView(line)
            }
        }
        for (i in 0 until tagsBox.childCount) {
            val line = tagsBox.getChildAt(i) as? LinearLayout ?: continue
            val label = line.getChildAt(0) as? TextView ?: continue
            val mac = label.tag as? String ?: continue
            val role = when (mac) {
                Prefs.outdoor(this) -> "[ULKO] "
                Prefs.indoor(this) -> "[SISÄ] "
                else -> ""
            }
            val live = Live.latest[mac]
            label.text = if (live != null) {
                "$role$mac  ${Fmt.temp(live.r.tempC)}  ${Fmt.hum(live.r.humidity)}  " +
                    "${Fmt.pres(live.r.pressureHpa)}  ${Fmt.volt(live.r.voltage)}  " +
                    "${live.rssi} dBm  ${Fmt.age(now - live.ts)}"
            } else {
                val s = stored[mac]
                "$role$mac  (ei kuulu nyt)" + if (s != null) "  viimeksi ${Fmt.age(now - s.ts)}" else ""
            }
        }

        val errors = db.recentErrors(15)
        errorView.text = if (errors.isEmpty()) "Ei virheitä" else errors.joinToString("\n")
        logView.text = db.recentEvents(40).joinToString("\n")
    }
}
