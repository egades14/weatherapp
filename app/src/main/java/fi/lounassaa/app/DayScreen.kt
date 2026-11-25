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
import kotlin.math.PI
import kotlin.math.sin

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
        info.addView(tv(17f, MUTED).apply {
            text = "Nimipäivä: Esimerkki · Äkäslompolo · päivän ylin −6,1° klo 13.10"
            setPadding(0, ui.px(4f), 0, 0)
        })
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
        top.addView(outCard, lp(0, MATCH_PARENT, 1.4f))

        fun tile(label: String, value: String, detail: String): Pair<TextView, TextView> {
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            card(t)
            t.addView(tv(15f, MUTED).apply { text = label })
            val v = tv(if (compact) 32f else 40f, bold = true).apply { text = value }
            t.addView(v)
            val d = tv(14f, MUTED).apply { text = detail }
            t.addView(d)
            top.addView(t, lp(0, MATCH_PARENT, 1f).apply { leftMargin = gap })
            return v to d
        }
        if (!compact) {
            tile("Viime yö alin", "−12,4°", "klo 05.40")
            tile("Ensi yö ennuste", "−15,1°", "FMI")
        }
        val (iv, idt) = tile("Sisällä", "21,6°", "")
        indoorValue = iv
        indoorDetail = idt

        main.addView(ChartView(ctx, ui), lp(MATCH_PARENT, 0, 1f).apply { topMargin = gap })

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
            outTemp.text = "−8,4°"
            outDetail.text = "esimerkkiarvo · 86 % · 1012 hPa"
        }
        val i = Prefs.indoor(ctx)?.let { Live.latest[it] }
        if (i != null) {
            indoorValue.text = deg(i.r.tempC)
            indoorDetail.text = Fmt.hum(i.r.humidity)
        } else {
            indoorDetail.text = "esimerkkiarvo"
        }
    }
}

/** 36 h chart (24 h measured + 12 h forecast) with sample curves, drawn to its own size. */
class ChartView(ctx: Context, private val ui: Ui) : View(ctx) {
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
    private val nowLine = Paint(grid).apply { color = Color.parseColor("#5c6873") }

    private fun sample(hr: Float, shift: Float = 0f): Float =
        (-11.0 + 3.5 * sin((hr - 8f + shift) / 24.0 * 2 * PI)).toFloat()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = ui.pxF(14f)
        c.drawRoundRect(0f, 0f, w, h, r, r, bg)

        val left = ui.pxF(52f)
        val right = w - ui.pxF(18f)
        val top = ui.pxF(44f)
        val bottom = h - ui.pxF(32f)
        if (bottom - top < ui.pxF(40f)) return

        val tMin = -20f
        val tMax = 0f
        fun x(hr: Float) = left + hr / 36f * (right - left)
        fun y(t: Float) = bottom - (t - tMin) / (tMax - tMin) * (bottom - top)

        // Legend
        var lx = left
        val ly = ui.pxF(22f)
        for ((p, name) in listOf(measured to "Mitattu", forecast to "Ennuste", yesterday to "Eilinen ennuste")) {
            c.drawLine(lx, ly, lx + ui.pxF(28f), ly, p)
            c.drawText(name, lx + ui.pxF(36f), ly + label.textSize / 3, label)
            lx += ui.pxF(36f) + label.measureText(name) + ui.pxF(28f)
        }
        val note = "esimerkkidata"
        c.drawText(note, right - label.measureText(note), ly + label.textSize / 3, label)

        // Temperature grid
        var t = tMin
        while (t <= tMax) {
            val yy = y(t)
            c.drawLine(left, yy, right, yy, grid)
            val s = String.format(FI, "%.0f°", t).replace('-', '−')
            c.drawText(s, left - ui.pxF(8f) - label.measureText(s), yy + label.textSize / 3, label)
            t += 5f
        }

        // Time labels every 6 h on the clock
        val cal = Calendar.getInstance()
        val nowMs = cal.timeInMillis
        cal.add(Calendar.HOUR_OF_DAY, -24)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = nowMs - 24 * 3600_000L
        repeat(38) {
            cal.add(Calendar.HOUR_OF_DAY, 1)
            val hr = (cal.timeInMillis - startMs) / 3600_000f
            if (hr in 0f..36f && cal.get(Calendar.HOUR_OF_DAY) % 6 == 0) {
                val xx = x(hr)
                c.drawLine(xx, bottom, xx, bottom + ui.pxF(5f), grid)
                val s = String.format(FI, "%02d", cal.get(Calendar.HOUR_OF_DAY))
                c.drawText(s, xx - label.measureText(s) / 2, bottom + ui.pxF(22f), label)
            }
        }

        // Curves
        fun path(from: Float, to: Float, shift: Float): Path {
            val p = Path()
            var hr = from
            p.moveTo(x(hr), y(sample(hr, shift)))
            while (hr < to) {
                hr = minOf(to, hr + 0.25f)
                p.lineTo(x(hr), y(sample(hr, shift)))
            }
            return p
        }
        c.drawPath(path(0f, 36f, 1.5f), yesterday)
        c.drawPath(path(0f, 24f, 0f), measured)
        c.drawPath(path(24f, 36f, 0f), forecast)

        val nx = x(24f)
        c.drawLine(nx, top, nx, bottom, nowLine)
        c.drawText("nyt", nx + ui.pxF(6f), top + label.textSize, label)
    }
}
