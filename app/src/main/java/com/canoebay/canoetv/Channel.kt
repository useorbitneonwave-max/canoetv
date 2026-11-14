package com.canoebay.canoetv

data class Channel(
    var name: String,
    val url: String,
    val logo: String,
    val group: String,
    val userAgent: String?,
    val referrer: String?,
    val tvgId: String = "",
) {
    var number: Int = 0
}

/** A playlist the app can load. A source may combine several M3U links. */
data class Source(val id: String, val name: String, val urls: List<String>, val custom: Boolean = false)

object Sources {
    const val FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
    private const val IPTV_ORG = "https://iptv-org.github.io/iptv"

    /** Built in for everyone: a curated list of channels that are free to watch. */
    fun core(): List<Source> = listOf(
        Source("freetv", "Free-TV — free, curated channels", listOf(FREE_TV)),
    )

    /** Community lists from iptv-org, all built in. */
    fun extras(): List<Source> = listOf(
        Source("all", "All free channels (Free-TV + iptv-org)", listOf(FREE_TV, "$IPTV_ORG/index.m3u")),
        Source("iptvorg", "iptv-org — every country", listOf("$IPTV_ORG/index.m3u")),
        Source("ca", "Canada", listOf("$IPTV_ORG/countries/ca.m3u")),
        Source("tt", "Trinidad & Tobago", listOf("$IPTV_ORG/countries/tt.m3u")),
        Source("us", "USA", listOf("$IPTV_ORG/countries/us.m3u")),
        Source("uk", "United Kingdom", listOf("$IPTV_ORG/countries/uk.m3u")),
        Source("news", "News", listOf("$IPTV_ORG/categories/news.m3u")),
        Source("sports", "Sports", listOf("$IPTV_ORG/categories/sports.m3u")),
        Source("movies", "Movies", listOf("$IPTV_ORG/categories/movies.m3u")),
        Source("kids", "Kids", listOf("$IPTV_ORG/categories/kids.m3u")),
        Source("music", "Music", listOf("$IPTV_ORG/categories/music.m3u")),
    )
}

object M3u {
    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")
    private val guideRe = Regex("""(?:x-tvg-url|url-tvg)="([^"]*)"""", RegexOption.IGNORE_CASE)

    /** TV-guide links listed in the playlist's #EXTM3U header line. */
    fun guideUrls(text: String): List<String> {
        val header = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return emptyList()
        if (!header.startsWith("#EXTM3U", ignoreCase = true)) return emptyList()
        return guideRe.findAll(header).flatMap { m -> m.groupValues[1].split(',').asSequence() }
            .map { it.trim() }.filter { it.startsWith("http") }.toList()
    }

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var pending = false
        var name = ""
        var logo = ""
        var group = "Uncategorized"
        var ua: String? = null
        var ref: String? = null
        var tvg = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val lastQuote = line.lastIndexOf('"')
                    val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                    name = (if (comma >= 0) line.substring(comma + 1).trim() else "")
                        .ifEmpty { attrs["tvg-name"] ?: "Unnamed" }
                    logo = attrs["tvg-logo"] ?: ""
                    group = (attrs["group-title"] ?: "").split(';').first().trim().ifEmpty { "Uncategorized" }
                    ua = attrs["http-user-agent"] ?: attrs["user-agent"]
                    ref = attrs["http-referrer"] ?: attrs["referrer"]
                    tvg = attrs["tvg-id"] ?: ""
                    pending = true
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    if (pending) group = line.substring(8).trim().ifEmpty { group }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11)
                    val i = opt.indexOf('=')
                    if (i > 0) {
                        val k = opt.substring(0, i).trim().lowercase()
                        val v = opt.substring(i + 1).trim()
                        if (k == "http-user-agent") ua = v
                        if (k == "http-referrer" || k == "http-referer") ref = v
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    if (pending) {
                        out.add(Channel(name, line, logo, group, ua, ref, tvg))
                    } else {
                        out.add(Channel(line.substringAfterLast('/').ifEmpty { "Stream" }, line, "", "Uncategorized", null, null))
                    }
                    pending = false; name = ""; logo = ""; group = "Uncategorized"; ua = null; ref = null; tvg = ""
                }
            }
        }
        return out
    }
}
