package com.canoebay.canoetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

interface TmdbHost {
    fun tmdbOpen(m: TMovie)
    fun tmdbFocused(m: TMovie)
    fun tmdbSeeAll(row: TRow)
    fun tmdbIsFree(m: TMovie): Boolean
    /** A poster in this row got focus: scroll so the row's name stays in view. */
    fun tmdbRowFocused(row: View) {}
}

/** A row of TMDB posters. [row] is null for rows that aren't a TMDB list (My List, search results). */
data class TmdbItem(val title: String, val movies: List<TMovie>, val row: TRow?, val total: Int = 0)

/** Rows of movie posters, Netflix style. */
class TmdbRowsAdapter(private val host: TmdbHost) : RecyclerView.Adapter<TmdbRowsAdapter.VH>() {

    private var items: List<TmdbItem> = emptyList()
    private val pool = RecyclerView.RecycledViewPool()

    fun submit(list: List<TmdbItem>) {
        if (list == items) return
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_home_row, parent, false)
        return VH(v).also { vh ->
            vh.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            vh.list.setRecycledViewPool(pool)
            vh.list.adapter = TmdbPosterAdapter(host)
            vh.list.itemAnimator = null
        }
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.title.text = if (item.total > 0) "${item.title}   ·   ${item.total} movies" else item.title
        val pa = h.list.adapter as TmdbPosterAdapter
        pa.submit(item.movies, item.row)
        pa.onFocus = { host.tmdbRowFocused(h.itemView) }
    }
}

/** Posters with the rating badge and title strip. Ends with a "See all" card when the row has more. */
class TmdbPosterAdapter(private val host: TmdbHost, private val fixedWidth: Boolean = true) :
    RecyclerView.Adapter<TmdbPosterAdapter.VH>() {

    private var items: List<TMovie> = emptyList()
    private var row: TRow? = null
    var onFocus: (() -> Unit)? = null

    fun submit(list: List<TMovie>, row: TRow?) {
        items = list
        this.row = row
        notifyDataSetChanged()
    }

    fun append(more: List<TMovie>) {
        val start = items.size
        items = items + more
        notifyItemRangeInserted(start, more.size)
    }

    fun movies() = items

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val posterBox: FrameLayout = v.findViewById(R.id.tpPosterBox)
        val image: ImageView = v.findViewById(R.id.tpImage)
        val fallback: TextView = v.findViewById(R.id.tpFallback)
        val rating: TextView = v.findViewById(R.id.tpRating)
        val free: TextView = v.findViewById(R.id.tpFree)
        val title: TextView = v.findViewById(R.id.tpTitle)
    }

    private val seeAll get() = row != null && items.size >= 10

    override fun getItemCount() = items.size + if (seeAll) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tmdb_poster, parent, false)
        if (!fixedWidth) v.layoutParams = v.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        if (!fixedWidth) {
            // Grid: keep the poster shape whatever the column width
            h.itemView.post {
                val w = h.itemView.width
                if (w > 0) h.posterBox.layoutParams = h.posterBox.layoutParams.apply { height = (w * 1.5f).toInt() }
            }
        }
        if (position >= items.size) {
            val r = row ?: return
            h.image.setImageDrawable(null)
            h.posterBox.background = Look.tile(0xFF2A2F3A.toInt(), 0f)
            h.fallback.text = "See all\n›"
            h.fallback.textSize = 22f
            h.rating.visibility = View.GONE
            h.free.visibility = View.GONE
            h.title.text = r.title
            h.itemView.setOnClickListener { host.tmdbSeeAll(r) }
            h.itemView.setOnFocusChangeListener { v, has -> zoom(v, has); if (has) onFocus?.invoke() }
            return
        }
        val m = items[position]
        h.posterBox.background = Look.tile(Look.colorFor(m.title), 0f)
        h.fallback.text = m.title
        h.fallback.textSize = 15f
        h.image.load(m.posterUrl) { crossfade(true) }
        h.rating.visibility = if (m.ratingText.isNotEmpty()) View.VISIBLE else View.GONE
        h.rating.text = m.ratingText
        h.free.visibility = if (host.tmdbIsFree(m)) View.VISIBLE else View.GONE
        h.title.text = if (m.year.isNotEmpty()) "${m.title} (${m.year})" else m.title
        h.itemView.setOnClickListener { host.tmdbOpen(m) }
        h.itemView.setOnFocusChangeListener { v, has ->
            zoom(v, has)
            h.title.isSelected = has
            if (has) { host.tmdbFocused(m); onFocus?.invoke() }
        }
    }

    private fun zoom(v: View, has: Boolean) {
        val s = if (has) 1.06f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        v.elevation = if (has) 8f else 0f
    }
}

/** Cast photos on the movie page. */
class CastAdapter(private val people: List<TPerson>) : RecyclerView.Adapter<CastAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val box: FrameLayout = v.findViewById(R.id.castBox)
        val photo: ImageView = v.findViewById(R.id.castPhoto)
        val initials: TextView = v.findViewById(R.id.castInitials)
        val name: TextView = v.findViewById(R.id.castName)
        val role: TextView = v.findViewById(R.id.castRole)
    }

    override fun getItemCount() = people.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cast, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = people[position]
        h.box.background = Look.tile(Look.colorFor(p.name), 0f)
        h.initials.text = Look.initials(p.name)
        h.photo.load(p.photoUrl)
        h.name.text = p.name
        h.role.text = p.role
    }
}
