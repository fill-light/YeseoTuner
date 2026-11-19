package com.guitartune

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sample-accurate metronome: clicks are mixed into a continuous PCM stream,
 * so the tempo never drifts. [onBeat] runs on the main thread with the beat index (0 = downbeat).
 */
class Metronome(private val onBeat: (Int) -> Unit) {

    @Volatile var bpm = 100
    @Volatile var beatsPerBar = 4

    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "metronome").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf)
            .build()

        val accent = click(1760.0, 0.9)   // downbeat: higher and louder
        val normal = click(1175.0, 0.6)
        val chunk = ShortArray(256)
        var pos = 0          // sample position inside the current beat
        var beat = 0

        track.play()
        main.post { onBeat(0) }
        try {
            while (running) {
                val beatLen = SAMPLE_RATE * 60 / bpm
                for (i in chunk.indices) {
                    if (pos >= beatLen) {
                        pos = 0
                        beat = (beat + 1) % beatsPerBar
                        val b = beat
                        main.post { onBeat(b) }
                    }
                    if (beat >= beatsPerBar) beat = 0   // time signature changed mid-bar
                    val c = if (beat == 0) accent else normal
                    chunk[i] = if (pos < c.size) c[pos] else 0
                    pos++
                }
                track.write(chunk, 0, chunk.size)   // blocks until there is room = real-time pacing
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun click(hz: Double, gain: Double): ShortArray {
        val n = SAMPLE_RATE * 30 / 1000   // 30 ms
        return ShortArray(n) {
            val t = it.toDouble() / SAMPLE_RATE
            (sin(2 * PI * hz * t) * exp(-t * 150) * gain * Short.MAX_VALUE).toInt().toShort()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        const val MIN_BPM = 30
        const val MAX_BPM = 250
    }
}
