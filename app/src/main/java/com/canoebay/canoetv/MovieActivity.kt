package com.canoebay.canoetv

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Full-screen movie player with seeking, which remembers where you stopped. */
@OptIn(UnstableApi::class)
class MovieActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_YEAR = "year"
        const val EXTRA_DESC = "desc"
        const val EXTRA_START = "start"
    }

    private lateinit var player: ExoPlayer
    private lateinit var store: Store
    private lateinit var movie: Movie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())

        store = Store(this)
        movie = Movie(
            intent.getStringExtra(EXTRA_ID) ?: "",
            intent.getStringExtra(EXTRA_TITLE) ?: "",
            intent.getStringExtra(EXTRA_YEAR) ?: "",
            intent.getStringExtra(EXTRA_DESC) ?: "",
        )
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val start = intent.getLongExtra(EXTRA_START, 0L)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val pv = PlayerView(this)
        val error = TextView(this).apply {
            setTextColor(0xFFFF6B6B.toInt()); textSize = 16f; visibility = View.GONE
            setPadding(48, 48, 48, 48)
        }
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))
        root.addView(error, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
        setContentView(root)

        player = ExoPlayer.Builder(this).build()
        pv.player = player
        pv.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        player.setMediaItem(MediaItem.fromUri(url))
        if (start > 5_000) player.seekTo(start)
        player.prepare()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error.text = "This film couldn't be played right now.\nThe Internet Archive may be busy — try again in a minute."
                error.visibility = View.VISIBLE
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { store.clearMoviePosition(movie.id); finish() }
            }
        })
        pv.requestFocus()
    }

    private fun savePosition() {
        if (!::player.isInitialized) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur > 0 && pos > 0) store.saveMoviePosition(movie, pos, dur)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
