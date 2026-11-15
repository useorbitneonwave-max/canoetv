package com.canoebay.canoetv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small wrapper around SharedPreferences for favourites, recents, settings and channel health. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("canoetv", Context.MODE_PRIVATE)

    val favs: MutableSet<String> = HashSet(p.getStringSet("favs", emptySet()) ?: emptySet())

    /** Favourites in the order the user arranged them. Kept in step with [favs] by [saveFavs]. */
    val favOrder: MutableList<String> = loadList("favOrder").also { order ->
        if (order.isEmpty() && favs.isNotEmpty()) order.addAll(favs)
    }

    fun saveFavs() {
        favOrder.retainAll(favs)
        for (u in favs) if (u !in favOrder) favOrder.add(u)
        p.edit()
            .putStringSet("favs", HashSet(favs))
            .putString("favOrder", JSONArray(favOrder).toString())
            .apply()
    }

    fun moveFav(url: String, delta: Int) {
        val i = favOrder.indexOf(url)
        val j = i + delta
        if (i < 0 || j < 0 || j >= favOrder.size) return
        favOrder[i] = favOrder[j]
        favOrder[j] = url
        saveFavs()
    }

    // ---- extra channel lists switched on by the user ----
    val enabledExtras: MutableSet<String> = HashSet(p.getStringSet("extras", emptySet()) ?: emptySet()).also { set ->
        // Upgrading from 1.0.x: keep the list the user was already watching
        if (!p.contains("extras")) {
            val sid = p.getString("sourceId", null)
            if (sid != null && sid != "freetv" && !sid.startsWith("custom:")) set.add(sid)
            p.edit().putStringSet("extras", HashSet(set)).apply()
        }
    }
    fun saveExtras() = p.edit().putStringSet("extras", HashSet(enabledExtras)).apply()

    // ---- movies: My List and where you stopped ----
    private val moviePos: JSONObject = try { JSONObject(p.getString("moviePos", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private val myMovies: JSONArray = try { JSONArray(p.getString("myMovies", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    fun saveMoviePosition(m: Movie, pos: Long, dur: Long) {
        // Nearly finished counts as watched
        if (dur > 0 && pos > dur * 95 / 100) { clearMoviePosition(m.id); return }
        moviePos.put(m.id, JSONObject().put("pos", pos).put("dur", dur).put("t", System.currentTimeMillis()).put("movie", m.toJson()))
        p.edit().putString("moviePos", moviePos.toString()).apply()
    }

    fun clearMoviePosition(id: String) {
        moviePos.remove(id)
        p.edit().putString("moviePos", moviePos.toString()).apply()
    }

    /** Position in ms to resume from, or 0. */
    fun moviePosition(id: String): Long = moviePos.optJSONObject(id)?.optLong("pos") ?: 0L

    fun movieProgress(id: String): Int {
        val o = moviePos.optJSONObject(id) ?: return -1
        val dur = o.optLong("dur")
        return if (dur > 0) (o.optLong("pos") * 100 / dur).toInt() else -1
    }

    fun continueMovies(): List<Movie> = moviePos.keys().asSequence()
        .mapNotNull { moviePos.optJSONObject(it) }
        .sortedByDescending { it.optLong("t") }
        .mapNotNull { it.optJSONObject("movie")?.let { m -> Movie.fromJson(m) } }
        .toList()

    fun myMovieList(): List<Movie> = (0 until myMovies.length()).map { Movie.fromJson(myMovies.getJSONObject(it)) }

    fun isMyMovie(id: String) = (0 until myMovies.length()).any { myMovies.getJSONObject(it).optString("id") == id }

    fun toggleMyMovie(m: Movie): Boolean {
        val i = (0 until myMovies.length()).firstOrNull { myMovies.getJSONObject(it).optString("id") == m.id }
        if (i != null) myMovies.remove(i) else myMovies.put(m.toJson())
        p.edit().putString("myMovies", myMovies.toString()).apply()
        return i == null
    }

    // ---- renamed channels: url -> custom name ----
    private val renames: JSONObject = try { JSONObject(p.getString("renames", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun renamed(url: String): String? = renames.optString(url, "").ifEmpty { null }

    fun rename(url: String, name: String?) {
        if (name.isNullOrBlank()) renames.remove(url) else renames.put(url, name.trim())
        p.edit().putString("renames", renames.toString()).apply()
    }

    // ---- hidden categories ----
    val hiddenGroups: MutableSet<String> = HashSet(p.getStringSet("hiddenGroups", emptySet()) ?: emptySet())
    fun saveHidden() = p.edit().putStringSet("hiddenGroups", HashSet(hiddenGroups)).apply()

    // ---- parental lock ----
    var pin: String?
        get() = p.getString("pin", null)
        set(v) = p.edit().putString("pin", v).apply()

    val lockedGroups: MutableSet<String> = HashSet(p.getStringSet("lockedGroups", emptySet()) ?: emptySet())
    fun saveLocked() = p.edit().putStringSet("lockedGroups", HashSet(lockedGroups)).apply()

    // ---- backup / restore (the PIN is deliberately not included) ----
    fun exportJson(): String = JSONObject()
        .put("app", "CanoeTV")
        .put("version", 1)
        .put("favOrder", JSONArray(favOrder))
        .put("renames", renames)
        .put("hiddenGroups", JSONArray(hiddenGroups.toList()))
        .put("lockedGroups", JSONArray(lockedGroups.toList()))
        .put("custom", try { JSONArray(p.getString("custom", "[]")) } catch (e: Exception) { JSONArray() })
        .put("sourceId", sourceId)
        .toString(2)

    /** Returns the number of favourites restored. Throws if the file isn't a CanoeTV backup. */
    fun importJson(text: String): Int {
        val o = JSONObject(text)
        if (o.optString("app") != "CanoeTV") throw IllegalArgumentException("Not a CanoeTV backup")
        val fo = o.optJSONArray("favOrder") ?: JSONArray()
        favs.clear(); favOrder.clear()
        for (i in 0 until fo.length()) { favs.add(fo.getString(i)); favOrder.add(fo.getString(i)) }
        saveFavs()
        val rn = o.optJSONObject("renames") ?: JSONObject()
        p.edit().putString("renames", rn.toString()).apply()
        renames.keys().asSequence().toList().forEach { renames.remove(it) }
        rn.keys().forEach { renames.put(it, rn.getString(it)) }
        hiddenGroups.clear()
        o.optJSONArray("hiddenGroups")?.let { a -> for (i in 0 until a.length()) hiddenGroups.add(a.getString(i)) }
        saveHidden()
        lockedGroups.clear()
        o.optJSONArray("lockedGroups")?.let { a -> for (i in 0 until a.length()) lockedGroups.add(a.getString(i)) }
        saveLocked()
        o.optJSONArray("custom")?.let { p.edit().putString("custom", it.toString()).apply() }
        o.optString("sourceId").takeIf { it.isNotEmpty() }?.let { sourceId = it }
        return favOrder.size
    }

    var recents: MutableList<String> = loadList("recents")
        private set

    fun addRecent(url: String) {
        recents.remove(url)
        recents.add(0, url)
        while (recents.size > 40) recents.removeAt(recents.size - 1)
        p.edit().putString("recents", JSONArray(recents).toString()).apply()
    }

    var lastUrl: String?
        get() = p.getString("lastUrl", null)
        set(v) = p.edit().putString("lastUrl", v).apply()

    var sourceId: String
        get() = p.getString("sourceId", "canada_mix") ?: "canada_mix"
        set(v) = p.edit().putString("sourceId", v).apply()

    var hideDead: Boolean
        get() = p.getBoolean("hideDead", false)
        set(v) = p.edit().putBoolean("hideDead", v).apply()

    var autoSkip: Boolean
        get() = p.getBoolean("autoSkip", true)
        set(v) = p.edit().putBoolean("autoSkip", v).apply()

    // ---- channel health: url -> (ok, time). Results older than 24h are ignored. ----
    private val health: JSONObject = try { JSONObject(p.getString("health", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** true = played fine, false = failed, null = unknown */
    fun health(url: String): Boolean? {
        val e = health.optJSONArray(url) ?: return null
        if (System.currentTimeMillis() - e.optLong(1) > 24 * 3600_000L) return null
        return e.optBoolean(0)
    }

    fun setHealth(url: String, ok: Boolean) {
        health.put(url, JSONArray().put(ok).put(System.currentTimeMillis()))
        if (health.length() > 8000) {
            val keys = health.keys().asSequence().take(health.length() - 8000).toList()
            keys.forEach { health.remove(it) }
        }
        p.edit().putString("health", health.toString()).apply()
    }

    /** Save many results with a single write (used by the background channel check). */
    fun setHealthBatch(results: Map<String, Boolean>) {
        if (results.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((url, ok) in results) health.put(url, JSONArray().put(ok).put(now))
        if (health.length() > 8000) {
            val keys = health.keys().asSequence().take(health.length() - 8000).toList()
            keys.forEach { health.remove(it) }
        }
        p.edit().putString("health", health.toString()).apply()
    }

    var englishOnly: Boolean
        get() = p.getBoolean("englishOnly", true)
        set(v) = p.edit().putBoolean("englishOnly", v).apply()

    /** One-time move to the new Canada mix when updating to 1.4. */
    fun takeMixSwitch(): Boolean {
        if (p.getBoolean("mixSwitch14", false)) return false
        p.edit().putBoolean("mixSwitch14", true).putString("sourceId", "canada_mix").apply()
        return true
    }

    /** One-time clean-up in 1.5.15: forget channels wrongly marked offline by the sound bug. */
    fun takeHealthReset15(): Boolean {
        if (p.getBoolean("healthReset15", false)) return false
        p.edit().putBoolean("healthReset15", true).apply()
        clearHealth()
        return true
    }

    /** "standard", "smooth" (max 720p, further behind live) or "saver" (max 480p). */
    var playbackMode: String
        get() = p.getString("playbackMode", "standard") ?: "standard"
        set(v) = p.edit().putString("playbackMode", v).apply()

    var showStats: Boolean
        get() = p.getBoolean("showStats", false)
        set(v) = p.edit().putBoolean("showStats", v).apply()

    /** Live TV opens as the guide ("guide") or the simple channel list ("list"). */
    var liveView: String
        get() = p.getString("liveView", "guide") ?: "guide"
        set(v) = p.edit().putString("liveView", v).apply()

    /** "fit", "fill" or "zoom" */
    var pictureMode: String
        get() = p.getString("pictureMode", "fit") ?: "fit"
        set(v) = p.edit().putString("pictureMode", v).apply()

    // ---- reminders for shows that haven't started yet
    data class Reminder(val url: String, val name: String, val logo: String, val group: String, val title: String, val start: Long)

    fun reminders(): List<Reminder> {
        val a = try { JSONArray(p.getString("reminders", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Reminder(it.optString("url"), it.optString("name"), it.optString("logo"), it.optString("group"),
                it.optString("title"), it.optLong("start"))
        }.sortedBy { it.start }
    }

    private fun saveReminders(list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(JSONObject().put("url", r.url).put("name", r.name).put("logo", r.logo).put("group", r.group)
                .put("title", r.title).put("start", r.start))
        }
        p.edit().putString("reminders", a.toString()).apply()
    }

    fun addReminder(ch: Channel, title: String, start: Long) {
        val list = reminders().filterNot { it.url == ch.url && it.start == start } +
            Reminder(ch.url, ch.name, ch.logo, ch.group, title, start)
        saveReminders(list)
    }

    fun removeReminder(r: Reminder) = saveReminders(reminders().filterNot { it == r })

    /** The next reminder that's starting (from a minute before to 10 minutes after); it's removed once returned. */
    fun dueReminders(now: Long): Reminder? {
        val list = reminders()
        val due = list.firstOrNull { now >= it.start - 60_000L && now <= it.start + 10 * 60_000L } ?: run {
            // Drop reminders that were missed long ago
            val stale = list.filter { now > it.start + 10 * 60_000L }
            if (stale.isNotEmpty()) saveReminders(list - stale.toSet())
            return null
        }
        saveReminders(list - due)
        return due
    }

    /** Your own TMDB API key. Kept only on this device (never in the app's code or backups). */
    var tmdbKey: String
        get() = (p.getString("tmdbKey", null) ?: BuildConfig.TMDB_KEY)
        set(v) = p.edit().putString("tmdbKey", v.trim()).apply()

    private val tmdbList: JSONArray = try { JSONArray(p.getString("tmdbList", "[]")) } catch (e: Exception) { JSONArray() }

    fun tmdbMyList(): List<TMovie> = (0 until tmdbList.length()).map { TMovie.fromJson(tmdbList.getJSONObject(it)) }.reversed()

    fun isTmdbMine(id: Int) = (0 until tmdbList.length()).any { tmdbList.getJSONObject(it).optInt("id") == id }

    fun toggleTmdbMine(m: TMovie): Boolean {
        val i = (0 until tmdbList.length()).firstOrNull { tmdbList.getJSONObject(it).optInt("id") == m.id }
        if (i != null) tmdbList.remove(i) else tmdbList.put(m.toJson())
        p.edit().putString("tmdbList", tmdbList.toString()).apply()
        return i == null
    }

    var welcomed: Boolean
        get() = p.getBoolean("welcomed", false)
        set(v) = p.edit().putBoolean("welcomed", v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0L)
        set(v) = p.edit().putLong("lastUpdateCheck", v).apply()

    /** Forget all offline/working marks, e.g. after switching networks or turning on a VPN. */
    fun clearHealth() {
        health.keys().asSequence().toList().forEach { health.remove(it) }
        p.edit().putString("health", "{}").apply()
    }

    // ---- custom playlists added by the user ----
    fun customSources(): List<Source> {
        val arr = try { JSONArray(p.getString("custom", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Source("custom:" + o.getString("url"), o.getString("name"), listOf(o.getString("url")), custom = true)
        }
    }

    fun addCustom(name: String, url: String) {
        val arr = try { JSONArray(p.getString("custom", "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("name", name).put("url", url))
        p.edit().putString("custom", arr.toString()).apply()
    }

    fun removeCustom(url: String) {
        val arr = try { JSONArray(p.getString("custom", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("url") != url) out.put(arr.getJSONObject(i))
        p.edit().putString("custom", out.toString()).apply()
    }

    private fun loadList(key: String): MutableList<String> = try {
        val a = JSONArray(p.getString(key, "[]"))
        MutableList(a.length()) { a.getString(it) }
    } catch (e: Exception) { mutableListOf() }
}
