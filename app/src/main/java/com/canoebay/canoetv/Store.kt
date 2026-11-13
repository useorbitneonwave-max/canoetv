package com.canoebay.canoetv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small wrapper around SharedPreferences for favourites, recents, settings and channel health. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("canoetv", Context.MODE_PRIVATE)

    val favs: MutableSet<String> = HashSet(p.getStringSet("favs", emptySet()) ?: emptySet())
    fun saveFavs() = p.edit().putStringSet("favs", HashSet(favs)).apply()

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
        get() = p.getString("sourceId", "all") ?: "all"
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
