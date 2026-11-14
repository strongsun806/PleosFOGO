package com.ssafy.fogo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Whole-country pixel board (Figma A1 지도 / A2 내 지도판 / A3 스티커 붙이기): South Korea as a grid
 * of [CELL_KM] tiles.
 *
 * - Figma look: the whole board is a grid of big square pixels. Sea tiles are pale blue, fog (unvisited)
 *   land is neutral grey with a dot, and a tile with an opened 1 km block is the flat, bright colour of
 *   its 시·도 (17 zones; 특별·광역시 a shade of the surrounding 도). No coast line; hairline 시·도 borders
 *   always, names only when zoomed in.
 * - Pinch, drag and the +/- buttons zoom and pan. The zoom of each [key] survives re-renders.
 * - Stickers / magnets sit on the tile that holds their block as tilted tiles.
 * - When [pick] is set (placement) every tappable opened tile gets a small view ("열린 칸") so it works
 *   with touch, accessibility and UI tests; picking a tile picks its first free opened block, so
 *   [Profile.place] still stores 1 km block ids.
 *
 * Jeju is drawn a few tiles north of its real place so the board is closer to square.
 */
class KoreaBoard(
    context: Context,
    private val ui: AtlasUi,
    private val art: AtlasArt,
    open: Set<String>,
    /** block id → item placed on it */
    private val placed: Map<String, BoardItem>,
    /** Block id of the tile picked for placement, if any. */
    pending: String? = null,
    /** Item shown on the pending tile while choosing. */
    private val preview: BoardItem? = null,
    /** Called with a free opened block id when an editable tile is tapped; null when not editing. */
    private val pick: ((String) -> Unit)? = null,
    /** Block to centre on the first time this [key] is shown; null shows the whole country. */
    focus: Block? = null,
    /** Remembers zoom and pan per screen across re-renders. */
    private val key: String = "board",
    /** Zoom buttons at the top-right corner, or halfway down the right edge when the corner is taken. */
    private val controlsAtTop: Boolean = true,
) : FrameLayout(context) {
    private val colors = ui.colors
    private val paint = Paint()
    private val text = Paint().apply { isAntiAlias = true; typeface = context.resources.getFont(R.font.galmuri_bold) }
    private val cells: Map<Cell, List<Block>> = open.mapNotNull { parse(it) }.groupBy { cellOf(it) }
    private val stickers: Map<Cell, BoardItem> = placed.entries.mapNotNull { (block, item) ->
        parse(block)?.let { cellOf(it) to item }
    }.toMap()
    private val pendingCell = pending?.let { parse(it) }?.let { cellOf(it) }
    private val view: ViewState = STATES.getOrPut(key) {
        focus?.let { cellOf(it) }?.let { ViewState(FOCUS_ZOOM, it.column + .5f, it.row + .5f) }
            ?: ViewState(0f, COLUMNS / 2f, MAINLAND_ROWS / 2f) // zoom 0: fit the mainland once the size is known
    }
    private var base = 0f
    private val cell get() = base * view.zoom
    private val originX get() = width / 2f - view.x * cell
    private val originY get() = height / 2f - view.y * cell
    private val targets = mutableListOf<Pair<Cell, View>>()
    private val zoomIn: TextView
    private val zoomOut: TextView

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor)
            return true
        }
    })

    init {
        setWillNotDraw(false)
        clipChildren = true
        pick?.let { choose ->
            cells.forEach { (tile, blocks) ->
                val free = blocks.firstOrNull { it.id !in placed } ?: return@forEach
                val target = View(context).apply {
                    contentDescription = if (tile == pendingCell) "배치할 칸" else "열린 칸"
                    isClickable = true
                    setOnClickListener { choose(free.id) }
                }
                targets += tile to target
                addView(target, LayoutParams(1, 1))
            }
        }
        zoomIn = zoomButton("+", "지도 확대") { zoomBy(1.6f) }
        zoomOut = zoomButton("-", "지도 축소") { zoomBy(1 / 1.6f) }
    }

    private fun zoomButton(label: String, description: String, click: () -> Unit) =
        // Press Start 2P: Galmuri has no U+2212 and its own +/- sit off-centre in a square button.
        ui.label(label, 18f, colors.ink, pixel = true).apply {
            gravity = android.view.Gravity.CENTER
            background = ui.box(colors.paper, shadow = true)
            contentDescription = description
            isClickable = true
            setOnClickListener { click() }
            this@KoreaBoard.addView(this, LayoutParams(ui.dp(48), ui.dp(48)))
        }

    private fun zoomBy(factor: Float) {
        view.zoom = (view.zoom * factor).coerceIn(1f, MAX_ZOOM)
        clampCenter()
        requestLayout()
        invalidate()
    }

    private fun clampCenter() {
        if (base <= 0f) return
        val halfW = width / 2f / cell
        val halfH = height / 2f / cell
        view.x = if (halfW * 2 >= COLUMNS) COLUMNS / 2f else view.x.coerceIn(halfW, COLUMNS - halfW)
        view.y = if (halfH * 2 >= ROWS) ROWS / 2f else view.y.coerceIn(halfH, ROWS - halfH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        base = minOf(w / COLUMNS.toFloat(), h / ROWS.toFloat())
        if (view.zoom == 0f && base > 0f) view.zoom = (h / MAINLAND_ROWS.toFloat() / base).coerceIn(1f, MAX_ZOOM)
        clampCenter()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        targets.forEach { (tile, target) ->
            val x = (originX + tile.column * cell).toInt()
            val y = (originY + tile.row * cell).toInt()
            val size = cell.toInt().coerceAtLeast(1)
            val shown = x >= 0 && y >= 0 && x + size <= width && y + size <= height && size >= ui.dp(8)
            val visibility = if (shown) View.VISIBLE else View.GONE
            if (target.visibility != visibility) target.visibility = visibility
            target.layout(x, y, x + size, y + size)
        }
        val size = ui.dp(48)
        val margin = ui.dp(12)
        val top = if (controlsAtTop) margin else (height - size * 2 - margin) / 2
        zoomIn.layout(width - margin - size, top, width - margin, top + size)
        zoomOut.layout(width - margin - size, top + margin + size, width - margin, top + margin + size * 2)
    }

    // Pinch and drag work anywhere on the board, including over tappable tiles.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return dragging || scaler.isInProgress
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return true
    }

    private fun track(event: MotionEvent) {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !scaler.isInProgress && hypot(event.x - downX, event.y - downY) > slop) dragging = true
                if ((dragging || scaler.isInProgress) && cell > 0f) {
                    view.x -= (event.x - lastX) / cell
                    view.y -= (event.y - lastY) / cell
                    clampCenter()
                    requestLayout()
                    invalidate()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cell <= 0f) return
        val night = colors.night
        val line = if (night) 0xff0f1a28.toInt() else 0xffc3d3e2.toInt()
        val sea = if (night) 0xff1a2b40.toInt() else 0xffd3e5f5.toInt()
        val fog = if (night) 0xff2c3038.toInt() else 0xffe6e8ec.toInt()
        val dot = if (night) 0xff3b404b.toInt() else 0xffc8ccd3.toInt()
        val border = if (night) 0xccd6deea.toInt() else 0xb30b1426.toInt()
        // Grid lines are the background showing between tiles.
        canvas.drawColor(line)
        val gap = maxOf(1f, cell / 20f)
        val first = floor(-originX / cell).toInt()
        val last = ceil((width - originX) / cell).toInt()
        val firstRow = floor(-originY / cell).toInt()
        val lastRow = ceil((height - originY) / cell).toInt()
        val zoomed = view.zoom >= LABEL_ZOOM
        val thin = maxOf(1f, cell / 30f)
        for (row in firstRow..lastRow) for (column in first..last) {
            val x = originX + column * cell
            val y = originY + row * cell
            val zone = zoneAt(column, row)
            if (zone == null) {
                paint.color = sea
                canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                continue
            }
            val tile = Cell(column, row)
            when {
                tile == pendingCell -> paint.color = colors.lime
                cells.containsKey(tile) -> paint.color = shade(ZONE_COLORS.getValue(zone), if (night) .9f else 1f)
                else -> paint.color = fog
            }
            canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
            if (tile != pendingCell && !cells.containsKey(tile)) {
                paint.color = dot
                val d = cell / 12f
                canvas.drawRect(x + cell / 2 - d, y + cell / 2 - d, x + cell / 2 + d, y + cell / 2 + d, paint)
            }
            // Hairline 시·도 borders at every zoom.
            run {
                paint.color = border
                val up = zoneAt(column, row - 1)
                val left = zoneAt(column - 1, row)
                if (up != null && up != zone) canvas.drawRect(x, y - gap / 2 - thin / 2, x + cell, y - gap / 2 + thin / 2, paint)
                if (left != null && left != zone) canvas.drawRect(x - gap / 2 - thin / 2, y, x - gap / 2 + thin / 2, y + cell, paint)
            }
            if (tile == pendingCell) {
                paint.color = colors.dark
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = gap * 3
                canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                paint.style = Paint.Style.FILL
            }
        }
        // 시·도 names only once zoomed in (도 first, 특별·광역시 a step further): small text with a paper halo, no box.
        if (zoomed) {
            text.textSize = ui.dp(MIN_TEXT.toInt()).toFloat()
            LABELS.forEach { (name, column, row) ->
                if (name in METROS && view.zoom < METRO_LABEL_ZOOM) return@forEach
                val cx = originX + column * cell
                val cy = originY + row * cell + text.textSize * .35f
                val w = text.measureText(name)
                if (cx + w < 0 || cy < 0 || cx - w > width || cy - text.textSize > height) return@forEach
                text.style = Paint.Style.STROKE
                text.strokeWidth = text.textSize * .22f
                text.color = colors.paper
                canvas.drawText(name, cx - w / 2, cy, text)
                text.style = Paint.Style.FILL
                text.color = colors.ink
                canvas.drawText(name, cx - w / 2, cy, text)
            }
        }
        stickers.forEach { (tile, item) -> sticker(canvas, tile, item) }
        if (preview != null && pendingCell != null && pendingCell !in stickers) {
            sticker(canvas, pendingCell, preview)
            bubble(canvas, pendingCell)
        }
        if (pick != null) hint(canvas)
    }

    private fun sticker(canvas: Canvas, tile: Cell, item: BoardItem) {
        val size = (cell * 1.7f).coerceIn(ui.dp(26).toFloat(), ui.dp(70).toFloat())
        val cx = originX + (tile.column + .5f) * cell
        val cy = originY + (tile.row + .5f) * cell
        if (cx < -size || cy < -size || cx > width + size || cy > height + size) return
        val box = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        val edge = maxOf(2f, size / 14f)
        canvas.save()
        canvas.rotate(if ((tile.column + tile.row) % 2 == 0) -6f else 5f, cx, cy)
        paint.color = colors.dark
        canvas.drawRect(box.left + edge, box.top + edge, box.right + edge, box.bottom + edge, paint)
        paint.color = 0xffffffff.toInt()
        canvas.drawRect(box, paint)
        paint.color = if (item.magnet) colors.violet else item.color
        canvas.drawRect(box.left + edge, box.top + edge, box.right - edge, box.bottom - edge, paint)
        if (item.magnet) {
            paint.color = 0xffffffff.toInt()
            canvas.drawRect(box.left + edge * 2, box.top + edge * 2, box.right - edge * 2, box.top + edge * 3.5f, paint)
            text.color = colors.onViolet
            text.textSize = size * .3f
            val name = item.name.substringBefore(' ')
            canvas.drawText(name, cx - text.measureText(name) / 2, cy + size * .22f, text)
        } else {
            val artwork = art[artKey(item)]
            val inner = size - edge * 5
            val scale = minOf(inner / artwork.width, inner / artwork.height)
            val ox = cx - artwork.width * scale / 2
            val oy = cy - artwork.height * scale / 2
            artwork.pixels.forEach { pixel ->
                paint.color = pixel.color
                canvas.drawRect(ox + pixel.x * scale, oy + pixel.y * scale,
                    ox + (pixel.x + pixel.w) * scale, oy + (pixel.y + pixel.h) * scale, paint)
            }
        }
        canvas.restore()
    }

    private fun bubble(canvas: Canvas, tile: Cell) {
        val label = "여기에 붙여요"
        text.textSize = ui.dp(MIN_TEXT.toInt()).toFloat()
        text.color = colors.dark
        val w = text.measureText(label) + ui.dp(16)
        val h = ui.dp(30).toFloat()
        val cx = originX + (tile.column + .5f) * cell
        val y = originY + (tile.row + 1f) * cell + ui.dp(18)
        paint.color = colors.dark
        canvas.drawRect(cx - w / 2 + 3, y + 3, cx + w / 2 + 3, y + h + 3, paint)
        paint.color = colors.lime
        canvas.drawRect(cx - w / 2, y, cx + w / 2, y + h, paint)
        canvas.drawText(label, cx - text.measureText(label) / 2, y + h * .7f, text)
    }

    private fun hint(canvas: Canvas) {
        val label = "안개 칸은 아직 안 간 곳이라 붙일 수 없어요"
        text.textSize = ui.dp(MIN_TEXT.toInt()).toFloat()
        text.color = 0xffffffff.toInt()
        val pad = ui.dp(12).toFloat()
        val w = text.measureText(label) + pad * 2
        val h = ui.dp(34).toFloat()
        val y = height - h - pad
        paint.color = 0xe644464e.toInt()
        canvas.drawRoundRect(RectF(pad, y, pad + w, y + h), h / 2, h / 2, paint)
        canvas.drawText(label, pad * 2, y + h * .66f, text)
    }

    private class ViewState(var zoom: Float, var x: Float, var y: Float)
    private data class Cell(val column: Int, val row: Int)

    companion object {
        private const val CELL_KM = 18.0
        private const val LON_LEFT = 125.95
        private const val LAT_TOP = 38.62
        private const val KM_PER_LAT = 111.195
        /** Jeju is drawn this far north so the empty strait does not stretch the board. */
        private const val JEJU_SHIFT_KM = 40.0
        private val kmPerLon = 111.195 * cos(Math.toRadians(36.0))
        private val JEJU_BELOW = (33.9 - 36.0) * KM_PER_LAT
        // Block x/y are km from (127°E, 36°N); see Point.block().
        private val ORIGIN_X = (LON_LEFT - 127.0) * kmPerLon
        private val ORIGIN_Y = (LAT_TOP - 36.0) * KM_PER_LAT
        private val STATES = mutableMapOf<String, ViewState>()
        private const val MAX_ZOOM = 4f
        private const val FOCUS_ZOOM = 2f
        private const val LABEL_ZOOM = 1.8f
        private const val METRO_LABEL_ZOOM = 2.8f

        /**
         * 18 km tiles (big pixels, as in the Figma board) rasterised from the KOSTAT 2012 시·도 boundaries (southkorea-maps, provinces-geo-simple):
         * each tile takes the 시·도 covering most of it (특별·광역시 win at 25% so they keep a shape); all 17 시·도.
         * Jeju rows are moved [JEJU_SHIFT_KM] north. Regenerate with scripts/raster_provinces.py.
         */
        private val GRID = listOf(
            "...................",
            "...........KK......",
            ".....GKKKKKKK......",
            "....GGGKKKKKKK.....",
            "....GGGGKKKKKKK....",
            "..IGGGGGKKKKKKK....",
            "..IISSGGGKKKKKKK...",
            "...IGGGGGKKKKKKK...",
            "...GGGGGGKKKKKKKK..",
            "....GGGGBBBBBKKKE..",
            "..NNNNGBBBBBBEEEEE.",
            ".NNNNNNBBBEEEEEEEE.",
            "..NNNNXBBBEEEEEEEE.",
            "..NNNNXDBBEEEEEEEE.",
            "...NNNDDBBEEEEEEE..",
            "...NNJNNBBEEEEEEE..",
            "...JJJJJJJEEETEEEE.",
            "...JJJJJJWWETTEEEE.",
            "...JJJJJWWWWTEEUUU.",
            "..JJJJJJWWWWWWWWU..",
            "..MMMMMMWWWWWWWPP..",
            "..MQQMMMMWWWWWPP...",
            "..MMMMMMMWWWWW.....",
            "M.MMMMMMMWW.WW.....",
            "MMMMMMMM...........",
            ".MMMM.MM...........",
            "...MM..............",
            "...................",
            "...................",
            "..ZZZ..............",
            ".ZZZZ..............",
            "...................",
        )
        private val LETTERS = mapOf(
            'S' to "서울", 'I' to "인천", 'G' to "경기", 'K' to "강원", 'X' to "세종", 'D' to "대전",
            'B' to "충북", 'N' to "충남", 'Q' to "광주", 'J' to "전북", 'M' to "전남", 'T' to "대구",
            'E' to "경북", 'U' to "울산", 'W' to "경남", 'P' to "부산", 'Z' to "제주",
        )
        /** 특별·광역시: coloured as a shade of the province around them, named only when zoomed in further. */
        private val METROS = setOf("서울", "인천", "세종", "대전", "광주", "대구", "울산", "부산")
        private val COLUMNS = GRID.maxOf { it.length }
        private val ROWS = GRID.size
        /** Rows down to the south coast; the first view fits these so the mainland fills the board (Jeju below). */
        private val MAINLAND_ROWS = GRID.indexOfLast { line -> line.any { it != '.' && it != 'Z' } } + 1

        private val ZONES: List<List<String?>> = GRID.map { line -> (0 until COLUMNS).map { LETTERS[line.getOrElse(it) { '.' }] } }

        private fun zoneAt(column: Int, row: Int) = ZONES.getOrNull(row)?.getOrNull(column)

        private val ZONE_COLORS = mapOf(
            "경기" to 0xff7fd890.toInt(), "서울" to 0xff4fb86a.toInt(), "인천" to 0xffb6ecc1.toInt(),
            "강원" to 0xff6ec6ff.toInt(),
            "충남" to 0xffffb347.toInt(), "대전" to 0xffe58e1f.toInt(), "세종" to 0xffffd49c.toInt(),
            "충북" to 0xffffe08a.toInt(),
            "경북" to 0xffff9f8a.toInt(), "대구" to 0xffeb6f55.toInt(),
            "전북" to 0xff9ee6df.toInt(),
            "전남" to 0xffb4e197.toInt(), "광주" to 0xff82c45f.toInt(),
            "경남" to 0xffffc46b.toInt(), "울산" to 0xffe9a23a.toInt(), "부산" to 0xffffe0a6.toInt(),
            "제주" to 0xffffa64d.toInt(),
        )

        /** Each zone's name sits on its most inland tile (ties broken towards the zone's centre). */
        private val LABELS: List<Triple<String, Float, Float>> = ZONE_COLORS.keys.mapNotNull { name ->
            val tiles = ZONES.flatMapIndexed { row, line -> line.mapIndexedNotNull { column, zone -> if (zone == name) column to row else null } }
            if (tiles.isEmpty()) return@mapNotNull null
            val cx = tiles.map { it.first }.average()
            val cy = tiles.map { it.second }.average()
            fun depth(column: Int, row: Int): Int {
                for (d in 1..6) for (dy in -d..d) for (dx in -d..d) {
                    if (maxOf(abs(dx), abs(dy)) == d && zoneAt(column + dx, row + dy) != name) return d
                }
                return 7
            }
            val best = tiles.maxWith(compareBy<Pair<Int, Int>> { minOf(depth(it.first, it.second), 3) }
                .thenByDescending { (it.first - cx) * (it.first - cx) + (it.second - cy) * (it.second - cy) })
            Triple(name, best.first + .5f, best.second + .5f)
        }

        private fun shade(color: Int, factor: Float): Int {
            fun channel(shift: Int) = ((color shr shift and 0xff) * factor).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private fun parse(id: String): Block? {
            val parts = id.split(',')
            if (parts.size != 2) return null
            val x = parts[0].toIntOrNull() ?: return null
            val y = parts[1].toIntOrNull() ?: return null
            return Block(x, y)
        }

        /** Tile of a block; a block on a coastal sea tile snaps to the nearest land tile so it still shows. */
        private fun cellOf(block: Block): Cell {
            val y = if (block.y < JEJU_BELOW) block.y + .5 + JEJU_SHIFT_KM else block.y + .5
            val column = floor((block.x + .5 - ORIGIN_X) / CELL_KM).toInt().coerceIn(0, COLUMNS - 1)
            val row = floor((ORIGIN_Y - y) / CELL_KM).toInt().coerceIn(0, ROWS - 1)
            if (zoneAt(column, row) != null) return Cell(column, row)
            for (d in 1..3) for (dy in -d..d) for (dx in -d..d) {
                if (maxOf(abs(dx), abs(dy)) == d && zoneAt(column + dx, row + dy) != null) return Cell(column + dx, row + dy)
            }
            return Cell(column, row)
        }

        internal fun artKey(item: BoardItem) = when {
            item.magnet -> "mtn"
            item.id.startsWith("STK_BS") -> "wave"
            item.id.startsWith("STK_GW") -> "cup"
            else -> "star"
        }
    }
}
