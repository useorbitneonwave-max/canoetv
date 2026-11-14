package com.canoebay.canoetv

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

interface MovieHost {
    fun openMovie(m: Movie)
    fun movieProgress(m: Movie): Int // 0..100, or -1 if never started
}

sealed class MovieItem {
    data class Header(val title: String, val sub: String) : MovieItem()
    data class Row(val title: String, val movies: List<Movie>) : MovieItem()
}

class MoviesAdapter(private val host: MovieHost) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<MovieItem> = emptyList()
    private val pool = RecyclerView.RecycledViewPool()

    fun submit(list: List<MovieItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (items[position] is MovieItem.Header) 0 else 1

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
    }

    class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_home_row, parent, false)
        return if (viewType == 0) {
            v.findViewById<View>(R.id.rowList).visibility = View.GONE
            HeaderVH(v)
        } else {
            RowVH(v).also { vh ->
                vh.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                vh.list.setRecycledViewPool(pool)
                vh.list.adapter = PosterAdapter(host)
                vh.list.itemAnimator = null
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MovieItem.Header -> {
                val h = holder as HeaderVH
                h.title.text = if (item.sub.isEmpty()) item.title else "${item.title}\n${item.sub}"
                h.title.textSize = 15f
                h.title.setTextColor(0xFF8A91A3.toInt())
                h.title.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            is MovieItem.Row -> {
                val h = holder as RowVH
                h.title.text = item.title
                (h.list.adapter as PosterAdapter).submit(item.movies)
            }
        }
    }
}

class PosterAdapter(private val host: MovieHost) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private var items: List<Movie> = emptyList()

    fun submit(list: List<Movie>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val box: FrameLayout = v.findViewById(R.id.posterBox)
        val image: ImageView = v.findViewById(R.id.posterImage)
        val fallback: TextView = v.findViewById(R.id.posterFallback)
        val progress: ProgressBar = v.findViewById(R.id.posterProgress)
        val title: TextView = v.findViewById(R.id.posterTitle)
        val year: TextView = v.findViewById(R.id.posterYear)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
        Look.focusZoom(v)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        val radius = 8 * h.itemView.resources.displayMetrics.density
        h.box.background = Look.tile(Look.colorFor(m.title), radius)
        h.box.clipToOutline = true
        h.fallback.text = m.title
        h.image.load(m.poster) { crossfade(true) }
        h.title.text = m.title
        h.year.text = m.year
        val p = host.movieProgress(m)
        h.progress.visibility = if (p > 0) View.VISIBLE else View.GONE
        h.progress.progress = p
        h.itemView.setOnClickListener { host.openMovie(m) }
    }
}
