package com.canoebay.canoetv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** A public-domain film from the Internet Archive. */
data class Movie(val id: String, val title: String, val year: String, val desc: String) {
    val poster: String get() = "https://archive.org/services/img/$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("year", year).put("desc", desc)

    companion object {
        fun fromJson(o: JSONObject) = Movie(o.optString("id"), o.optString("title"), o.optString("year"), o.optString("desc"))
    }
}

/** One Netflix-style row on the Movies screen. [query] narrows the public-domain catalogue. */
data class MovieRowSpec(val title: String, val query: String)

object MovieCatalog {
    /** Only films the Internet Archive marks as public domain. */
    private const val BASE = "collection:(feature_films) AND mediatype:(movies) AND licenseurl:(*publicdomain*)"

    val rows = listOf(
        MovieRowSpec("Popular classics", ""),
        MovieRowSpec("Comedy", "subject:(comedy)"),
        MovieRowSpec("Film noir", "subject:(\"film noir\" OR noir)"),
        MovieRowSpec("Mystery & crime", "subject:(mystery OR crime OR detective)"),
        MovieRowSpec("Horror", "subject:(horror)"),
        MovieRowSpec("Sci-Fi", "subject:(\"science fiction\" OR sci-fi OR scifi)"),
        MovieRowSpec("Westerns", "subject:(western OR westerns)"),
        MovieRowSpec("Drama", "subject:(drama)"),
        MovieRowSpec("Adventure", "subject:(adventure OR action)"),
        MovieRowSpec("Animation & family", "subject:(animation OR cartoon OR family)"),
        MovieRowSpec("Silent films", "subject:(silent)"),
    )

    fun searchUrl(extra: String, rows: Int = 40): String {
        val q = if (extra.isEmpty()) BASE else "$BASE AND $extra"
        val e = { v: String -> URLEncoder.encode(v, "UTF-8") }
        return "https://archive.org/advancedsearch.php?q=${e(q)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&fl%5B%5D=description" +
            "&sort%5B%5D=${e("downloads desc")}&rows=$rows&page=1&output=json"
    }

    fun searchByTitleUrl(text: String): String = searchUrl("title:(${text.replace("\"", "")})", 40)

    private val tags = Regex("<[^>]*>")
    private val spaces = Regex("\\s+")

    fun parseSearch(json: String): List<Movie> {
        val docs = JSONObject(json).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val out = ArrayList<Movie>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier")
            val title = d.optString("title").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            val descRaw = when (val v = d.opt("description")) {
                is JSONArray -> (0 until v.length()).joinToString(" ") { v.optString(it) }
                null -> ""
                else -> v.toString()
            }
            val desc = spaces.replace(tags.replace(descRaw, " "), " ").trim()
            out.add(Movie(id, title, d.optString("year").take(4), desc))
        }
        return out
    }

    /** Picks the best streamable video file from the item's file list. */
    fun streamUrl(id: String, metadataJson: String): String? {
        val files = JSONObject(metadataJson).optJSONArray("files") ?: return null
        val candidates = (0 until files.length()).map { files.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".mp4", ignoreCase = true) }
        if (candidates.isEmpty()) return null
        fun rank(f: JSONObject): Int {
            val fmt = f.optString("format").lowercase()
            return when {
                fmt == "h.264" -> 0
                fmt.contains("h.264") -> 1
                fmt == "mpeg4" -> 2
                fmt.contains("512kb") -> 3
                else -> 4
            }
        }
        val best = candidates.minByOrNull { rank(it) } ?: return null
        val path = best.optString("name").split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "https://archive.org/download/$id/$path"
    }

    fun metadataUrl(id: String) = "https://archive.org/metadata/$id"

    /** Disk cache for catalogue answers, so the Movies screen opens instantly. */
    fun cached(dir: File, url: String, maxAgeMs: Long, fetch: (String) -> String): String {
        val f = File(dir, "movies_" + Integer.toHexString(url.hashCode()) + ".json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f.readText()
        return try {
            fetch(url).also { f.writeText(it) }
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw e
        }
    }
}
