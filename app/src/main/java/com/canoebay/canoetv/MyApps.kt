package com.canoebay.canoetv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build

/**
 * Streaming apps you have your own subscriptions for. CanoeTV opens them if they're installed,
 * or takes you to the app store to get them. Package names differ between phones, Android TV and Fire TV.
 */
data class AppEntry(val name: String, val color: Int, val phone: String, val tv: String, val others: List<String> = emptyList()) {
    val packages: List<String> get() = (listOf(tv, phone) + others).distinct()
}

object MyApps {
    val all = listOf(
        AppEntry("Netflix", 0xFF5A1A1A.toInt(), "com.netflix.mediaclient", "com.netflix.ninja"),
        AppEntry("Prime Video", 0xFF1A3A5A.toInt(), "com.amazon.avod.thirdpartyclient", "com.amazon.amazonvideo.livingroom",
            listOf("com.amazon.avod")),
        AppEntry("Disney+", 0xFF1A2A5A.toInt(), "com.disney.disneyplus", "com.disney.disneyplus"),
        AppEntry("Crave", 0xFF1A3A4A.toInt(), "ca.bellmedia.cravetv", "ca.bellmedia.cravetv"),
        AppEntry("YouTube", 0xFF5A1A22.toInt(), "com.google.android.youtube", "com.google.android.youtube.tv",
            listOf("com.amazon.firetv.youtube")),
        AppEntry("Tubi", 0xFF3A1A5A.toInt(), "com.tubitv", "com.tubitv"),
        AppEntry("Pluto TV", 0xFF4A4A1A.toInt(), "tv.pluto.android", "tv.pluto.android"),
        AppEntry("Plex", 0xFF4A3A1A.toInt(), "com.plexapp.android", "com.plexapp.android"),
        AppEntry("Paramount+", 0xFF1A2A6A.toInt(), "com.cbs.app", "com.cbs.ott"),
        AppEntry("DAZN", 0xFF2A2A2A.toInt(), "com.dazn", "com.dazn"),
        AppEntry("CBC Gem", 0xFF5A1A1A.toInt(), "ca.cbc.android.cbctv", "ca.cbc.android.cbctv"),
    )

    fun installed(context: Context, app: AppEntry): String? {
        val pm = context.packageManager
        return app.packages.firstOrNull { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
        }
    }

    fun icon(context: Context, app: AppEntry): Drawable? {
        val pkg = installed(context, app) ?: return null
        return try { context.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
    }

    /** Opens the app, or its app-store page if it isn't installed. Returns a message to show, if any. */
    fun open(context: Context, app: AppEntry, isTv: Boolean): String? {
        val pm = context.packageManager
        val pkg = installed(context, app)
        if (pkg != null) {
            val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return null
            }
        }
        val storePkg = if (isTv) app.tv else app.phone
        val amazon = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        val tries = if (amazon) listOf("amzn://apps/android?p=$storePkg", "market://details?id=$storePkg")
        else listOf("market://details?id=$storePkg", "https://play.google.com/store/apps/details?id=$storePkg")
        for (u in tries) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "${app.name} isn't installed yet — opening the app store"
            } catch (e: Exception) { }
        }
        return "${app.name} isn't installed. Get it from your device's app store."
    }
}
