package fi.lounassaa.app

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

enum class LayoutMode(val label: String) {
    /** Lunch panel beside the main column (every landscape tablet at full screen). */
    WIDE("leveä"),
    /** Lunch panel below (portrait, narrow split screen). */
    STACKED("pino"),
    /** Low window (landscape split screen): tiles reduced, lunch panel hidden. */
    COMPACT("matala"),
}

/**
 * Sizes for one window. The reference design is Galaxy Tab A11+ in landscape, 1280 × 800 dp.
 * Everything is drawn in reference units and multiplied by [scale], so the A11+ gets the design 1:1
 * and a larger tablet gets the same layout proportionally larger. Space left over in the other
 * direction (other aspect ratios) goes to the chart and the lunch panel.
 * Text ignores the system font size on purpose: a wall display has a fixed layout.
 */
class Ui(val density: Float, val widthDp: Float, val heightDp: Float) {
    companion object {
        const val REF_W = 1280f
        const val REF_H = 800f

        fun of(c: Context, widthPx: Int, heightPx: Int): Ui {
            val d = c.resources.displayMetrics.density
            return Ui(d, widthPx / d, heightPx / d)
        }
    }

    val scale: Float = min(widthDp / REF_W, heightDp / REF_H).coerceIn(0.7f, 1.6f)
    val refWidth: Float = widthDp / scale
    val refHeight: Float = heightDp / scale

    val mode: LayoutMode = when {
        heightDp < 520f -> LayoutMode.COMPACT
        refWidth >= 1180f -> LayoutMode.WIDE
        else -> LayoutMode.STACKED
    }

    fun px(v: Float): Int = (v * scale * density).roundToInt()
    fun pxF(v: Float): Float = v * scale * density
    fun text(tv: TextView, size: Float) = tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(size))

    fun describe(): String = String.format(
        Locale("fi", "FI"), "%.0f × %.0f dp · tiheys %.2f · skaala %.2f · asettelu %s",
        widthDp, heightDp, density, scale, mode.label
    )
}

/**
 * Root view that rebuilds its content whenever its usable size changes
 * (rotation, split screen, different tablet). [padForBars] keeps content clear of the
 * status and navigation bars (Android 15 draws apps edge to edge).
 */
class ScaledRoot(
    context: Context,
    private val padForBars: Boolean,
    private val build: (Ui) -> View,
) : FrameLayout(context) {
    private var key = ""

    init {
        if (padForBars) {
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(
                        insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                    )
                }
                maybeRebuild()
                insets
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maybeRebuild()
    }

    private fun maybeRebuild() {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val k = "${w}x$h"
        if (k == key) return
        key = k
        post {
            removeAllViews()
            addView(build(Ui.of(context, w, h)), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }
}

fun hideSystemBars(w: Window) {
    if (Build.VERSION.SDK_INT >= 30) {
        w.setDecorFitsSystemWindows(false)
        w.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}
