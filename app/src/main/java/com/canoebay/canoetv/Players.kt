package com.canoebay.canoetv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Builds players that always decode sound inside the app (plain stereo/PCM output).
 *
 * By default the player may try to pass surround sound (Dolby AC-3 / E-AC-3) straight through to the TV
 * or soundbar. Some Fire TV and Android TV devices refuse that and the stream fails with
 * "AUDIO_TRACK_INIT_FAILED". Decoding in the app works on every device.
 */
@OptIn(UnstableApi::class)
object Players {

    private class SafeAudioRenderers(context: Context) : DefaultRenderersFactory(context) {
        init {
            setEnableDecoderFallback(true)
        }

        @Suppress("DEPRECATION")
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    fun build(context: Context, loadControl: LoadControl? = null): ExoPlayer {
        val b = ExoPlayer.Builder(context, SafeAudioRenderers(context))
        if (loadControl != null) b.setLoadControl(loadControl)
        return b.build()
    }
}
