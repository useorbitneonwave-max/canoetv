package com.canoebay.canoetv

/**
 * 24/7 movie channels from the free TV services (Pluto TV, Samsung TV Plus, Plex, Tubi).
 * They play right inside CanoeTV like any live channel, and the services' own guides say which movie is on.
 */
object MovieChannels {
    /** Canadian lists first, so the version that works in Canada wins when a channel is on several services. */
    val lists = listOf(
        "${Sources.PLUTO}/pluto_ca.m3u",
        "${Sources.FAST}/samsungtvplus_ca.m3u",
        "${Sources.FAST}/plex_ca.m3u",
        "${Sources.FAST}/tubi_all.m3u",
    )

    private val movieGroup = Regex("""(?i)\b(movies?|movie channels|cinema)\b""")
    private val movieName = Regex(
        """(?i)(movie|cinema|cinevault|flicks|filmrise (action|horror|western|sci-fi|free movies)|\b365\b|grjngo|""" +
            """thrillers|horror by alter|midnight pulp|the asylum|gravitas|haunt tv)"""
    )
    // Not movie channels, or not in English
    private val skip = Regex(
        """(?i)(spicy|k-?movie|kmovies|k\.movies|noovo|cinéma|canadien|italy|stingray|movie music|documentar|true crime|""" +
            """classic tv|black tv|creators|made in canada|surf cinema|at the movies|en français|español)"""
    )

    fun isMovieChannel(c: Channel): Boolean {
        if (skip.containsMatchIn(c.name)) return false
        return movieName.containsMatchIn(c.name) || movieGroup.containsMatchIn(c.group)
    }

    /** Genre rows, in the order they're shown. */
    val genres = listOf(
        "Action & thrillers" to Regex("""(?i)(action|thriller|gravitas)"""),
        "Horror & sci-fi" to Regex("""(?i)(horror|haunt|midnight pulp|asylum|sci-fi|scifi)"""),
        "Comedy" to Regex("""(?i)comedy"""),
        "Drama, romance & holiday" to Regex("""(?i)(drama|romance|christmas|holiday|hallmark|love)"""),
        "Westerns" to Regex("""(?i)(western|grjngo)"""),
        "Classics" to Regex("""(?i)(classic|retro|cinevault)"""),
    )
    const val OTHER = "More movie channels"

    fun genreOf(c: Channel): String = genres.firstOrNull { it.second.containsMatchIn(c.name) }?.first ?: OTHER

    /** Picks the movie channels from the downloaded lists, one copy of each, tagged with their genre. */
    fun pick(all: List<Channel>, englishOnly: Boolean): List<Channel> {
        val seen = HashSet<String>()
        val out = ArrayList<Channel>()
        for (c in all) {
            if (!isMovieChannel(c)) continue
            if (englishOnly && !Lang.isEnglish(c)) continue
            val key = Epg.nameKey(c.name.replace(Regex("(?i)\\bby (lionsgate|vvs films)\\b"), "")).ifEmpty { c.name.lowercase() }
            if (!seen.add(key)) continue
            out.add(c.copy(group = genreOf(c)))
        }
        return out
    }
}
