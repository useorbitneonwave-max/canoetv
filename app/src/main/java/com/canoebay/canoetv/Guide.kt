package com.canoebay.canoetv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shared position of the guide: the time at the left edge, the selected time, and the zoom. */
class GuideState(context: Context) {
    val pxPerMs: Float = 5f * context.resources.displayMetrics.density / 60_000f // 5dp per minute
    var left: Long = halfHour(System.currentTimeMillis())
    var cursor: Long = System.currentTimeMillis()

    companion object {
        const val HALF_HOUR = 30 * 60_000L
        fun halfHour(t: Long) = t - (t % HALF_HOUR)
    }
}

/** Draws one channel's programmes as blocks along the timeline (like a cable TV guide). */
class ProgramStripView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onDrag(dxPx: Float)
        fun onTap(prog: Epg.Prog?)
    }

    var progs: List<Epg.Prog> = emptyList()
    var state: GuideState? = null
    var baseColor: Int = Look.palette[0]
    var rowFocused = false
    var listener: Listener? = null

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.WHITE
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * d }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); textSize = 11 * d }
    private val rect = RectF()
    private val timeFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())

    fun progAt(t: Long): Epg.Prog? = progs.firstOrNull { it.start <= t && t < it.stop }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val horizontal = abs(distanceX) > abs(distanceY)
            parent?.requestDisallowInterceptTouchEvent(horizontal)
            if (horizontal) listener?.onDrag(distanceX)
            return horizontal
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val s = state ?: return false
            listener?.onTap(progAt(s.left + (e.x / s.pxPerMs).toLong()))
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 2 * d
        val radius = 6 * d
        val pad = 10 * d
        val now = System.currentTimeMillis()
        val right = s.left + (w / s.pxPerMs).toLong()

        if (progs.isEmpty()) {
            fill.color = 0xFF22262F.toInt()
            rect.set(gap, gap, w - gap, h - gap)
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (rowFocused) canvas.drawRoundRect(rect, radius, radius, stroke)
            canvas.drawText("No guide information", pad, h / 2 + titlePaint.textSize / 3, timePaint)
            return
        }

        for (p in progs) {
            if (p.stop <= s.left || p.start >= right) continue
            val x1 = max(0f, (p.start - s.left) * s.pxPerMs) + gap
            val x2 = min(w, (p.stop - s.left) * s.pxPerMs) - gap
            if (x2 - x1 < 4 * d) continue
            val airing = p.start <= now && now < p.stop
            fill.color = if (airing) baseColor else blend(baseColor, 0xFF15171D.toInt(), 0.45f)
            rect.set(x1, gap, x2, h - gap)
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (airing) {
                // How far through the show we are
                fill.color = 0x33FFFFFF
                val px = min(x2, (now - s.left) * s.pxPerMs)
                if (px > x1) canvas.drawRect(x1, h - gap - 3 * d, px, h - gap, fill)
            }
            if (rowFocused && p.start <= s.cursor && s.cursor < p.stop) {
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
            val avail = x2 - x1 - 2 * pad
            if (avail > 12 * d) {
                val time = TextUtils.ellipsize(timeFmt.format(java.util.Date(p.start)), timePaint, avail, TextUtils.TruncateAt.END)
                val title = TextUtils.ellipsize(p.title, titlePaint, avail, TextUtils.TruncateAt.END)
                canvas.drawText(time, 0, time.length, x1 + pad, h / 2 - 4 * d, timePaint)
                canvas.drawText(title, 0, title.length, x1 + pad, h / 2 + 14 * d, titlePaint)
            }
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}

/** The time ruler above the guide, with a marker at the current time. */
class TimeRulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var state: GuideState? = null
    private val d = resources.displayMetrics.density
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE7E9EE.toInt(); textSize = 14 * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DD6B5.toInt(); strokeWidth = 2 * d }
    private val tick = Paint().apply { color = 0x33FFFFFF; strokeWidth = d }
    private val timeFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state ?: return
        val right = s.left + (width / s.pxPerMs).toLong()
        var t = GuideState.halfHour(s.left)
        while (t <= right) {
            val x = (t - s.left) * s.pxPerMs
            if (x >= 0) {
                canvas.drawLine(x, height * 0.55f, x, height.toFloat(), tick)
                canvas.drawText(timeFmt.format(java.util.Date(t)), x + 6 * d, height * 0.6f, text)
            }
            t += GuideState.HALF_HOUR
        }
        val now = System.currentTimeMillis()
        val nx = (now - s.left) * s.pxPerMs
        if (nx in 0f..width.toFloat()) canvas.drawLine(nx, 0f, nx, height.toFloat(), line)
    }
}

/** What the guide needs from the main screen. */
interface GuideHost {
    fun guideProgs(ch: Channel): List<Epg.Prog>
    fun guideTap(ch: Channel, prog: Epg.Prog?)
    fun guideDrag(dxPx: Float)
    fun guideCursorMoved()
    fun guideOptions(ch: Channel)
}

class GuideAdapter(private val state: GuideState, private val host: GuideHost) :
    RecyclerView.Adapter<GuideAdapter.VH>() {

    var items: List<Channel> = emptyList()
        private set

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: FrameLayout = v.findViewById(R.id.guideTile)
        val logo: ImageView = v.findViewById(R.id.guideLogo)
        val name: TextView = v.findViewById(R.id.guideName)
        val num: TextView = v.findViewById(R.id.guideNum)
        val strip: ProgramStripView = v.findViewById(R.id.guideStrip)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        val dens = h.itemView.resources.displayMetrics.density
        h.tile.background = Look.tile(blendTile(Look.colorFor(ch.group + ch.name)), 6 * dens)
        h.num.text = ch.number.toString()
        if (ch.logo.isNotEmpty()) {
            h.name.visibility = View.GONE
            h.logo.visibility = View.VISIBLE
            h.logo.load(ch.logo) {
                listener(onError = { _, _ ->
                    h.logo.visibility = View.GONE
                    h.name.text = ch.name
                    h.name.visibility = View.VISIBLE
                })
            }
        } else {
            h.logo.visibility = View.GONE
            h.name.text = ch.name
            h.name.visibility = View.VISIBLE
        }

        h.strip.state = state
        h.strip.progs = host.guideProgs(ch)
        h.strip.baseColor = Look.palette[position % Look.palette.size]
        h.strip.rowFocused = h.itemView.hasFocus()
        h.strip.listener = object : ProgramStripView.Listener {
            override fun onDrag(dxPx: Float) = host.guideDrag(dxPx)
            override fun onTap(prog: Epg.Prog?) = host.guideTap(ch, prog)
        }
        h.strip.invalidate()

        h.itemView.setOnFocusChangeListener { _, has -> h.strip.rowFocused = has; h.strip.invalidate() }
        h.itemView.setOnClickListener { host.guideTap(ch, h.strip.progAt(state.cursor)) }
        h.itemView.setOnLongClickListener { host.guideOptions(ch); true }
        h.itemView.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val sel = h.strip.progAt(state.cursor)
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    state.cursor = sel?.stop ?: (state.cursor + GuideState.HALF_HOUR)
                    host.guideCursorMoved(); true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val earliest = System.currentTimeMillis() - 3600_000L
                    val target = sel?.start?.minus(1) ?: (state.cursor - GuideState.HALF_HOUR)
                    // At the left edge, let focus move to the side menu instead
                    if (target < earliest) return@setOnKeyListener false
                    state.cursor = target
                    host.guideCursorMoved(); true
                }
                else -> false
            }
        }
    }

    private fun blendTile(c: Int): Int {
        // Lighter tile behind logos so dark logos stay readable
        val r = (Color.red(c) + 255 * 2) / 3
        val g = (Color.green(c) + 255 * 2) / 3
        val b = (Color.blue(c) + 255 * 2) / 3
        return Color.rgb(r, g, b)
    }
}
