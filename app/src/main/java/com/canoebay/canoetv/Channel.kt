package com.canoebay.canoetv

data class Channel(
    var name: String,
    val url: String,
    val logo: String,
    val group: String,
    val userAgent: String?,
    val referrer: String?,
    val tvgId: String = "",
    val country: String = "",  // two-letter country code, lower case ("" if unknown)
    val lang: String = "",     // tvg-language, if the playlist gives one
) {
    var number: Int = 0
}

/** A playlist the app can load. A source may combine several M3U links. */
data class Source(
    val id: String,
    val name: String,
    val urls: List<String>,
    val custom: Boolean = false,
    val section: String = "Your playlists",
    val country: String? = null, // keep only channels from this country (lower-case code)
)

object Sources {
    const val FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
    private const val IPTV_ORG = "https://iptv-org.github.io/iptv"
    private const val PLUTO = "https://raw.githubusercontent.com/BuddyChewChew/pluto/main"
    private const val FAST = "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists"
    private const val XUMO = "https://raw.githubusercontent.com/BuddyChewChew/xumo-playlist-generator/main/playlists/xumo_playlist.m3u"

    const val START = "Start here"

    /** Built-in entries for the main T&T stations, using their official live streams and apps. */
    const val TT_BUILTIN = "builtin:tt"
    val ttBuiltin = """
        #EXTM3U
        #EXTINF:-1 tvg-id="CNC3.tt" tvg-country="TT" tvg-logo="https://i.imgur.com/1E73l2j.png" group-title="Trinidad & Tobago",CNC3 — official live stream
        dailymotion:x9vba4u
        #EXTINF:-1 tvg-id="TV6.tt" tvg-country="TT" group-title="Trinidad & Tobago",TV6 — opens the TV6 app
        app:com.tv6.android.prod
        #EXTINF:-1 tvg-id="TTT.tt" tvg-country="TT" tvg-logo="https://i.imgur.com/rxf4x8J.jpg" group-title="Trinidad & Tobago",TTT — opens YouTube (TTT Live Online)
        ytsearch:TTT Live Online live
    """.trimIndent()
    const val SERVICES = "Free TV services"
    const val WORLD = "Worldwide"
    const val COUNTRY = "By country"
    const val TOPIC = "By topic"
    val sectionOrder = listOf(START, SERVICES, WORLD, COUNTRY, TOPIC, "Your playlists")

    /** Every built-in list, in the order shown in the list picker. */
    fun all(): List<Source> = listOf(
        // Start here: one list with the best English channels for where you are
        Source("canada_mix", "Canada mix — best English channels",
            listOf("$PLUTO/pluto_ca.m3u", "$FAST/samsungtvplus_ca.m3u", "$FAST/plex_ca.m3u", FREE_TV), section = START),
        Source("usa_mix", "USA mix (works best in the US)",
            listOf("$PLUTO/pluto_us.m3u", "$FAST/samsungtvplus_us.m3u", "$FAST/plex_us.m3u", "$FAST/roku_all.m3u",
                "$FAST/tubi_all.m3u", XUMO), section = START),
        Source("uk_mix", "UK mix (works best in the UK)",
            listOf("$PLUTO/pluto_gb.m3u", "$FAST/samsungtvplus_gb.m3u", "$FAST/plex_gb.m3u"), section = START),

        Source("tt_all", "🇹🇹 Trinidad & Tobago — CNC3, TTT, TV6 & more",
            listOf(TT_BUILTIN, "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/tt.m3u", FREE_TV),
            section = START, country = "tt"),

        // Free TV services
        Source("pluto_ca", "Pluto TV — Canada", listOf("$PLUTO/pluto_ca.m3u"), section = SERVICES),
        Source("pluto_us", "Pluto TV — USA", listOf("$PLUTO/pluto_us.m3u"), section = SERVICES),
        Source("pluto_gb", "Pluto TV — UK", listOf("$PLUTO/pluto_gb.m3u"), section = SERVICES),
        Source("samsung_ca", "Samsung TV Plus — Canada", listOf("$FAST/samsungtvplus_ca.m3u"), section = SERVICES),
        Source("samsung_us", "Samsung TV Plus — USA", listOf("$FAST/samsungtvplus_us.m3u"), section = SERVICES),
        Source("samsung_gb", "Samsung TV Plus — UK", listOf("$FAST/samsungtvplus_gb.m3u"), section = SERVICES),
        Source("plex_ca", "Plex — Canada", listOf("$FAST/plex_ca.m3u"), section = SERVICES),
        Source("plex_us", "Plex — USA", listOf("$FAST/plex_us.m3u"), section = SERVICES),
        Source("plex_gb", "Plex — UK", listOf("$FAST/plex_gb.m3u"), section = SERVICES),
        Source("roku", "The Roku Channel (USA)", listOf("$FAST/roku_all.m3u"), section = SERVICES),
        Source("tubi", "Tubi", listOf("$FAST/tubi_all.m3u"), section = SERVICES),
        Source("xumo", "Xumo", listOf(XUMO), section = SERVICES),

        // Worldwide
        Source("all", "All free channels (Free-TV + iptv-org)", listOf(FREE_TV, "$IPTV_ORG/index.m3u"), section = WORLD),
        Source("freetv", "Free-TV — curated channels", listOf(FREE_TV), section = WORLD),
        Source("iptvorg", "iptv-org — every country", listOf("$IPTV_ORG/index.m3u"), section = WORLD),

        // By country
        Source("ca", "Canada", listOf("$IPTV_ORG/countries/ca.m3u"), section = COUNTRY),
        Source("tt", "Trinidad & Tobago", listOf("$IPTV_ORG/countries/tt.m3u"), section = COUNTRY),
        Source("us", "USA", listOf("$IPTV_ORG/countries/us.m3u"), section = COUNTRY),
        Source("uk", "United Kingdom", listOf("$IPTV_ORG/countries/uk.m3u"), section = COUNTRY),

        // By topic
        Source("news", "News", listOf("$IPTV_ORG/categories/news.m3u"), section = TOPIC),
        Source("sports", "Sports", listOf("$IPTV_ORG/categories/sports.m3u"), section = TOPIC),
        Source("movies", "Movies", listOf("$IPTV_ORG/categories/movies.m3u"), section = TOPIC),
        Source("kids", "Kids", listOf("$IPTV_ORG/categories/kids.m3u"), section = TOPIC),
        Source("music", "Music", listOf("$IPTV_ORG/categories/music.m3u"), section = TOPIC),
    )
}

/** Decides whether a channel is in English, from the clues the playlists give. */
object Lang {
    private val english = setOf(
        "us", "ca", "uk", "gb", "ie", "au", "nz", "tt", "jm", "bb", "bs", "gy", "bz", "ag", "lc", "gd", "vc",
        "kn", "dm", "bm", "ky", "vg", "vi", "pr", "mt", "za", "ng", "gh", "ke", "sg", "ph", "int",
    )

    // Words in a channel's name or category that point to another language
    private val foreign = Regex(
        "(?i)\\b(bollywood|hindi|punjabi|tamil|telugu|malayalam|marathi|kannada|gujarati|bengali|bangla|urdu|" +
            "arabic|persian|farsi|turk|türk|español|espanol|spanish|latino|latina|en español|portugu|brasil|" +
            "fran[cç]ais|french|qu[ée]bec|télé|deutsch|german|italia|polsk|polish|czech|česk|russian|" +
            "русск|ukrain|greek|ελλ|dutch|nederland|svensk|swedish|norsk|danish|dansk|suomi|finnish|romania|" +
            "magyar|hungar|korean|chinese|japanese|vietnam|thai|indonesia|filipino|tagalog|hebrew|kurd)"
    )

    // Category names that are non-English-speaking countries (Free-TV groups channels by country)
    private val foreignCountry = Regex("(?i)\\b(italy|poland|hungary|greece|russia|spain|ukraine|mongolia|emirates|germany|france|india|macedonia|finland|iran|croatia|chile|argentina|mexico|brazil|portugal|turkey|albania|serbia|bosnia|slovenia|slovakia|bulgaria|austria|switzerland|belgium|netherlands|sweden|norway|denmark|estonia|latvia|lithuania|belarus|georgia|armenia|azerbaijan|kazakhstan|uzbekistan|pakistan|afghanistan|iraq|syria|lebanon|jordan|egypt|morocco|algeria|tunisia|libya|saudi|qatar|kuwait|bahrain|oman|yemen|israel|china|japan|korea|taiwan|vietnam|thailand|indonesia|malaysia|cambodia|laos|myanmar|nepal|bangladesh|sri lanka|peru|colombia|venezuela|ecuador|bolivia|paraguay|uruguay|cuba|dominican|haiti|costa rica|panama|honduras|guatemala|salvador|nicaragua|luxembourg|andorra|monaco|montenegro|kosovo|moldova|romania|cyprus|iceland|faroe|czech|kyrgyz|tajik|turkmen|somalia|ethiopia|sudan|angola|mozambique|senegal|ivory|cameroon|congo|mali|niger\\b)")

    private val langTag = Regex("\\(([A-Z]{2})\\)")

    // Writing systems other than Latin (Cyrillic, Greek, Arabic, Hebrew, Indian scripts, Thai, CJK, Korean)
    private val nonLatin = Regex("[\\u0370-\\u03FF\\u0400-\\u04FF\\u0590-\\u06FF\\u0900-\\u0DFF\\u0E00-\\u0E7F\\u3040-\\u30FF\\u3400-\\u9FFF\\uAC00-\\uD7AF]")

    fun isEnglish(c: Channel): Boolean {
        if (c.lang.isNotBlank()) return c.lang.contains("english", ignoreCase = true) || c.lang.equals("en", ignoreCase = true)
        if (c.group.contains("(EN)")) return true
        // Categories tagged with another language code, like "News (ES)"
        langTag.find(c.group)?.let { if (it.groupValues[1] != "EN") return false }
        if (nonLatin.containsMatchIn(c.name)) return false
        if (foreign.containsMatchIn(c.name) || foreign.containsMatchIn(c.group)) return false
        if (foreignCountry.containsMatchIn(c.group)) return false
        if (c.country.isNotEmpty()) return c.country in english
        return true
    }
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
        var country = ""
        var lang = ""

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
                    lang = attrs["tvg-language"] ?: ""
                    country = (attrs["tvg-country"] ?: "").split(';', ',', ' ').firstOrNull { it.isNotBlank() }?.lowercase() ?: ""
                    if (country.isEmpty()) {
                        val suffix = tvg.substringBefore('@').substringAfterLast('.', "").lowercase()
                        if (suffix.length == 2 && suffix.all { it.isLetter() }) country = suffix
                    }
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
                        out.add(Channel(name, line, logo, group, ua, ref, tvg, country, lang))
                    } else {
                        out.add(Channel(line.substringAfterLast('/').ifEmpty { "Stream" }, line, "", "Uncategorized", null, null))
                    }
                    pending = false; name = ""; logo = ""; group = "Uncategorized"; ua = null; ref = null; tvg = ""; country = ""; lang = ""
                }
            }
        }
        return out
    }
}
