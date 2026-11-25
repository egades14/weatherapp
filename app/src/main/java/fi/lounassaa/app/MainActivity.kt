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
    private lateinit var ui: Ui
    private lateinit var nightStartBtn: Button
    private lateinit var nightEndBtn: Button

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
        setContentView(ScaledRoot(this, true) { u -> build(u) })
        Night.scheduleAll(this)
    }

    private fun build(u: Ui): android.view.View {
        ui = u
        tagSignature = ""
        val p = u.px(24f)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }
        col.addView(title("Lounassää – laitetestiversio"))

        statusView = TextView(this).apply { u.text(this, 18f) }
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

        col.addView(title("Yöjakso"))
        col.addView(row(
            button("Ota näytön sammutus käyttöön") { enableAdmin() },
            button("Salli näyttö lukitusnäytön päällä") { overlayPermission() },
        ))
        nightStartBtn = button("") { pickTime(true) }
        nightEndBtn = button("") { pickTime(false) }
        col.addView(row(
            nightStartBtn,
            nightEndBtn,
            button("Testaa yöjakso (alkaa 1 min, kestää 5 min)") { Night.startTest(this) },
        ))
        col.addView(row(
            button("Sammuta näyttö nyt") { lockNow() },
            button("Sammuta, herätä 2 min päästä") { lockAndWake() },
        ))
        col.addView(row(
            button("Avaa näytönsäästäjäasetukset") { openDreamSettings() },
            button("Käynnistä näytönsäästäjä nyt") { startDreamNow() },
            button("Päivänäkymän esikatselu") {
                startActivity(Intent(this, DayActivity::class.java))
            },
        ))

        col.addView(title("Virheet"))
        errorView = TextView(this).apply {
            u.text(this, 14f)
            typeface = Typeface.MONOSPACE
            setTextColor(android.graphics.Color.rgb(200, 40, 40))
        }
        col.addView(errorView)
        col.addView(row(button("Jaa loki") { shareLog() }))

        col.addView(title("Tapahtumaloki (uusin ensin)"))
        logView = TextView(this).apply {
            u.text(this, 14f)
            typeface = Typeface.MONOSPACE
        }
        col.addView(logView)

        refresh()
        return ScrollView(this).apply { addView(col) }
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
        ui.text(this, 22f)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, ui.px(24f), 0, ui.px(8f))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        ui.text(this, 16f)
        minHeight = ui.px(52f)
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

    private fun overlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun pickTime(start: Boolean) {
        val cur = if (start) Prefs.nightStart(this) else Prefs.nightEnd(this)
        android.app.TimePickerDialog(this, { _, h, m ->
            if (start) Prefs.setNightStart(this, h * 60 + m) else Prefs.setNightEnd(this, h * 60 + m)
            Db.get(this).logEvent("yöjakso_muutettu", "${Night.hhmm(Prefs.nightStart(this))}–${Night.hhmm(Prefs.nightEnd(this))}")
            Night.scheduleAll(this)
        }, cur / 60, cur % 60, true).show()
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
        if (!::statusView.isInitialized) return
        nightStartBtn.text = "Yö alkaa ${Night.hhmm(Prefs.nightStart(this))}"
        nightEndBtn.text = "Herätys ${Night.hhmm(Prefs.nightEnd(this))}"
        val db = Db.get(this)
        val now = System.currentTimeMillis()
        statusView.text = buildString {
            append("Näyttö: ${ui.describe()}\n")
            append("Yöjakso: ${Night.hhmm(Prefs.nightStart(this@MainActivity))}–${Night.hhmm(Prefs.nightEnd(this@MainActivity))}" +
                " (nyt ${if (Night.isNight(this@MainActivity)) "yö" else "päivä"})\n")
            append("Lukitusnäytön päällä näyttäminen: ${if (Night.canShowOverLock(this@MainActivity)) "sallittu" else "ei sallittu"}\n")
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
                val label = TextView(this).apply { ui.text(this, 17f); tag = mac }
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
