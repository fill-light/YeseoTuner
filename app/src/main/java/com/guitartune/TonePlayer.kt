package com.guitartune

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Plays a plucked-string-like reference tone. */
class TonePlayer {

    private var track: AudioTrack? = null

    /** Synthesizes and plays [hz] for [DURATION_MS]. Call off the main thread (synthesis takes a moment). */
    @Synchronized
    fun play(hz: Double) {
        stop()
        val n = SAMPLE_RATE * DURATION_MS / 1000
        val tmp = DoubleArray(n)
        var peak = 1e-9
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            var s = 0.0
            // Harmonics matter: phone speakers can't reproduce bass fundamentals,
            // but the ear still hears the pitch from the overtones.
            for (k in HARMONICS.indices) {
                val f = hz * (k + 1)
                if (f < SAMPLE_RATE / 2) s += HARMONICS[k] * sin(2 * PI * f * t) * exp(-t * (1.0 + k * 0.7))
            }
            s *= min(1.0, t / 0.005)          // 5 ms attack, avoids a click
            tmp[i] = s
            peak = max(peak, abs(s))
        }
        val pcm = ShortArray(n) { (tmp[it] / peak * 0.8 * Short.MAX_VALUE).toInt().toShort() }

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()
        t.write(pcm, 0, n)
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        track?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        track = null
    }

    companion object {
        const val DURATION_MS = 2500
        private const val SAMPLE_RATE = 44100
        private val HARMONICS = doubleArrayOf(1.0, 0.7, 0.5, 0.35, 0.25, 0.15)
    }
}
