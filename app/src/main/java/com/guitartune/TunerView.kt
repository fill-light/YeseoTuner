package com.guitartune

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The whole app UI, drawn on a Canvas:
 *  - header: dedication + screen pills (통기타 / 베이스 4현 / 베이스 6현 / 메트로놈)
 *  - tuner screen: tuning picker, note, needle meter, headstock with tuning keys
 *    (tap a key = lock that string, long-press = play its reference tone)
 *  - metronome screen
 */
class TunerView(context: Context) : View(context) {

    interface Listener {
        fun onInstrumentChanged(inst: Instrument)
        fun onTuningChanged(inst: Instrument, index: Int)
        fun onPlayTone(hz: Double)
        fun onScreenChanged(metronome: Boolean)
        fun onMetronomeToggle()
        fun onMetronomeSettings(bpm: Int, beats: Int)
    }

    var listener: Listener? = null

    // ---------------------------------------------------------------- state

    var instrument = Instrument.GUITAR
        private set
    private val tuningIdx = IntArray(Instrument.entries.size)
    private val tuning: Tuning get() = instrument.tunings[tuningIdx[instrument.ordinal]]

    var metronomeScreen = false
        private set
    var metronomeRunning = false
        set(value) { field = value; if (!value) currentBeat = -1; invalidate() }
    private var bpm = 100
    private var beats = 4
    private var currentBeat = -1
    private val taps = ArrayDeque<Long>()

    private var hz = -1f
    private var stringIdx = -1
    private var cents = 0f        // target value
    private var shownCents = 0f   // animated needle value
    private var lastHeard = 0L
    private var inTune = false    // latched with hysteresis so the colour doesn't flicker

    /** -1 = auto (nearest string), otherwise the string locked by tapping its tuning key. */
    private var lockedIdx = -1

    /** Key whose reference tone is playing (highlighted) and until when. */
    private var toneKey = -1
    private var toneUntil = 0L

    /** Error message (e.g. no permission). null = normal operation. */
    var message: String? = null
        set(value) { field = value; invalidate() }

    fun restore(inst: Instrument, tunings: IntArray, bpm: Int, beats: Int) {
        instrument = inst
        for (i in tuningIdx.indices) {
            tuningIdx[i] = tunings[i].coerceIn(0, Instrument.entries[i].tunings.size - 1)
        }
        this.bpm = bpm.coerceIn(Metronome.MIN_BPM, Metronome.MAX_BPM)
        this.beats = beats
        invalidate()
    }

    private fun resetReading() {
        hz = -1f
        stringIdx = -1
        lockedIdx = -1
        inTune = false
    }

    fun onPitch(newHz: Float, from: Instrument) {
        if (from != instrument) return   // stale result from before a switch
        if (newHz > 0) {
            hz = newHz
            lastHeard = SystemClock.uptimeMillis()
            updateTarget()
        }
        invalidate()
    }

    fun onBeat(beat: Int) {
        currentBeat = beat
        invalidate()
    }

    private fun updateTarget() {
        if (hz <= 0) return
        val f = tuning.freqs
        val prev = stringIdx
        stringIdx = if (lockedIdx >= 0) lockedIdx
        else f.indices.minBy { abs(ln(hz / f[it])) }
        cents = (1200 * ln(hz / f[stringIdx]) / ln(2.0)).toFloat()
        val c = abs(cents)
        inTune = if (inTune && prev == stringIdx) c <= IN_TUNE_EXIT else c <= IN_TUNE_ENTER
    }

    private fun isActive() = hz > 0 && SystemClock.uptimeMillis() - lastHeard < HOLD_MS

    // ---------------------------------------------------------------- touch

    /** A tappable area registered while drawing (layout coordinates). */
    private class Hit(val rect: RectF, val onLong: (() -> Unit)?, val onTap: () -> Unit)

    private val hits = ArrayList<Hit>()
    private var pressed: Hit? = null
    private var longFired = false
    private val longPress = Runnable {
        pressed?.onLong?.let { longFired = true; it() }
    }

    private fun hit(l: Float, t: Float, r: Float, b: Float, onLong: (() -> Unit)? = null, onTap: () -> Unit) {
        hits += Hit(RectF(l, t, r, b), onLong, onTap)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Layout coordinates are relative to the padded (safe) area
        val x = event.x - paddingLeft
        val y = event.y - paddingTop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.lastOrNull { it.rect.contains(x, y) }
                longFired = false
                if (pressed?.onLong != null) postDelayed(longPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val p = pressed
                pressed = null
                if (p != null && !longFired && p.rect.contains(x, y)) {
                    p.onTap()
                    performClick()
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                pressed = null
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ---------------------------------------------------------------- actions

    private fun selectInstrument(inst: Instrument) {
        if (metronomeScreen) {
            metronomeScreen = false
            listener?.onScreenChanged(false)
        }
        if (inst != instrument) {
            instrument = inst
            resetReading()
            listener?.onInstrumentChanged(inst)
        }
    }

    private fun openMetronome() {
        if (metronomeScreen) return
        metronomeScreen = true
        resetReading()
        listener?.onScreenChanged(true)
    }

    private fun toggleLock(i: Int) {
        lockedIdx = if (lockedIdx == i) -1 else i   // tap again to go back to auto
        updateTarget()
    }

    private fun playTone(i: Int) {
        toneKey = i
        toneUntil = SystemClock.uptimeMillis() + TonePlayer.DURATION_MS
        listener?.onPlayTone(tuning.freqs[i])
        invalidate()
        postDelayed({ invalidate() }, TonePlayer.DURATION_MS + 50L)
    }

    private fun pickTuning() {
        val inst = instrument
        val items = inst.tunings.map { "${it.label}    ${it.summary}" }.toTypedArray()
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${inst.label} 튜닝")
            .setSingleChoiceItems(items, tuningIdx[inst.ordinal]) { dialog, which ->
                tuningIdx[inst.ordinal] = which
                resetReading()
                listener?.onTuningChanged(inst, which)
                invalidate()
                dialog.dismiss()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun setBpm(value: Int) {
        bpm = value.coerceIn(Metronome.MIN_BPM, Metronome.MAX_BPM)
        listener?.onMetronomeSettings(bpm, beats)
    }

    private fun setBeats(value: Int) {
        beats = value
        listener?.onMetronomeSettings(bpm, beats)
    }

    /** Tap tempo: average of the last few intervals; a pause > 2 s starts over. */
    private fun tapTempo() {
        val now = SystemClock.uptimeMillis()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.addLast(now)
        if (taps.size > 5) taps.removeFirst()
        if (taps.size >= 2) {
            val avg = (taps.last() - taps.first()).toFloat() / (taps.size - 1)
            setBpm((60000f / avg).roundToInt())
        }
    }

    // ---------------------------------------------------------------- insets

    /**
     * Android 15+ draws apps edge-to-edge (behind the status and navigation bars).
     * Pad the view by the system-bar / camera-cutout insets so nothing is hidden under them.
     */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            setPadding(b.left, b.top, b.right, b.bottom)
        } else {
            @Suppress("DEPRECATION")
            setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
        }
        return insets
    }

    // ---------------------------------------------------------------- drawing

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()
    private val tmpRect = RectF()
    private val headPath = Path()

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        text.textSize = size
        text.color = color
        text.isFakeBoldText = bold
        c.drawText(s, x, y, text)
        text.isFakeBoldText = false
    }

    private fun pill(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        tmpRect.set(l, t, r, b)
        fill.color = color
        c.drawRoundRect(tmpRect, (b - t) / 2, (b - t) / 2, fill)
    }

    override fun onDraw(canvas: Canvas) {
        // Background fills the whole screen (also behind the system bars)...
        canvas.drawColor(BG)
        // ...but the layout lives inside the safe area.
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        hits.clear()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val availW = (width - paddingLeft - paddingRight).toFloat()
        // Lay everything out in a centered portrait-shaped column (width ≤ 50% of height),
        // so wide screens (tablets, landscape) don't stretch the layout.
        val w = min(availW, h * 0.5f)
        val cx = availW / 2f
        // Size unit: 1u = 1dp on a 360dp-wide phone, scales up proportionally on tablets.
        val u = w / 360f
        // Extra vertical room on tall screens; part of it pushes the middle block down
        val e = max(0f, h - 720 * u) * 0.3f

        drawHeader(canvas, cx, w, u)
        if (metronomeScreen) drawMetronome(canvas, cx, w, u, e)
        else drawTuner(canvas, cx, w, u, h, e)
        canvas.restore()
    }

    private fun drawHeader(c: Canvas, cx: Float, w: Float, u: Float) {
        txt(c, DEDICATION, cx, 24 * u, 15 * u, PINK)

        val labels = Instrument.entries.map { it.label } + "메트로놈"
        val gap = 6 * u
        val pillW = (w - 16 * u - gap * (labels.size - 1)) / labels.size
        val left0 = cx - w / 2 + 8 * u
        val top = 41 * u
        val bottom = 71 * u
        for ((k, label) in labels.withIndex()) {
            val l = left0 + k * (pillW + gap)
            val on = if (k < Instrument.entries.size) !metronomeScreen && instrument.ordinal == k else metronomeScreen
            pill(c, l, top, l + pillW, bottom, if (on) PINK else DARK)
            txt(c, label, l + pillW / 2, 61 * u, 13 * u, if (on) BG else Color.LTGRAY, on)
            hit(l, top - 6 * u, l + pillW, bottom + 6 * u) {
                if (k < Instrument.entries.size) selectInstrument(Instrument.entries[k]) else openMetronome()
            }
        }
    }

    // ---------------------------------------------------------------- tuner screen

    private fun drawTuner(c: Canvas, cx: Float, w: Float, u: Float, h: Float, e: Float) {
        val inst = instrument
        val tun = tuning
        val active = isActive()
        val good = active && inTune
        val accent = when {
            !active -> GREY
            good -> GREEN
            else -> ORANGE
        }

        // --- Tuning picker chip ---
        val chip = "튜닝: ${tun.label}   ${tun.summary}   ▾"
        text.textSize = 13 * u
        val chipW = text.measureText(chip) + 28 * u
        pill(c, cx - chipW / 2, 81 * u, cx + chipW / 2, 107 * u, DARK)
        txt(c, chip, cx, 98.5f * u, 13 * u, Color.LTGRAY)
        hit(cx - chipW / 2, 75 * u, cx + chipW / 2, 113 * u) { pickTuning() }

        // --- Note name ---
        txt(c, if (active) tun.notes[stringIdx] else "-", cx, 172 * u + e, 76 * u, if (active) accent else GREY, true)

        // --- Hint ---
        val hint = message ?: when {
            !active -> "줄을 튕겨 주세요"
            good -> "정확해요"
            cents < 0 -> "낮아요 ▲ 조이세요"
            else -> "높아요 ▼ 푸세요"
        }
        txt(c, hint, cx, 204 * u + e, 20 * u, Color.WHITE)

        // --- Meter arc (±50 cents mapped to ±60°) ---
        val r = w * 0.35f
        val cy = 342 * u + e
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        line.strokeWidth = 4 * u
        line.color = DARK
        c.drawArc(arcRect, -150f, 120f, false, line)
        line.color = GREEN
        line.strokeWidth = 8 * u
        val zone = IN_TUNE_ENTER / 50f * 60f
        c.drawArc(arcRect, -90f - zone, zone * 2, false, line)

        // Ticks every 10 cents
        line.color = Color.LTGRAY
        for (t in -50..50 step 10) {
            val a = Math.toRadians((t / 50.0 * 60.0) - 90.0)
            val len = if (t == 0 || abs(t) == 50) 18 * u else 10 * u
            line.strokeWidth = if (t == 0) 3 * u else 2 * u
            val x1 = cx + (r - 6 * u) * cos(a).toFloat()
            val y1 = cy + (r - 6 * u) * sin(a).toFloat()
            val x2 = cx + (r - 6 * u - len) * cos(a).toFloat()
            val y2 = cy + (r - 6 * u - len) * sin(a).toFloat()
            c.drawLine(x1, y1, x2, y2, line)
        }

        // Needle: eased toward the target so it glides instead of jumping
        val target = if (active) cents.coerceIn(-50f, 50f) else 0f
        shownCents += (target - shownCents) * NEEDLE_EASE
        val a = Math.toRadians((shownCents / 50.0 * 60.0) - 90.0)
        line.color = accent
        line.strokeWidth = 5 * u
        c.drawLine(cx, cy, cx + (r - 28 * u) * cos(a).toFloat(), cy + (r - 28 * u) * sin(a).toFloat(), line)
        fill.color = accent
        c.drawCircle(cx, cy, 10 * u, fill)

        // --- Cents and frequency (just under the needle pivot) ---
        if (active) {
            txt(c, "%+.0f cent".format(cents), cx, cy + 30 * u, 18 * u, Color.LTGRAY)
            txt(c, "%.1f Hz  (목표 %.1f Hz)".format(hz, tun.freqs[stringIdx]), cx, cy + 50 * u, 14 * u, GREY)
        }

        // --- Mode label ---
        val mode = if (lockedIdx >= 0)
            "고정: ${inst.count - lockedIdx}번 줄 (${tun.notes[lockedIdx]}) · 다시 누르면 자동"
        else
            "줄감개: 누르면 고정 · 길게 누르면 기준음"
        txt(c, mode, cx, cy + 72 * u, 13 * u, if (lockedIdx >= 0) Color.WHITE else GREY)

        // --- Headstock, anchored to the bottom ---
        val nut = h - 26 * u
        val hh = max(150 * u, min(250 * u, nut - (cy + 86 * u)))
        drawHeadstock(c, inst, tun, cx, w, u, nut - hh, nut, active, accent)

        // Keep animating while the needle is still moving.
        // (The audio thread calls onPitch() every ~23 ms, so the idle state after HOLD_MS appears on its own.)
        if (abs(target - shownCents) > 0.05f) postInvalidateOnAnimation()
    }

    /**
     * Headstock seen from the front, pointing up, with half the tuning keys on each side.
     * Left side holds the low strings (lowest at the bottom), right side the high strings
     * (highest at the bottom) — e.g. guitar: left 6E/5A/4D, right 3G/2B/1E (top→bottom).
     * [top] is the headstock top, [nut] is where the neck starts.
     */
    private fun drawHeadstock(
        c: Canvas, inst: Instrument, tun: Tuning, cx: Float, w: Float, u: Float,
        top: Float, nut: Float, active: Boolean, accent: Int
    ) {
        val n = inst.count
        val perSide = n / 2
        val hh = nut - top
        val nutHalf = w * (if (inst.isBass) 0.11f else 0.10f)
        val topHalf = w * 0.22f
        val shoulder = top + hh * 0.10f

        // Neck / fretboard below the nut, extending past the safe area behind the navigation bar
        fill.color = FRETBOARD
        c.drawRect(cx - nutHalf, nut, cx + nutHalf, (height - paddingTop).toFloat(), fill)

        // Post / key positions. Row 0 = top.
        val levels = if (perSide == 3) floatArrayOf(0.24f, 0.47f, 0.70f) else floatArrayOf(0.30f, 0.60f)
        val postX = w * 0.10f
        val keyOffset = w * 0.33f
        val keyR = w * 0.068f
        val keyX = FloatArray(n)
        val keyY = FloatArray(n)
        fun isLeft(i: Int) = i < perSide
        fun postXOf(i: Int) = if (isLeft(i)) cx - postX else cx + postX
        for (i in 0 until n) {
            val row = if (isLeft(i)) perSide - 1 - i else i - perSide
            keyX[i] = if (isLeft(i)) cx - keyOffset else cx + keyOffset
            keyY[i] = top + hh * levels[row]
        }

        // Key shafts, drawn first so the headstock hides the part behind it
        line.color = SHAFT
        line.strokeWidth = 5 * u
        for (i in 0 until n) c.drawLine(postXOf(i), keyY[i], keyX[i], keyY[i], line)

        // Headstock body: tapered sides, curved top with a center dip
        headPath.reset()
        headPath.moveTo(cx - nutHalf, nut)
        headPath.lineTo(cx - topHalf, shoulder)
        headPath.quadTo(cx - topHalf - 4 * u, top, cx - topHalf * 0.5f, top)
        headPath.quadTo(cx, top + hh * 0.07f, cx + topHalf * 0.5f, top)
        headPath.quadTo(cx + topHalf + 4 * u, top, cx + topHalf, shoulder)
        headPath.lineTo(cx + nutHalf, nut)
        headPath.close()
        fill.color = WOOD
        c.drawPath(headPath, fill)
        line.color = WOOD_EDGE
        line.strokeWidth = 2 * u
        c.drawPath(headPath, line)

        // Headstock "brand" logo, like a custom-made instrument
        txt(c, HEAD_LOGO, cx, top + hh * 0.165f, hh * 0.09f, GOLD, true)

        // Strings: from nut to their post. Bass strings are thicker.
        val spread = nutHalf * 0.8f
        val thickStart = if (inst.isBass) 4.6f else 3.2f
        for (i in 0 until n) {
            val nx = cx - spread + i * (2 * spread / (n - 1))
            val selected = active && i == stringIdx
            line.color = if (selected) accent else STRING
            line.strokeWidth = (thickStart - i * 2.0f / (n - 1)) * u + if (selected) 1.5f * u else 0f
            c.drawLine(nx, nut, postXOf(i), keyY[i], line)
        }

        // Posts
        for (i in 0 until n) {
            fill.color = POST
            c.drawCircle(postXOf(i), keyY[i], 6 * u, fill)
            fill.color = WOOD_EDGE
            c.drawCircle(postXOf(i), keyY[i], 2.5f * u, fill)
        }

        // Nut
        fill.color = NUT
        c.drawRect(cx - nutHalf, nut - 3 * u, cx + nutHalf, nut + 4 * u, fill)

        // Tuning keys = string buttons (tap = lock, long-press = reference tone)
        val toneOn = SystemClock.uptimeMillis() < toneUntil
        for (i in 0 until n) {
            val x = keyX[i]
            val y = keyY[i]
            val selected = active && i == stringIdx
            val playing = toneOn && i == toneKey
            fill.color = when {
                playing -> PINK
                selected -> accent
                else -> KEY
            }
            c.drawCircle(x, y, keyR, fill)
            if (i == lockedIdx) {   // ring marks the locked string
                line.color = Color.WHITE
                line.strokeWidth = 3 * u
                c.drawCircle(x, y, keyR + 5 * u, line)
            }
            val label = tun.notes[i]
            val size = keyR * (if (label.length > 1) 0.75f else 0.95f)
            txt(c, label, x, y + size * 0.35f, size, if (selected || playing) BG else Color.WHITE, true)

            // String number outside the key
            val lx = if (isLeft(i)) x - keyR - 11 * u else x + keyR + 11 * u
            txt(c, "${n - i}", lx, y + 4.5f * u, 13 * u, GREY)

            val hr = keyR * 1.5f
            hit(x - hr, y - hr, x + hr, y + hr, onLong = { playTone(i) }) { toggleLock(i) }
        }
    }

    // ---------------------------------------------------------------- metronome screen

    private fun drawMetronome(c: Canvas, cx: Float, w: Float, u: Float, e: Float) {
        // --- BPM ---
        txt(c, "$bpm", cx, 196 * u + e, 96 * u, Color.WHITE, true)
        txt(c, "BPM · ${tempoName(bpm)}", cx, 226 * u + e, 15 * u, GREY)

        // --- -5 -1 +1 +5 ---
        val steps = intArrayOf(-5, -1, 1, 5)
        val stepX = floatArrayOf(-105f, -35f, 35f, 105f)
        val by = 282 * u + e
        val br = 25 * u
        for (k in steps.indices) {
            val x = cx + stepX[k] * u
            fill.color = DARK
            c.drawCircle(x, by, br, fill)
            val s = steps[k]
            txt(c, if (s > 0) "+$s" else "−${-s}", x, by + 6 * u, 17 * u, Color.WHITE, true)
            hit(x - br * 1.3f, by - br * 1.3f, x + br * 1.3f, by + br * 1.3f) { setBpm(bpm + s) }
        }

        // --- Beat dots ---
        val dy = 350 * u + e
        val spacing = min(40 * u, (w - 40 * u) / beats)
        for (i in 0 until beats) {
            val x = cx + (i - (beats - 1) / 2f) * spacing
            val lit = metronomeRunning && i == currentBeat
            fill.color = when {
                lit && i == 0 -> PINK
                lit -> ORANGE
                else -> DARK
            }
            c.drawCircle(x, dy, if (i == 0) 13 * u else 10 * u, fill)
        }

        // --- Time signature ---
        val sigs = intArrayOf(2, 3, 4, 6)
        val sigLabels = arrayOf("2/4", "3/4", "4/4", "6/8")
        val sy = 404 * u + e
        val sw = 62 * u
        val sg = 10 * u
        val s0 = cx - (sigs.size * sw + (sigs.size - 1) * sg) / 2
        for (k in sigs.indices) {
            val l = s0 + k * (sw + sg)
            val on = beats == sigs[k]
            pill(c, l, sy - 15 * u, l + sw, sy + 15 * u, if (on) PINK else DARK)
            txt(c, sigLabels[k], l + sw / 2, sy + 5 * u, 14 * u, if (on) BG else Color.LTGRAY, on)
            hit(l, sy - 21 * u, l + sw, sy + 21 * u) { setBeats(sigs[k]) }
        }

        // --- Start / stop ---
        val py = 500 * u + e
        val pr = 50 * u
        fill.color = if (metronomeRunning) ORANGE else GREEN
        c.drawCircle(cx, py, pr, fill)
        txt(c, if (metronomeRunning) "■ 정지" else "▶ 시작", cx, py + 7 * u, 19 * u, BG, true)
        hit(cx - pr, py - pr, cx + pr, py + pr) { listener?.onMetronomeToggle() }

        // --- Tap tempo ---
        val ty = 594 * u + e
        pill(c, cx - 70 * u, ty - 20 * u, cx + 70 * u, ty + 20 * u, DARK)
        txt(c, "탭 템포 (박자대로 누르기)", cx, ty + 5 * u, 13 * u, Color.LTGRAY)
        hit(cx - 80 * u, ty - 26 * u, cx + 80 * u, ty + 26 * u) { tapTempo() }
    }

    private fun tempoName(b: Int) = when {
        b < 60 -> "Largo (아주 느리게)"
        b < 76 -> "Adagio (느리게)"
        b < 108 -> "Andante (걷는 빠르기)"
        b < 120 -> "Moderato (보통 빠르게)"
        b < 168 -> "Allegro (빠르게)"
        else -> "Presto (아주 빠르게)"
    }

    companion object {
        private const val HOLD_MS = 1500L
        private const val LONG_PRESS_MS = 450L
        private const val IN_TUNE_ENTER = 4f   // cents: turns green within ±4
        private const val IN_TUNE_EXIT = 6f    // ...and stays green until beyond ±6
        private const val NEEDLE_EASE = 0.15f

        private const val DEDICATION = "♥ 예서를 위해 아빠가 만듦 ♥"
        private const val HEAD_LOGO = "예서"
        private val PINK = Color.rgb(244, 143, 177)
        private val GOLD = Color.rgb(230, 190, 110)
        private val BG = Color.rgb(18, 18, 18)
        private val DARK = Color.rgb(60, 60, 60)
        private val GREY = Color.rgb(120, 120, 120)
        private val GREEN = Color.rgb(76, 175, 80)
        private val ORANGE = Color.rgb(255, 152, 0)

        private val WOOD = Color.rgb(92, 58, 38)
        private val WOOD_EDGE = Color.rgb(140, 95, 60)
        private val FRETBOARD = Color.rgb(45, 30, 22)
        private val NUT = Color.rgb(235, 225, 200)
        private val STRING = Color.rgb(200, 195, 180)
        private val POST = Color.rgb(200, 200, 200)
        private val SHAFT = Color.rgb(150, 150, 150)
        private val KEY = Color.rgb(70, 70, 70)
    }
}
