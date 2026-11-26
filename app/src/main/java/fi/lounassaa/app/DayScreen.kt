package fi.lounassaa.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val FI = Locale("fi", "FI")
private val BG = Color.parseColor("#0f1418")
private val CARD = Color.parseColor("#1a2128")
private val FG = Color.parseColor("#f2f4f6")
private val MUTED = Color.parseColor("#93a0ab")
private val BLUE = Color.parseColor("#4aa3ff")
private val ORANGE = Color.parseColor("#ff9a3c")

private val SAMPLE_LUNCH = listOf(
    "Yllätsken Kalakauppa" to "Esimerkki: lohikeitto, ruisleipä",
    "Lapland Hotels Ylläskaltio" to "Lounasbuffet 12–16 · 17 €",
    "Saaga Biegga" to "Esimerkki: poronkäristys, perunamuusi · 19 €",
    "Äkäshotelli Pirtukirkko" to "Esimerkki: päivän keitto",
    "Kuerkievari" to "Lounastiedot puuttuvat",
)

/**
 * Day view layout (step 2 of the plan) with sample data, so the layout can be checked on
 * any tablet before the sensors arrive. Live sensor values replace the samples when available.
 */
class DayScreen(private val ctx: Context, private val ui: Ui, private val onExit: () -> Unit) {
    private val compact = ui.mode == LayoutMode.COMPACT
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var outTemp: TextView
    private lateinit var outDetail: TextView
    private lateinit var indoorValue: TextView
    private lateinit var indoorDetail: TextView
    private lateinit var placeLine: TextView
    private lateinit var precipLine: TextView
    private lateinit var lastNightValue: TextView
    private lateinit var lastNightDetail: TextView
    private var nightValue: TextView? = null
    private var nightDetail: TextView? = null
    private var lastNightLabel: TextView? = null
    private lateinit var chart: ChartView
    private var loadedAt = 0L
    private var loadedVersion = -1

    val root: View = build()

    init {
        update()
    }

    private fun tv(size: Float, color: Int = FG, bold: Boolean = false, single: Boolean = true) =
        TextView(ctx).apply {
            ui.text(this, size)
            setTextColor(color)
            includeFontPadding = false
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (single) {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    private fun card(v: View) {
        v.background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = ui.pxF(14f)
        }
        val p = ui.px(16f)
        v.setPadding(p, p, p, p)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun build(): View {
        val pad = ui.px(20f)
        val gap = ui.px(14f)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(pad, pad, pad, pad)
        }

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clock = tv(if (compact) 48f else 64f, bold = true)
        header.addView(clock)
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(20f), 0, ui.px(12f), 0)
        }
        date = tv(22f)
        info.addView(date)
        placeLine = tv(17f, MUTED).apply { setPadding(0, ui.px(4f), 0, 0) }
        info.addView(placeLine)
        if (!compact) {
            info.addView(tv(17f, MUTED).apply {
                text = "Päivän pituus 3 h 45 min (−9 min) · kaamos alkaa 14.12."
                setPadding(0, ui.px(4f), 0, 0)
            })
        }
        header.addView(info, lp(0, WRAP_CONTENT, 1f))
        header.addView(Button(ctx).apply {
            text = "Poistu"
            isAllCaps = false
            ui.text(this, 18f)
            minHeight = ui.px(56f)
            minWidth = ui.px(120f)
            setOnClickListener { onExit() }
        })
        col.addView(header)

        // Body
        val body = LinearLayout(ctx).apply {
            orientation = if (ui.mode == LayoutMode.WIDE) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        col.addView(body, lp(MATCH_PARENT, 0, 1f).apply { topMargin = gap })

        val main = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(top, lp(MATCH_PARENT, ui.px(if (compact) 112f else 150f)))

        val outCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card(outCard)
        outCard.addView(tv(15f, MUTED).apply { text = "Ulkona" })
        outTemp = tv(if (compact) 44f else 56f, bold = true)
        outCard.addView(outTemp)
        outDetail = tv(16f, MUTED)
        outCard.addView(outDetail)
        precipLine = tv(16f, Color.parseColor("#5ec8e5"))
        outCard.addView(precipLine)
        top.addView(outCard, lp(0, MATCH_PARENT, 1.4f))

        fun tile(label: String, value: String, detail: String): Triple<TextView, TextView, TextView> {
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            card(t)
            val l = tv(15f, MUTED).apply { text = label }
            t.addView(l)
            val v = tv(if (compact) 32f else 40f, bold = true).apply { text = value }
            t.addView(v)
            val d = tv(14f, MUTED).apply { text = detail }
            t.addView(d)
            top.addView(t, lp(0, MATCH_PARENT, 1f).apply { leftMargin = gap })
            return Triple(l, v, d)
        }
        if (!compact) {
            val a = tile("Viime yö alin", "–", "")
            lastNightLabel = a.first
            lastNightValue = a.second
            lastNightDetail = a.third
            val b = tile("Ensi yö ennuste", "–", "")
            nightValue = b.second
            nightDetail = b.third
        } else {
            lastNightValue = TextView(ctx)
            lastNightDetail = TextView(ctx)
        }
        val ind = tile("Sisällä", "–", "")
        indoorValue = ind.second
        indoorDetail = ind.third

        chart = ChartView(ctx, ui)
        main.addView(chart, lp(MATCH_PARENT, 0, 1f).apply { topMargin = gap })

        when (ui.mode) {
            LayoutMode.WIDE -> {
                body.addView(main, lp(0, MATCH_PARENT, 1f))
                body.addView(lunchPanel(), lp(ui.px(360f), MATCH_PARENT).apply { leftMargin = gap })
            }
            LayoutMode.STACKED -> {
                body.addView(main, lp(MATCH_PARENT, 0, 1.6f))
                body.addView(lunchPanel(), lp(MATCH_PARENT, 0, 1f).apply { topMargin = gap })
            }
            LayoutMode.COMPACT -> body.addView(main, lp(MATCH_PARENT, 0, 1f))
        }
        return col
    }

    private fun lunchPanel(): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card(col)
        col.addView(tv(20f, bold = true).apply { text = "Lounas tänään" })
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for ((name, menu) in SAMPLE_LUNCH) {
            list.addView(tv(17f, bold = true).apply {
                text = name
                setPadding(0, ui.px(14f), 0, ui.px(3f))
            })
            list.addView(tv(15f, MUTED, single = false).apply { text = menu })
        }
        col.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, lp(MATCH_PARENT, 0, 1f))
        return col
    }

    private fun deg(v: Double) = String.format(FI, "%.1f°", v).replace('-', '−')

    fun update() {
        val now = Date()
        clock.text = SimpleDateFormat("HH.mm", FI).format(now)
        date.text = SimpleDateFormat("cccc d.M.yyyy", FI).format(now).replaceFirstChar { it.uppercase() }

        val o = Prefs.outdoor(ctx)?.let { Live.latest[it] }
        if (o != null) {
            outTemp.text = deg(o.r.tempC)
            outDetail.text = "${Fmt.hum(o.r.humidity)} · ${Fmt.pres(o.r.pressureHpa)}"
        } else {
            outTemp.text = "–"
            outDetail.text = "ei mittausta"
        }
        val i = Prefs.indoor(ctx)?.let { Live.latest[it] }
        if (i != null) {
            indoorValue.text = deg(i.r.tempC)
            indoorDetail.text = Fmt.hum(i.r.humidity)
        } else {
            indoorValue.text = "–"
            indoorDetail.text = "ei mittausta"
        }
        val nowMs = now.time
        if (nowMs - loadedAt >= 60_000L || Weather.version != loadedVersion) loadData(nowMs)
    }

    private fun at(now: Long, dayOffset: Int, hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Reads the database: chart data, night values, day max, precipitation line. Once a minute. */
    private fun loadData(now: Long) {
        loadedAt = now
        loadedVersion = Weather.version
        val db = Db.get(ctx)
        val hhmm = SimpleDateFormat("HH.mm", FI)
        val start = now - 24 * 3600_000L
        val end = now + 12 * 3600_000L
        val mac = Prefs.outdoor(ctx)
        val measured = mac?.let { db.temps(it, start, now) } ?: emptyList()
        val latest = db.latestForecastFetch()
        val fc = if (latest > 0) db.forecast(latest, now - 2 * 3600_000L, now + 48 * 3600_000L) else emptyList()
        val yFetch = db.forecastFetchNear(now - 24 * 3600_000L, 3 * 3600_000L)
        val yest = if (yFetch > 0) db.forecast(yFetch, start, end) else emptyList()
        chart.data = ChartData(start, end, now, measured, fc, yest)

        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val (nFrom, nTo) = if (hour < 9) at(now, -1, 18) to now else at(now, -1, 18) to at(now, 0, 9)
        lastNightLabel?.text = if (hour < 9) "Tämä yö alin" else "Viime yö alin"
        val low = mac?.let { db.temps(it, nFrom, nTo) }?.minByOrNull { it.second }
        if (low != null) {
            lastNightValue.text = deg(low.second)
            lastNightDetail.text = "klo ${hhmm.format(Date(low.first))}"
        } else {
            lastNightValue.text = "–"
            lastNightDetail.text = "ei mittauksia"
        }

        val nm = Weather.nightMin(fc, now)
        nightValue?.text = nm?.let { deg(it.temp) } ?: "–"
        nightDetail?.text = nm?.let { "klo ${SimpleDateFormat("HH", FI).format(Date(it.ts))} · Yr" } ?: "ei ennustetta"

        val dayMax = mac?.let { db.temps(it, at(now, 0, 0), now) }?.maxByOrNull { it.second }
        placeLine.text = "Nimipäivä: Esimerkki · Äkäslompolo" +
            (dayMax?.let { " · päivän ylin ${deg(it.second)} klo ${hhmm.format(Date(it.first))}" } ?: "")

        val p = Weather.precipText(Weather.nowcast, fc, now)
        precipLine.text = p ?: ""
        precipLine.visibility = if (p == null) View.GONE else View.VISIBLE
    }
}

class ChartData(
    val start: Long,
    val end: Long,
    val now: Long,
    val measured: List<Pair<Long, Double>>,
    val forecast: List<FcPoint>,
    val yesterday: List<FcPoint>,
)

/** 36 h chart: 24 h measured + 12 h forecast, yesterday's forecast faint, precipitation as bars. */
class ChartView(ctx: Context, private val ui: Ui) : View(ctx) {
    var data: ChartData? = null
        set(v) {
            field = v
            invalidate()
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2a333c")
        strokeWidth = ui.pxF(1f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = ui.pxF(14f)
    }
    private val measured = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BLUE
        style = Paint.Style.STROKE
        strokeWidth = ui.pxF(3.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val forecast = Paint(measured).apply {
        color = ORANGE
        pathEffect = DashPathEffect(floatArrayOf(ui.pxF(10f), ui.pxF(7f)), 0f)
    }
    private val yesterday = Paint(forecast).apply {
        color = Color.argb(110, 255, 154, 60)
        strokeWidth = ui.pxF(2f)
    }
    private val rain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 94, 200, 229) }
    private val rainLegend = Paint(rain).apply { strokeWidth = ui.pxF(8f) }
    private val nowLine = Paint(grid).apply { color = Color.parseColor("#5c6873") }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = ui.pxF(14f)
        c.drawRoundRect(0f, 0f, w, h, r, r, bg)
        val d = data ?: return

        val left = ui.pxF(52f)
        val right = w - ui.pxF(18f)
        val top = ui.pxF(44f)
        val bottom = h - ui.pxF(32f)
        if (bottom - top < ui.pxF(40f)) return
        val span = (d.end - d.start).toFloat()
        fun x(ts: Long) = left + (ts - d.start) / span * (right - left)

        // Legend and source credit
        var lx = left
        val ly = ui.pxF(22f)
        for ((p, name) in listOf(measured to "Mitattu", forecast to "Ennuste", yesterday to "Eilinen ennuste", rainLegend to "Sade")) {
            c.drawLine(lx, ly, lx + ui.pxF(28f), ly, p)
            c.drawText(name, lx + ui.pxF(36f), ly + label.textSize / 3, label)
            lx += ui.pxF(36f) + label.measureText(name) + ui.pxF(26f)
        }
        val credit = "Ennuste: MET Norway / yr.no"
        c.drawText(credit, right - label.measureText(credit), ly + label.textSize / 3, label)

        val temps = d.measured.map { it.second } +
            d.forecast.filter { it.ts in d.start..d.end }.map { it.temp } +
            d.yesterday.map { it.temp }
        if (temps.isEmpty()) {
            val msg = "Ei vielä dataa. Ennuste haetaan, kun seuranta on käynnissä."
            c.drawText(msg, (w - label.measureText(msg)) / 2, h / 2, label)
            return
        }
        var tMin = (kotlin.math.floor(temps.min() / 5.0) * 5).toFloat()
        var tMax = (kotlin.math.ceil(temps.max() / 5.0) * 5).toFloat()
        if (tMax - tMin < 10f) {
            val mid = (tMax + tMin) / 2
            tMin = (kotlin.math.floor((mid - 5) / 5.0) * 5).toFloat()
            tMax = tMin + 10f
        }
        fun y(t: Double) = bottom - ((t.toFloat() - tMin) / (tMax - tMin)) * (bottom - top)

        // Temperature grid
        var t = tMin
        while (t <= tMax + 0.01f) {
            val yy = y(t.toDouble())
            c.drawLine(left, yy, right, yy, grid)
            val s = String.format(FI, "%.0f°", t).replace('-', '−')
            c.drawText(s, left - ui.pxF(8f) - label.measureText(s), yy + label.textSize / 3, label)
            t += 5f
        }

        // Time labels every 6 h
        val cal = Calendar.getInstance().apply {
            timeInMillis = d.start
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= d.end) {
            val ts = cal.timeInMillis
            if (ts >= d.start && cal.get(Calendar.HOUR_OF_DAY) % 6 == 0) {
                val xx = x(ts)
                c.drawLine(xx, bottom, xx, bottom + ui.pxF(5f), grid)
                val s = String.format(FI, "%02d", cal.get(Calendar.HOUR_OF_DAY))
                c.drawText(s, xx - label.measureText(s) / 2, bottom + ui.pxF(22f), label)
            }
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }

        // Precipitation bars in the lowest 30 % of the plot
        val bars = d.forecast.filter { it.ts + 3600_000L > d.now && it.ts < d.end && !it.precip.isNaN() && it.precip > 0.0 }
        if (bars.isNotEmpty()) {
            val scale = maxOf(2.0, bars.maxOf { it.precip })
            val band = (bottom - top) * 0.3f
            val gap = ui.pxF(2f)
            for (b in bars) {
                val x0 = maxOf(x(maxOf(b.ts, d.now)), left) + gap
                val x1 = minOf(x(b.ts + 3600_000L), right) - gap
                if (x1 <= x0) continue
                val hh = (b.precip / scale).toFloat().coerceAtMost(1f) * band
                c.drawRect(x0, bottom - hh, x1, bottom, rain)
            }
        }

        // Curves
        fun pathOf(points: List<Pair<Long, Double>>, maxGap: Long): Path {
            val p = Path()
            var prev = Long.MIN_VALUE
            for ((ts, v) in points) {
                if (ts < d.start || ts > d.end) continue
                if (prev == Long.MIN_VALUE || ts - prev > maxGap) p.moveTo(x(ts), y(v)) else p.lineTo(x(ts), y(v))
                prev = ts
            }
            return p
        }
        c.drawPath(pathOf(d.yesterday.map { it.ts to it.temp }, 3 * 3600_000L), yesterday)
        c.drawPath(pathOf(d.forecast.filter { it.ts >= d.now - 3600_000L }.map { it.ts to it.temp }, 3 * 3600_000L), forecast)
        c.drawPath(pathOf(d.measured, 10 * 60_000L), measured)

        val nx = x(d.now)
        c.drawLine(nx, top, nx, bottom, nowLine)
        c.drawText("nyt", nx + ui.pxF(6f), top + label.textSize, label)
    }
}
