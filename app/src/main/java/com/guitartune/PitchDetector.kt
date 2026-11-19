package com.guitartune

import kotlin.math.min

/**
 * YIN pitch detector (de Cheveigné & Kawahara, 2002).
 * Searches [minHz] ~ [maxHz]; the buffer must hold at least 2 periods of [minHz].
 */
class PitchDetector(private val sampleRate: Int, bufferSize: Int, minHz: Int, maxHz: Int) {

    private val window = bufferSize / 2
    private val minTau = sampleRate / maxHz
    private val maxTau = min(window - 1, sampleRate / minHz)
    private val yin = FloatArray(maxTau + 1)

    /** Returns the detected frequency in Hz, or -1 if no clear pitch. */
    fun detect(buf: FloatArray): Float {
        // 1. Difference function
        for (tau in 1..maxTau) {
            var sum = 0f
            for (i in 0 until window) {
                val d = buf[i] - buf[i + tau]
                sum += d * d
            }
            yin[tau] = sum
        }

        // 2. Cumulative mean normalized difference
        yin[0] = 1f
        var running = 0f
        for (tau in 1..maxTau) {
            running += yin[tau]
            yin[tau] = if (running == 0f) 1f else yin[tau] * tau / running
        }

        // 3. Absolute threshold: first dip below THRESHOLD, then walk to its local minimum
        var tau = minTau
        var found = -1
        while (tau < maxTau) {
            if (yin[tau] < THRESHOLD) {
                while (tau + 1 < maxTau && yin[tau + 1] < yin[tau]) tau++
                found = tau
                break
            }
            tau++
        }
        if (found < 0) return -1f

        // 4. Parabolic interpolation for sub-sample accuracy
        val better = if (found > 1 && found < maxTau) {
            val s0 = yin[found - 1]
            val s1 = yin[found]
            val s2 = yin[found + 1]
            val denom = 2f * (2f * s1 - s2 - s0)
            if (denom != 0f) found + (s2 - s0) / denom else found.toFloat()
        } else {
            found.toFloat()
        }
        return sampleRate / better
    }

    companion object {
        private const val THRESHOLD = 0.15f
    }
}
