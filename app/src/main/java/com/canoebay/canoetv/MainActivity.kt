package com.canoebay.canoetv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity(), TvHost, GuideHost, MovieHost, TmdbHost {

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
    private lateinit var banner: LinearLayout
    private lateinit var bannerLogo: ImageView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerSub: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var sourceBtn: TextView
    private lateinit var groupSpinner: Spinner
    private lateinit var search: EditText
    private lateinit var shell: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var navBrand: TextView
    private lateinit var navItems: List<TextView>
    private lateinit var content: FrameLayout
    private lateinit var homeList: RecyclerView
    private lateinit var homeAdapter: HomeAdapter
    private lateinit var guideView: LinearLayout
    private lateinit var guideList: RecyclerView
    private lateinit var guideRuler: TimeRulerView
    private lateinit var guideEmpty: TextView
    private lateinit var guideState: GuideState
    private lateinit var guideAdapter: GuideAdapter
    private lateinit var settingsView: View
    private lateinit var settingsList: LinearLayout
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
    private var previous: Channel? = null
    private var fullscreen = false
    private var inPip = false
    private var skipCount = 0
    private var loadToken = 0
    private lateinit var epg: Epg
    private val guideCatalog = LinkedHashSet<String>()
    private var unlocked = false // parental lock opened with the PIN this session

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor(lowPriority("lists"))
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
        banner = findViewById(R.id.banner)
        bannerLogo = findViewById(R.id.bannerLogo)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerSub = findViewById(R.id.bannerSub)
        nowPlaying = findViewById(R.id.nowPlaying)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        sourceBtn = findViewById(R.id.sourceBtn)
        sourceBtn.setOnClickListener { showListPicker() }
        groupSpinner = findViewById(R.id.groupSpinner)
        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)

        store = Store(this)
        store.takeHealthReset15()
        epg = Epg(File(filesDir, "epg").apply { mkdirs() })

        // Bigger buffer than the default so short network hiccups don't interrupt playback
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 2_500, 6_000)
            .build()
        player = Players.build(this, loadControl)
        player.addListener(playerListener)
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long,
            ) { statDropped += droppedFrames }
        })
        playerView.player = player
        playerView.keepScreenOn = true
        playerView.setFullscreenButtonClickListener { full -> setFullscreen(full) }

        adapter = ChannelAdapter(
            store,
            nowShowing = { ch -> epg.now(ch)?.let { "Now: ${it.title}" } },
            isLocked = { ch -> isLockedGroup(ch) },
            onClick = { ch -> if (ch.url == current?.url && player.isPlaying) setFullscreen(true) else play(ch, auto = false) },
            onToggleFav = { ch -> toggleFav(ch) },
            onOptions = { ch -> showChannelOptions(ch) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setHasFixedSize(true)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })


        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyFilter()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<View>(R.id.lastBtn).setOnClickListener { lastChannel() }
        findViewById<View>(R.id.menuBtn).setOnClickListener { showMenu() }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    surfVisible() -> hideSurf()
                    fullscreen -> {
                        setFullscreen(false)
                        // Came from the guide or Movies: go back there
                        returnTo?.let { returnTo = null; showSection(it) }
                    }
                    else -> showSection(Section.HOME)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        setupShell()
        setupSurf()
        applyPictureMode()
        playerView.setShowSubtitleButton(true)
        if (store.takeMixSwitch() && store.welcomed) {
            handler.postDelayed({ toast("New: the Canada mix — English channels from Pluto TV, Samsung TV Plus, Plex and more") }, 1500)
        }
        setupSources(selectId = store.sourceId)
        applyLayout()
        showSection(Section.HOME)
        if (!store.welcomed) handler.postDelayed({ if (!isFinishing) showWelcome() }, 600)
        handleLink(intent)

        // Look for a newer version shortly after start-up (at most twice a day)
        handler.postDelayed({ checkForUpdates(manual = false) }, 8_000)
    }

    private fun isTv(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        val sleepLabel = if (sleepAt > 0) {
            val mins = ((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
            "⏾ Sleep timer (stops in $mins min)"
        } else "⏾ Sleep timer"
        val actions = listOf<Pair<String, () -> Unit>>(
            "📺 TV guide — this channel" to { current?.let { showGuide(it) } ?: toast("Play a channel first") },
            "🔁 Find another source for this channel" to { current?.let { findAnotherSource(it) } ?: toast("Play a channel first") },
            "💬 Subtitles" to { showTrackPicker(C.TRACK_TYPE_TEXT) },
            "🔊 Audio track" to { showTrackPicker(C.TRACK_TYPE_AUDIO) },
            "🖼 Picture: ${store.pictureMode.replaceFirstChar { it.uppercase() }} (change)" to { cyclePictureMode() },
            "⏰ Reminders" to { showReminders() },
            "⇄ Last channel" to { lastChannel() },
            "▦ Multi-view (watch up to 4)" to { pickMultiView() },
            sleepLabel to { showSleepTimer() },
            "🙈 Show / hide categories" to { showHideCategories() },
            "🔒 Parental lock" to { showParentalLock() },
            "↻ Retry offline channels" to { retryOffline() },
            "+ Add playlist URL" to { showAddDialog() },
            "– Remove a custom playlist" to { showRemoveDialog() },
            "💾 Back up / restore" to { showBackup() },
            "⬆ Check for updates" to { checkForUpdates(manual = true) },
            "ℹ About CanoeTV" to { showAbout() },
        )
        AlertDialog.Builder(this)
            .setTitle("CanoeTV")
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("CanoeTV ${BuildConfig.VERSION_NAME}")
            .setMessage(
                "CanoeTV is a TV player. It doesn't host, sell or own any channels — it plays free streams " +
                    "that broadcasters make publicly available, and playlists you add yourself.\n\n" +
                    "Channel lists\n• Free-TV (github.com/Free-TV/IPTV) — built in\n" +
                    "• iptv-org (github.com/iptv-org/iptv) — built in\n\n" +
                    "Movies\n• 24/7 movie channels from Pluto TV, Samsung TV Plus, Plex and Tubi\n" +
                    "• Free movie apps open in their own app\n• Public-domain films from the Internet Archive (archive.org)\n" +
                    "• Movie info, posters and where-to-watch data from TMDB (themoviedb.org), with streaming data from JustWatch. " +
                    "This product uses the TMDB API but is not endorsed or certified by TMDB.\n\n" +
                    "TV guide data\n• epgshare01.online, and guide links named by your playlists\n\n" +
                    "Made with\n• AndroidX Media3 (Apache 2.0)\n• Coil (Apache 2.0)\n\n" +
                    "If you own a channel and want it removed, please contact the list it comes from.\n\n" +
                    "Remote tips\n• OK — play / OK again for full screen\n• Up/Down in full screen — change channel\n" +
                    "• Number keys — jump to a channel number\n• 0 or Last — previous channel\n" +
                    "• Long-press OK — channel options\n• Back — return to Home"
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Privacy policy") { _, _ -> showPrivacy() }
            .show()
    }

    private fun showPrivacy() {
        AlertDialog.Builder(this)
            .setTitle("Privacy policy")
            .setMessage(
                "CanoeTV has no accounts, no ads and no tracking.\n\n" +
                    "Stored only on this device: your favourites, renamed channels, hidden and locked categories, " +
                    "your PIN, recently watched channels and app settings. Uninstalling the app deletes them.\n\n" +
                    "Internet requests: CanoeTV downloads channel lists, TV guide files and the movie catalogue " +
                    "(archive.org), plays the streams and films you choose " +
                    "(directly from each channel's server) and checks GitHub for app updates. Those servers see your IP " +
                    "address, as with any website. CanoeTV doesn't send them anything about you.\n\n" +
                    "Full policy: github.com/${BuildConfig.REPO}/blob/main/PRIVACY.md"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun retryOffline() {
        store.clearHealth()
        skipCount = 0
        applyFilter()
        toast("Offline marks cleared — all channels will be tried again.")
    }

    // ------------------------------------------------------------------ sections (Home, Live TV, Guide, Settings)

    private enum class Section { HOME, SEARCH, LIVE, MOVIES, GUIDE, SETTINGS }

    private var section = Section.HOME
    private var homeDirty = true
    private var guideFilter = "guide"
    private val sectionRefresh = Runnable { refreshVisibleSection() }
    private val minuteTick = object : Runnable {
        override fun run() {
            refreshVisibleSection()
            handler.postDelayed(this, 60_000)
        }
    }

    private fun setupShell() {
        shell = findViewById(R.id.shell)
        navBar = findViewById(R.id.navBar)
        navBrand = findViewById(R.id.navBrand)
        content = findViewById(R.id.content)
        homeList = findViewById(R.id.homeList)
        guideView = findViewById(R.id.guideView)
        guideList = findViewById(R.id.guideList)
        guideRuler = findViewById(R.id.guideRuler)
        guideEmpty = findViewById(R.id.guideEmpty)
        settingsView = findViewById(R.id.settingsView)
        settingsList = findViewById(R.id.settingsList)

        val navHome = findViewById<TextView>(R.id.navHome)
        val navSearch = findViewById<TextView>(R.id.navSearch)
        val navLive = findViewById<TextView>(R.id.navLive)
        val navMovies = findViewById<TextView>(R.id.navMovies)
        val navGuide = findViewById<TextView>(R.id.navGuide)
        val navSettings = findViewById<TextView>(R.id.navSettings)
        navItems = listOf(navHome, navSearch, navLive, navMovies, navGuide, navSettings)
        navSearch.setOnClickListener { showSection(Section.SEARCH) }
        setupSearch()
        navMovies.setOnClickListener { showSection(Section.MOVIES) }
        navHome.setOnClickListener { showSection(Section.HOME) }
        navLive.setOnClickListener { showSection(if (store.liveView == "guide") Section.GUIDE else Section.LIVE) }
        findViewById<View>(R.id.toListView).setOnClickListener { store.liveView = "list"; showSection(Section.LIVE) }
        findViewById<View>(R.id.toGuideView).setOnClickListener { store.liveView = "guide"; showSection(Section.GUIDE) }
        findViewById<View>(R.id.micBtn).setOnClickListener { startVoiceSearch() }
        navGuide.setOnClickListener { showSection(Section.GUIDE) }
        navSettings.setOnClickListener { showSection(Section.SETTINGS) }

        moviesList = findViewById(R.id.moviesList)
        moviesAdapter = MoviesAdapter(this)
        moviesTop = MoviesAdapter(this)
        movieChannelsAdapter = HomeAdapter(this)
        tmdbRows = TmdbRowsAdapter(this)
        moviesList.layoutManager = LinearLayoutManager(this)
        moviesList.adapter = androidx.recyclerview.widget.ConcatAdapter(moviesTop, tmdbRows, movieChannelsAdapter, moviesAdapter)
        moviesList.itemAnimator = null

        homeAdapter = HomeAdapter(this)
        homeList.layoutManager = LinearLayoutManager(this)
        homeList.adapter = homeAdapter
        homeList.itemAnimator = null

        guideState = GuideState(this)
        guideRuler.state = guideState
        guidePreview = findViewById(R.id.guidePreview)
        findViewById<View>(R.id.guideListBtn).setOnClickListener { showListPicker() }
        guideAdapter = GuideAdapter(guideState, this)
        guideList.layoutManager = LinearLayoutManager(this)
        guideList.adapter = guideAdapter
        guideList.itemAnimator = null

        findViewById<View>(R.id.chipGuide).setOnClickListener { guideFilter = "guide"; refreshGuideScreen(); focusGuideRow() }
        findViewById<View>(R.id.chipFav).setOnClickListener { guideFilter = "fav"; refreshGuideScreen(); focusGuideRow() }
        findViewById<View>(R.id.chipAll).setOnClickListener { guideFilter = "all"; refreshGuideScreen(); focusGuideRow() }
        findViewById<View>(R.id.guideEarlier).setOnClickListener { moveGuide(-3600_000L) }
        findViewById<View>(R.id.guideLater).setOnClickListener { moveGuide(3600_000L) }
        findViewById<View>(R.id.guideNow).setOnClickListener {
            guideState.left = GuideState.halfHour(System.currentTimeMillis())
            guideState.cursor = System.currentTimeMillis()
            invalidateGuide()
        }
    }

    /** Side menu on TVs and landscape screens; a bottom bar on phones held upright. */
    private fun applyShell() {
        if (!::shell.isInitialized) return
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val dens = resources.displayMetrics.density
        val bottomBarMode = !isTv() && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val hideForGuide = section == Section.GUIDE && !bottomBarMode
        navBar.visibility = if (fullscreen || inPip || hideForGuide) View.GONE else View.VISIBLE
        val labels = listOf("🏠" to "Home", "🔍" to "Search", "📺" to "Live TV", "🎬" to "Movies", "🗓" to "Guide", "⚙" to "Settings")
        val bottomBar = !isTv() && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (bottomBar) {
            shell.orientation = LinearLayout.VERTICAL
            if (shell.indexOfChild(navBar) != shell.childCount - 1) { shell.removeView(navBar); shell.addView(navBar) }
            navBar.orientation = LinearLayout.HORIZONTAL
            navBar.layoutParams = LinearLayout.LayoutParams(mp, wc)
            navBar.setPadding((4 * dens).toInt(), (4 * dens).toInt(), (4 * dens).toInt(), (4 * dens).toInt())
            content.layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
            navBrand.visibility = View.GONE
            navItems.forEachIndexed { i, v ->
                v.layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
                v.gravity = android.view.Gravity.CENTER
                v.isSingleLine = false
                v.textSize = 11f
                v.text = "${labels[i].first}\n${labels[i].second}"
            }
        } else {
            shell.orientation = LinearLayout.HORIZONTAL
            if (shell.indexOfChild(navBar) != 0) { shell.removeView(navBar); shell.addView(navBar, 0) }
            navBar.orientation = LinearLayout.VERTICAL
            val w = if (isTv() || resources.configuration.smallestScreenWidthDp >= 600) 190 else 150
            navBar.layoutParams = LinearLayout.LayoutParams((w * dens).toInt(), mp)
            navBar.setPadding((10 * dens).toInt(), (18 * dens).toInt(), (10 * dens).toInt(), (10 * dens).toInt())
            content.layoutParams = LinearLayout.LayoutParams(0, mp, 1f)
            navBrand.visibility = View.VISIBLE
            navItems.forEachIndexed { i, v ->
                v.layoutParams = LinearLayout.LayoutParams(mp, wc).also { it.bottomMargin = (4 * dens).toInt() }
                v.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                v.isSingleLine = true
                v.textSize = 16f
                v.text = "${labels[i].first}   ${labels[i].second}"
            }
        }
    }

    private fun updateBack() {
        if (::backCallback.isInitialized) backCallback.isEnabled = fullscreen || section != Section.HOME || surfVisible()
    }

    // ---- Search

    private lateinit var searchBox: EditText
    private lateinit var searchHint: TextView
    private lateinit var searchList: RecyclerView
    private lateinit var searchChannels: HomeAdapter
    private lateinit var searchMovies: MoviesAdapter
    private lateinit var searchTmdb: TmdbRowsAdapter
    private var searchToken = 0
    private val searchRunnable = Runnable { runSearch(searchBox.text.toString().trim()) }

    private fun setupSearch() {
        searchBox = findViewById(R.id.searchBox)
        searchHint = findViewById(R.id.searchHint)
        searchList = findViewById(R.id.searchList)
        searchChannels = HomeAdapter(this)
        searchMovies = MoviesAdapter(this)
        searchTmdb = TmdbRowsAdapter(this)
        searchList.layoutManager = LinearLayoutManager(this)
        searchList.adapter = androidx.recyclerview.widget.ConcatAdapter(searchChannels, searchTmdb, searchMovies)
        searchList.itemAnimator = null
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, 350)
            }
        })
        searchBox.setOnEditorActionListener { _, _, _ ->
            handler.removeCallbacks(searchRunnable)
            runSearch(searchBox.text.toString().trim())
            searchList.post { searchList.getChildAt(0)?.requestFocus() }
            true
        }
    }

    private fun runSearch(q: String) {
        val token = ++searchToken
        if (q.length < 2) {
            searchChannels.submit(emptyList())
            searchMovies.submit(emptyList())
            searchTmdb.submit(emptyList())
            searchHint.text = "Type at least 2 letters. Search looks through channel names, categories, what's on in the TV guide, and free movies."
            return
        }
        val visible = channels.filter { it.group !in store.hiddenGroups }
        val rows = ArrayList<HomeItem>()

        val byName = visible.filter { it.name.contains(q, ignoreCase = true) }
            .sortedByDescending { it.name.startsWith(q, ignoreCase = true) }
        if (byName.isNotEmpty()) rows.add(HomeItem.Row("Channels", byName.take(60)))

        val now = System.currentTimeMillis()
        val airingNow = ArrayList<Channel>()
        val later = ArrayList<Channel>()
        for (ch in visible) {
            val hit = epg.upcoming(ch, 40).firstOrNull { it.title.contains(q, ignoreCase = true) } ?: continue
            if (hit.start <= now) airingNow.add(ch) else later.add(ch)
            if (airingNow.size + later.size >= 120) break
        }
        if (airingNow.isNotEmpty()) rows.add(HomeItem.Row("“$q” — on now", airingNow))
        if (later.isNotEmpty()) rows.add(HomeItem.Row("“$q” — coming up (see TV Guide for times)", later))

        visible.map { it.group }.distinct().filter { it.contains(q, ignoreCase = true) }.take(5).forEach { g ->
            rows.add(HomeItem.Row("Category: $g", visible.filter { it.group == g }.take(40)))
        }

        searchChannels.submit(rows)
        searchMovies.submit(emptyList())
        searchTmdb.submit(emptyList())
        store.tmdbKey.takeIf { it.isNotEmpty() }?.let { key ->
            moviePool.execute {
                val found = try { Tmdb.parseList(download(Tmdb.searchUrl(q, key))).movies } catch (e: Exception) { emptyList() }
                runOnUiThread {
                    if (token == searchToken && found.isNotEmpty()) searchTmdb.submit(listOf(TmdbItem("Movies", found, null)))
                }
            }
        }
        val chCount = byName.size
        val showCount = airingNow.size + later.size
        searchHint.text = if (rows.isEmpty()) "No channels or shows match “$q”. Looking for movies…"
        else "$chCount channels · $showCount with matching shows. Looking for movies…"

        // Movies come from the internet, so they arrive a moment later
        moviePool.execute {
            val found = try {
                MovieCatalog.parseSearch(download(MovieCatalog.searchByTitleUrl(q)))
            } catch (e: Exception) { null }
            runOnUiThread {
                if (token != searchToken) return@runOnUiThread
                if (!found.isNullOrEmpty()) searchMovies.submit(listOf(MovieItem.Row("Free classics", found)))
                searchHint.text = when {
                    rows.isEmpty() && found.isNullOrEmpty() -> "Nothing matches “$q”. Try a shorter word."
                    found == null -> "$chCount channels · $showCount with matching shows · movies couldn't be searched right now"
                    else -> "$chCount channels · $showCount with matching shows · ${found.size} movies"
                }
            }
        }
    }

    // ---- First-run welcome

    private fun showWelcome() {
        AlertDialog.Builder(this)
            .setTitle("Welcome to CanoeTV")
            .setMessage(
                "Free live TV from around the world, a TV guide, and free classic movies.\n\n" +
                    "• Home — what you watched last, your favourites, sports on now and what's on now\n" +
                    "• Live TV — every channel, with a list on the side\n" +
                    "• Movies — free 24/7 movie channels, free movie apps and classic films\n" +
                    "• TV Guide — what's on now and next\n\n" +
                    "Tip: long-press a channel (or hold OK on the remote) to add it to your favourites."
            )
            .setCancelable(false)
            .setPositiveButton("Next") { _, _ -> chooseStartList() }
            .show()
    }

    private fun chooseStartList() {
        store.welcomed = true
        toast("Canada mix is ready. You can switch lists any time.")
        showListPicker()
    }

    // ---- Android TV home-screen row and canoetv:// links

    private var pendingPlay: Uri? = null
    private var tvRowPublished = false

    private fun publishTvRow() {
        val favs = store.favOrder.mapNotNull { byUrl[it] }
        Thread { TvHomeRow.publish(applicationContext, favs) { epg.now(it)?.title } }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "canoetv") return
        if (uri.host == "play") {
            pendingPlay = uri
            playPending()
        } else {
            showSection(Section.HOME)
        }
    }

    private fun playPending() {
        val uri = pendingPlay ?: return
        val url = uri.getQueryParameter("url") ?: return
        val ch = byUrl[url] ?: if (channels.isEmpty()) return else Channel(
            uri.getQueryParameter("name") ?: "Channel", url,
            uri.getQueryParameter("logo") ?: "", uri.getQueryParameter("group") ?: "", null, null,
        )
        pendingPlay = null
        watch(ch)
    }


    // ---- Movies 2.0: the latest movies from TMDB, with where to watch them in Canada

    private lateinit var tmdbRows: TmdbRowsAdapter
    private val tmdbLists = ConcurrentHashMap<String, Tmdb.Page>()
    private val tmdbFree = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val tmdbDetails = ConcurrentHashMap<Int, TDetails>()
    private var tmdbLoadedKey = ""
    @Volatile private var tmdbFailed: String? = null
    private var heroMovie: TMovie? = null
    private val heroRunnable = Runnable { heroMovie?.let { loadHeroDetails(it) } }

    private fun loadTmdb(force: Boolean = false) {
        val key = store.tmdbKey
        if (key.isEmpty()) { tmdbLists.clear(); renderTmdb(); return }
        if (!force && key == tmdbLoadedKey && tmdbLists.isNotEmpty()) return
        tmdbLoadedKey = key
        tmdbFailed = null
        val failures = AtomicInteger()
        for (row in Tmdb.rows) {
            moviePool.execute {
                try {
                    val page = Tmdb.parseList(Tmdb.cached(cacheDir, Tmdb.url(row.path, key), 6 * 3600_000L) { download(it) })
                    if (row.free) page.movies.forEach { tmdbFree.add(it.id) }
                    tmdbLists[row.title] = page
                } catch (e: Exception) {
                    if (failures.incrementAndGet() >= Tmdb.rows.size) {
                        tmdbFailed = if (e.message?.contains("401") == true)
                            "Your TMDB key wasn't accepted. Check it in Settings → Movies."
                        else "Couldn't load the latest movies right now. Check your internet connection, then open Movies again."
                        tmdbLoadedKey = ""
                    }
                }
                runOnUiThread { if (section == Section.MOVIES) renderMovies() }
            }
        }
    }

    private fun renderTmdb() {
        if (!::tmdbRows.isInitialized) return
        val items = ArrayList<TmdbItem>()
        if (store.tmdbKey.isNotEmpty()) {
            store.tmdbMyList().takeIf { it.isNotEmpty() }?.let { items.add(TmdbItem("♥ My List", it, null)) }
            for (row in Tmdb.rows) {
                val page = tmdbLists[row.title] ?: continue
                if (page.movies.size >= 4) items.add(TmdbItem(row.title, page.movies, row))
            }
        }
        tmdbRows.submit(items)
        val showHero = items.isNotEmpty() && (isTv() || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val hero = findViewById<View>(R.id.mHero)
        hero.visibility = if (showHero) View.VISIBLE else View.GONE
        if (showHero && heroMovie == null) items.firstOrNull { it.row != null }?.movies?.firstOrNull()?.let { tmdbFocused(it) }
    }

    override fun tmdbIsFree(m: TMovie) = m.id in tmdbFree

    override fun tmdbFocused(m: TMovie) {
        heroMovie = m
        findViewById<ImageView>(R.id.mHeroImage).load(m.backdropUrl ?: m.posterUrl) { crossfade(250) }
        findViewById<TextView>(R.id.mHeroTitle).text = if (m.year.isNotEmpty()) "${m.title} (${m.year})" else m.title
        findViewById<TextView>(R.id.mHeroOverview).text = m.overview
        showHeroFacts(m, tmdbDetails[m.id])
        handler.removeCallbacks(heroRunnable)
        if (tmdbDetails[m.id] == null) handler.postDelayed(heroRunnable, 450)
    }

    private fun showHeroFacts(m: TMovie, d: TDetails?) {
        val parts = listOf(m.year, d?.runtimeText ?: "", if (m.ratingText.isNotEmpty()) "★ ${m.ratingText}" else "",
            d?.genres?.joinToString(", ") ?: Tmdb.genres(m)).filter { it.isNotEmpty() }
        findViewById<TextView>(R.id.mHeroMeta).text = parts.joinToString("   •   ") +
            if (tmdbIsFree(m)) "   •   FREE" else ""
        findViewById<TextView>(R.id.mHeroPeople).text = if (d == null) "" else listOfNotNull(
            d.cast.take(4).joinToString(", ") { it.name }.takeIf { it.isNotEmpty() }?.let { "Cast:  $it" },
            d.director.takeIf { it.isNotEmpty() }?.let { "Director:  $it" },
        ).joinToString("\n")
    }

    private fun loadHeroDetails(m: TMovie) {
        val key = store.tmdbKey.ifEmpty { return }
        moviePool.execute {
            val d = try {
                Tmdb.parseDetails(Tmdb.cached(cacheDir, Tmdb.detailsUrl(m.id, key), 24 * 3600_000L) { download(it) })
            } catch (e: Exception) { null } ?: return@execute
            tmdbDetails[m.id] = d
            runOnUiThread { if (heroMovie?.id == m.id) showHeroFacts(m, d) }
        }
    }

    /** The movie page: poster, facts, cast photos, trailer, and where to watch it. */
    override fun tmdbOpen(m: TMovie) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg.setContentView(R.layout.dialog_tmdb_detail)
        val v = dlg.window!!.decorView
        v.findViewById<ImageView>(R.id.dBackdrop).load(m.backdropUrl)
        v.findViewById<ImageView>(R.id.dPoster).load(m.posterUrl) { crossfade(true) }
        v.findViewById<TextView>(R.id.dTitle).text = if (m.year.isNotEmpty()) "${m.title} (${m.year})" else m.title
        v.findViewById<android.widget.RatingBar>(R.id.dStars).rating = (m.rating / 2).toFloat()
        v.findViewById<TextView>(R.id.dOverview).text = m.overview
        val play = v.findViewById<TextView>(R.id.dPlay)
        val trailer = v.findViewById<TextView>(R.id.dTrailer)
        val fav = v.findViewById<TextView>(R.id.dFav)
        val where = v.findViewById<TextView>(R.id.dWhere)
        val castList = v.findViewById<RecyclerView>(R.id.dCast)
        castList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        fun favLabel() = if (store.isTmdbMine(m.id)) "♥  My List" else "♡  My List"
        fav.text = favLabel()
        fav.setOnClickListener {
            val added = store.toggleTmdbMine(m)
            fav.text = favLabel()
            toast(if (added) "Added to My List" else "Removed from My List")
            renderTmdb()
        }
        trailer.visibility = View.GONE
        fillDetailFacts(v, m, null)
        play.setOnClickListener { toast("Still checking where to watch…") }
        dlg.show()
        play.requestFocus()

        val key = store.tmdbKey
        moviePool.execute {
            val d = try {
                tmdbDetails[m.id] ?: Tmdb.parseDetails(Tmdb.cached(cacheDir, Tmdb.detailsUrl(m.id, key), 24 * 3600_000L) { download(it) })
                    .also { tmdbDetails[m.id] = it }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (!dlg.isShowing) return@runOnUiThread
                if (d == null) {
                    play.text = "Couldn't load details"
                    where.text = "Check your internet connection and try again."
                    return@runOnUiThread
                }
                fillDetailFacts(v, m, d)
                castList.adapter = CastAdapter(d.cast)
                v.findViewById<View>(R.id.dCastTitle).visibility = if (d.cast.isEmpty()) View.GONE else View.VISIBLE
                d.trailerKey?.let { k ->
                    trailer.visibility = View.VISIBLE
                    trailer.setOnClickListener { openTrailer(k) }
                }
                setupWatchButton(m, d, play, where)
            }
        }
    }

    private fun fillDetailFacts(v: View, m: TMovie, d: TDetails?) {
        fun line(label: String, value: String) =
            if (value.isEmpty()) "" else "<b>$label</b>&nbsp;&nbsp;${android.text.TextUtils.htmlEncode(value)}<br>"
        val html = line("Directed by:", d?.director ?: "") +
            line("Release date:", d?.releaseDate ?: m.year) +
            line("Duration:", d?.runtimeText ?: "") +
            line("Genre:", d?.genres?.joinToString(", ") ?: Tmdb.genres(m)) +
            line("Rating:", if (m.ratingText.isNotEmpty()) "${m.ratingText} / 10" else "") +
            line("Cast:", d?.cast?.take(5)?.joinToString(", ") { it.name } ?: "")
        v.findViewById<TextView>(R.id.dFacts).text =
            androidx.core.text.HtmlCompat.fromHtml(html.removeSuffix("<br>"), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    /** Play opens the movie's free or subscription service; rent and buy are never offered. */
    private fun setupWatchButton(m: TMovie, d: TDetails, play: TextView, where: TextView) {
        data class Option(val provider: TProvider, val app: AppEntry)
        val options = d.providers.mapNotNull { p -> Tmdb.appFor(p.name)?.let { Option(p, it) } }
            .distinctBy { it.app.name }
            .sortedWith(compareBy<Option> { if (it.provider.kind == "flatrate") 1 else 0 }
                .thenBy { if (MyApps.installed(this, it.app) != null) 0 else 1 })
        fun kindLabel(k: String) = when (k) { "free" -> "free"; "ads" -> "free with ads"; else -> "with your subscription" }

        val free = d.providers.filter { it.kind != "flatrate" }.map { it.name }
        val subs = d.providers.filter { it.kind == "flatrate" }.map { it.name }
        where.text = listOfNotNull(
            free.takeIf { it.isNotEmpty() }?.let { "Free in Canada on: ${it.joinToString(", ")}" },
            subs.takeIf { it.isNotEmpty() }?.let { "Included with: ${it.joinToString(", ")}" },
        ).joinToString("\n").ifEmpty {
            if (tmdbLists[Tmdb.rows[2].title]?.movies?.any { it.id == m.id } == true) "In theatres now — not streaming yet."
            else "Not streaming free or on a subscription service in Canada right now."
        }

        if (options.isEmpty()) {
            play.text = if (d.providers.isEmpty()) "Not streaming in Canada" else "Not on your apps"
            play.alpha = 0.6f
            play.setOnClickListener { toast(where.text.toString().lines().first()) }
            return
        }
        val first = options.first()
        val verb = if (first.provider.kind == "flatrate") "Watch on" else "Free on"
        play.alpha = 1f
        play.text = "▶  $verb ${first.app.name}" + if (options.size > 1) "  (+${options.size - 1})" else ""
        fun go(o: Option) {
            openApp(o.app)
            toast("In ${o.app.name}, search for “${m.title}”")
        }
        play.setOnClickListener {
            if (options.size == 1) { go(first); return@setOnClickListener }
            val labels = options.map { "${it.app.name} — ${kindLabel(it.provider.kind)}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Watch “${m.title}” on…")
                .setItems(labels) { _, i -> go(options[i]) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun openTrailer(key: String) {
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")),
        )
        for (i in tries) {
            try { startActivity(i); return } catch (e: ActivityNotFoundException) { }
        }
        toast("Install YouTube to watch trailers")
    }

    /** "See all": a full poster grid that keeps loading as you scroll. */
    override fun tmdbSeeAll(row: TRow) {
        val key = store.tmdbKey.ifEmpty { return }
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg.setContentView(R.layout.dialog_tmdb_grid)
        val v = dlg.window!!.decorView
        val title = v.findViewById<TextView>(R.id.gTitle)
        val grid = v.findViewById<RecyclerView>(R.id.gList)
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val cols = (widthDp / 150).toInt().coerceIn(3, 8)
        val lm = androidx.recyclerview.widget.GridLayoutManager(this, cols)
        grid.layoutManager = lm
        val adapter = TmdbPosterAdapter(this, fixedWidth = false)
        grid.adapter = adapter
        grid.itemAnimator = null
        val first = tmdbLists[row.title]
        var page = 1
        var totalPages = first?.totalPages ?: 1
        var loading = false
        val total = first?.totalResults ?: 0
        title.text = if (total > 0) "${row.title}   ·   $total movies" else row.title
        adapter.submit(first?.movies ?: emptyList(), null)
        fun loadMore() {
            if (loading || page >= totalPages || page >= 500) return
            loading = true
            val next = page + 1
            moviePool.execute {
                val p = try { Tmdb.parseList(Tmdb.cached(cacheDir, Tmdb.url(row.path, key, next), 6 * 3600_000L) { download(it) }) }
                catch (e: Exception) { null }
                runOnUiThread {
                    loading = false
                    if (p == null || !dlg.isShowing) return@runOnUiThread
                    page = next
                    totalPages = p.totalPages
                    if (row.free) p.movies.forEach { tmdbFree.add(it.id) }
                    val have = adapter.movies().map { it.id }.toHashSet()
                    adapter.append(p.movies.filter { it.id !in have })
                }
            }
        }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - cols * 2) loadMore()
            }
        })
        if (first == null || first.movies.size < cols * 3) loadMore()
        dlg.show()
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    private fun showTmdbKeyDialog(onDone: () -> Unit) {
        val input = EditText(this).apply {
            hint = "Paste or type your TMDB API Key"
            setText(store.tmdbKey)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("TMDB API key")
            .setMessage("Get a free key at themoviedb.org → Settings → API (use the short “API Key”). " +
                "It's saved only on this device.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isEmpty()) { store.tmdbKey = ""; tmdbLists.clear(); onDone(); return@setPositiveButton }
                toast("Checking your key…")
                moviePool.execute {
                    val ok = try { download(Tmdb.checkUrl(key)); true } catch (e: Exception) { e.message?.contains("401") != true }
                    runOnUiThread {
                        if (ok) {
                            store.tmdbKey = key
                            tmdbLists.clear(); tmdbLoadedKey = ""
                            toast("Key saved — open Movies to see the latest movies")
                            loadTmdb(force = true)
                        } else toast("That key wasn't accepted. Check that you copied the short “API Key”.")
                        onDone()
                    }
                }
            }
            .setNeutralButton("Remove") { _, _ -> store.tmdbKey = ""; tmdbLists.clear(); tmdbLoadedKey = ""; renderMovies(); onDone() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Movies (public-domain films from the Internet Archive)

    private lateinit var moviesList: RecyclerView
    private lateinit var moviesAdapter: MoviesAdapter
    private val movieRows = ConcurrentHashMap<String, List<Movie>>()
    private val moviePool = Executors.newFixedThreadPool(2)
    private var moviesRequested = false
    private val moviesFailed = AtomicInteger()

    // Movie channels: 24/7 movie channels from Pluto TV, Samsung TV Plus, Plex and Tubi, playing inside CanoeTV
    private lateinit var moviesTop: MoviesAdapter
    private lateinit var movieChannelsAdapter: HomeAdapter
    @Volatile private var movieChannels: List<Channel> = emptyList()
    @Volatile private var sportsChannels: List<Channel> = emptyList()
    private var movieChannelsLoadedAt = 0L
    private var movieChannelsLoading = false
    private var movieChannelsFailed = false
    private var lastMoviesTop: List<MovieItem> = emptyList()
    private var lastMovieChannelItems: List<HomeItem> = emptyList()
    private var movieByUrl: Map<String, Channel> = emptyMap()

    /** A channel from the loaded list, or one of the movie channels. */
    private fun anyChannel(url: String): Channel? = byUrl[url] ?: movieByUrl[url]

    private fun loadMovieChannels(force: Boolean = false) {
        if (movieChannelsLoading) return
        if (!force && movieChannels.isNotEmpty() && System.currentTimeMillis() - movieChannelsLoadedAt < 6 * 3600_000L) return
        movieChannelsLoading = true
        movieChannelsFailed = false
        val englishOnly = store.englishOnly
        io.execute {
            val all = ArrayList<Channel>()
            val guides = ArrayList<String>()
            for (u in MovieChannels.lists) {
                val cached = cacheFile(u)
                val fresh = !force && cached.exists() && System.currentTimeMillis() - cached.lastModified() < 3 * 3600_000L
                val text = if (fresh) cached.readText() else try {
                    download(u).also { cached.writeText(it) }
                } catch (e: Exception) {
                    cached.takeIf { it.exists() }?.readText()
                }
                if (text != null) {
                    guides.addAll(M3u.guideUrls(text))
                    all.addAll(M3u.parse(text))
                }
            }
            val picked = MovieChannels.pick(all, englishOnly)
            val sports = SportsChannels.pick(all, emptyList(), englishOnly)
            (picked + sports).forEach { c -> store.renamed(c.url)?.let { c.name = it } }
            runOnUiThread {
                movieChannelsLoading = false
                movieChannels = picked
                sportsChannels = sports
                movieByUrl = (picked + sports).associateBy { it.url }
                movieChannelsFailed = picked.isEmpty()
                if (picked.isNotEmpty()) movieChannelsLoadedAt = System.currentTimeMillis()
                renderMovies()
                homeDirty = true
                if (section == Section.HOME) refreshHome()
                // Which movie or game is on now, from each service's own guide
                val ids = (picked + sports).mapNotNull { c -> Epg.norm(c.tvgId).takeIf { it.isNotEmpty() } }.toHashSet()
                val names = (picked + sports).mapNotNull { c -> Epg.nameKey(c.name).takeIf { it.isNotEmpty() } }.toHashSet()
                val urls = guides.filter { !it.contains("epgshare01") }.distinct()
                if (urls.isNotEmpty()) epg.load(urls, ids, names, ::downloadToFile, tag = "movies:") {
                    runOnUiThread {
                        when (section) {
                            Section.MOVIES -> movieChannelsAdapter.refreshInPlace(moviesList)
                            Section.HOME -> homeAdapter.refreshInPlace(homeList)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun movieChannelItems(): List<HomeItem> {
        val items = ArrayList<HomeItem>()
        val list = movieChannels.filter { store.health(it.url) != false }
        if (list.isNotEmpty()) {
            // Channels with a movie on right now come first
            items.add(HomeItem.Row("🎬 Movie channels — playing now", list.sortedByDescending { epg.now(it) != null }))
            for (g in MovieChannels.genres.map { it.first } + MovieChannels.OTHER) {
                val inGenre = list.filter { it.group == g }
                if (inGenre.size >= 2) items.add(HomeItem.Row(g, inGenre))
            }
        }
        items.add(HomeItem.Apps("Free movies on demand — free with ads, opens the app", MyApps.freeMovieApps))
        return items
    }

    private fun loadMovies(force: Boolean = false) {
        loadMovieChannels(force)
        loadTmdb(force)
        renderMovies()
        if (moviesRequested && !force) return
        moviesRequested = true
        moviesFailed.set(0)
        for (spec in MovieCatalog.rows) {
            moviePool.execute {
                try {
                    val json = MovieCatalog.cached(cacheDir, MovieCatalog.searchUrl(spec.query), 24 * 3600_000L) { download(it) }
                    movieRows[spec.title] = MovieCatalog.parseSearch(json)
                } catch (e: Exception) {
                    moviesFailed.incrementAndGet()
                }
                runOnUiThread { if (section == Section.MOVIES) renderMovies() }
            }
        }
    }

    private fun renderMovies() {
        if (!::moviesAdapter.isInitialized) return
        val top = ArrayList<MovieItem>()
        top.add(MovieItem.Header("🎬  Movies", when {
            store.tmdbKey.isEmpty() -> "Add your free TMDB key in Settings → Movies to see the latest movies with posters, " +
                "trailers and where to watch them. Below: free movie channels, free movie apps and classics."
            tmdbFailed != null -> tmdbFailed!!
            tmdbLists.isEmpty() -> "Loading the latest movies…"
            else -> "The latest movies and where to watch them free or on your apps · free movie channels · free classics"
        }.ifEmpty { when {
            movieChannels.isNotEmpty() -> "Free 24/7 movie channels that play right here, free movie apps, and free classics"
            movieChannelsLoading -> "Loading movie channels…"
            movieChannelsFailed -> "Couldn't load movie channels right now. Check your internet connection, then open Movies again."
            else -> "Free 24/7 movie channels, free movie apps, and free classics"
        } }))
        renderTmdb()
        // Only rebuild rows that changed, so TV focus stays put while the classics load
        if (top != lastMoviesTop) { lastMoviesTop = top; moviesTop.submit(top) }
        val mc = movieChannelItems()
        if (mc != lastMovieChannelItems) { lastMovieChannelItems = mc; movieChannelsAdapter.submit(mc) }

        val items = ArrayList<MovieItem>()
        items.add(MovieItem.Header("Free classics — play right here", "Classic films in the public domain, from the Internet Archive"))
        store.continueMovies().takeIf { it.isNotEmpty() }?.let { items.add(MovieItem.Row("Continue watching", it)) }
        store.myMovieList().takeIf { it.isNotEmpty() }?.let { items.add(MovieItem.Row("My List", it)) }
        var rowsShown = 0
        for (spec in MovieCatalog.rows) {
            val list = movieRows[spec.title] ?: continue
            if (list.size >= 4) { items.add(MovieItem.Row(spec.title, list)); rowsShown++ }
        }
        if (rowsShown == 0) {
            val allFailed = moviesFailed.get() >= MovieCatalog.rows.size
            items.add(
                if (allFailed) MovieItem.Header("Couldn't load movies", "Check your internet connection, then open Movies again.")
                else MovieItem.Header("Loading movies…", "")
            )
            if (allFailed) moviesRequested = false
        }
        moviesAdapter.submit(items)
    }

    override fun movieProgress(m: Movie) = store.movieProgress(m.id)

    override fun openMovie(m: Movie) {
        val dens = resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(20), dp(12), dp(20), 0) }
        val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        poster.load(m.poster)
        val info = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            textSize = 14f
            maxLines = 12
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = listOf(m.year, m.desc.ifEmpty { "No description." }).filter { it.isNotEmpty() }.joinToString("\n\n")
            setPadding(dp(16), 0, 0, 0)
        }
        box.addView(poster, LinearLayout.LayoutParams(dp(120), dp(180)))
        box.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val resumeAt = store.moviePosition(m.id)
        AlertDialog.Builder(this)
            .setTitle(m.title)
            .setView(box)
            .setPositiveButton(if (resumeAt > 0) "▶ Resume" else "▶ Play") { _, _ -> playMovie(m, resumeAt) }
            .setNeutralButton(if (store.isMyMovie(m.id)) "✓ My List" else "＋ My List") { _, _ ->
                val added = store.toggleMyMovie(m)
                toast(if (added) "Added to My List" else "Removed from My List")
                renderMovies()
            }
            .setNegativeButton(if (resumeAt > 0) "Start over" else "Close") { _, _ -> if (resumeAt > 0) playMovie(m, 0L) }
            .show()
    }

    private fun playMovie(m: Movie, startAt: Long) {
        toast("Opening ${m.title}…")
        moviePool.execute {
            val url = try {
                MovieCatalog.streamUrl(m.id, download(MovieCatalog.metadataUrl(m.id)))
            } catch (e: Exception) { null }
            runOnUiThread {
                if (url == null) { toast("This film isn't available to stream right now."); return@runOnUiThread }
                startActivity(
                    Intent(this, MovieActivity::class.java)
                        .putExtra(MovieActivity.EXTRA_URL, url)
                        .putExtra(MovieActivity.EXTRA_ID, m.id)
                        .putExtra(MovieActivity.EXTRA_TITLE, m.title)
                        .putExtra(MovieActivity.EXTRA_YEAR, m.year)
                        .putExtra(MovieActivity.EXTRA_DESC, m.desc)
                        .putExtra(MovieActivity.EXTRA_START, startAt)
                )
            }
        }
    }

    private fun showSection(s: Section, resume: Boolean = true) {
        val prev = section
        section = s
        homeList.visibility = if (s == Section.HOME) View.VISIBLE else View.GONE
        findViewById<View>(R.id.homeEmpty).visibility = if (s == Section.HOME && channels.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.searchView).visibility = if (s == Section.SEARCH) View.VISIBLE else View.GONE
        root.visibility = if (s == Section.LIVE) View.VISIBLE else View.GONE
        guideView.visibility = if (s == Section.GUIDE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.moviesView).visibility = if (s == Section.MOVIES) View.VISIBLE else View.GONE
        settingsView.visibility = if (s == Section.SETTINGS) View.VISIBLE else View.GONE
        // The guide is part of Live TV now, so Live TV stays highlighted for both views
        navItems.forEachIndexed { i, v -> v.isSelected = i == s.ordinal || (s == Section.GUIDE && i == Section.LIVE.ordinal) }
        applyShell()
        if (s != Section.LIVE) findViewById<View>(R.id.surfPanel).visibility = View.GONE

        val prevShowsVideo = prev == Section.LIVE || prev == Section.GUIDE
        val showsVideo = s == Section.LIVE || s == Section.GUIDE
        if (s == Section.GUIDE) attachPlayer(guidePreview) else attachPlayer(playerView)
        if (prev == Section.LIVE && s != Section.LIVE) banner.visibility = View.GONE
        if (prevShowsVideo && !showsVideo) player.pause()
        if (showsVideo && !prevShowsVideo && resume) current?.let { reconnects = 0; startSource(it) }
        if (s != Section.GUIDE) handler.removeCallbacks(previewRunnable)

        when (s) {
            Section.HOME -> {
                if (homeDirty || homeAdapter.itemCount == 0) refreshHome()
                homeList.post { homeAdapter.focusHero(homeList) }
            }
            Section.SEARCH -> searchBox.requestFocus()
            Section.LIVE -> list.requestFocus()
            Section.MOVIES -> { loadMovies(); moviesList.post { (moviesList.getChildAt(1) ?: moviesList.getChildAt(0))?.requestFocus() } }
            Section.GUIDE -> {
                refreshGuideScreen()
                if (current == null) showPreviewMsg("Move through the guide to preview a channel")
                focusGuideRow()
            }
            Section.SETTINGS -> { buildSettings(); settingsList.post { settingsList.getChildAt(1)?.requestFocus() } }
        }
        updateBack()
    }

    /** Opens a channel full screen from anywhere in the app. */
    private fun watch(ch: Channel) {
        if (opensElsewhere(ch)) { openElsewhere(ch); return }
        if (needsPin(ch)) {
            askPin("Enter your PIN to watch ${ch.name}") { unlocked = true; adapter.notifyDataSetChanged(); watch(ch) }
            return
        }
        val alreadyPlaying = ch.url == current?.url && player.playbackState != Player.STATE_IDLE
        returnTo = if (section == Section.GUIDE || section == Section.MOVIES) section else null
        if (section != Section.LIVE) showSection(Section.LIVE, resume = false)
        if (alreadyPlaying) {
            store.lastUrl = ch.url
            store.addRecent(ch.url)
            adapter.setPlaying(ch.url)
            player.play()
            showBanner(ch)
        } else {
            play(ch, auto = false)
        }
        setFullscreen(true)
    }

    private fun refreshSections() {
        homeDirty = true
        when (section) {
            Section.HOME -> refreshHome()
            Section.GUIDE -> refreshGuideScreen()
            else -> Unit
        }
    }

    private fun scheduleSectionRefresh() {
        handler.removeCallbacks(sectionRefresh)
        handler.postDelayed(sectionRefresh, 1_500)
    }

    /** New guide data or a minute passed: update what's on screen without moving focus. */
    private fun refreshVisibleSection() {
        when (section) {
            Section.HOME -> {
                val onNowAvailable = channels.any { epg.now(it) != null }
                if (homeAdapter.itemCount == 0 || (onNowAvailable && !homeAdapter.hasRow(ROW_ON_NOW))) refreshHome()
                else homeAdapter.refreshInPlace(homeList)
            }
            Section.GUIDE -> {
                if (guideAdapter.itemCount == 0) refreshGuideScreen()
                else { guideAdapter.notifyItemRangeChanged(0, guideAdapter.itemCount); invalidateGuide() }
            }
            else -> Unit
        }
    }

    // ---- Home

    private val ROW_ON_NOW = "On now"
    private val ROW_SPORTS = "🏆 Sports on now"

    private fun refreshHome() {
        homeDirty = false
        findViewById<View>(R.id.homeEmpty).visibility =
            if (section == Section.HOME && channels.isEmpty()) View.VISIBLE else View.GONE
        if (channels.isEmpty()) { homeAdapter.submit(emptyList()); return }
        val visible = channels.filter { it.group !in store.hiddenGroups && store.health(it.url) != false }
        val items = ArrayList<HomeItem>()

        val heroCh = current
            ?: store.lastUrl?.let { anyChannel(it) }
            ?: store.favOrder.firstNotNullOfOrNull { anyChannel(it) }
            ?: visible.firstOrNull { epg.now(it) != null }
            ?: visible.firstOrNull()
        if (heroCh != null) {
            val label = if (heroCh.url == current?.url || heroCh.url == store.lastUrl) "CONTINUE WATCHING" else "LIVE NOW"
            items.add(HomeItem.Hero(heroCh, label))
        }

        fun row(title: String, list: List<Channel>) { if (list.isNotEmpty()) items.add(HomeItem.Row(title, list)) }
        items.add(HomeItem.Apps("My apps", myAppsInOrder()))
        row("Continue watching", store.recents.mapNotNull { anyChannel(it) }.filter { it.url != heroCh?.url }.take(15))
        row("My List", store.favOrder.mapNotNull { anyChannel(it) })
        // Sports channels from the free services, plus any in the list you're watching
        row(ROW_SPORTS, SportsChannels.pick(sportsChannels, visible, englishOnly = false).filter { store.health(it.url) != false }
            .sortedByDescending { SportsChannels.rank(it, epg.now(it)) }.take(40))
        row("🎬 Movie channels", movieChannels.filter { store.health(it.url) != false }
            .sortedByDescending { epg.now(it) != null }.take(30))
        row(ROW_ON_NOW, visible.filter { epg.now(it) != null }.take(40))
        visible.groupBy { it.group }.entries
            .sortedByDescending { it.value.size }
            .take(12)
            .forEach { (group, chs) ->
                row(group, chs.sortedByDescending { store.health(it.url) == true }.take(30))
            }
        homeAdapter.submit(items)
    }

    override fun epgNow(ch: Channel) = epg.now(ch)
    override fun epgNext(ch: Channel) = epg.next(ch)
    override fun isFav(ch: Channel) = ch.url in store.favs
    override fun isLocked(ch: Channel) = isLockedGroup(ch)
    override fun open(ch: Channel) = watch(ch)
    override fun options(ch: Channel) = showChannelOptions(ch)
    override fun openGuide() = showSection(Section.GUIDE)

    // ---- Guide grid

    private fun refreshGuideScreen() {
        val visible = channels.filter { it.group !in store.hiddenGroups }
        val list = when (guideFilter) {
            "fav" -> store.favOrder.mapNotNull { byUrl[it] }
            "all" -> visible
            "guide" -> visible.filter { epg.upcoming(it, 1).isNotEmpty() }
            else -> visible.filter { "group:${it.group}" == guideFilter }
        }
        buildGuideGroupChips()
        val box = findViewById<LinearLayout>(R.id.guideGroupChips)
        for (i in 0 until box.childCount) box.getChildAt(i).isSelected = box.getChildAt(i).tag == guideFilter
        findViewById<TextView>(R.id.guideListBtn).text =
            "📺 " + (sources.firstOrNull { it.id == loadedSourceId }?.name?.substringBefore(" —")?.substringBefore(" (") ?: "Channel list") + " ▾"
        findViewById<View>(R.id.chipGuide).isSelected = guideFilter == "guide"
        findViewById<View>(R.id.chipFav).isSelected = guideFilter == "fav"
        findViewById<View>(R.id.chipAll).isSelected = guideFilter == "all"
        guideEmpty.text = when {
            channels.isEmpty() -> "Channels are loading…"
            guideFilter == "fav" -> "No favourites yet. Long-press a channel and choose Add to favourites."
            guideFilter == "guide" -> "TV guide data is downloading for the countries you watch. Check back in a minute, or choose All channels."
            else -> "No channels."
        }
        guideEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        guideAdapter.submit(list)
        invalidateGuide()
    }

    private fun invalidateGuide() {
        guideRuler.invalidate()
        findViewById<TextView>(R.id.guideDay).text =
            java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        for (i in 0 until guideList.childCount) {
            (guideList.getChildViewHolder(guideList.getChildAt(i)) as? GuideAdapter.VH)?.strip?.invalidate()
        }
    }

    private fun clampGuide() {
        val min = GuideState.halfHour(System.currentTimeMillis() - 3600_000L)
        val max = System.currentTimeMillis() + 30 * 3600_000L
        guideState.left = guideState.left.coerceIn(min, max)
    }

    private fun moveGuide(byMs: Long) {
        guideState.left += byMs
        clampGuide()
        guideState.cursor = guideState.left + 15 * 60_000L
        invalidateGuide()
    }

    override fun guideProgs(ch: Channel): List<Epg.Prog> = epg.upcoming(ch, 80)

    override fun guideDrag(dxPx: Float) {
        guideState.left += (dxPx / guideState.pxPerMs).toLong()
        clampGuide()
        val span = (guideRuler.width / guideState.pxPerMs).toLong()
        if (guideState.cursor < guideState.left || guideState.cursor > guideState.left + span) {
            guideState.cursor = guideState.left + span / 4
        }
        invalidateGuide()
    }

    override fun guideCursorMoved() {
        guideFocused?.let { updateGuideInfo(it) }
        val span = (guideRuler.width / guideState.pxPerMs).toLong().coerceAtLeast(3600_000L)
        if (guideState.cursor < guideState.left) guideState.left = GuideState.halfHour(guideState.cursor)
        if (guideState.cursor > guideState.left + span * 85 / 100) {
            guideState.left = GuideState.halfHour(guideState.cursor - span * 30 / 100)
        }
        clampGuide()
        invalidateGuide()
    }

    override fun guideTap(ch: Channel, prog: Epg.Prog?) {
        val now = System.currentTimeMillis()
        // On a TV the highlighted row is already picked, so OK goes straight to full screen
        val alreadyPicked = ch.url == guideFocused?.url && (ch.url == current?.url || isTv())
        if (!alreadyPicked) {
            // Touch screens: the first tap picks the channel and previews it
            guideAdapter.selectedUrl = ch.url
            if (prog != null) guideState.cursor = maxOf(prog.start, now)
            guideFocused = ch
            updateGuideInfo(ch, prog)
            invalidateGuide()
            handler.removeCallbacks(previewRunnable)
            previewChannel(ch)
            return
        }
        if (prog == null || (prog.start <= now && now < prog.stop)) { watch(ch); return }
        AlertDialog.Builder(this)
            .setTitle(prog.title)
            .setMessage("${ch.name}\n${hhmm(prog.start)} – ${hhmm(prog.stop)}" + if (prog.desc.isNotEmpty()) "\n\n${prog.desc}" else "")
            .setPositiveButton("⏰ Remind me") { _, _ -> addReminder(ch, prog) }
            .setNeutralButton("Watch ${ch.name} now") { _, _ -> watch(ch) }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun guideOptions(ch: Channel) = showChannelOptions(ch)

    // ---- Guide preview: highlight a channel to preview it, OK to watch full screen

    private lateinit var guidePreview: PlayerView
    private var guideFocused: Channel? = null
    private var guideGroupsFor: String? = null
    private val previewRunnable = Runnable { guideFocused?.let { previewChannel(it) } }

    /** The main player can show in the Live TV screen or in the guide's preview window. */
    private fun attachPlayer(to: PlayerView) {
        // Only the screen that's showing keeps a video surface, so no old frame can linger on top
        guidePreview.visibility = if (to === guidePreview) View.VISIBLE else View.INVISIBLE
        if (to.player === player) return
        playerView.player = null
        guidePreview.player = null
        to.player = player
    }

    /** Starts a channel in the preview window without adding it to "recently watched". */
    private fun previewChannel(ch: Channel) {
        if (section != Section.GUIDE) return
        if (needsPin(ch)) {
            player.stop()
            showPreviewMsg("🔒 Locked — press OK to enter your PIN")
            return
        }
        if (opensElsewhere(ch)) {
            player.stop()
            showPreviewMsg("Press OK to open ${ch.name.substringAfter("— ").ifEmpty { "it" }}")
            return
        }
        if (ch.url == current?.url && player.playbackState != Player.STATE_IDLE) return
        handler.removeCallbacks(skipRunnable)
        current = ch
        nowPlaying.text = ch.name
        playedOk = false
        reconnects = 0
        showPreviewMsg(null)
        startSource(ch)
    }

    private fun showPreviewMsg(msg: String?) {
        val v = findViewById<TextView>(R.id.guidePreviewMsg)
        v.text = msg ?: ""
        v.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    /** Fills the details panel for a channel and the show under the guide's cursor. */
    private fun updateGuideInfo(ch: Channel, progIn: Epg.Prog? = null) {
        val prog = progIn ?: epg.upcoming(ch, 80).firstOrNull { it.start <= guideState.cursor && guideState.cursor < it.stop } ?: epg.now(ch)
        val now = System.currentTimeMillis()
        findViewById<TextView>(R.id.guideInfoChannel).text = "${ch.number}  ·  ${ch.name.uppercase()}"
        findViewById<TextView>(R.id.guideInfoTitle).text = prog?.title ?: ch.name
        val timeView = findViewById<TextView>(R.id.guideInfoTime)
        val bar = findViewById<ProgressBar>(R.id.guideInfoProgress)
        if (prog != null) {
            val airing = prog.start <= now && now < prog.stop
            val extra = when {
                airing -> "  ·  ${((prog.stop - now) / 60_000).coerceAtLeast(1)} min left"
                prog.start > now -> "  ·  starts in ${((prog.start - now) / 60_000).coerceAtLeast(1)} min"
                else -> "  ·  ended"
            }
            timeView.text = "${hhmm(prog.start)} – ${hhmm(prog.stop)}$extra"
            bar.visibility = if (airing) View.VISIBLE else View.GONE
            bar.progress = Look.progress(prog)
            findViewById<TextView>(R.id.guideInfoDesc).text = prog.desc.ifEmpty { ch.group }
        } else {
            timeView.text = ch.group
            bar.visibility = View.GONE
            findViewById<TextView>(R.id.guideInfoDesc).text = "No guide information for this channel."
        }
        findViewById<View>(R.id.guideLiveBadge).visibility = if (prog == null || prog.start <= now) View.VISIBLE else View.GONE
    }

    override fun guideFocusRow(position: Int) {
        val vh = guideList.findViewHolderForAdapterPosition(position)
        if (vh != null) { vh.itemView.requestFocus(); return }
        // The row isn't on screen yet: bring it in, then focus it
        guideList.scrollToPosition(position)
        guideList.post { guideList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
    }

    override fun guideRowFocused(ch: Channel) {
        guideFocused = ch
        updateGuideInfo(ch)
        // Wait a moment so scrolling quickly through the guide doesn't start every channel
        handler.removeCallbacks(previewRunnable)
        handler.postDelayed(previewRunnable, 700)
    }

    /** Category chips under the preview, built from the channel list's own categories. */
    private fun buildGuideGroupChips() {
        if (guideGroupsFor == loadedSourceId && guideGroupsFor != null) return
        guideGroupsFor = loadedSourceId
        val box = findViewById<LinearLayout>(R.id.guideGroupChips)
        box.removeAllViews()
        val groups = channels.filter { it.group !in store.hiddenGroups }
            .groupingBy { it.group }.eachCount().entries.sortedByDescending { it.value }.take(14).map { it.key }
        for (g in groups) {
            val chip = layoutInflater.inflate(R.layout.chip, box, false) as TextView
            chip.text = g
            chip.tag = "group:$g"
            chip.setOnClickListener { guideFilter = "group:$g"; refreshGuideScreen(); focusGuideRow() }
            box.addView(chip)
        }
    }

    private fun focusGuideRow() {
        guideList.post {
            val idx = guideAdapter.items.indexOfFirst { it.url == current?.url }
            if (idx >= 0) {
                guideList.scrollToPosition(idx)
                guideList.post { guideList.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }
            } else {
                guideList.getChildAt(0)?.requestFocus()
            }
        }
    }



    // ---- Settings

    private fun buildSettings() {
        settingsList.removeAllViews()
        val dens = resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()

        val title = TextView(this).apply {
            text = "Settings"; textSize = 28f; setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        settingsList.addView(title)

        fun header(t: String) {
            settingsList.addView(TextView(this).apply {
                text = t.uppercase(); textSize = 13f; letterSpacing = 0.1f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(20), 0, dp(8))
            })
        }

        fun row(t: String, sub: String, action: (TextView) -> Unit) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.settings_row_bg)
                isFocusable = true; isClickable = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            val tv = TextView(this).apply { text = t; textSize = 16f; setTextColor(0xFFFFFFFF.toInt()) }
            val sv = TextView(this).apply {
                text = sub; textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            }
            box.addView(tv); box.addView(sv)
            box.setOnClickListener { action(sv) }
            settingsList.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(6) })
        }

        fun toggle(t: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            fun label() = (if (get()) "On" else "Off") + " — " + sub
            row(t, label()) { sv -> set(!get()); sv.text = label() }
        }

        header("Channels")
        row("Channel list", sources.firstOrNull { it.id == loadedSourceId }?.name ?: "Choose") { showListPicker() }
        toggle("English channels only", "hides Bollywood, European and other foreign-language channels", { store.englishOnly }) {
            store.englishOnly = it
            loadedSourceId?.let { id -> loadedSourceId = null; current = null; setupSources(id) }
            loadMovieChannels(force = true)
        }
        row("Add your own playlist", "An M3U link from a provider you subscribe to") { showAddDialog() }
        row("Remove one of your playlists", "${store.customSources().size} added") { showRemoveDialog() }
        row("Show / hide categories", "${store.hiddenGroups.size} hidden") { showHideCategories() }
        row("Retry offline channels", "Clear the red offline marks and test again") { retryOffline() }

        header("Movies")
        row("Movie database (TMDB) key", if (store.tmdbKey.isEmpty()) "Not set — add your free key to see the latest movies"
            else "Added ✓ — tap to change") { sv ->
            showTmdbKeyDialog { sv.text = if (store.tmdbKey.isEmpty()) "Not set — add your free key to see the latest movies" else "Added ✓ — tap to change" }
        }

        header("Playback")
        row("Buffering & quality", playbackModeLabel()) { sv ->
            store.playbackMode = when (store.playbackMode) { "standard" -> "smooth"; "smooth" -> "saver"; else -> "standard" }
            autoLowered = false
            sv.text = playbackModeLabel()
            applyQuality()
            current?.let { if (section == Section.LIVE || section == Section.GUIDE) { reconnects = 0; startSource(it) } }
        }
        toggle("Hide offline channels", "dead channels disappear from lists", { store.hideDead }) { store.hideDead = it; applyFilter() }
        toggle("Skip dead channels automatically", "jump to the next channel when one fails", { store.autoSkip }) { store.autoSkip = it }
        row("Picture fit", "Now: ${store.pictureMode.replaceFirstChar { it.uppercase() }} — tap to switch Fit / Fill / Zoom") { sv ->
            cyclePictureMode(); sv.text = "Now: ${store.pictureMode.replaceFirstChar { it.uppercase() }} — tap to switch Fit / Fill / Zoom"
        }
        row("Reminders", "${store.reminders().size} set — shows you asked to be reminded about") { showReminders() }
        row("Live TV opens as", if (store.liveView == "guide") "TV guide (tap for the simple list)" else "Simple list (tap for the TV guide)") { sv ->
            store.liveView = if (store.liveView == "guide") "list" else "guide"
            sv.text = if (store.liveView == "guide") "TV guide (tap for the simple list)" else "Simple list (tap for the TV guide)"
        }
        row("Sleep timer", if (sleepAt > 0) "Stops in ${((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)} min" else "Off") { showSleepTimer() }

        toggle("Show connection info", "internet speed, picture quality and buffer on the video — helps find buffering causes", { store.showStats }) {
            store.showStats = it
            updateStats()
        }

        header("TV guide")
        row("Refresh the TV guide", "Download fresh guide data now") {
            epg.reset(); refreshGuide(); toast("Downloading fresh guide data…")
        }

        header("Family & backup")
        row("Parental lock", if (store.pin != null) "On — ${store.lockedGroups.size} categories locked" else "Off") { showParentalLock() }
        row("Back up / restore", "Copy your favourites and settings to another device") { showBackup() }

        header("App")
        row("Check for updates", "You have version ${BuildConfig.VERSION_NAME}") { checkForUpdates(manual = true) }
        row("About CanoeTV & legal", "Where channels come from, credits, remote tips") { showAbout() }
        row("Privacy policy", "What CanoeTV stores and sends") { showPrivacy() }
    }

    // ------------------------------------------------------------------ TV guide

    private val timeFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    override fun hhmm(t: Long): String = timeFmt.format(java.util.Date(t))

    /** Downloads guide data for the countries of the channels you actually watch (max 4 at a time). */
    private fun refreshGuide() {
        if (channels.isEmpty()) return
        val ids = channels.mapNotNull { c -> Epg.norm(c.tvgId).takeIf { it.isNotEmpty() } }.toHashSet()
        val names = channels.mapNotNull { c -> Epg.nameKey(c.name).takeIf { it.isNotEmpty() } }.toHashSet()
        fun country(c: Channel) = Epg.norm(c.tvgId).substringAfterLast('.', "")
            .takeIf { it.length in 2..3 && it.all { ch -> ch.isLetter() } }

        // Countries you actually watch come first, then the most common countries in this list
        val interest = listOfNotNull(current) +
            store.favOrder.mapNotNull { byUrl[it] } +
            store.recents.take(20).mapNotNull { byUrl[it] }
        val countries = LinkedHashSet<String>()
        interest.mapNotNull { country(it) }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.forEach { countries.add(it.key) }
        channels.mapNotNull { country(it) }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.forEach { countries.add(it.key) }

        val urls = ArrayList<String>()
        // Lists like Pluto, Samsung and Plex bring their own guides, so fewer big country files are needed
        val maxCountries = if (guideCatalog.any { !it.contains("epgshare01") }) 2 else 4
        for (cc in countries.take(maxCountries)) {
            val code = cc.uppercase(java.util.Locale.ROOT)
            urls.add(guideCatalog.firstOrNull { it.contains("_${code}1.xml") }
                ?: "https://epgshare01.online/epgshare01/epg_ripper_${code}1.xml.gz")
        }
        // Guides a custom playlist names itself (not the big shared list)
        urls.addAll(guideCatalog.filter { !it.contains("epgshare01") }.take(8))
        if (urls.isEmpty()) return
        epg.load(urls, ids, names, ::downloadToFile) {
            runOnUiThread {
                adapter.notifyDataSetChanged()
                if (banner.visibility == View.VISIBLE) current?.let { showBanner(it) }
                scheduleSectionRefresh()
            }
        }
    }

    private fun downloadToFile(url: String, file: File) {
        val conn = open(url, timeoutMs = 60_000)
        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun showGuide(ch: Channel) {
        val progs = epg.upcoming(ch)
        val msg = if (progs.isEmpty()) {
            "No guide information for this channel yet.\n\nGuides download in the background for the countries you watch, " +
                "so check again in a minute. Not every free channel has a guide."
        } else {
            val t = System.currentTimeMillis()
            progs.joinToString("\n") { p ->
                val mark = if (p.start <= t && t < p.stop) "▶ " else "    "
                "$mark${hhmm(p.start)}   ${p.title}"
            }
        }
        val now = epg.now(ch)
        AlertDialog.Builder(this)
            .setTitle("📺 ${ch.name}")
            .setMessage(if (now != null && now.desc.isNotEmpty()) "$msg\n\nNow: ${now.title}\n${now.desc}" else msg)
            .setPositiveButton("Watch") { _, _ -> if (ch.url != current?.url) play(ch, auto = false) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------ channel options (long-press)

    private fun showChannelOptions(ch: Channel) {
        val inFavView = groupKeys.getOrNull(groupSpinner.selectedItemPosition) == KEY_FAV
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add((if (ch.url in store.favs) "☆ Remove from favourites" else "★ Add to favourites") to { toggleFav(ch) })
        actions.add("📺 TV guide" to { showGuide(ch) })
        actions.add("🔁 Find another source" to { findAnotherSource(ch) })
        actions.add("✎ Rename" to { renameChannel(ch) })
        if (inFavView && ch.url in store.favs) {
            actions.add("↑ Move up" to { store.moveFav(ch.url, -1); applyFilter() })
            actions.add("↓ Move down" to { store.moveFav(ch.url, 1); applyFilter() })
        }
        actions.add("🙈 Hide category \"${ch.group}\"" to {
            store.hiddenGroups.add(ch.group); store.saveHidden(); buildGroups(); applyFilter()
            toast("Hidden. Bring it back from ⋮ → Show / hide categories")
        })
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun renameChannel(ch: Channel) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply { setText(ch.name); setSingleLine(); setSelectAllOnFocus(true) }
        val box = FrameLayout(this).apply { setPadding(pad, pad, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Rename channel")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                store.rename(ch.url, name)
                ch.name = name
                adapter.refresh(ch.url)
                if (ch.url == current?.url) nowPlaying.text = name
            }
            .setNeutralButton("Reset name") { _, _ -> store.rename(ch.url, null); toast("Original name returns next time the list loads") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHideCategories() {
        val groups = channels.map { it.group }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (groups.isEmpty()) return
        val checked = BooleanArray(groups.size) { groups[it] !in store.hiddenGroups }
        AlertDialog.Builder(this)
            .setTitle("Show these categories")
            .setMultiChoiceItems(groups.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                store.hiddenGroups.removeAll(groups.toSet())
                groups.forEachIndexed { i, g -> if (!checked[i]) store.hiddenGroups.add(g) }
                store.saveHidden()
                buildGroups(); applyFilter()
            }
            .setNeutralButton("Show all") { _, _ ->
                store.hiddenGroups.clear(); store.saveHidden(); buildGroups(); applyFilter()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ parental lock

    private val adultWords = Regex("(?i)\\b(xxx|adult|18\\+|porn|erotic)")

    private fun isLockedGroup(ch: Channel) = store.pin != null && ch.group in store.lockedGroups
    private fun needsPin(ch: Channel) = isLockedGroup(ch) && !unlocked

    private fun askPin(title: String, onOk: () -> Unit) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        val box = FrameLayout(this).apply { setPadding(pad, pad, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == store.pin) onOk() else toast("Wrong PIN")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showParentalLock() {
        if (store.pin == null) { setNewPin(); return }
        askPin("Enter your PIN") {
            val actions = listOf<Pair<String, () -> Unit>>(
                "Choose locked categories" to { chooseLockedGroups() },
                "Change PIN" to { setNewPin() },
                "Turn parental lock off" to {
                    store.pin = null; store.lockedGroups.clear(); store.saveLocked(); unlocked = false
                    adapter.notifyDataSetChanged(); toast("Parental lock is off")
                },
                (if (unlocked) "Lock again now" else "Keep unlocked until the app closes") to {
                    unlocked = !unlocked; adapter.notifyDataSetChanged()
                },
            )
            AlertDialog.Builder(this)
                .setTitle("🔒 Parental lock")
                .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
                .show()
        }
    }

    private fun setNewPin() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4 to 8 digits"
        }
        val box = FrameLayout(this).apply { setPadding(pad, pad, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Choose a PIN")
            .setMessage("You'll need this PIN to watch locked categories or change the lock.")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                val pin = input.text.toString()
                if (pin.length !in 4..8) { toast("The PIN must be 4 to 8 digits"); return@setPositiveButton }
                store.pin = pin
                unlocked = false
                if (store.lockedGroups.isEmpty()) {
                    channels.map { it.group }.filter { adultWords.containsMatchIn(it) }.forEach { store.lockedGroups.add(it) }
                    store.saveLocked()
                }
                chooseLockedGroups()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseLockedGroups() {
        val groups = (channels.map { it.group } + store.lockedGroups).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (groups.isEmpty()) return
        val checked = BooleanArray(groups.size) { groups[it] in store.lockedGroups }
        AlertDialog.Builder(this)
            .setTitle("Lock these categories")
            .setMultiChoiceItems(groups.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                store.lockedGroups.clear()
                groups.forEachIndexed { i, g -> if (checked[i]) store.lockedGroups.add(g) }
                store.saveLocked()
                adapter.notifyDataSetChanged()
                toast("${store.lockedGroups.size} categories locked")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ backup / restore

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray()) }
            toast("Backup saved")
        } catch (e: Exception) {
            toast("Couldn't save the backup")
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val n = store.importJson(text)
            toast("Restored $n favourites and your settings")
            loadedSourceId = null
            setupSources(selectId = store.sourceId)
        } catch (e: Exception) {
            toast("That file isn't a CanoeTV backup")
        }
    }

    private fun showBackup() {
        AlertDialog.Builder(this)
            .setTitle("💾 Back up / restore")
            .setMessage("Saves your favourites (in order), renamed channels, hidden and locked categories and your own playlists to a file. Restore it on another phone or TV to copy everything across. Your PIN is not saved.")
            .setPositiveButton("Save backup") { _, _ ->
                try { exportLauncher.launch("CanoeTV-backup.json") } catch (e: ActivityNotFoundException) { noFilePicker() }
            }
            .setNeutralButton("Restore") { _, _ ->
                try { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) } catch (e: ActivityNotFoundException) { noFilePicker() }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun noFilePicker() = toast("This device has no file picker. Make backups on your phone instead.")

    // ------------------------------------------------------------------ multi-view

    private val multiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringExtra(MultiViewActivity.RESULT_URL)?.let { url -> byUrl[url]?.let { play(it, auto = false) } }
        }
    }

    private fun pickMultiView() {
        val pool = (store.favOrder.mapNotNull { byUrl[it] } + store.recents.mapNotNull { byUrl[it] })
            .distinctBy { it.url }.filter { !needsPin(it) }.take(30)
        if (pool.size < 2) { toast("Add at least 2 favourites (or watch a few channels) to use multi-view"); return }
        val checked = BooleanArray(pool.size) { it < 4 && pool[it].url in store.favs }
        AlertDialog.Builder(this)
            .setTitle("Pick up to 4 channels")
            .setMultiChoiceItems(pool.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Watch") { _, _ ->
                val chosen = pool.filterIndexed { i, _ -> checked[i] }.take(4)
                if (chosen.size < 2) { toast("Pick at least 2 channels"); return@setPositiveButton }
                player.pause()
                val intent = Intent(this, MultiViewActivity::class.java)
                    .putStringArrayListExtra(MultiViewActivity.EXTRA_NAMES, ArrayList(chosen.map { it.name }))
                    .putStringArrayListExtra(MultiViewActivity.EXTRA_URLS, ArrayList(chosen.map { it.url }))
                    .putStringArrayListExtra(MultiViewActivity.EXTRA_UAS, ArrayList(chosen.map { it.userAgent ?: "" }))
                    .putStringArrayListExtra(MultiViewActivity.EXTRA_REFS, ArrayList(chosen.map { it.referrer ?: "" }))
                multiLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ sleep timer

    private var sleepAt = 0L
    private val sleepRunnable = Runnable {
        sleepAt = 0L
        player.pause()
        showMessage("Sleep timer: playback stopped.\nPress play or pick a channel to keep watching.", error = false)
    }

    private fun showSleepTimer() {
        val labels = arrayOf("Off", "15 minutes", "30 minutes", "1 hour", "1½ hours", "2 hours")
        val minutes = intArrayOf(0, 15, 30, 60, 90, 120)
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(labels) { _, i ->
                handler.removeCallbacks(sleepRunnable)
                if (minutes[i] == 0) {
                    sleepAt = 0L
                    toast("Sleep timer off")
                } else {
                    sleepAt = System.currentTimeMillis() + minutes[i] * 60_000L
                    handler.postDelayed(sleepRunnable, minutes[i] * 60_000L)
                    toast("Playback will stop in ${labels[i]}")
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ sources & loading

    private fun setupSources(selectId: String) {
        sources = Sources.all() + store.customSources()
        val src = sources.firstOrNull { it.id == selectId } ?: sources.first()
        if (loadedSourceId != src.id) load(src)
        updateSourceButton()
    }

    // ---- Channel-list picker (organised in sections)

    private fun sourceLabel(src: Source) = (if (src.custom) "📁 " else "") + src.name

    private fun updateSourceButton() {
        val src = sources.firstOrNull { it.id == loadedSourceId }
        sourceBtn.text = "📺  " + (src?.let { sourceLabel(it) } ?: "Choose a channel list") + "   ▾"
    }

    private fun showListPicker() {
        val entries = ArrayList<Any>()
        for (section in Sources.sectionOrder) {
            val inSection = sources.filter { it.section == section }
            if (inSection.isEmpty()) continue
            entries.add(section)
            entries.addAll(inSection)
        }
        entries.add("More")
        entries.add("+ Add your own playlist (M3U link)")
        val dens = resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()

        val listAdapter = object : android.widget.BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (isHeader(position)) 0 else 1
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = !isHeader(position)
            fun isHeader(position: Int): Boolean {
                val e = entries[position]
                return e is String && !e.startsWith("+")
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity)
                val e = entries[position]
                if (isHeader(position)) {
                    tv.text = (e as String).uppercase()
                    tv.textSize = 12f
                    tv.letterSpacing = 0.1f
                    tv.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    tv.setPadding(dp(20), dp(16), dp(20), dp(6))
                    tv.background = null
                } else {
                    val selected = e is Source && e.id == loadedSourceId
                    tv.text = when (e) {
                        is Source -> (if (selected) "✓  " else "     ") + sourceLabel(e)
                        else -> "     $e"
                    }
                    tv.textSize = 16f
                    tv.setTextColor(if (selected) ContextCompat.getColor(this@MainActivity, R.color.accent) else 0xFFFFFFFF.toInt())
                    tv.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    tv.setPadding(dp(20), dp(12), dp(20), dp(12))
                    tv.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.row_bg)
                }
                return tv
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Channel lists")
            .setAdapter(listAdapter) { _, i ->
                val e = entries[i]
                if (e is Source) chooseSource(e)
                else if (e is String && e.startsWith("+")) showAddDialog()
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
        // Start the list at the current choice
        val at = entries.indexOfFirst { it is Source && it.id == loadedSourceId }
        if (at > 0) dialog.listView?.setSelection(at)
    }

    private fun chooseSource(src: Source) {
        if (src.id == loadedSourceId) return
        current = null
        setupSources(selectId = src.id)
        toast("Loading ${src.name}…")
    }

    // ---- My apps

    override fun appIcon(app: AppEntry) = MyApps.icon(this, app)

    override fun openApp(app: AppEntry) {
        try {
            MyApps.open(this, app, isTv())?.let { toast(it) }
        } catch (e: Exception) {
            toast("Couldn't open ${app.name}")
        }
    }

    private fun myAppsInOrder(): List<AppEntry> =
        MyApps.all.sortedByDescending { MyApps.installed(this, it) != null }

    private fun load(src: Source) {
        val token = ++loadToken
        loadedSourceId = src.id
        checkGen++ // stop any background check of the old list
        status.text = "Loading…"
        if (current == null) showMessage("Loading ${src.name}…", error = false)
        io.execute {
            val all = LinkedHashMap<String, Channel>()
            val guides = ArrayList<String>()
            var failed = 0
            for (u in src.urls) {
                val text = if (u == Sources.TT_BUILTIN) Sources.ttBuiltin else try {
                    download(u).also { cacheFile(u).writeText(it) }
                } catch (e: Exception) {
                    failed++
                    cacheFile(u).takeIf { it.exists() }?.readText()
                }
                if (text != null) {
                    guides.addAll(M3u.guideUrls(text))
                    for (c in M3u.parse(text)) if (c.url !in all) all[c.url] = c
                }
            }
            val everything = all.values.toList()
            val inCountry = src.country?.let { cc -> everything.filter { it.country == cc } } ?: everything
            val result = if (store.englishOnly) inCountry.filter { Lang.isEnglish(it) } else inCountry
            val hiddenForeign = everything.size - result.size
            result.forEachIndexed { i, c ->
                c.number = i + 1
                store.renamed(c.url)?.let { c.name = it }
            }
            runOnUiThread {
                if (token != loadToken) return@runOnUiThread
                guideGroupsFor = null
                guideCatalog.clear()
                guideCatalog.addAll(guides)
                updateSourceButton()
                if (hiddenForeign > 0) status.text = "${result.size} channels · $hiddenForeign other-language channels hidden"
                channels = result
                byUrl = result.associateBy { it.url }
                store.sourceId = src.id
                buildGroups()
                applyFilter()
                refreshGuide()
                when {
                    result.isEmpty() -> showMessage("Couldn't load this playlist. Check your internet connection and try again.", error = true)
                    failed > 0 -> toast("Some of the playlist couldn't be downloaded — showing what loaded.")
                }
                if (result.isNotEmpty() && current == null) showMessage("Pick a channel to start watching", error = false)
                refreshSections()
                playPending()
                if (!tvRowPublished && result.isNotEmpty()) { tvRowPublished = true; publishTvRow() }
                loadMovieChannels()
            }
        }
    }

    /** Opens a URL, following redirects (including http↔https), and returns the connection. */
    private fun open(url: String, ua: String = UA, referrer: String? = null, timeoutMs: Int = 20_000): HttpURLConnection {
        var target = URL(url)
        repeat(6) {
            val conn = target.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", ua)
            if (referrer != null) conn.setRequestProperty("Referer", referrer)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw Exception("Bad redirect")
                target = URL(target, loc)
                return@repeat
            }
            return conn
        }
        throw Exception("Too many redirects")
    }

    private fun download(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
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
                val removingLoaded = loadedSourceId == custom[i].id
                val keep = if (removingLoaded) "all" else (loadedSourceId ?: "all")
                if (removingLoaded) loadedSourceId = null
                setupSources(selectId = keep)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ list & filtering

    private fun buildGroups() {
        val prev = groupKeys.getOrNull(groupSpinner.selectedItemPosition)
        val visible = channels.filter { it.group !in store.hiddenGroups }
        val counts = visible.groupingBy { it.group }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val favCount = channels.count { it.url in store.favs }
        groupKeys = listOf(KEY_ALL, KEY_FAV, KEY_RECENT) + counts.keys
        val labels = listOf("All channels (${visible.size})", "★ Favourites ($favCount)", "⟲ Recently watched") +
            counts.map { (g, n) -> "$g ($n)" }
        groupSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, labels).also {
            it.setDropDownViewResource(R.layout.spinner_item)
        }
        val i = groupKeys.indexOf(prev)
        groupSpinner.setSelection(if (i >= 0) i else 0, false)
    }

    private fun applyFilter() {
        if (!::adapter.isInitialized) return
        val key = groupKeys.getOrNull(groupSpinner.selectedItemPosition) ?: KEY_ALL
        var base = when (key) {
            KEY_ALL -> channels.filter { it.group !in store.hiddenGroups }
            KEY_FAV -> store.favOrder.mapNotNull { byUrl[it] }
            KEY_RECENT -> store.recents.mapNotNull { byUrl[it] }
            else -> channels.filter { it.group == key }
        }
        val q = search.text.toString().trim()
        if (q.isNotEmpty()) base = base.filter { it.name.contains(q, ignoreCase = true) }
        if (store.hideDead) base = base.filter { store.health(it.url) != false }
        filtered = base
        adapter.submit(filtered, current?.url)
        status.text = "${filtered.size} channels"
        // Quietly test whatever is now on screen
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, 2_000)
    }

    override fun toggleFav(ch: Channel) {
        if (!store.favs.remove(ch.url)) store.favs.add(ch.url)
        store.saveFavs()
        toast(if (ch.url in store.favs) "Added ${ch.name} to favourites" else "Removed from favourites")
        val key = groupKeys.getOrNull(groupSpinner.selectedItemPosition)
        buildGroups()
        if (key == KEY_FAV) applyFilter() else adapter.refresh(ch.url)
        homeDirty = true
        publishTvRow()
    }

    // ------------------------------------------------------------------ background channel check

    @Volatile private var checkGen = 0
    private val checkPool = Executors.newFixedThreadPool(3, lowPriority("check"))

    /** True while video is playing: background work waits so it doesn't cause buffering. */
    @Volatile private var videoBusy = false
    private val checkRunnable = Runnable { startBackgroundCheck() }

    /** Tests the channels in the current list that haven't been tested in the last 24h. */
    private fun startBackgroundCheck() {
        if (videoBusy) return // tested later, when nothing is playing
        val gen = ++checkGen
        val todo = filtered.filter { it.url.startsWith("http") && store.health(it.url) == null && it.url != current?.url }.take(400)
        if (todo.isEmpty()) return
        val total = todo.size
        val done = AtomicInteger()
        val working = AtomicInteger()
        val pending = ConcurrentHashMap<String, Boolean>()
        for (c in todo) checkPool.execute {
            if (gen != checkGen || videoBusy) return@execute
            val ok = probe(c)
            pending[c.url] = ok
            if (ok) working.incrementAndGet()
            val n = done.incrementAndGet()
            if (n % 20 == 0 || n == total) runOnUiThread {
                val batch = HashMap(pending)
                batch.keys.forEach { pending.remove(it) }
                store.setHealthBatch(batch)
                if (gen != checkGen) return@runOnUiThread
                if (n == total) {
                    status.text = "${working.get()} of $total checked work"
                    if (store.hideDead) {
                        // Re-filter without starting another check straight away
                        applyFilter()
                        handler.removeCallbacks(checkRunnable)
                    } else adapter.notifyDataSetChanged()
                } else {
                    status.text = "Checking channels… $n/$total"
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    /** Quick test: does the stream answer with something playable? Reads at most a few KB. */
    private fun probe(c: Channel): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(c.url, c.userAgent ?: UA, c.referrer, timeoutMs = 7_000)
            if (conn.responseCode !in 200..299) return false
            val buf = ByteArray(2048)
            val n = conn.inputStream.read(buf)
            if (n <= 0) return false
            val head = String(buf, 0, n, Charsets.UTF_8)
            when {
                head.contains("#EXTM3U") -> true
                head.trimStart().startsWith("<") -> false // a web page, not video
                else -> true
            }
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------ playback

    private fun play(ch: Channel, auto: Boolean) {
        if (opensElsewhere(ch)) { if (!auto) openElsewhere(ch); return }
        if (needsPin(ch)) {
            if (auto) return
            askPin("Enter your PIN to watch ${ch.name}") { unlocked = true; adapter.notifyDataSetChanged(); play(ch, auto = false) }
            return
        }
        handler.removeCallbacks(skipRunnable)
        if (!auto) skipCount = 0
        val prev = current
        if (!auto && prev != null && prev.url != ch.url) previous = prev
        current = ch
        nowPlaying.text = ch.name
        store.lastUrl = ch.url
        store.addRecent(ch.url)
        adapter.setPlaying(ch.url)
        overlay.visibility = View.GONE
        loading.visibility = View.VISIBLE
        playedOk = false
        reconnects = 0
        rebuffers.clear()
        findViewById<View>(R.id.slowBar).visibility = View.GONE
        statStalls = 0
        statDropped = 0
        showBanner(ch)
        startSource(ch)
        refreshGuide()

        val pos = filtered.indexOfFirst { it.url == ch.url }
        if (pos >= 0) list.scrollToPosition(pos)
    }

    /** Loads the channel's stream into the player and starts it at the live edge. */
    private fun startSource(ch: Channel) {
        if (ch.url != soundOffFor) {
            soundOffFor = null
            if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
            }
        }
        bufferingSince = 0L
        reconnectPending = false
        applyQuality()
        val offset = if (store.playbackMode == "standard" && !autoLowered) 20_000L else 45_000L
        if (ch.url.startsWith("dailymotion:")) { startDailymotion(ch, offset); return }
        player.setMediaSource(Streams.source(ch, offset))
        player.prepare()
        player.playWhenReady = true
    }

    // ---- Channel surfing over full-screen video

    private lateinit var surfAdapter: ChannelAdapter

    private fun setupSurf() {
        val list = findViewById<RecyclerView>(R.id.surfList)
        surfAdapter = ChannelAdapter(
            store,
            nowShowing = { ch -> epg.now(ch)?.let { "Now: ${it.title}" } },
            isLocked = { ch -> isLockedGroup(ch) },
            onClick = { ch -> hideSurf(); play(ch, auto = false) },
            onToggleFav = { ch -> toggleFav(ch) },
            onOptions = { ch -> showChannelOptions(ch) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = surfAdapter
    }

    private fun surfVisible() = findViewById<View>(R.id.surfPanel).visibility == View.VISIBLE

    private fun showSurf() {
        if (filtered.isEmpty()) return
        val list = findViewById<RecyclerView>(R.id.surfList)
        findViewById<TextView>(R.id.surfTitle).text =
            "📺 " + (sources.firstOrNull { it.id == loadedSourceId }?.name?.substringBefore(" —") ?: "Channels")
        surfAdapter.submit(filtered, current?.url)
        findViewById<View>(R.id.surfPanel).visibility = View.VISIBLE
        playerView.hideController()
        val pos = filtered.indexOfFirst { it.url == current?.url }.coerceAtLeast(0)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 200)
        list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() ?: list.getChildAt(0)?.requestFocus() }
        updateBack()
    }

    private fun hideSurf() {
        findViewById<View>(R.id.surfPanel).visibility = View.GONE
        playerView.requestFocus()
        updateBack()
    }

    // ---- Reminders

    private val reminderTick = object : Runnable {
        override fun run() {
            checkReminders()
            handler.postDelayed(this, 30_000)
        }
    }

    private fun addReminder(ch: Channel, prog: Epg.Prog) {
        store.addReminder(ch, prog.title, prog.start)
        toast("⏰ Reminder set: ${prog.title} at ${hhmm(prog.start)}")
    }

    private fun checkReminders() {
        val due = store.dueReminders(System.currentTimeMillis()) ?: return
        val ch = byUrl[due.url] ?: Channel(due.name, due.url, due.logo, due.group, null, null)
        AlertDialog.Builder(this)
            .setTitle("⏰ Starting now")
            .setMessage("${due.title}\non ${due.name}")
            .setPositiveButton("Watch") { _, _ -> watch(ch) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showReminders() {
        val list = store.reminders()
        if (list.isEmpty()) {
            toast("No reminders. In the guide, pick a show that hasn't started and choose Remind me.")
            return
        }
        val labels = list.map { r -> "${hhmm(r.start)}  ${r.title}  —  ${r.name}" }
        AlertDialog.Builder(this)
            .setTitle("Reminders (tap one to delete it)")
            .setItems(labels.toTypedArray()) { _, i ->
                store.removeReminder(list[i])
                toast("Reminder deleted")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---- Subtitles, audio tracks and picture fit

    private fun trackLabel(f: androidx.media3.common.Format, n: Int): String {
        val lang = f.language?.let { java.util.Locale(it).displayLanguage }?.takeIf { it.isNotBlank() && it != "und" }
        return listOfNotNull(f.label, lang).distinct().joinToString(" · ").ifEmpty { "Track $n" }
    }

    private fun showTrackPicker(type: Int) {
        val groups = player.currentTracks.groups.filter { it.type == type }
        val options = ArrayList<Pair<String, (() -> Unit)>>()
        if (type == C.TRACK_TYPE_TEXT) {
            options.add("Off" to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
        }
        var n = 0
        for (g in groups) for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            n++
            val label = trackLabel(g.getTrackFormat(i), n) + if (g.isTrackSelected(i)) "  ✓" else ""
            options.add(label to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
            })
        }
        val what = if (type == C.TRACK_TYPE_TEXT) "subtitles" else "other audio tracks"
        if (n == 0) { toast("This channel doesn't offer $what"); return }
        AlertDialog.Builder(this)
            .setTitle(if (type == C.TRACK_TYPE_TEXT) "💬 Subtitles" else "🔊 Audio track")
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun applyPictureMode() {
        playerView.resizeMode = when (store.pictureMode) {
            "fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun cyclePictureMode() {
        store.pictureMode = when (store.pictureMode) { "fit" -> "fill"; "fill" -> "zoom"; else -> "fit" }
        applyPictureMode()
        toast(when (store.pictureMode) {
            "fill" -> "Picture: Fill screen (stretches to remove black bars)"
            "zoom" -> "Picture: Zoom (fills the screen, trims the edges)"
            else -> "Picture: Fit (whole picture, may show black bars)"
        })
    }

    // ---- Voice search

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            if (section != Section.SEARCH) showSection(Section.SEARCH)
            searchBox.setText(text)
            searchBox.setSelection(text.length)
        }
    }

    private fun startVoiceSearch() {
        if (section != Section.SEARCH) showSection(Section.SEARCH)
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a channel, show or movie")
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            toast("Voice search isn't available on this device — use the keyboard's mic instead")
        }
    }

    // ---- channels that live in another app, or need their stream looked up first

    private fun opensElsewhere(ch: Channel) = ch.url.startsWith("app:") || ch.url.startsWith("ytsearch:")

    private fun openElsewhere(ch: Channel) {
        try {
            if (ch.url.startsWith("app:")) {
                val pkg = ch.url.removePrefix("app:")
                val app = MyApps.all.firstOrNull { pkg in it.packages } ?: AppEntry(ch.name, 0, pkg, pkg)
                MyApps.open(this, app, isTv())?.let { toast(it) }
            } else {
                val q = Uri.encode(ch.url.removePrefix("ytsearch:"))
                val web = Uri.parse("https://www.youtube.com/results?search_query=$q")
                val yt = Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Prefer the YouTube app (TV or phone); otherwise any browser
                val pkg = listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube")
                    .firstOrNull { p -> try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false } }
                if (pkg != null) yt.setPackage(pkg)
                startActivity(yt)
            }
        } catch (e: Exception) {
            toast("Couldn't open ${ch.name}")
        }
    }

    private val dmCache = HashMap<String, Pair<String, Long>>()

    /** CNC3 streams live on Dailymotion: look up the current stream address, then play it. */
    private fun startDailymotion(ch: Channel, offset: Long) {
        val id = ch.url.removePrefix("dailymotion:")
        val cached = dmCache[id]?.takeIf { System.currentTimeMillis() - it.second < 20 * 60_000L }?.first
        if (cached != null) {
            player.setMediaSource(Streams.source(cached, ch.userAgent, "https://www.dailymotion.com/", offset))
            player.prepare(); player.playWhenReady = true
            return
        }
        moviePool.execute {
            val url = try {
                val meta = JSONObject(download("https://www.dailymotion.com/player/metadata/video/$id"))
                meta.optJSONObject("qualities")?.optJSONArray("auto")?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (current?.url != ch.url) return@runOnUiThread
                if (url == null) {
                    showMessage("${ch.name} isn't streaming right now. Try again later, or open the CNC3 app.", error = true)
                    if (section == Section.GUIDE) showPreviewMsg("CNC3 isn't streaming right now")
                    loading.visibility = View.GONE
                    return@runOnUiThread
                }
                dmCache[id] = url to System.currentTimeMillis()
                player.setMediaSource(Streams.source(url, ch.userAgent, "https://www.dailymotion.com/", offset))
                player.prepare(); player.playWhenReady = true
            }
        }
    }

    private fun lastChannel() {
        val p = previous
        if (p == null) { toast("No previous channel yet"); return }
        if (section == Section.LIVE) play(p, auto = false) else watch(p)
    }

    private fun step(delta: Int, auto: Boolean = false) {
        if (filtered.isEmpty()) return
        val size = filtered.size
        var pos = filtered.indexOfFirst { it.url == current?.url }
        for (i in 0 until size) {
            pos = if (pos < 0) 0 else (pos + delta + size) % size
            // When skipping automatically, jump over channels already known to be dead
            if (!auto || (store.health(filtered[pos].url) != false && !needsPin(filtered[pos]))) break
        }
        play(filtered[pos], auto)
    }

    // ------------------------------------------------------------------ channel banner & number entry

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private fun showBanner(ch: Channel) {
        if (inPip) return
        bannerLogo.visibility = View.VISIBLE
        if (ch.logo.isNotEmpty()) {
            bannerLogo.load(ch.logo) {
                placeholder(R.drawable.logo_placeholder)
                error(R.drawable.logo_placeholder)
            }
        } else bannerLogo.setImageResource(R.drawable.logo_placeholder)
        bannerTitle.text = "${ch.number}   ${ch.name}"
        val now = epg.now(ch)
        val next = epg.next(ch)
        bannerSub.text = when {
            now != null && next != null -> "Now: ${now.title}   ·   ${hhmm(next.start)} ${next.title}"
            now != null -> "Now: ${now.title}"
            else -> ch.group
        }
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 4_000)
    }

    private var typedNumber = ""
    private val commitNumber = Runnable {
        val n = typedNumber.toIntOrNull()
        typedNumber = ""
        val ch = n?.let { channels.getOrNull(it - 1) }
        if (ch != null) watch(ch)
        else { banner.visibility = View.GONE; toast("There's no channel $n in this list") }
    }

    private fun typeDigit(d: Int) {
        if (typedNumber.isEmpty() && d == 0) { lastChannel(); return }
        if (typedNumber.length >= 5) return
        typedNumber += d
        bannerLogo.visibility = View.GONE
        bannerTitle.text = "Channel  ${typedNumber}_"
        bannerSub.text = channels.getOrNull(typedNumber.toInt() - 1)?.name ?: ""
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.removeCallbacks(commitNumber)
        handler.postDelayed(commitNumber, 1_800)
    }

    // ------------------------------------------------------------------ auto-reconnect

    private var playedOk = false        // current channel has played at least once
    private var reconnects = 0          // reconnect attempts since it last played
    private var bufferingSince = 0L     // when the current stall started (0 = not stalled)
    private val maxReconnects = 6
    private var reconnectPending = false
    private var soundOffFor: String? = null
    private var returnTo: Section? = null

    // ---- connection info box
    private var statDropped = 0
    private var statStalls = 0
    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
        }
    }

    private fun updateStats() {
        val v = findViewById<TextView>(R.id.stats)
        handler.removeCallbacks(statsTick)
        if (!store.showStats) { v.visibility = View.GONE; return }
        val f = player.videoFormat
        val speed = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).bitrateEstimate
        fun mbps(bits: Long) = String.format(java.util.Locale.US, "%.1f Mbps", bits / 1_000_000.0)
        val picture = if (f != null && f.width > 0) "${f.width}×${f.height}" + (if (f.bitrate > 0) " · ${mbps(f.bitrate.toLong())}" else "") else "—"
        v.text = "Internet (measured)  ${mbps(speed)}\n" +
            "Picture              $picture\n" +
            "Loaded ahead         ${player.totalBufferedDuration / 1000} s\n" +
            "Stalls               $statStalls\n" +
            "Dropped frames       $statDropped"
        v.visibility = View.VISIBLE
        handler.postDelayed(statsTick, 1_000)
    }

    // ---- buffering & quality
    private var autoLowered = false            // quality lowered for this session after repeated buffering
    private val rebuffers = ArrayList<Long>()

    private fun playbackModeLabel() = when (store.playbackMode) {
        "smooth" -> "Smooth — max 720p, plays 45 s behind live for fewer pauses (tap to change)"
        "saver" -> "Data saver — max 480p, best on slow internet or a VPN (tap to change)"
        else -> "Standard — best picture, plays 20 s behind live (tap to change)"
    }

    /** Caps the picture size for Smooth / Data saver, or after repeated buffering. */
    private fun applyQuality() {
        val b = player.trackSelectionParameters.buildUpon()
        when {
            store.playbackMode == "saver" -> b.setMaxVideoSize(854, 480)
            store.playbackMode == "smooth" || autoLowered -> b.setMaxVideoSize(1280, 720)
            else -> b.clearVideoSizeConstraints()
        }
        player.trackSelectionParameters = b.build()
    }

    /** Three stalls within three minutes: lower the quality for the rest of this session. */
    private fun noteRebuffer() {
        val now = System.currentTimeMillis()
        rebuffers.add(now)
        rebuffers.removeAll { now - it > 180_000L }
        if (rebuffers.size >= 3 && store.playbackMode == "standard" && !autoLowered) {
            autoLowered = true
            rebuffers.clear()
            applyQuality()
            toast("Buffering a lot — lowered the picture to 720p to keep it smooth")
        }
        checkSlowServer()
    }  // channel playing without sound after a sound error

    // ---- Slow-server warning and "Find another source"

    private var slowWarnedFor: String? = null
    private val hideSlowBar = Runnable { findViewById<View>(R.id.slowBar).visibility = View.GONE }

    /** After a stall: is the channel's server the bottleneck (and there's no lower quality to drop to)? */
    private fun checkSlowServer() {
        val ch = current ?: return
        if (slowWarnedFor == ch.url || rebuffers.size < 2) return
        val bitrate = player.videoFormat?.bitrate ?: return
        if (bitrate <= 0) return
        val speed = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).bitrateEstimate
        if (speed > bitrate * 1.15) return // video is arriving fast enough; not a slow server
        val lowerQuality = player.currentTracks.groups.any { g ->
            g.type == C.TRACK_TYPE_VIDEO && (0 until g.length).any { i ->
                val br = g.getTrackFormat(i).bitrate
                br in 1 until (bitrate * 0.8).toInt()
            }
        }
        if (lowerQuality && !autoLowered) return // the app can still step down the quality
        slowWarnedFor = ch.url
        val bar = findViewById<TextView>(R.id.slowBar)
        fun mbps(b: Long) = String.format(java.util.Locale.US, "%.1f", b / 1_000_000.0)
        bar.text = "⚠  ${ch.name}'s server is slow — it sends ${mbps(speed)} Mbps but needs ${mbps(bitrate.toLong())}. " +
            "Your internet is fine.   Find another source ▸"
        bar.visibility = View.VISIBLE
        bar.setOnClickListener { bar.visibility = View.GONE; findAnotherSource(ch) }
        if (fullscreen || isTv()) bar.requestFocus()
        handler.removeCallbacks(hideSlowBar)
        handler.postDelayed(hideSlowBar, 20_000)
    }

    private fun altKey(name: String) = Epg.nameKey(name).removePrefix("name:")

    /** Looks through every built-in list (and yours) for the same channel from a different stream. */
    private fun findAnotherSource(ch: Channel) {
        val key = altKey(ch.name)
        if (key.length < 3) { toast("This channel's name is too short to search for"); return }
        toast("Looking for other sources of ${ch.name}…")
        // Label each list link with a service name rather than a mix name
        val labels = LinkedHashMap<String, String>()
        (Sources.all().filter { it.section != Sources.START } + store.customSources() + Sources.all())
            .forEach { src -> src.urls.forEach { u -> if (u !in labels) labels[u] = src.name } }
        io.execute {
            val found = LinkedHashMap<String, Pair<Channel, String>>()
            for ((url, label) in labels) {
                val f = cacheFile(url)
                val text = try {
                    if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) f.readText()
                    else download(url).also { f.writeText(it) }
                } catch (e: Exception) { null } ?: continue
                for (c in M3u.parse(text)) {
                    if (c.url == ch.url || c.url in found) continue
                    val k = altKey(c.name)
                    val match = k == key || (k.length >= 4 && key.length >= 4 && (k.startsWith(key) || key.startsWith(k)))
                    if (!match) continue
                    if (store.englishOnly && !Lang.isEnglish(c)) continue
                    if (store.health(c.url) == false) continue
                    found[c.url] = c to label
                }
                if (found.size >= 25) break
            }
            runOnUiThread { showAlternatives(ch, found.values.toList()) }
        }
    }

    private fun showAlternatives(ch: Channel, alts: List<Pair<Channel, String>>) {
        if (isFinishing) return
        if (alts.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No other sources found")
                .setMessage("None of your channel lists has another stream of ${ch.name}. Try a similar channel instead.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        // Streams already known to work come first
        val sorted = alts.sortedByDescending { store.health(it.first.url) == true }
        val labels = sorted.map { (c, from) -> (if (store.health(c.url) == true) "✓ " else "") + "${c.name}  —  $from" }
        AlertDialog.Builder(this)
            .setTitle("Other sources for ${ch.name}")
            .setItems(labels.toTypedArray()) { _, i ->
                val alt = sorted[i].first
                slowWarnedFor = null
                watch(alt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A live stream that stopped, stalled or dropped: reload it at the live edge. */
    private fun reconnect(why: String) {
        val ch = current ?: return
        if (reconnects >= maxReconnects || reconnectPending) return
        reconnectPending = true
        reconnects++
        handler.removeCallbacks(skipRunnable)
        loading.visibility = View.VISIBLE
        status.text = "Reconnecting… ($why)"
        handler.postDelayed({ if (current?.url == ch.url) startSource(ch) }, 1000L * reconnects)
    }

    /** Every few seconds, check for a stream that is stuck loading or silently stopped. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (current != null && player.playWhenReady) {
                val now = System.currentTimeMillis()
                when (player.playbackState) {
                    Player.STATE_BUFFERING ->
                        if (playedOk && bufferingSince > 0 && now - bufferingSince > 15_000) reconnect("stalled")
                    Player.STATE_ENDED -> if (playedOk) reconnect("stream ended")
                }
            }
            handler.postDelayed(this, 5_000)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            bufferingSince = if (state == Player.STATE_BUFFERING) System.currentTimeMillis() else 0L
            if (state == Player.STATE_BUFFERING && playedOk) { noteRebuffer(); statStalls++ }
            when (state) {
                Player.STATE_READY -> {
                    overlay.visibility = View.GONE
                    if (reconnects > 0) status.text = "${filtered.size} channels"
                    playedOk = true
                    reconnects = 0
                    current?.let {
                        if (store.health(it.url) != true) { store.setHealth(it.url, true); adapter.refresh(it.url) }
                    }
                }
                // Live channels should never "end" — if one does, the server hiccuped: pick it back up
                Player.STATE_ENDED -> if (playedOk && player.playWhenReady) reconnect("stream ended")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            videoBusy = isPlaying
            if (isPlaying) checkGen++ // stop any channel testing that's running
            else { handler.removeCallbacks(checkRunnable); handler.postDelayed(checkRunnable, 20_000) }
            if (isPlaying) { overlay.visibility = View.GONE; if (section == Section.GUIDE) showPreviewMsg(null) }
        }

        override fun onPlayerError(error: PlaybackException) {
            loading.visibility = View.GONE
            val ch = current ?: return
            // A channel that was already playing just dropped out: reconnect instead of giving up
            val audioProblem = error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
            if (audioProblem) {
                // The TV's sound output refused to start — the channel itself is fine. Try again,
                // and as a last resort play the picture without sound rather than skipping the channel.
                if (reconnects < 3) { reconnect("starting sound"); return }
                if (!player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    toast("This TV couldn't start the sound for ${ch.name} — playing without sound")
                    soundOffFor = ch.url
                    reconnects = 0
                    startSource(ch)
                    return
                }
            }
            val transient = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || playedOk
            if (transient && reconnects < maxReconnects) {
                reconnect("connection dropped")
                return
            }
            store.setHealth(ch.url, false)
            adapter.refresh(ch.url)
            if (section == Section.GUIDE) {
                showPreviewMsg("This channel is offline or blocked right now")
                return
            }
            val willSkip = store.autoSkip && skipCount < 15 && filtered.size > 1
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

    private fun reason(e: PlaybackException): String = explain(e) + "\n(code: ${e.errorCodeName})"

    private fun explain(e: PlaybackException): String {
        // Walk the cause chain for the real underlying problem
        var c: Throwable? = e.cause
        while (c != null) {
            when (c) {
                is HttpDataSource.InvalidResponseCodeException -> return when (c.responseCode) {
                    401, 403, 451 -> "Blocked in your region, or the server blocks VPNs (HTTP ${c.responseCode}). If you're on a VPN, try a server in the channel's own country."
                    404, 410 -> "This channel is offline — the stream no longer exists (HTTP ${c.responseCode})."
                    in 500..599 -> "The channel's server is having problems right now (HTTP ${c.responseCode})."
                    else -> "The server refused the stream (HTTP ${c.responseCode})."
                }
                is HttpDataSource.InvalidContentTypeException ->
                    return "The server sent a web page instead of video. This usually means a region/VPN block or the channel has moved."
                is java.net.UnknownHostException ->
                    return "The channel's server address doesn't exist anymore, or your VPN's DNS can't find it."
                is java.net.SocketTimeoutException ->
                    return "The server took too long to answer. It may be offline, or your VPN server is too slow — try another VPN location."
                is java.net.ConnectException ->
                    return "Couldn't connect to the channel's server. It's probably offline."
                is javax.net.ssl.SSLException ->
                    return "The secure connection to the server failed. Try turning the VPN off and on, or another VPN location."
            }
            c = c.cause
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the channel's server — it may be offline."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This stream's format isn't supported on this device."
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "The stream fell behind. Pick the channel again."
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "This TV couldn't start the sound for this channel. Try it again in a moment."
            else -> "The stream couldn't be played — most likely it's offline."
        }
    }

    private fun showMessage(msg: String, error: Boolean) {
        overlay.text = msg
        overlay.setTextColor(ContextCompat.getColor(this, if (error) R.color.danger else R.color.muted))
        overlay.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ updates

    private fun checkForUpdates(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual && now - store.lastUpdateCheck < 12 * 3600_000L) return
        store.lastUpdateCheck = now
        if (manual) toast("Checking for updates…")
        Thread {
            try {
                val json = JSONObject(download("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest"))
                val tag = json.getString("tag_name")
                val latest = tag.substringAfterLast('.').toIntOrNull() ?: 0
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) apkUrl = a.optString("browser_download_url")
                }
                runOnUiThread {
                    if (latest > BuildConfig.VERSION_CODE && apkUrl != null) showUpdateDialog(tag.removePrefix("v"), apkUrl)
                    else if (manual) toast("You have the newest version (${BuildConfig.VERSION_NAME})")
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread { toast("Couldn't check for updates. Try again later.") }
            }
        }.start()
    }

    private fun showUpdateDialog(version: String, apkUrl: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("CanoeTV $version is ready (you have ${BuildConfig.VERSION_NAME}).\n\nInstall it now? Your favourites and settings are kept.")
            .setPositiveButton("Install") { _, _ -> downloadAndInstall(apkUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(apkUrl: String) {
        toast("Downloading update…")
        Thread {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "CanoeTV.apk")
                val conn = open(apkUrl, timeoutMs = 60_000)
                try {
                    if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
                runOnUiThread { installApk(file) }
            } catch (e: Exception) {
                runOnUiThread { toast("Update download failed. Try again later.") }
            }
        }.start()
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            toast("Couldn't open the installer.")
        }
    }

    // ------------------------------------------------------------------ layout, fullscreen & picture-in-picture

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        updateBack()
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
        applyShell()
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        when {
            fullscreen || inPip -> {
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

    // Leaving the app while a channel plays (phones/tablets): keep watching in a small floating window
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isTv() && section == Section.LIVE && player.isPlaying) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) { /* device doesn't support it */ }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        playerView.useController = !inPip
        if (inPip) { banner.visibility = View.GONE; overlay.visibility = View.GONE }
        applyLayout()
    }

    // ------------------------------------------------------------------ TV remote

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val typing = currentFocus is EditText
            val code = event.keyCode
            when {
                code == KeyEvent.KEYCODE_CHANNEL_UP && section == Section.LIVE -> { step(-1); return true }
                code == KeyEvent.KEYCODE_CHANNEL_DOWN && section == Section.LIVE -> { step(1); return true }
                code == KeyEvent.KEYCODE_LAST_CHANNEL -> { lastChannel(); return true }
                code == KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
                !typing && code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    typeDigit(code - KeyEvent.KEYCODE_0); return true
                }
                !typing && code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    typeDigit(code - KeyEvent.KEYCODE_NUMPAD_0); return true
                }
                code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST -> { startVoiceSearch(); return true }
                code == KeyEvent.KEYCODE_DPAD_LEFT && fullscreen && !surfVisible() && !playerView.isControllerFullyVisible -> {
                    showSurf(); return true
                }
                (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) &&
                    fullscreen && !surfVisible() && !playerView.isControllerFullyVisible -> {
                    step(if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 5_000)
        handler.removeCallbacks(minuteTick)
        handler.postDelayed(minuteTick, 60_000)
        handler.removeCallbacks(reminderTick)
        handler.postDelayed(reminderTick, 5_000)
        updateStats()
        // Coming back to the app: jump to the live edge instead of resuming stale video
        if (section == Section.LIVE || section == Section.GUIDE) current?.let { reconnects = 0; startSource(it) }
        // Back from a movie: refresh "Continue watching"
        if (section == Section.MOVIES) renderMovies()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(minuteTick)
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        checkGen++
        player.release()
        io.shutdownNow()
        checkPool.shutdownNow()
        moviePool.shutdownNow()
        epg.shutdown()
        super.onDestroy()
    }

    /** Threads for background work run at low priority so playback always comes first. */
    private fun lowPriority(name: String) = java.util.concurrent.ThreadFactory { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, name)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
