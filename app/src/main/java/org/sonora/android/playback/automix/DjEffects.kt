package org.sonora.android.playback.automix

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor

/**
 * A tempo-synced feedback delay, one line per channel. As an echo its repeats get darker and
 * thinner, as a DJ mixer's echo does; as a delay they stay clean.
 */
internal class EchoLine(private val channels: Int, private val capacity: Int, sampleRate: Double) {
    private val lines = Array(channels) { FloatArray(capacity) }
    private val lowState = DoubleArray(channels)
    private val highState = DoubleArray(channels)
    private val lowCoef = 1 - exp(-2 * PI * DAMP_LOW_PASS_HZ / sampleRate)
    private val highCoef = 1 - exp(-2 * PI * DAMP_HIGH_PASS_HZ / sampleRate)
    private var write = 0

    /** Feeds [input] into [channel] and returns what comes back [delayFrames] later. */
    fun process(channel: Int, input: Double, delayFrames: Int, feedback: Double, damped: Boolean): Double {
        val line = lines[channel]
        val delay = delayFrames.coerceIn(1, capacity - 1)
        var read = write - delay
        if (read < 0) read += capacity
        val delayed = line[read].toDouble()
        var back = delayed
        if (damped) {
            lowState[channel] += (back - lowState[channel]) * lowCoef
            back = lowState[channel]
            highState[channel] += (back - highState[channel]) * highCoef
            back -= highState[channel]
        }
        line[write] = (input + back * feedback).toFloat()
        return delayed
    }

    /** Moves on by one frame, after every channel was processed. */
    fun advance() {
        write = if (write + 1 == capacity) 0 else write + 1
    }

    fun clear() {
        lines.forEach { it.fill(0f) }
        lowState.fill(0.0)
        highState.fill(0.0)
    }

    private companion object {
        const val DAMP_LOW_PASS_HZ = 3_500.0
        const val DAMP_HIGH_PASS_HZ = 250.0
    }
}

/**
 * A small plate-like reverb (four damped combs into two all-passes per channel, the Freeverb
 * design), tuned to wash out a song rather than place it in a room.
 */
internal class Reverb(private val channels: Int, sampleRate: Double) {
    private class Comb(size: Int) {
        val buffer = FloatArray(size)
        var index = 0
        var store = 0.0

        fun process(x: Double): Double {
            val y = buffer[index].toDouble()
            store = y * (1 - DAMP) + store * DAMP
            buffer[index] = (x + store * FEEDBACK).toFloat()
            index = if (index + 1 == buffer.size) 0 else index + 1
            return y
        }
    }

    private class AllPass(size: Int) {
        val buffer = FloatArray(size)
        var index = 0

        fun process(x: Double): Double {
            val b = buffer[index].toDouble()
            buffer[index] = (x + b * 0.5).toFloat()
            index = if (index + 1 == buffer.size) 0 else index + 1
            return b - x
        }
    }

    private val combs: Array<Array<Comb>>
    private val allPasses: Array<Array<AllPass>>

    init {
        val scale = sampleRate / 44_100.0
        combs = Array(channels) { ch -> Array(COMB_SIZES.size) { Comb(((COMB_SIZES[it] + ch * SPREAD) * scale).toInt().coerceAtLeast(1)) } }
        allPasses = Array(channels) { ch -> Array(ALL_PASS_SIZES.size) { AllPass(((ALL_PASS_SIZES[it] + ch * SPREAD) * scale).toInt().coerceAtLeast(1)) } }
    }

    fun process(channel: Int, input: Double): Double {
        val x = input * INPUT_GAIN
        var out = 0.0
        for (comb in combs[channel]) out += comb.process(x)
        for (allPass in allPasses[channel]) out = allPass.process(out)
        return out * OUTPUT_GAIN
    }

    fun clear() {
        combs.forEach { row -> row.forEach { it.buffer.fill(0f); it.store = 0.0 } }
        allPasses.forEach { row -> row.forEach { it.buffer.fill(0f) } }
    }

    private companion object {
        val COMB_SIZES = intArrayOf(1_116, 1_188, 1_277, 1_356)
        val ALL_PASS_SIZES = intArrayOf(556, 441)
        const val SPREAD = 23
        const val FEEDBACK = 0.86
        const val DAMP = 0.3
        const val INPUT_GAIN = 0.03
        const val OUTPUT_GAIN = 2.2
    }
}

/** White noise, the same on every channel (a riser is not meant to sound wide). */
internal class NoiseSource {
    private var state = 0x2545F491

    fun next(): Double {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return x / 2_147_483_648.0
    }
}

/**
 * The last few seconds of what a deck played, so a record can be slowed down or spun back over it.
 * Frames are counted from the first one written; reading between frames interpolates.
 */
internal class DeckHistory(private val channels: Int, private val capacity: Int) {
    private val buffer = FloatArray(capacity * channels)

    /** Frames written so far; the newest frame is [written] - 1. */
    var written = 0L
        private set

    fun write(frame: FloatArray) {
        val base = ((written % capacity) * channels).toInt()
        for (ch in 0 until channels) buffer[base + ch] = frame[ch]
        written++
    }

    /** The sound at [position] (a frame number, fractional), or silence when it is no longer kept. */
    fun read(position: Double, channel: Int): Double {
        val i = floor(position).toLong()
        val oldest = (written - capacity + 1).coerceAtLeast(0L)
        if (i < oldest || i >= written) return 0.0
        if (i == written - 1) return at(i, channel)
        val frac = position - i
        return at(i, channel) * (1 - frac) + at(i + 1, channel) * frac
    }

    private fun at(frame: Long, channel: Int): Double = buffer[((frame % capacity) * channels).toInt() + channel].toDouble()

    fun clear() {
        written = 0L
    }
}

/**
 * The stretch of a song a loop repeats, captured as it plays: from a few milliseconds before the
 * loop's start, which lets each repeat cross into the next without a click.
 */
internal class LoopBuffer(private val channels: Int, private val lengthFrames: Int, val crossFrames: Int) {
    private val buffer = FloatArray((lengthFrames + crossFrames) * channels)

    /** Frames captured so far, counted from [crossFrames] before the loop start. */
    var captured = 0
        private set

    val isFull: Boolean get() = captured >= lengthFrames + crossFrames

    fun capture(frame: FloatArray) {
        if (isFull) return
        val base = captured * channels
        for (ch in 0 until channels) buffer[base + ch] = frame[ch]
        captured++
    }

    /** The frame [offset] frames from the loop start; negative offsets reach into the lead-in. */
    fun at(offset: Int, channel: Int): Double {
        val index = offset + crossFrames
        if (index < 0 || index >= captured) return 0.0
        return buffer[index * channels + channel].toDouble()
    }

    /**
     * The looped sound at [position] frames from the loop start, for a loop of [length] frames. Near
     * the end of each repeat it crosses into the lead-in, which runs into the loop start seamlessly.
     */
    fun looped(position: Long, length: Int, channel: Int): Double {
        val n = length.coerceIn(1, lengthFrames)
        val m = (position % n).toInt()
        val cross = crossFrames.coerceAtMost(n / 2)
        if (cross <= 0 || m < n - cross) return at(m, channel)
        val w = (m - (n - cross) + 1).toDouble() / cross
        return at(m, channel) * (1 - w) + at(m - n, channel) * w
    }

    fun clear() {
        captured = 0
    }
}
