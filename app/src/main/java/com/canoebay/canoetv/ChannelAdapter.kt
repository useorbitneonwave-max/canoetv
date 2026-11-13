package com.canoebay.canoetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val store: Store,
    private val nowShowing: (Channel) -> String?,
    private val isLocked: (Channel) -> Boolean,
    private val onClick: (Channel) -> Unit,
    private val onToggleFav: (Channel) -> Unit,
    private val onOptions: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        private set
    private var playingUrl: String? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val nowShow: TextView = v.findViewById(R.id.nowShow)
        val dot: TextView = v.findViewById(R.id.dot)
        val star: TextView = v.findViewById(R.id.star)
    }

    fun submit(list: List<Channel>, playing: String?) {
        items = list
        playingUrl = playing
        notifyDataSetChanged()
    }

    fun setPlaying(url: String?) {
        val old = items.indexOfFirst { it.url == playingUrl }
        playingUrl = url
        if (old >= 0) notifyItemChanged(old)
        val now = items.indexOfFirst { it.url == url }
        if (now >= 0) notifyItemChanged(now)
    }

    fun refresh(url: String) {
        val i = items.indexOfFirst { it.url == url }
        if (i >= 0) notifyItemChanged(i)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        val ctx = h.itemView.context
        h.num.text = c.number.toString()
        h.name.text = if (isLocked(c)) "🔒 ${c.name}" else c.name
        if (c.logo.isNotEmpty()) {
            h.logo.load(c.logo) {
                placeholder(R.drawable.logo_placeholder)
                error(R.drawable.logo_placeholder)
                crossfade(false)
            }
        } else {
            h.logo.setImageResource(R.drawable.logo_placeholder)
        }

        val show = nowShowing(c)
        if (show != null) {
            h.nowShow.text = show
            h.nowShow.visibility = View.VISIBLE
        } else {
            h.nowShow.visibility = View.GONE
        }

        val health = store.health(c.url)
        h.dot.setTextColor(
            when (health) {
                true -> ContextCompat.getColor(ctx, R.color.accent)
                false -> ContextCompat.getColor(ctx, R.color.danger)
                null -> 0xFF2A3040.toInt()
            }
        )
        h.name.alpha = if (health == false) 0.45f else 1f

        val fav = c.url in store.favs
        h.star.setTextColor(if (fav) ContextCompat.getColor(ctx, R.color.star) else 0xFF3A4152.toInt())
        h.star.setOnClickListener { onToggleFav(c) }

        h.itemView.isSelected = c.url == playingUrl
        h.itemView.setOnClickListener { onClick(c) }
        // Long-press (or long-press OK on a TV remote) opens channel options
        h.itemView.setOnLongClickListener { onOptions(c); true }
    }
}
