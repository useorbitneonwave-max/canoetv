package com.canoebay.canoetv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
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
    private lateinit var banner: LinearLayout
    private lateinit var bannerLogo: ImageView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerSub: TextView
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
    private var previous: Channel? = null
    private var fullscreen = false
    private var inPip = false
    private var skipCount = 0
    private var loadToken = 0
    private lateinit var epg: Epg
    private val guideCatalog = LinkedHashSet<String>()
    private var unlocked = false // parental lock opened with the PIN this session

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
        banner = findViewById(R.id.banner)
        bannerLogo = findViewById(R.id.bannerLogo)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerSub = findViewById(R.id.bannerSub)
        nowPlaying = findViewById(R.id.nowPlaying)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        groupSpinner = findViewById(R.id.groupSpinner)
        search = findViewById(R.id.search)
        hideDead = findViewById(R.id.hideDead)
        autoSkip = findViewById(R.id.autoSkip)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)

        store = Store(this)
        epg = Epg(File(filesDir, "epg").apply { mkdirs() })

        // Bigger buffer than the default so short network hiccups don't interrupt playback
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 2_500, 6_000)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        player.addListener(playerListener)
        playerView.player = player
        playerView.keepScreenOn = true
        playerView.setFullscreenButtonClickListener { full -> setFullscreen(full) }

        adapter = ChannelAdapter(
            store,
            nowShowing = { ch -> epg.now(ch.tvgId)?.let { "Now: ${it.title}" } },
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

        hideDead.isChecked = store.hideDead
        hideDead.setOnCheckedChangeListener { _, on -> store.hideDead = on; applyFilter() }
        autoSkip.isChecked = store.autoSkip
        autoSkip.setOnCheckedChangeListener { _, on -> store.autoSkip = on }

        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyFilter()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<View>(R.id.lastBtn).setOnClickListener { lastChannel() }
        findViewById<View>(R.id.menuBtn).setOnClickListener { showMenu() }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = setFullscreen(false)
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        setupSources(selectId = store.sourceId)
        applyLayout()

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
                "Free channels from Free-TV and iptv-org.\n\n" +
                    "Remote tips:\n• OK — play / OK again for full screen\n• Up/Down in full screen — change channel\n" +
                    "• Number keys — jump to a channel number\n• 0 or Last — back to the previous channel\n" +
                    "• Long-press OK — channel options (favourite, guide, rename, move)\n• Menu — this menu"
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

    // ------------------------------------------------------------------ TV guide

    private val timeFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    private fun hhmm(t: Long): String = timeFmt.format(java.util.Date(t))

    /** Downloads guide data for the countries of the channels you actually watch (max 4 at a time). */
    private fun refreshGuide() {
        if (channels.isEmpty()) return
        val ids = channels.mapNotNull { c -> Epg.norm(c.tvgId).takeIf { it.isNotEmpty() } }.toHashSet()
        if (ids.isEmpty()) return
        val interest = listOfNotNull(current) +
            store.favOrder.mapNotNull { byUrl[it] } +
            store.recents.take(20).mapNotNull { byUrl[it] }
        val countries = interest.mapNotNull { c ->
            Epg.norm(c.tvgId).substringAfterLast('.', "").takeIf { it.length in 2..3 && it.all { ch -> ch.isLetter() } }
        }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(4)
        val urls = ArrayList<String>()
        for (cc in countries) {
            val code = cc.uppercase(java.util.Locale.ROOT)
            urls.add(guideCatalog.firstOrNull { it.contains("_${code}1.xml") }
                ?: "https://epgshare01.online/epgshare01/epg_ripper_${code}1.xml.gz")
        }
        // Guides a custom playlist names itself (not the big shared list)
        urls.addAll(guideCatalog.filter { !it.contains("epgshare01") }.take(2))
        if (urls.isEmpty()) return
        epg.load(urls, ids, ::downloadToFile) {
            runOnUiThread {
                adapter.notifyDataSetChanged()
                if (banner.visibility == View.VISIBLE) current?.let { showBanner(it) }
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
        val progs = epg.upcoming(ch.tvgId)
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
        val now = epg.now(ch.tvgId)
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
        sources = Sources.builtIn() + store.customSources()
        val labels = sources.map { if (it.custom) "📁 ${it.name}" else it.name }
        sourceSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, labels).also {
            it.setDropDownViewResource(R.layout.spinner_item)
        }
        val idx = sources.indexOfFirst { it.id == selectId }.let { if (it < 0) 0 else it }
        sourceSpinner.setSelection(idx, false)
        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos in sources.indices && sources[pos].id != loadedSourceId) load(sources[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        if (loadedSourceId != sources[idx].id) load(sources[idx])
    }

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
                val text = try {
                    download(u).also { cacheFile(u).writeText(it) }
                } catch (e: Exception) {
                    failed++
                    cacheFile(u).takeIf { it.exists() }?.readText()
                }
                if (text != null) {
                    guides.addAll(M3u.guideUrls(text))
                    for (c in M3u.parse(text)) all.putIfAbsent(c.url, c)
                }
            }
            val result = all.values.toList()
            result.forEachIndexed { i, c ->
                c.number = i + 1
                store.renamed(c.url)?.let { c.name = it }
            }
            runOnUiThread {
                if (token != loadToken) return@runOnUiThread
                guideCatalog.addAll(guides)
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
                if (result.isNotEmpty() && current == null) {
                    val last = store.lastUrl?.let { byUrl[it] }
                    if (last != null) play(last, auto = false)
                    else showMessage("Pick a channel to start watching", error = false)
                }
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
        if (hideDead.isChecked) base = base.filter { store.health(it.url) != false }
        filtered = base
        adapter.submit(filtered, current?.url)
        status.text = "${filtered.size} channels"
        // Quietly test whatever is now on screen
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, 2_000)
    }

    private fun toggleFav(ch: Channel) {
        if (!store.favs.remove(ch.url)) store.favs.add(ch.url)
        store.saveFavs()
        toast(if (ch.url in store.favs) "Added ${ch.name} to favourites" else "Removed from favourites")
        val key = groupKeys.getOrNull(groupSpinner.selectedItemPosition)
        buildGroups()
        if (key == KEY_FAV) applyFilter() else adapter.refresh(ch.url)
    }

    // ------------------------------------------------------------------ background channel check

    @Volatile private var checkGen = 0
    private val checkPool = Executors.newFixedThreadPool(6)
    private val checkRunnable = Runnable { startBackgroundCheck() }

    /** Tests the channels in the current list that haven't been tested in the last 24h. */
    private fun startBackgroundCheck() {
        val gen = ++checkGen
        val todo = filtered.filter { store.health(it.url) == null && it.url != current?.url }.take(400)
        if (todo.isEmpty()) return
        val total = todo.size
        val done = AtomicInteger()
        val working = AtomicInteger()
        val pending = ConcurrentHashMap<String, Boolean>()
        for (c in todo) checkPool.execute {
            if (gen != checkGen) return@execute
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
                    if (hideDead.isChecked) {
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
        showBanner(ch)
        startSource(ch)
        refreshGuide()

        val pos = filtered.indexOfFirst { it.url == ch.url }
        if (pos >= 0) list.scrollToPosition(pos)
    }

    /** Loads the channel's stream into the player and starts it at the live edge. */
    private fun startSource(ch: Channel) {
        bufferingSince = 0L
        reconnectPending = false
        player.setMediaSource(Streams.source(ch))
        player.prepare()
        player.playWhenReady = true
    }

    private fun lastChannel() {
        val p = previous
        if (p == null) { toast("No previous channel yet"); return }
        play(p, auto = false)
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
        val now = epg.now(ch.tvgId)
        val next = epg.next(ch.tvgId)
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
        if (ch != null) play(ch, auto = false)
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
            if (isPlaying) overlay.visibility = View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            loading.visibility = View.GONE
            val ch = current ?: return
            // A channel that was already playing just dropped out: reconnect instead of giving up
            val transient = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || playedOk
            if (transient && reconnects < maxReconnects) {
                reconnect("connection dropped")
                return
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isTv() && player.isPlaying) {
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
                code == KeyEvent.KEYCODE_CHANNEL_UP -> { step(-1); return true }
                code == KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(1); return true }
                code == KeyEvent.KEYCODE_LAST_CHANNEL -> { lastChannel(); return true }
                code == KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
                !typing && code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    typeDigit(code - KeyEvent.KEYCODE_0); return true
                }
                !typing && code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    typeDigit(code - KeyEvent.KEYCODE_NUMPAD_0); return true
                }
                (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) &&
                    fullscreen && !playerView.isControllerFullyVisible -> {
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
        // Coming back to the app: jump to the live edge instead of resuming stale video
        current?.let { reconnects = 0; startSource(it) }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(watchdog)
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        checkGen++
        player.release()
        io.shutdownNow()
        checkPool.shutdownNow()
        epg.shutdown()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
