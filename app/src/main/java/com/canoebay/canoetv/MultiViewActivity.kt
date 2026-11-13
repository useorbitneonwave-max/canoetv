package com.canoebay.canoetv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Watch 2–4 channels at once. Tap (or press OK on) a tile to hear it; tap the tile you're
 * already hearing to open it full size in the main screen.
 */
@OptIn(UnstableApi::class)
class MultiViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAMES = "names"
        const val EXTRA_URLS = "urls"
        const val EXTRA_UAS = "uas"
        const val EXTRA_REFS = "refs"
        const val RESULT_URL = "url"
    }

    private class Tile(val frame: FrameLayout, val player: ExoPlayer, val label: TextView, val url: String, val name: String)

    private val tiles = ArrayList<Tile>()
    private var audioIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())

        val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
        val uas = intent.getStringArrayListExtra(EXTRA_UAS) ?: arrayListOf()
        val refs = intent.getStringArrayListExtra(EXTRA_REFS) ?: arrayListOf()
        val count = urls.size.coerceAtMost(4)
        if (count == 0) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val rows = if (count <= 2) 1 else 2
        val perRow = if (count == 1) 1 else 2
        val rowViews = (0 until rows).map {
            LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
                root.addView(r, LinearLayout.LayoutParams(mp, 0, 1f))
            }
        }

        for (i in 0 until count) {
            val frame = FrameLayout(this).apply {
                isFocusable = true
                isClickable = true
                setPadding(4, 4, 4, 4)
            }
            val pv = PlayerView(this).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.BLACK)
            }
            val label = TextView(this).apply {
                text = names.getOrElse(i) { "Channel ${i + 1}" }
                setTextColor(Color.WHITE)
                textSize = 13f
                setBackgroundColor(0xB30B0D12.toInt())
                setPadding(16, 8, 16, 8)
            }
            frame.addView(pv, FrameLayout.LayoutParams(mp, mp))
            frame.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

            val player = ExoPlayer.Builder(this).build()
            pv.player = player
            player.setMediaSource(Streams.source(urls[i], uas.getOrNull(i)?.ifEmpty { null }, refs.getOrNull(i)?.ifEmpty { null }))
            player.prepare()
            player.playWhenReady = true
            val tile = Tile(frame, player, label, urls[i], names.getOrElse(i) { "" })
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    tile.label.text = "${tile.name} — offline"
                    tile.label.setTextColor(ContextCompat.getColor(this@MultiViewActivity, R.color.danger))
                }
                override fun onPlaybackStateChanged(state: Int) {
                    // Keep live tiles going if a stream briefly ends
                    if (state == Player.STATE_ENDED) { player.seekToDefaultPosition(); player.prepare() }
                }
            })
            tiles.add(tile)

            val idx = i
            frame.setOnClickListener {
                if (idx == audioIndex) openFull(idx) else selectAudio(idx)
            }
            frame.setOnFocusChangeListener { _, _ -> highlight() }
            rowViews[i / perRow].addView(frame, LinearLayout.LayoutParams(0, mp, 1f))
        }

        setContentView(root)
        selectAudio(0)
        tiles[0].frame.requestFocus()
    }

    private fun selectAudio(i: Int) {
        audioIndex = i
        tiles.forEachIndexed { j, t -> t.player.volume = if (j == i) 1f else 0f }
        highlight()
    }

    private fun highlight() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        tiles.forEachIndexed { j, t ->
            t.frame.setBackgroundColor(
                when {
                    j == audioIndex -> accent
                    t.frame.hasFocus() -> 0xFF8A91A3.toInt()
                    else -> Color.BLACK
                }
            )
            t.label.text = (if (j == audioIndex) "🔊 " else "") + t.name +
                (if (t.label.text.endsWith("offline")) " — offline" else "")
        }
    }

    private fun openFull(i: Int) {
        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_URL, tiles[i].url))
        finish()
    }

    override fun onStop() {
        super.onStop()
        tiles.forEach { it.player.pause() }
    }

    override fun onStart() {
        super.onStart()
        tiles.forEach { it.player.play() }
    }

    override fun onDestroy() {
        tiles.forEach { it.player.release() }
        super.onDestroy()
    }
}
