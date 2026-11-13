package com.canoebay.canoetv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * TV guide (XMLTV). Downloads guide files in the background, keeps only the programmes for channels
 * in the current playlist from 1h ago to 36h ahead, and answers "what's on now / next".
 */
class Epg(private val dir: File) {

    data class Prog(val start: Long, val stop: Long, val title: String, val desc: String)

    companion object {
        fun norm(id: String) = id.substringBefore('@').trim().lowercase(Locale.ROOT)
    }

    private val data = ConcurrentHashMap<String, List<Prog>>()
    private val attempted = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun now(id: String): Prog? {
        if (id.isBlank()) return null
        val t = System.currentTimeMillis()
        return data[norm(id)]?.firstOrNull { it.start <= t && t < it.stop }
    }

    fun next(id: String): Prog? {
        if (id.isBlank()) return null
        val t = System.currentTimeMillis()
        return data[norm(id)]?.firstOrNull { it.start > t }
    }

    fun upcoming(id: String, max: Int = 40): List<Prog> {
        if (id.isBlank()) return emptyList()
        val t = System.currentTimeMillis()
        return data[norm(id)]?.filter { it.stop > t }?.take(max) ?: emptyList()
    }

    /**
     * Loads each guide URL (cached on disk for 12h), keeping only channels in [wanted].
     * [fetch] downloads a URL into a file. [onUpdate] runs (on a background thread) after each file.
     */
    fun load(urls: List<String>, wanted: Set<String>, fetch: (String, File) -> Unit, onUpdate: () -> Unit) {
        val now = System.currentTimeMillis()
        for (u in urls.distinct()) {
            val last = attempted[u]
            if (last != null && now - last < 6 * 3600_000L) continue
            attempted[u] = now
            pool.execute {
                try {
                    val f = File(dir, "guide_" + Integer.toHexString(u.hashCode()) + ".xml")
                    if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) {
                        val tmp = File(dir, f.name + ".part")
                        fetch(u, tmp)
                        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
                    }
                    parse(f, wanted)
                    onUpdate()
                } catch (e: Throwable) {
                    // No guide for this country right now — try again later
                }
            }
        }
    }

    private fun parse(f: File, wanted: Set<String>) {
        val windowStart = System.currentTimeMillis() - 3600_000L
        val windowEnd = System.currentTimeMillis() + 36 * 3600_000L
        val found = HashMap<String, MutableList<Prog>>()

        val raw = BufferedInputStream(f.inputStream(), 65_536)
        raw.mark(4)
        val b1 = raw.read()
        val b2 = raw.read()
        raw.reset()
        val input = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 65_536) else raw

        input.use { ins ->
            val p = Xml.newPullParser()
            p.setInput(ins, null)
            var ch: String? = null
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "programme" -> {
                            val id = norm(p.getAttributeValue(null, "channel") ?: "")
                            ch = if (id in wanted) id else null
                            if (ch != null) {
                                start = time(p.getAttributeValue(null, "start"))
                                stop = time(p.getAttributeValue(null, "stop"))
                                title = ""; desc = ""
                            }
                        }
                        "title" -> if (ch != null && title.isEmpty()) title = p.nextText().trim()
                        "desc" -> if (ch != null && desc.isEmpty()) desc = p.nextText().trim()
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                    val c = ch
                    if (c != null && stop > windowStart && start < windowEnd && start > 0 && title.isNotEmpty()) {
                        found.getOrPut(c) { ArrayList() }.add(Prog(start, if (stop > start) stop else start + 1_800_000L, title, desc))
                    }
                    ch = null
                }
                ev = p.next()
            }
        }

        for ((id, progs) in found) {
            val merged = ((data[id] ?: emptyList()) + progs).distinctBy { it.start }.sortedBy { it.start }
            data[id] = merged
        }
    }

    /** XMLTV time like "20261113180000 +0000". */
    private fun time(v: String?): Long {
        if (v == null || v.length < 14) return 0L
        return try {
            val base = fmt.parse(v.substring(0, 14))?.time ?: return 0L
            val zone = v.substring(14).trim()
            if (zone.length >= 5 && (zone[0] == '+' || zone[0] == '-')) {
                val sign = if (zone[0] == '-') -1 else 1
                val mins = zone.substring(1, 3).toInt() * 60 + zone.substring(3, 5).toInt()
                base - sign * mins * 60_000L
            } else base
        } catch (e: Exception) {
            0L
        }
    }

    fun shutdown() = pool.shutdownNow()
}
