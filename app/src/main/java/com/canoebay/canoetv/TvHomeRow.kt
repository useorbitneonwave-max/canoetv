package com.canoebay.canoetv

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import java.net.URLEncoder

/**
 * Puts a "CanoeTV Favourites" row on the Android TV home screen. Each card opens that channel
 * in CanoeTV. Fire TV and phones don't have this home screen, so it quietly does nothing there.
 */
object TvHomeRow {

    fun playUri(ch: Channel): Uri = Uri.parse(
        "canoetv://play?url=" + enc(ch.url) + "&name=" + enc(ch.name) + "&logo=" + enc(ch.logo) +
            "&group=" + enc(ch.group)
    )

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    fun publish(context: Context, favourites: List<Channel>, nowTitle: (Channel) -> String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        try {
            val prefs = context.getSharedPreferences("canoetv_tvrow", Context.MODE_PRIVATE)
            val helper = PreviewChannelHelper(context)

            var channelId = prefs.getLong("channelId", -1L)
            if (channelId != -1L && helper.getPreviewChannel(channelId) == null) channelId = -1L
            if (channelId == -1L) {
                val channel = PreviewChannel.Builder()
                    .setDisplayName("CanoeTV Favourites")
                    .setDescription("Your favourite channels")
                    .setAppLinkIntentUri(Uri.parse("canoetv://home"))
                    .setLogo(logo(context))
                    .build()
                channelId = helper.publishChannel(channel)
                prefs.edit().putLong("channelId", channelId).apply()
                TvContractCompat.requestChannelBrowsable(context, channelId)
            }

            // Replace last time's cards with the current favourites
            prefs.getString("programIds", "")?.split(',')?.mapNotNull { it.toLongOrNull() }?.forEach {
                try { helper.deletePreviewProgram(it) } catch (e: Exception) { }
            }
            val ids = ArrayList<Long>()
            for (ch in favourites.take(20)) {
                val poster = if (ch.logo.isNotEmpty()) Uri.parse(ch.logo)
                else Uri.parse("android.resource://${context.packageName}/drawable/banner")
                val program = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                    .setTitle(ch.name)
                    .setDescription(nowTitle(ch) ?: ch.group)
                    .setPosterArtUri(poster)
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    .setLive(true)
                    .setIntentUri(playUri(ch))
                    .build()
                ids.add(helper.publishPreviewProgram(program))
            }
            prefs.edit().putString("programIds", ids.joinToString(",")).apply()
        } catch (e: Throwable) {
            // This TV doesn't support home-screen rows
        }
    }

    private fun logo(context: Context): Bitmap {
        val d = ContextCompat.getDrawable(context, R.drawable.ic_launcher)!!
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, 160, 160)
        d.draw(c)
        return bmp
    }
}
