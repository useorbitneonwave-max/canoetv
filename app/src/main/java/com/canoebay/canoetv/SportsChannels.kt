package com.canoebay.canoetv

/**
 * 24/7 and live sports channels from the free TV services (same lists as the movie channels),
 * plus sports channels from the list you're watching. Home shows the ones with a game on first.
 */
object SportsChannels {
    private val sportsGroup = Regex("""(?i)sport""")
    private val sportsName = Regex(
        """(?i)(sport|\bnfl\b|\bnba\b|\bnhl\b|\bmlb\b|milb|\bufc\b|\bpfl\b|golf|\bpga\b|tennis|fight|kickbox|mma|racing|nascar|nhra|""" +
            """wrestl|\baew\b|boxing|ringside|willow|cricket|fifa|uefa|soccer|football|hockey|real madrid|darts|snooker|pickleball|""" +
            """dazn|fubo|stadium|accdn|big 12|one championship|tsn)"""
    )
    // Car, fishing and outdoor shows, prank clips, and channels not in English
    private val skip = Regex(
        """(?i)(español|espanol|top gear|donut|hagerty|powernation|pluto tv cars|meateater|fish|waypoint|outside tv|""" +
            """people are awesome|jesser|light fight|christmas)"""
    )

    fun isSportsChannel(c: Channel): Boolean {
        if (skip.containsMatchIn(c.name)) return false
        return sportsGroup.containsMatchIn(c.group) || sportsName.containsMatchIn(c.name)
    }

    /** Sports channels from [lists] first (the free services), then from [current]; one copy of each. */
    fun pick(lists: List<Channel>, current: List<Channel>, englishOnly: Boolean): List<Channel> {
        val seen = HashSet<String>()
        val out = ArrayList<Channel>()
        for (c in lists + current) {
            if (!isSportsChannel(c)) continue
            if (englishOnly && !Lang.isEnglish(c)) continue
            val key = Epg.nameKey(c.name.replace(Regex("(?i)\\b(channel|tv|on tubi|network)\\b"), "")).ifEmpty { c.name.lowercase() }
            if (!seen.add(key)) continue
            out.add(if (c.group.isEmpty() || c.group == "Canada") c.copy(group = "Sports") else c)
        }
        return out
    }

    private val liveWord = Regex("""(?i)\blive\b|\bvs\.?\b| v\. | @ """)

    /** Higher is better: a live game, then anything with a guide entry, then the rest. */
    fun rank(c: Channel, now: Epg.Prog?): Int = when {
        now == null -> 0
        liveWord.containsMatchIn(now.title) || liveWord.containsMatchIn(now.desc) -> 2
        else -> 1
    }
}
