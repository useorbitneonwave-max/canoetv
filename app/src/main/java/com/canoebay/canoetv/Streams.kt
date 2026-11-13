package com.canoebay.canoetv

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/** Builds a playable source for a channel, with its own user agent / referrer when the playlist asks for one. */
@OptIn(UnstableApi::class)
object Streams {
    const val UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun source(url: String, userAgent: String?, referrer: String?): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        if (referrer != null) http.setDefaultRequestProperties(mapOf("Referer" to referrer))

        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (url.contains(".mpd", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_MPD)
        }.build()
        return DefaultMediaSourceFactory(http).createMediaSource(item)
    }

    fun source(ch: Channel): MediaSource = source(ch.url, ch.userAgent, ch.referrer)
}
