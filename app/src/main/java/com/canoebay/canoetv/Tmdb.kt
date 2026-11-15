package com.canoebay.canoetv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** A movie from The Movie Database (TMDB). */
data class TMovie(
    val id: Int,
    val title: String,
    val year: String,
    val rating: Double,
    val overview: String,
    val poster: String?,   // path like "/abc.jpg"
    val backdrop: String?,
    val genreIds: List<Int>,
    val lang: String = "",        // original language, like "en"
    val votes: Int = 0,
    val released: String = "",    // yyyy-mm-dd
) {
    /** Not out yet, or too few votes for the score to mean much. */
    val ratingReliable: Boolean get() =
        votes >= 25 && (released.isEmpty() || released <= java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
    val posterUrl: String? get() = poster?.let { Tmdb.IMG + "w342" + it }
    val backdropUrl: String? get() = backdrop?.let { Tmdb.IMG + "w1280" + it }
    val ratingText: String get() = if (rating > 0 && ratingReliable) String.format(java.util.Locale.US, "%.1f", rating) else ""

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("year", year).put("rating", rating)
        .put("overview", overview).put("poster", poster ?: "").put("backdrop", backdrop ?: "")
        .put("genres", JSONArray(genreIds)).put("original_language", lang).put("vote_count", votes).put("released", released)

    companion object {
        fun fromJson(o: JSONObject): TMovie {
            val date = o.optString("release_date", o.optString("year"))
            val g = o.optJSONArray("genre_ids") ?: o.optJSONArray("genres")
            val ids = ArrayList<Int>()
            if (g != null) for (i in 0 until g.length()) {
                val v = g.opt(i)
                if (v is Number) ids.add(v.toInt()) else if (v is JSONObject) ids.add(v.optInt("id"))
            }
            return TMovie(
                o.optInt("id"),
                o.optString("title").ifEmpty { o.optString("name") },
                date.take(4),
                o.optDouble("vote_average", o.optDouble("rating", 0.0)).let { if (it.isNaN()) 0.0 else it },
                o.optString("overview"),
                o.optString("poster_path", o.optString("poster")).takeIf { it.isNotEmpty() && it != "null" },
                o.optString("backdrop_path", o.optString("backdrop")).takeIf { it.isNotEmpty() && it != "null" },
                ids,
                o.optString("original_language"),
                o.optInt("vote_count"),
                o.optString("release_date", o.optString("released")).takeIf { it.length >= 4 && it != "null" } ?: "",
            )
        }
    }
}

data class TPerson(val name: String, val role: String, val photo: String?) {
    val photoUrl: String? get() = photo?.let { Tmdb.IMG + "w185" + it }
}

/** Where a movie streams in Canada, from TMDB's JustWatch data. [kind] is "free", "ads" or "flatrate" (subscription). */
data class TProvider(val name: String, val kind: String, val logo: String?)

data class TDetails(
    val movie: TMovie,
    val runtime: Int,
    val genres: List<String>,
    val releaseDate: String,
    val cast: List<TPerson>,
    val director: String,
    val trailerKey: String?,
    val providers: List<TProvider>,
) {
    val runtimeText: String get() = if (runtime > 0) "${runtime / 60}h ${runtime % 60}m" else ""
}

/** One row on the Movies screen. [path] is a TMDB API path with its query (without the key). */
data class TRow(val title: String, val path: String, val free: Boolean = false)

object Tmdb {
    const val IMG = "https://image.tmdb.org/t/p/"
    private const val API = "https://api.themoviedb.org/3"
    const val REGION = "CA"

    // Free or with ads in Canada (Tubi, Plex, Pluto TV, CBC Gem...)
    private const val FREE = "watch_region=$REGION&with_watch_monetization_types=free%7Cads"

    val rows = listOf(
        TRow("Free to watch now", "/discover/movie?$FREE&sort_by=popularity.desc&vote_count.gte=50", free = true),
        TRow("Trending this week", "/trending/movie/week?"),
        TRow("Now playing in theatres", "/movie/now_playing?region=$REGION"),
        TRow("Popular", "/movie/popular?region=$REGION"),
        TRow("New on streaming in Canada", "/discover/movie?watch_region=$REGION&with_watch_monetization_types=flatrate%7Cfree%7Cads" +
            "&sort_by=primary_release_date.desc&vote_count.gte=40&primary_release_date.lte=TODAY"),
        TRow("Top rated", "/movie/top_rated?"),
        TRow("Coming soon", "/movie/upcoming?region=$REGION"),
        TRow("Free action & thrillers", "/discover/movie?$FREE&with_genres=28%7C53&sort_by=popularity.desc&vote_count.gte=30", free = true),
        TRow("Free comedy", "/discover/movie?$FREE&with_genres=35&sort_by=popularity.desc&vote_count.gte=30", free = true),
        TRow("Free horror", "/discover/movie?$FREE&with_genres=27&sort_by=popularity.desc&vote_count.gte=30", free = true),
        TRow("Free family & animation", "/discover/movie?$FREE&with_genres=10751%7C16&sort_by=popularity.desc&vote_count.gte=30", free = true),
        TRow("Free drama & romance", "/discover/movie?$FREE&with_genres=18%7C10749&sort_by=popularity.desc&vote_count.gte=30", free = true),
        TRow("Action", "/discover/movie?with_genres=28&sort_by=popularity.desc&vote_count.gte=200&watch_region=$REGION"),
        TRow("Comedy", "/discover/movie?with_genres=35&sort_by=popularity.desc&vote_count.gte=200&watch_region=$REGION"),
        TRow("Horror", "/discover/movie?with_genres=27&sort_by=popularity.desc&vote_count.gte=200&watch_region=$REGION"),
        TRow("Science fiction", "/discover/movie?with_genres=878&sort_by=popularity.desc&vote_count.gte=200&watch_region=$REGION"),
        TRow("Family", "/discover/movie?with_genres=10751&sort_by=popularity.desc&vote_count.gte=200&watch_region=$REGION"),
    )

    val genreNames = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller",
        10752 to "War", 37 to "Western",
    )

    fun genres(m: TMovie) = m.genreIds.mapNotNull { genreNames[it] }.joinToString(", ")

    /** Asks TMDB for English-language films only (for the lists that can filter). */
    fun english(path: String) = if (path.startsWith("/discover/")) "$path&with_original_language=en" else path

    fun url(path: String, key: String, page: Int = 1): String {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val p = path.replace("TODAY", today)
        val sep = if (p.contains('?')) (if (p.endsWith("?")) "" else "&") else "?"
        return "$API$p${sep}api_key=${URLEncoder.encode(key, "UTF-8")}&language=en-US&page=$page"
    }

    fun searchUrl(text: String, key: String) =
        url("/search/movie?query=${URLEncoder.encode(text, "UTF-8")}&include_adult=false&region=$REGION", key)

    fun detailsUrl(id: Int, key: String) =
        url("/movie/$id?append_to_response=credits,videos,watch/providers", key)

    fun checkUrl(key: String) = url("/configuration?", key)

    data class Page(val movies: List<TMovie>, val totalPages: Int, val totalResults: Int)

    fun parseList(json: String): Page {
        val o = JSONObject(json)
        val arr = o.optJSONArray("results") ?: JSONArray()
        val out = ArrayList<TMovie>()
        for (i in 0 until arr.length()) {
            val m = TMovie.fromJson(arr.getJSONObject(i))
            if (m.id != 0 && m.title.isNotEmpty() && m.poster != null) out.add(m)
        }
        return Page(out, o.optInt("total_pages", 1), o.optInt("total_results", out.size))
    }

    fun parseDetails(json: String): TDetails {
        val o = JSONObject(json)
        val movie = TMovie.fromJson(o)
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList()
        val credits = o.optJSONObject("credits")
        val castArr = credits?.optJSONArray("cast") ?: JSONArray()
        val cast = (0 until minOf(castArr.length(), 20)).map {
            val c = castArr.getJSONObject(it)
            TPerson(c.optString("name"), c.optString("character"), c.optString("profile_path").takeIf { p -> p.isNotEmpty() && p != "null" })
        }
        val crew = credits?.optJSONArray("crew") ?: JSONArray()
        val director = (0 until crew.length()).map { crew.getJSONObject(it) }
            .filter { it.optString("job") == "Director" }.joinToString(", ") { it.optString("name") }
        val vids = o.optJSONObject("videos")?.optJSONArray("results") ?: JSONArray()
        val videos = (0 until vids.length()).map { vids.getJSONObject(it) }.filter { it.optString("site") == "YouTube" }
        val trailer = (videos.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
            ?: videos.firstOrNull { it.optString("type") == "Trailer" }
            ?: videos.firstOrNull { it.optString("type") == "Teaser" })?.optString("key")
        val ca = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(REGION)
        val providers = ArrayList<TProvider>()
        for (kind in listOf("free", "ads", "flatrate")) {
            val a = ca?.optJSONArray(kind) ?: continue
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i)
                val name = p.optString("provider_name")
                if (providers.none { it.name == name }) providers.add(TProvider(name, kind, p.optString("logo_path").takeIf { it.isNotEmpty() }))
            }
        }
        return TDetails(movie, o.optInt("runtime"), genres, o.optString("release_date"), cast, director, trailer, providers)
    }

    /** Matches a TMDB provider name to one of the apps in My apps. */
    fun appFor(provider: String): AppEntry? {
        val p = provider.lowercase()
        val name = when {
            p.contains("netflix") -> "Netflix"
            p.contains("amazon prime") || p == "prime video" || p.contains("amazon video") -> "Prime Video"
            p.contains("disney") -> "Disney+"
            p.contains("crave") -> "Crave"
            p.contains("tubi") -> "Tubi"
            p.contains("pluto") -> "Pluto TV"
            p.contains("plex") -> "Plex"
            p.contains("paramount") -> "Paramount+"
            p.contains("cbc gem") -> "CBC Gem"
            p == "youtube" || p.contains("youtube free") -> "YouTube"
            else -> null
        } ?: return null
        return MyApps.all.firstOrNull { it.name == name }
    }

    /** Disk cache, so the Movies screen opens instantly. */
    fun cached(dir: File, url: String, maxAgeMs: Long, fetch: (String) -> String): String {
        val keyless = url.replace(Regex("api_key=[^&]*"), "")
        val f = File(dir, "tmdb_" + Integer.toHexString(keyless.hashCode()) + ".json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f.readText()
        return try {
            fetch(url).also { f.writeText(it) }
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw e
        }
    }
}
