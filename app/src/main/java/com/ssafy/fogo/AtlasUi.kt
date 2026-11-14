package com.ssafy.fogo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class AtlasColors(val night: Boolean) {
    val background = rgb(if (night) 0x111317 else 0xf3f4f6)
    val paper = rgb(if (night) 0x1d1f24 else 0xffffff)
    val fog = rgb(if (night) 0x30343c else 0xe4e6ea)
    val border = rgb(if (night) 0x66717e else 0x0b1426)
    val ink = rgb(if (night) 0xf2f4f7 else 0x0b1426)
    val muted = rgb(if (night) 0xbbc2cb else 0x5b6270)
    val navy = rgb(if (night) 0x1d1f24 else 0x002c5f)
    val yellow = rgb(if (night) 0xe8be2a else 0xffd43b)
    val lime = rgb(if (night) 0x9bd62a else 0xb8f53c)
    val violet = rgb(if (night) 0xa893ff else 0x7b5cff)
    val onViolet = if (night) rgb(0x0b1426) else Color.WHITE
    val dark = rgb(0x0b1426)
    val white = Color.WHITE

    private fun rgb(value: Int) = value or 0xff000000.toInt()
}

/**
 * [scale] enlarges every dp and text size together so screens follow the Figma proportions
 * (1700×1184 / 840×1184 frames) on wide Pleos displays instead of rendering at phone size.
 */
/** Text-size factor for Galmuri so it fits where the previous sans did. */
const val GALMURI = .9f
/**
 * Smallest Hangul size (sp before [AtlasUi.scale]). Galmuri is a pixel face, so it needs more size than a sans
 * to read at arm's length: on the 1682 px Pleos screen (scale ≈ 1.7) this is ≈ 27 px, glyphs ≈ 25 px tall.
 * Press Start 2P (Latin caps) keeps its own sizes.
 */
const val MIN_TEXT = 16f

/** Figma sizes in frame px ([AtlasUi.fx]): big buttons, segmented tabs, count chips. */
const val BUTTON = 100
const val TAB = 76
const val CHIP = 56

class AtlasUi(private val context: Context, val colors: AtlasColors, val wide: Boolean, val scale: Float = 1f) {
    /** Galmuri11 (Korean pixel font) for all Hangul text; Press Start 2P stays for Latin ticket marks. */
    private val textFont by lazy { context.resources.getFont(R.font.galmuri) }
    private val displayFont by lazy { context.resources.getFont(R.font.press_start) }

    fun dp(n: Int) = (n * scale * context.resources.displayMetrics.density + .5f).toInt()
    /** Unscaled dp, for sizes already derived from the screen width. */
    fun rawDp(n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()
    /** Figma px of the 1700-wide frames (≈ 1 px on the 1682 px Pleos screen), for sizes measured off the design. */
    fun fx(n: Int) = (n * scale / 1.7f * context.resources.displayMetrics.density + .5f).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun box(fill: Int, outline: Int = colors.border, shadow: Boolean = false): android.graphics.drawable.Drawable {
        val face = GradientDrawable().apply { setColor(fill); setStroke(dp(3), outline) }
        if (!shadow) return face
        return LayerDrawable(arrayOf(GradientDrawable().apply { setColor(this@AtlasUi.colors.dark) }, face)).apply {
            setLayerInset(0, dp(4), dp(4), 0, 0)
            setLayerInset(1, 0, 0, dp(4), dp(4))
        }
    }

    fun label(value: String, size: Float = 18f, color: Int = colors.ink, bold: Boolean = false, pixel: Boolean = false) =
        TextView(context).apply {
            text = value
            // Galmuri runs ~19% wider than the old sans, so it is set a notch smaller to keep the layouts.
            // Press Start 2P has no Hangul; a "pixel" label with Korean in it uses bold Galmuri instead of a system fallback.
            val latin = pixel && value.none { it in '\uAC00'..'\uD7A3' }
            textSize = scale * if (latin) size else maxOf(size * GALMURI, MIN_TEXT)
            setTextColor(color)
            typeface = if (latin) displayFont else Typeface.create(textFont, if (bold || pixel) 700 else 400, false)
        }

    fun text(parent: LinearLayout, value: String, size: Float = 18f, color: Int = colors.ink,
             bold: Boolean = false, pixel: Boolean = false) =
        label(value, size, color, bold, pixel).also {
            parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

    fun card(parent: LinearLayout, fill: Int = colors.paper, shadow: Boolean = false): LinearLayout = column().apply {
        background = box(fill, shadow = shadow)
        setPadding(dp(16), dp(13), dp(16), dp(14))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    /** Big button, 100 px tall like the Figma action bars; tabs pass [height] = [TAB]. */
    fun action(value: String, selected: Boolean = false, primary: Boolean = false, enabled: Boolean = true,
               height: Int = BUTTON, fill: Int? = null, click: () -> Unit) =
        Button(context).apply {
            text = value
            isAllCaps = false
            textSize = scale * maxOf((if (height < BUTTON) 16f else 17f) * GALMURI, MIN_TEXT)
            typeface = Typeface.create(textFont, 700, false)
            minHeight = fx(height)
            minimumHeight = fx(height)
            val face = fill ?: when {
                primary -> colors.lime
                selected -> if (colors.night) 0xff324963.toInt() else colors.navy
                else -> colors.paper
            }
            setTextColor(if (primary || face == colors.yellow) colors.dark else if (selected || face == colors.navy) colors.white else colors.ink)
            background = RippleDrawable(ColorStateList.valueOf(0x335b6270), box(face, shadow = primary || fill != null), null)
            setPadding(dp(13), dp(8), dp(13), dp(8))
            isEnabled = enabled
            alpha = if (enabled) 1f else .58f
            setOnClickListener { click() }
        }

    fun button(parent: LinearLayout, value: String, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
        parent.addView(action(value, primary = primary, enabled = enabled, click = click),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
    }

    /** Figma action bar: buttons side by side, the primary one usually wider. */
    fun actions(parent: LinearLayout, items: List<Act>, topMargin: Int = 10) {
        val line = row()
        items.forEachIndexed { index, item ->
            line.addView(action(item.label, primary = item.primary, enabled = item.enabled, fill = item.fill, click = item.click),
                LinearLayout.LayoutParams(0, -2, item.weight).apply { if (index > 0) marginStart = dp(12) })
        }
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { this.topMargin = dp(topMargin) })
    }

    /** Bordered chip, the small tag used for counts, rewards and statuses in the Figma screens. */
    fun chip(value: String, fill: Int, size: Float = 16f, color: Int = colors.dark, pixel: Boolean = false) =
        label(value, size, color, bold = !pixel, pixel = pixel).apply {
            background = box(fill, colors.dark)
            gravity = Gravity.CENTER
            minHeight = fx(CHIP)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

    /** Lays out [views] left to right, wrapping into rows of [perRow]. */
    fun grid(parent: LinearLayout, views: List<View>, perRow: Int, gap: Int = 10, height: Int = -2) {
        views.chunked(perRow).forEach { items ->
            val line = row().apply { gravity = Gravity.TOP }
            parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(gap) })
            items.forEachIndexed { index, view ->
                line.addView(view, LinearLayout.LayoutParams(0, height, 1f).apply { if (index > 0) marginStart = dp(gap) })
            }
            repeat(perRow - items.size) {
                line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(gap) })
            }
        }
    }

    /** Wraps [views] (chips) into lines that fit, measured against [maxWidth] px. */
    fun flow(parent: LinearLayout, views: List<View>, maxWidth: Int, gap: Int = 8) {
        var line: LinearLayout? = null
        var used = 0
        views.forEach { view ->
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val width = view.measuredWidth + dp(gap)
            if (line == null || used + width > maxWidth) {
                line = row().also { parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(gap) }) }
                used = 0
            }
            line!!.addView(view, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(gap) })
            used += width
        }
    }

    fun strip(parent: LinearLayout, choices: List<Pair<String, () -> Unit>>, selected: String? = null) {
        val container = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val line = row()
        container.addView(line)
        choices.forEach { (name, click) ->
            line.addView(action(name, name == selected, height = TAB, click = click),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        parent.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    fun split(parent: LinearLayout, first: (LinearLayout) -> Unit, second: (LinearLayout) -> Unit) {
        if (!wide) {
            first(parent)
            second(parent)
            return
        }
        val line = row().apply { gravity = Gravity.TOP }
        val left = column()
        val right = column()
        line.addView(left, LinearLayout.LayoutParams(0, -2, 1.35f).apply { marginEnd = dp(16) })
        line.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(line, LinearLayout.LayoutParams(-1, -2))
        first(left)
        second(right)
    }

    fun progress(parent: LinearLayout, ratio: Float) {
        val bar = row().apply { background = box(colors.fog); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        repeat(16) { index ->
            bar.addView(View(context).apply { setBackgroundColor(if (index < ratio * 16) colors.lime else colors.fog) },
                LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(2) })
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(19)).apply { bottomMargin = dp(8) })
    }
}

data class Act(val label: String, val primary: Boolean = false, val enabled: Boolean = true,
               val weight: Float = 1f, val fill: Int? = null, val click: () -> Unit)
