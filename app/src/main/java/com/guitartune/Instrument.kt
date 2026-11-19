package com.guitartune

import kotlin.math.pow

private val SHARPS = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
private val FLATS = arrayOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")

fun noteName(midi: Int, flats: Boolean): String = (if (flats) FLATS else SHARPS)[midi % 12]

/** One tuning: MIDI note per string, lowest → highest. [flats] = spell notes with ♭. */
class Tuning(val label: String, val midi: IntArray, flats: Boolean = false) {
    val notes: Array<String> = Array(midi.size) { noteName(midi[it], flats) }
    val freqs: DoubleArray = DoubleArray(midi.size) { 440.0 * 2.0.pow((midi[it] - 69) / 12.0) }
    val summary: String get() = notes.joinToString(" ")
}

/**
 * Supported instruments. The first tuning of each is the standard one.
 *
 * [decimate] = 2 halves the sample rate before pitch detection. Bass notes go down to
 * B♭0 (29 Hz), so they need a longer analysis window; decimating keeps the CPU cost
 * the same as for guitar.
 */
enum class Instrument(
    val label: String,
    val tunings: List<Tuning>,
    val minHz: Int,
    val maxHz: Int,
    val decimate: Int,
) {
    GUITAR(
        "통기타",
        listOf(
            Tuning("표준", intArrayOf(40, 45, 50, 55, 59, 64)),
            Tuning("드롭 D", intArrayOf(38, 45, 50, 55, 59, 64)),
            Tuning("반음 내림", intArrayOf(39, 44, 49, 54, 58, 63), flats = true),
            Tuning("온음 내림", intArrayOf(38, 43, 48, 53, 57, 62)),
            Tuning("오픈 G", intArrayOf(38, 43, 50, 55, 59, 62)),
            Tuning("DADGAD", intArrayOf(38, 45, 50, 55, 57, 62)),
        ),
        60, 1000, 1,
    ),
    BASS4(
        "베이스 4현",
        listOf(
            Tuning("표준", intArrayOf(28, 33, 38, 43)),
            Tuning("드롭 D", intArrayOf(26, 33, 38, 43)),
            Tuning("반음 내림", intArrayOf(27, 32, 37, 42), flats = true),
        ),
        28, 400, 2,
    ),
    BASS6(
        "베이스 6현",
        listOf(
            Tuning("표준", intArrayOf(23, 28, 33, 38, 43, 48)),
            Tuning("반음 내림", intArrayOf(22, 27, 32, 37, 42, 47), flats = true),
        ),
        28, 400, 2,
    );

    val count: Int get() = tunings[0].midi.size
    val isBass: Boolean get() = this != GUITAR
}
