package com.canoebay.canoetv

import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_ALL = "\u0000all"
        private const val KEY_FAV = "\u0000fav"
        private const val KEY_RECENT = "\u0000recent"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }

    private lateinit var root: LinearLayout
    private lateinit var playerBox: FrameLayout
    private lateinit var listPanel: LinearLayout
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var overlay: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var groupSpinner: Spinner
    private lateinit var search: EditText
    private lateinit var hideDead: CheckBox
    private lateinit var autoSkip: CheckBox
    private lateinit var status: TextView
    private lateinit var list: RecyclerView

    private lateinit var store: Store
    private lateinit var player: ExoPlayer
    private lateinit var adapter: ChannelAdapter
    private lateinit var backCallback: OnBackPressedCallback

    private var sources: List<Source> = emptyList()
    private var loadedSourceId: String? = null
    private var channels: List<Channel> = emptyList()
    private var byUrl: Map<String, Channel> = emptyMap()
    private var filtered: List<Channel> = emptyList()
    private var groupKeys: List<String> = listOf(KEY_ALL)
    private var current: Channel? = null
    private var fullscreen = false
    private var skipCount = 0
    private var loadToken = 0

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val skipRunnable = Runnable { step(1, auto = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        playerBox = findViewById(R.id.playerBox)
        listPanel = findViewById(R.id.listPanel)
        playerView = findViewById(R.id.playerView)
        loading = findViewById(R.id.loading)
        overlay = findViewById(R.id.overlay)
        nowPlaying = findViewById(R.id.nowPlaying)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        groupSpinner = findViewById(R.id.groupSpinner)
        search = findViewById(R.id.search)
        hideDead = findViewById(R.id.hideDead)
        autoSkip = findViewById(R.id.autoSkip)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)

        store = Store(this)

        player = ExoPlayer.Builder(this).build()
        player.addListener(playerListener)
        playerView.player = player
        playerView.keepScreenOn = true
        playerView.setFullscreenButtonClickListener { full -> setFullscreen(full) }

        adapter = ChannelAdapter(
            store,
            onClick = { ch -> if (ch.url == current?.url && player.isPlaying) setFullscreen(true) else play(ch, auto = false) },
            onToggleFav = { ch -> toggleFav(ch) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setHasFixedSize(true)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        hideDead.isChecked = store.hideDead
        hideDead.setOnCheckedChangeListener { _, on -> store.hideDead = on; applyFilter() }
        autoSkip.isChecked = store.autoSkip
        autoSkip.setOnCheckedChangeListener { _, on -> store.autoSkip = on }

        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyFilter()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = setFullscreen(false)
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        setupSources(selectId = store.sourceId)
        applyLayout()
    }

    // ------------------------------------------------------------------ sources & loading

    private fun setupSources(selectId: String) {
        sources = Sources.builtIn() + store.customSources()
        val labels = sources.map { if (it.custom) "📁 ${it.name}" else it.name } +
            listOf("+ Add playlist URL…", "– Remove a custom playlist…")
        sourceSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, labels).also {
            it.setDropDownViewResource(R.layout.spinner_item)
        }
        val idx = sources.indexOfFirst { it.id == selectId }.let { if (it < 0) 0 else it }
        sourceSpinner.setSelection(idx, false)
        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                when {
                    pos == sources.size -> { resetSourceSelection(); showAddDialog() }
                    pos == sources.size + 1 -> { resetSourceSelection(); showRemoveDialog() }
                    sources[pos].id != loadedSourceId -> load(sources[pos])
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        if (loadedSourceId != sources[idx].id) load(sources[idx])
    }

    private fun resetSourceSelection() {
        val i = sources.indexOfFirst { it.id == loadedSourceId }
        if (i >= 0) sourceSpinner.setSelection(i, false)
    }

    private fun load(src: Source) {
        val token = ++loadToken
        loadedSourceId = src.id
        status.text = "Loading…"
        if (current == null) showMessage("Loading ${src.name}…", error = false)
        io.execute {
            val all = LinkedHashMap<String, Channel>()
            var failed = 0
            for (u in src.urls) {
                val text = try {
                    download(u).also { cacheFile(u).writeText(it) }
                } catch (e: Exception) {
                    failed++
                    cacheFile(u).takeIf { it.exists() }?.readText()
                }
                if (text != null) for (c in M3u.parse(text)) all.putIfAbsent(c.url, c)
            }
            val result = all.values.toList()
            result.forEachIndexed { i, c -> c.number = i + 1 }
            runOnUiThread {
                if (token != loadToken) return@runOnUiThread
                channels = result
                byUrl = result.associateBy { it.url }
                store.sourceId = src.id
                buildGroups()
                applyFilter()
                when {
                    result.isEmpty() -> showMessage("Couldn't load this playlist. Check your internet connection and try again.", error = true)
                    failed > 0 -> toast("Some of the playlist couldn't be downloaded — showing what loaded.")
                }
                if (result.isNotEmpty() && current == null) {
                    val last = store.lastUrl?.let { byUrl[it] }
                    if (last != null) play(last, auto = false)
                    else showMessage("Pick a channel to start watching", error = false)
                }
            }
        }
    }

    private fun download(url: String): String {
        var target = URL(url)
        repeat(5) {
            val conn = target.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw Exception("Bad redirect")
                target = URL(target, loc)
                conn.disconnect()
                return@repeat
            }
            if (code != 200) throw Exception("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
        throw Exception("Too many redirects")
    }

    private fun cacheFile(url: String) = File(filesDir, "playlist_" + Integer.toHexString(url.hashCode()) + ".m3u")

    private fun showAddDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val name = EditText(this).apply { hint = "Name (e.g. My provider)"; setSingleLine() }
        val url = EditText(this).apply { hint = "M3U playlist URL"; setSingleLine() }
        box.addView(name); box.addView(url)
        AlertDialog.Builder(this)
            .setTitle("Add playlist")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                val n = name.text.toString().trim().ifEmpty { "My playlist" }
                store.addCustom(n, u)
                setupSources(selectId = "custom:$u")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoveDialog() {
        val custom = store.customSources()
        if (custom.isEmpty()) { toast("You haven't added any playlists yet."); return }
        AlertDialog.Builder(this)
            .setTitle("Remove which playlist?")
            .setItems(custom.map { it.name }.toTypedArray()) { _, i ->
                store.removeCustom(custom[i].urls.first())
                val keep = if (loadedSourceId == custom[i].id) "all" else (loadedSourceId ?: "all")
                if (loadedSourceId == custom[i].id) loadedSourceId = null
                setupSources(selectId = keep)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ list & filtering

    private fun buildGroups() {
        val previous = groupKeys.getOrNull(groupSpinner.selectedItemPosition)
        val counts = channels.groupingBy { it.group }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val favCount = channels.count { it.url in store.favs }
        groupKeys = listOf(KEY_ALL, KEY_FAV, KEY_RECENT) + counts.keys
        val labels = listOf("All channels (${channels.size})", "★ Favourites ($favCount)", "⟲ Recently watched") +
            counts.map { (g, n) -> "$g ($n)" }
        groupSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, labels).also {
            it.setDropDownViewResource(R.layout.spinner_item)
        }
        val i = groupKeys.indexOf(previous)
        groupSpinner.setSelection(if (i >= 0) i else 0, false)
    }

    private fun applyFilter() {
        if (!::adapter.isInitialized) return
        val key = groupKeys.getOrNull(groupSpinner.selectedItemPosition) ?: KEY_ALL
        var base = when (key) {
            KEY_ALL -> channels
            KEY_FAV -> channels.filter { it.url in store.favs }
            KEY_RECENT -> store.recents.mapNotNull { byUrl[it] }
            else -> channels.filter { it.group == key }
        }
        val q = search.text.toString().trim()
        if (q.isNotEmpty()) base = base.filter { it.name.contains(q, ignoreCase = true) }
        if (hideDead.isChecked) base = base.filter { store.health(it.url) != false }
        filtered = base
        adapter.submit(filtered, current?.url)
        status.text = "${filtered.size} channels"
    }

    private fun toggleFav(ch: Channel) {
        if (!store.favs.remove(ch.url)) store.favs.add(ch.url)
        store.saveFavs()
        toast(if (ch.url in store.favs) "Added ${ch.name} to favourites" else "Removed from favourites")
        val key = groupKeys.getOrNull(groupSpinner.selectedItemPosition)
        buildGroups()
        if (key == KEY_FAV) applyFilter() else adapter.refresh(ch.url)
    }

    // ------------------------------------------------------------------ playback

    private fun play(ch: Channel, auto: Boolean) {
        handler.removeCallbacks(skipRunnable)
        if (!auto) skipCount = 0
        current = ch
        nowPlaying.text = ch.name
        store.lastUrl = ch.url
        store.addRecent(ch.url)
        adapter.setPlaying(ch.url)
        overlay.visibility = View.GONE
        loading.visibility = View.VISIBLE

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent ?: UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        ch.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder().setUri(ch.url).apply {
            if (ch.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (ch.url.contains(".mpd", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_MPD)
        }.build()

        player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
        player.prepare()
        player.playWhenReady = true

        val pos = filtered.indexOfFirst { it.url == ch.url }
        if (pos >= 0) list.scrollToPosition(pos)
    }

    private fun step(delta: Int, auto: Boolean = false) {
        if (filtered.isEmpty()) return
        val size = filtered.size
        var pos = filtered.indexOfFirst { it.url == current?.url }
        for (i in 0 until size) {
            pos = if (pos < 0) 0 else (pos + delta + size) % size
            // When skipping automatically, jump over channels already known to be dead
            if (!auto || store.health(filtered[pos].url) != false) break
        }
        play(filtered[pos], auto)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                overlay.visibility = View.GONE
                current?.let {
                    if (store.health(it.url) != true) { store.setHealth(it.url, true); adapter.refresh(it.url) }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            loading.visibility = View.GONE
            val ch = current ?: return
            store.setHealth(ch.url, false)
            adapter.refresh(ch.url)
            val willSkip = autoSkip.isChecked && skipCount < 15 && filtered.size > 1
            showMessage(
                "Couldn't play ${ch.name}\n${reason(error)}" + if (willSkip) "\n\nSkipping to the next channel…" else "",
                error = true,
            )
            if (willSkip) {
                skipCount++
                handler.postDelayed(skipRunnable, 1500)
            }
        }
    }

    private fun reason(e: PlaybackException): String {
        val cause = e.cause
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return when (cause.responseCode) {
                403, 451 -> "This channel is blocked in your region or by its provider."
                404, 410 -> "This channel is offline."
                in 500..599 -> "The channel's server is having problems right now."
                else -> "The server refused the stream (error ${cause.responseCode})."
            }
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the channel's server — it may be offline."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This stream's format isn't supported on this device."
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "The stream fell behind. Pick the channel again."
            else -> "The stream may be offline or unavailable in your region."
        }
    }

    private fun showMessage(msg: String, error: Boolean) {
        overlay.text = msg
        overlay.setTextColor(ContextCompat.getColor(this, if (error) R.color.danger else R.color.muted))
        overlay.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ layout & fullscreen

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        backCallback.isEnabled = on
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        applyLayout()
        if (on) playerView.requestFocus() else list.requestFocus()
    }

    private fun applyLayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        when {
            fullscreen -> {
                listPanel.visibility = View.GONE
                playerBox.layoutParams = LinearLayout.LayoutParams(mp, mp)
            }
            land -> {
                root.orientation = LinearLayout.HORIZONTAL
                listPanel.visibility = View.VISIBLE
                playerBox.layoutParams = LinearLayout.LayoutParams(0, mp, 0.6f)
                listPanel.layoutParams = LinearLayout.LayoutParams(0, mp, 0.4f)
            }
            else -> {
                root.orientation = LinearLayout.VERTICAL
                listPanel.visibility = View.VISIBLE
                val h = resources.displayMetrics.widthPixels * 9 / 16
                playerBox.layoutParams = LinearLayout.LayoutParams(mp, h)
                listPanel.layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    // TV remote: channel up/down anywhere; D-pad up/down in fullscreen when the controls are hidden
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { step(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(1); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (fullscreen && !playerView.isControllerFullyVisible) {
                        step(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        if (current != null) player.play()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
