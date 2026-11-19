package com.guitartune

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : Activity(), TunerView.Listener {

    private lateinit var tuner: TunerView
    private lateinit var prefs: SharedPreferences
    private val tone = TonePlayer()
    private val metronome = Metronome { beat -> tuner.onBeat(beat) }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Read by the audio thread every hop; changed from the UI. */
    @Volatile
    private var instrument = Instrument.GUITAR

    /** While a reference tone plays, the mic would just hear our own speaker: ignore it. */
    @Volatile
    private var toneUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        instrument = Instrument.entries.firstOrNull { it.name == prefs.getString(KEY_INSTRUMENT, null) }
            ?: Instrument.GUITAR
        val tunings = IntArray(Instrument.entries.size) { prefs.getInt(KEY_TUNING + Instrument.entries[it].name, 0) }
        metronome.bpm = prefs.getInt(KEY_BPM, 100)
        metronome.beatsPerBar = prefs.getInt(KEY_BEATS, 4)

        tuner = TunerView(this)
        tuner.restore(instrument, tunings, metronome.bpm, metronome.beatsPerBar)
        tuner.listener = this
        setContentView(tuner)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!tuner.metronomeScreen && hasMicPermission()) startListening()
    }

    override fun onPause() {
        stopListening()
        stopMetronome()
        tone.stop()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            tuner.message = "마이크 권한이 필요합니다"
        }
        // When granted, onResume() runs right after this and starts listening.
    }

    // --- TunerView.Listener ---

    override fun onInstrumentChanged(inst: Instrument) {
        instrument = inst
        prefs.edit().putString(KEY_INSTRUMENT, inst.name).apply()   // remember for next launch
    }

    override fun onTuningChanged(inst: Instrument, index: Int) {
        prefs.edit().putInt(KEY_TUNING + inst.name, index).apply()
    }

    override fun onPlayTone(hz: Double) {
        toneUntil = SystemClock.uptimeMillis() + TonePlayer.DURATION_MS
        Thread({ tone.play(hz) }, "tone").start()
    }

    override fun onScreenChanged(metronome: Boolean) {
        if (metronome) {
            stopListening()      // no need for the mic on the metronome screen
            tone.stop()
        } else {
            stopMetronome()      // clicks would confuse the tuner
            if (hasMicPermission()) startListening()
        }
    }

    override fun onMetronomeToggle() {
        if (metronome.isRunning) stopMetronome() else metronome.start()
        tuner.metronomeRunning = metronome.isRunning
    }

    override fun onMetronomeSettings(bpm: Int, beats: Int) {
        metronome.bpm = bpm
        metronome.beatsPerBar = beats
        prefs.edit().putInt(KEY_BPM, bpm).putInt(KEY_BEATS, beats).apply()
    }

    private fun stopMetronome() {
        metronome.stop()
        tuner.metronomeRunning = false
    }

    // --- Microphone / pitch detection ---

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        if (running) return
        tuner.message = null
        running = true
        thread = Thread(::audioLoop, "tuner-audio").also { it.start() }
    }

    private fun stopListening() {
        running = false
        thread?.join(500)
        thread = null
    }

    @SuppressLint("MissingPermission") // checked in hasMicPermission() before starting
    private fun audioLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            // VOICE_RECOGNITION disables AGC / noise suppression on most devices
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, FRAME * 2 * 2)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            runOnUiThread { tuner.message = "마이크를 열 수 없습니다" }
            running = false
            return
        }

        // One detector per instrument; bass runs on a 2x-decimated signal (22050 Hz)
        val detectors = Instrument.entries.associateWith {
            PitchDetector(SAMPLE_RATE / it.decimate, FRAME, it.minHz, it.maxHz)
        }
        val raw = FloatArray(RAW)              // sliding history of mic samples
        val frame = FloatArray(FRAME)          // analysis window handed to the detector
        val hop = ShortArray(HOP)
        val history = ArrayDeque<Float>()      // recent pitches for median smoothing
        var smoothed = -1f                     // exponentially smoothed pitch (Hz)
        var lastInstrument = instrument

        recorder.startRecording()
        try {
            while (running) {
                val n = recorder.read(hop, 0, HOP)
                if (n <= 0) continue

                // Slide history left by n samples and append the new ones
                System.arraycopy(raw, n, raw, 0, RAW - n)
                for (i in 0 until n) raw[RAW - n + i] = hop[i] / 32768f

                val inst = instrument
                if (inst != lastInstrument) {
                    history.clear()
                    smoothed = -1f
                    lastInstrument = inst
                }

                // Build the analysis frame: guitar = newest FRAME samples,
                // bass = all RAW samples averaged in pairs (simple low-pass + decimate)
                if (inst.decimate == 1) {
                    System.arraycopy(raw, RAW - FRAME, frame, 0, FRAME)
                } else {
                    for (i in 0 until FRAME) frame[i] = (raw[2 * i] + raw[2 * i + 1]) * 0.5f
                }

                var energy = 0f
                for (v in frame) energy += v * v
                val rms = sqrt(energy / FRAME)

                val toneplaying = SystemClock.uptimeMillis() < toneUntil
                val hz = if (rms > SILENCE_RMS && !toneplaying) detectors.getValue(inst).detect(frame) else -1f

                val result = if (hz > 0) {
                    // 1) median of the last few readings removes single-frame outliers
                    history.addLast(hz)
                    if (history.size > MEDIAN) history.removeFirst()
                    val med = history.sorted()[history.size / 2]

                    // 2) exponential smoothing (in cents) calms the small frame-to-frame wobble,
                    //    but a big jump (new string / large peg turn) is followed immediately
                    smoothed = if (smoothed <= 0 || abs(ln(med / smoothed)) > JUMP) med
                    else smoothed * exp(SMOOTH * ln(med / smoothed)).toFloat()
                    smoothed
                } else {
                    history.clear()
                    smoothed = -1f
                    -1f
                }
                tuner.post { tuner.onPitch(result, inst) }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    companion object {
        private const val REQ_MIC = 1
        private const val SAMPLE_RATE = 44100
        private const val FRAME = 4096        // detector input: ~93 ms guitar, ~186 ms bass
        private const val RAW = FRAME * 2     // mic history long enough for decimated bass
        private const val HOP = 1024          // ~23 ms between updates
        private const val SILENCE_RMS = 0.01f
        private const val MEDIAN = 5
        private const val SMOOTH = 0.15f      // 0..1, lower = calmer but slower
        private val JUMP = ln(2.0).toFloat() * 40f / 1200f   // 40 cents

        private const val PREFS = "tuner"
        private const val KEY_INSTRUMENT = "instrument"
        private const val KEY_TUNING = "tuning_"
        private const val KEY_BPM = "bpm"
        private const val KEY_BEATS = "beats"
    }
}
