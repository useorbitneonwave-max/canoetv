package com.canoebay.canoetv

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

sealed class HomeItem {
    data class Hero(val ch: Channel, val label: String) : HomeItem()
    data class Row(val title: String, val channels: List<Channel>) : HomeItem()
}

/** What the Home and Guide screens need from the main screen. */
interface TvHost {
    fun epgNow(ch: Channel): Epg.Prog?
    fun epgNext(ch: Channel): Epg.Prog?
    fun isFav(ch: Channel): Boolean
    fun isLocked(ch: Channel): Boolean
    fun open(ch: Channel)
    fun options(ch: Channel)
    fun toggleFav(ch: Channel)
    fun openGuide()
    fun hhmm(t: Long): String
}

object Look {
    /** Muted tile colours, picked per channel so the screen isn't one flat colour. */
    val palette = intArrayOf(
        0xFF2E4A62.toInt(), 0xFF4A2E5C.toInt(), 0xFF2E5C4A.toInt(), 0xFF5C3A2E.toInt(),
        0xFF5C2E3E.toInt(), 0xFF3F4A2E.toInt(), 0xFF2E3F5C.toInt(), 0xFF5C522E.toInt(),
    )

    fun colorFor(key: String) = palette[(key.hashCode() and 0x7fffffff) % palette.size]

    fun tile(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    fun initials(name: String) = name.split(' ', '-', '_').filter { it.isNotBlank() }
        .take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "TV" }

    fun progress(p: Epg.Prog?): Int {
        if (p == null) return 0
        val t = System.currentTimeMillis()
        return (((t - p.start) * 100) / (p.stop - p.start).coerceAtLeast(1)).toInt().coerceIn(0, 100)
    }

    /** Netflix-style focus: the focused card grows slightly. */
    fun focusZoom(v: View) {
        v.setOnFocusChangeListener { view, has ->
            val s = if (has) 1.07f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }
}

class HomeAdapter(private val host: TvHost) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<HomeItem> = emptyList()
    private val pool = RecyclerView.RecycledViewPool()

    fun submit(list: List<HomeItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (items[position] is HomeItem.Hero) 0 else 1

    class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.heroLogo)
        val label: TextView = v.findViewById(R.id.heroLabel)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val show: TextView = v.findViewById(R.id.heroShow)
        val progress: ProgressBar = v.findViewById(R.id.heroProgress)
        val watch: TextView = v.findViewById(R.id.heroWatch)
        val fav: TextView = v.findViewById(R.id.heroFav)
        val guide: TextView = v.findViewById(R.id.heroGuide)
    }

    class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeroVH(inf.inflate(R.layout.item_hero, parent, false))
        } else {
            RowVH(inf.inflate(R.layout.item_home_row, parent, false)).also { vh ->
                vh.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                vh.list.setRecycledViewPool(pool)
                vh.list.adapter = CardAdapter(host)
                vh.list.itemAnimator = null
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HomeItem.Hero -> bindHero(holder as HeroVH, item)
            is HomeItem.Row -> {
                val h = holder as RowVH
                h.title.text = item.title
                (h.list.adapter as CardAdapter).submit(item.channels)
                h.list.scrollToPosition(0)
            }
        }
    }

    private fun bindHero(h: HeroVH, item: HomeItem.Hero) {
        val ch = item.ch
        h.label.text = item.label
        h.title.text = ch.name
        if (ch.logo.isNotEmpty()) h.logo.load(ch.logo) else h.logo.setImageDrawable(null)
        val now = host.epgNow(ch)
        val next = host.epgNext(ch)
        h.show.text = when {
            now != null && next != null -> "Now: ${now.title}\nNext ${host.hhmm(next.start)}: ${next.title}"
            now != null -> "Now: ${now.title}"
            else -> ch.group
        }
        h.progress.visibility = if (now != null) View.VISIBLE else View.GONE
        h.progress.progress = Look.progress(now)
        h.watch.setOnClickListener { host.open(ch) }
        h.fav.text = if (host.isFav(ch)) "✓  My List" else "＋  My List"
        h.fav.setOnClickListener {
            host.toggleFav(ch)
            h.fav.text = if (host.isFav(ch)) "✓  My List" else "＋  My List"
        }
        h.guide.setOnClickListener { host.openGuide() }
    }

    /** Refresh "now showing" text without rebuilding rows, so TV focus isn't lost. */
    fun refreshInPlace(recycler: RecyclerView) {
        for (i in 0 until recycler.childCount) {
            val vh = recycler.getChildViewHolder(recycler.getChildAt(i))
            if (vh is RowVH) (vh.list.adapter as? CardAdapter)?.let { it.notifyItemRangeChanged(0, it.itemCount) }
            if (vh is HeroVH) { val item = items.getOrNull(vh.bindingAdapterPosition); if (item is HomeItem.Hero) bindHero(vh, item) }
        }
    }

    fun hasRow(title: String) = items.any { it is HomeItem.Row && it.title == title }

    fun focusHero(recycler: RecyclerView) {
        (recycler.findViewHolderForAdapterPosition(0) as? HeroVH)?.watch?.requestFocus()
    }
}

class CardAdapter(private val host: TvHost) : RecyclerView.Adapter<CardAdapter.VH>() {

    private var items: List<Channel> = emptyList()

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: FrameLayout = v.findViewById(R.id.cardTile)
        val logo: ImageView = v.findViewById(R.id.cardLogo)
        val initials: TextView = v.findViewById(R.id.cardInitials)
        val badge: TextView = v.findViewById(R.id.cardBadge)
        val progress: ProgressBar = v.findViewById(R.id.cardProgress)
        val name: TextView = v.findViewById(R.id.cardName)
        val show: TextView = v.findViewById(R.id.cardShow)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        Look.focusZoom(v)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        val radius = 10 * h.itemView.resources.displayMetrics.density
        h.tile.background = Look.tile(Look.colorFor(ch.group + ch.name), radius)
        if (ch.logo.isNotEmpty()) {
            h.initials.visibility = View.GONE
            h.logo.visibility = View.VISIBLE
            h.logo.load(ch.logo) {
                listener(onError = { _, _ ->
                    h.logo.visibility = View.GONE
                    h.initials.text = Look.initials(ch.name)
                    h.initials.visibility = View.VISIBLE
                })
            }
        } else {
            h.logo.visibility = View.GONE
            h.initials.text = Look.initials(ch.name)
            h.initials.visibility = View.VISIBLE
        }
        val now = host.epgNow(ch)
        h.name.text = if (host.isLocked(ch)) "🔒 ${ch.name}" else ch.name
        h.show.text = now?.title ?: ch.group
        h.progress.visibility = if (now != null) View.VISIBLE else View.INVISIBLE
        h.progress.progress = Look.progress(now)
        h.badge.visibility = if (now != null) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener { host.open(ch) }
        h.itemView.setOnLongClickListener { host.options(ch); true }
    }
}
