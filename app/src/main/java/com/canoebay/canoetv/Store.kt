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
        get() = p.getString("sourceId", "freetv") ?: "freetv"
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
