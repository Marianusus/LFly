package org.sonora.android.playback.automix

import android.content.Context
import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * A transition as one deck plays it on its own song's clock: the outgoing side of [recipe] from
 * [startMs] of the song for [mixMs], with the effects ringing on for their tail afterwards.
 */
internal class DeckSchedule(
    val recipe: MixRecipe,
    /** Song time where the mix starts, in ms. */
    val startMs: Double,
    val mixMs: Double,
    /** One beat of the song at its own tempo, in ms. */
    val beatMs: Double,
    val outgoingGain: Float = 1f,
    val swapAt: Float = TransitionCurves.SWAP_AT,
    val swapWidth: Float = TransitionCurves.SWAP_WIDTH
) {
    val beatShare: Float = (beatMs / mixMs.coerceAtLeast(1.0)).toFloat()
    val mixBeats: Double = mixMs / beatMs.coerceAtLeast(1.0)
    val tailShare: Float = (TransitionEffects.tailMs(recipe.effect) / mixMs.coerceAtLeast(1.0)).toFloat()

    fun progress(songMs: Double): Float = ((songMs - startMs) / mixMs.coerceAtLeast(1.0)).toFloat()
}

/**
 * One deck of a DJ mixer inside ExoPlayer's audio chain: gain, a three-band EQ, and a high-pass and a
 * low-pass with resonance.
 * Settings come from any thread through [set] and are glided to over a few tens of milliseconds,
 * so moving them never clicks. At rest it copies the audio through untouched.
 *
 * It also knows which moment of the song it is processing ([stamp], from the audio sink), which runs
 * ahead of what is heard by the output buffer. With a [schedule] the deck plays the outgoing side of a
 * transition by itself on that clock, with its effects: echo, delay, reverb, noise, a record stop or
 * spinback, and loops, each landing on the beat it was planned for.
 */
@OptIn(UnstableApi::class)
internal class DjFilterProcessor : BaseAudioProcessor() {

    private companion object {
        /** Coefficients are recomputed once per block while settings move. */
        const val BLOCK_FRAMES = 32

        /** A schedule is read every this many frames, about 5 ms. */
        const val EVAL_FRAMES = 256
        const val GLIDE_SECONDS = 0.03
        const val SNAP = 1e-3
        const val MAX_Q = 4.0

        /** A stamp this far from the counted clock is a seek: the kept audio no longer follows on. */
        const val JUMP_SECONDS = 0.05

        /** Longest echo the deck keeps room for: one beat at 40 BPM. */
        const val MAX_ECHO_SECONDS = 1.5
        const val LOOP_CROSS_SECONDS = 0.004
        const val HISTORY_MARGIN_SECONDS = 0.5

        /** A larger lead is not the output buffer but a clock gone wrong. */
        const val MAX_LEAD_MS = 10_000L

        /** Where the song stood at the start of each recent buffer sent to the AudioTrack: seconds of them. */
        const val MAP_SIZE = 1_024
    }

    @Volatile
    private var target = DjFilterSettings.NEUTRAL

    @Volatile
    private var wanted: DeckSchedule? = null

    @Volatile
    private var pendingStampUs = Long.MIN_VALUE

    @Volatile
    private var processedMs = Double.NaN

    fun set(settings: DjFilterSettings) {
        target = settings
    }

    fun setNeutral() = set(DjFilterSettings.NEUTRAL)

    /** Plays [schedule] on this deck from now on, or stops doing so (null), back to the settings set from outside. */
    fun schedule(schedule: DeckSchedule?) {
        wanted = schedule
        if (schedule == null) target = DjFilterSettings.NEUTRAL
    }

    /** Called by the audio sink before each buffer of the song: the song time where that buffer starts. */
    fun stamp(songUs: Long) {
        pendingStampUs = songUs
    }

    /**
     * How far the audio being processed is ahead of what is heard (from the AudioTrack, or
     * [positionMs] when it cannot tell); null
     * while the deck has no clock yet or the difference makes no sense.
     */
    fun leadMs(positionMs: Long): Long? {
        val processed = processedMs
        if (processed.isNaN()) return null
        val lead = (processed - (heardSongMs() ?: positionMs.toDouble())).toLong()
        // With the tempo on the AudioTrack its buffer is sized for the fastest speed: seconds of audio.
        return lead.takeIf { it in 0L..MAX_LEAD_MS }
    }

    // ── What is heard, from the AudioTrack itself ───────────────────────────
    // ExoPlayer's own position is a guess for a while after every seek and tempo change, too rough
    // to line up two copies of a song. The AudioTrack's timestamp says which frame it is presenting
    // now; the map below says which moment of the song each frame written to it was.

    @Volatile
    private var track: AudioTrack? = null
    private var trackOutBase = 0L
    private val mapLock = Any()
    private val mapOut = LongArray(MAP_SIZE)
    private val mapSong = LongArray(MAP_SIZE)
    private var mapCount = 0L

    /** Frames handed on since the last flush, which is also when the sink makes a new AudioTrack. */
    private var outFrames = 0L
    private val timestamp = AudioTimestamp()

    /** Called with each AudioTrack the sink makes for this deck. */
    fun attach(audioTrack: AudioTrack) {
        synchronized(mapLock) {
            trackOutBase = outFrames
            track = audioTrack
        }
    }

    /**
     * The song time coming out of the speaker right now, in ms, read from the AudioTrack's own
     * timestamp; null while it has none yet (just started, seeked or flushed) or on a device without.
     */
    fun heardSongMs(): Double? {
        val audioTrack = track ?: return null
        val rate = sampleRate.takeIf { it > 0 } ?: return null
        return try {
            synchronized(mapLock) {
                if (audioTrack !== track || !audioTrack.getTimestamp(timestamp)) return null
                if (timestamp.framePosition <= 0L) return null
                val speed = audioTrack.playbackParams.speed.toDouble().takeIf { it > 0 } ?: 1.0
                val elapsedSeconds = (System.nanoTime() - timestamp.nanoTime) / 1e9
                val presented = trackOutBase + timestamp.framePosition + elapsedSeconds * rate * speed
                songFrameOfOutput(presented)?.let { it * 1_000.0 / rate }
            }
        } catch (e: IllegalStateException) {
            null
        }
    }

    /** The song frame of the [output]th frame handed on since the flush, from the map; under [mapLock]. */
    private fun songFrameOfOutput(output: Double): Double? {
        if (mapCount == 0L || output > outFrames) return null
        val oldest = maxOf(0L, mapCount - MAP_SIZE)
        var i = mapCount - 1
        while (i >= oldest) {
            val slot = (i % MAP_SIZE).toInt()
            if (mapOut[slot] <= output) return mapSong[slot] + (output - mapOut[slot])
            i--
        }
        return null
    }

    private fun recordOutput(frames: Int) {
        synchronized(mapLock) {
            if (songFrame >= 0) {
                val slot = (mapCount % MAP_SIZE).toInt()
                mapOut[slot] = outFrames
                mapSong[slot] = songFrame
                mapCount++
            }
            outFrames += frames
        }
    }

    // Current (glided) values. High-pass and low-pass are kept in log frequency so sweeps sound even.
    private var gain = 1.0
    private var bassDb = 0.0
    private var midDb = 0.0
    private var highDb = 0.0
    private var q = DjFilterMath.FLAT_Q
    private var logHighPass = 0.0
    private var logLowPass = 0.0

    private var sampleRate = 0.0
    private var channels = 0
    private var shelf = emptyArray<BiquadState>()
    private var mid = emptyArray<BiquadState>()
    private var high = emptyArray<BiquadState>()
    private var highPass = emptyArray<BiquadState>()
    private var lowPass = emptyArray<BiquadState>()

    /** The song frame being processed next; negative until the first stamp. */
    private var songFrame = -1L

    // ── The scheduled transition, on the audio thread ──────────────────────
    private var active: DeckSchedule? = null
    private var evalLeft = 0
    private var dry = 1.0
    private var send = 0.0
    private var wet = 1.0
    private var noiseLevel = 0.0
    private var logNoiseHz = ln(FxFrame.NOISE_LOW_HZ.toDouble())
    private var dryWant = 1.0
    private var sendWant = 0.0
    private var wetWant = 1.0
    private var noiseWant = 0.0
    private var logNoiseHzWant = logNoiseHz
    private var echo: EchoLine? = null
    private var reverb: Reverb? = null
    private var noise: NoiseSource? = null
    private var noiseFilter = emptyArray<BiquadState>()
    private var history: DeckHistory? = null
    private var loopBuffer: LoopBuffer? = null
    private var frameScratch = FloatArray(0)
    private var silentFrame = FloatArray(0)

    // Frame positions of the schedule on this song.
    private var mixStartFrame = 0.0
    private var beatFrames = 1.0
    private var echoFrames = 1
    private var jogStartFrame = Long.MAX_VALUE
    private var jogFrames = 1.0
    private var jogPosition = Double.NaN
    private var loopAnchorFrame = Long.MAX_VALUE
    private var loopValid = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        val newRate = inputAudioFormat.sampleRate.toDouble()
        if (newRate != sampleRate || inputAudioFormat.channelCount != channels) active = null
        sampleRate = newRate
        channels = inputAudioFormat.channelCount
        if (shelf.size != channels) {
            shelf = Array(channels) { BiquadState() }
            mid = Array(channels) { BiquadState() }
            high = Array(channels) { BiquadState() }
            highPass = Array(channels) { BiquadState() }
            lowPass = Array(channels) { BiquadState() }
        }
        (shelf + mid + high + highPass + lowPass).forEach(BiquadState::clear)
        val settled = target
        gain = settled.gain.toDouble()
        bassDb = settled.bassDb.toDouble()
        midDb = settled.midDb.toDouble()
        highDb = settled.highDb.toDouble()
        q = qOf(settled)
        logHighPass = ln(highPassOf(settled))
        logLowPass = ln(lowPassOf(settled))
        // What the effects held belongs to the audio before the flush.
        clearEffects()
        // The sink flushes its processors when it makes a new AudioTrack (just before or just after
        // making it), and nothing is written to that track before: counting starts again from zero.
        // A track being released meanwhile reads past what was written, which [heardSongMs] rejects.
        synchronized(mapLock) {
            trackOutBase = 0L
            outFrames = 0L
            mapCount = 0L
        }
    }

    override fun onReset() {
        target = DjFilterSettings.NEUTRAL
        wanted = null
        active = null
        shelf = emptyArray()
        mid = emptyArray()
        high = emptyArray()
        highPass = emptyArray()
        lowPass = emptyArray()
        releaseEffects()
        songFrame = -1L
        processedMs = Double.NaN
        synchronized(mapLock) {
            track = null
            outFrames = 0L
            mapCount = 0L
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        takeStamp()
        val output = replaceOutputBuffer(bytes)
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = channels * (if (float) 4 else 2)
        val frames = bytes / frameBytes
        recordOutput(frames)

        val schedule = wanted
        if (schedule !== active) prepare(schedule)

        if (schedule == null && isSettled(target)) {
            // Nothing to do but keep each filter's history, so the next move starts clean.
            for (frame in 0 until frames) {
                for (ch in 0 until channels) {
                    val x = read(inputBuffer, float)
                    shelf[ch].pass(x)
                    mid[ch].pass(x)
                    high[ch].pass(x)
                    highPass[ch].pass(x)
                    lowPass[ch].pass(x)
                    write(output, float, x)
                }
            }
            if (songFrame >= 0) songFrame += frames
        } else {
            process(inputBuffer, output, float, frames, schedule)
        }
        // A partial frame (never expected) is passed on as it is.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
        if (songFrame >= 0 && sampleRate > 0) processedMs = songFrame * 1_000.0 / sampleRate
    }

    /** Takes the sink's word for where the song is; a jump means a seek, and the effects start over. */
    private fun takeStamp() {
        val us = pendingStampUs
        if (us == Long.MIN_VALUE || sampleRate <= 0) return
        pendingStampUs = Long.MIN_VALUE
        val frame = (us * sampleRate / 1_000_000.0).roundToLong()
        if (songFrame < 0 || abs(frame - songFrame) > JUMP_SECONDS * sampleRate) {
            if (songFrame >= 0) clearEffects()
            songFrame = frame
        } else if (frame != songFrame) {
            songFrame = frame
        }
    }

    private fun process(input: ByteBuffer, output: ByteBuffer, float: Boolean, frames: Int, schedule: DeckSchedule?) {
        val step = 1 - exp(-BLOCK_FRAMES / (GLIDE_SECONDS * sampleRate))
        val openHighPass = ln(DjFilterMath.OPEN_HIGH_PASS_HZ)
        val openLowPass = ln(DjFilterMath.OPEN_LOW_PASS_FRACTION * sampleRate)
        val fx = schedule != null && songFrame >= 0
        var done = 0
        while (done < frames) {
            val count = minOf(BLOCK_FRAMES, frames - done)
            if (fx && evalLeft <= 0) {
                evaluate(schedule!!)
                evalLeft = EVAL_FRAMES
            }
            val want = target
            val startGain = gain
            gain = glide(gain, want.gain.toDouble(), step)
            bassDb = glide(bassDb, want.bassDb.toDouble(), step)
            midDb = glide(midDb, want.midDb.toDouble(), step)
            highDb = glide(highDb, want.highDb.toDouble(), step)
            q = glide(q, qOf(want), step)
            logHighPass = glide(logHighPass, ln(highPassOf(want)), step)
            logLowPass = glide(logLowPass, ln(lowPassOf(want)), step)

            val shelfOn = abs(bassDb) > SNAP
            val midOn = abs(midDb) > SNAP
            val highOn = abs(highDb) > SNAP
            val highPassOn = logHighPass > openHighPass + SNAP
            val lowPassOn = logLowPass < openLowPass - SNAP
            val shelfC = if (shelfOn) DjFilterMath.lowShelf(sampleRate, DjFilterMath.BASS_HZ, bassDb) else Biquad.IDENTITY
            val midC = if (midOn) DjFilterMath.peaking(sampleRate, DjFilterMath.MID_HZ, midDb) else Biquad.IDENTITY
            val highC = if (highOn) DjFilterMath.highShelf(sampleRate, DjFilterMath.HIGH_HZ, highDb) else Biquad.IDENTITY
            val highPassC = if (highPassOn) DjFilterMath.highPass(sampleRate, exp(logHighPass), q) else Biquad.IDENTITY
            val lowPassC = if (lowPassOn) DjFilterMath.lowPass(sampleRate, exp(logLowPass), q) else Biquad.IDENTITY

            if (!fx) {
                for (i in 0 until count) {
                    val g = startGain + (gain - startGain) * (i + 1) / count
                    for (ch in 0 until channels) {
                        var x = read(input, float)
                        if (shelfOn) x = shelf[ch].run(shelfC, x) else shelf[ch].pass(x)
                        if (midOn) x = mid[ch].run(midC, x) else mid[ch].pass(x)
                        if (highOn) x = high[ch].run(highC, x) else high[ch].pass(x)
                        if (highPassOn) x = highPass[ch].run(highPassC, x) else highPass[ch].pass(x)
                        if (lowPassOn) x = lowPass[ch].run(lowPassC, x) else lowPass[ch].pass(x)
                        write(output, float, x * g)
                    }
                }
                if (songFrame >= 0) songFrame += count
            } else {
                val s = schedule!!
                val startDry = dry
                val startSend = send
                val startWet = wet
                val startNoise = noiseLevel
                dry = glide(dry, dryWant, step)
                send = glide(send, sendWant, step)
                wet = glide(wet, wetWant, step)
                noiseLevel = glide(noiseLevel, noiseWant, step)
                logNoiseHz = glide(logNoiseHz, logNoiseHzWant, step)
                val noiseOn = noiseLevel > SNAP || startNoise > SNAP
                val noiseC = if (noiseOn) DjFilterMath.highPass(sampleRate, exp(logNoiseHz)) else Biquad.IDENTITY
                val kind = s.recipe.effect.kind
                val feedback = TransitionEffects.feedback(s.recipe.effect).toDouble()
                val damped = kind == EffectCurve.Kind.ECHO
                val frameIn = frameScratch
                for (i in 0 until count) {
                    val k = (i + 1).toDouble() / count
                    val g = startGain + (gain - startGain) * k
                    val d = startDry + (dry - startDry) * k
                    val sn = startSend + (send - startSend) * k
                    val w = startWet + (wet - startWet) * k
                    val nl = startNoise + (noiseLevel - startNoise) * k
                    for (ch in 0 until channels) frameIn[ch] = read(input, float).toFloat()
                    source(s, frameIn)
                    val n = if (noiseOn) noise!!.next() else 0.0
                    for (ch in 0 until channels) {
                        var x = frameIn[ch].toDouble()
                        if (shelfOn) x = shelf[ch].run(shelfC, x) else shelf[ch].pass(x)
                        if (midOn) x = mid[ch].run(midC, x) else mid[ch].pass(x)
                        if (highOn) x = high[ch].run(highC, x) else high[ch].pass(x)
                        if (highPassOn) x = highPass[ch].run(highPassC, x) else highPass[ch].pass(x)
                        if (lowPassOn) x = lowPass[ch].run(lowPassC, x) else lowPass[ch].pass(x)
                        x *= g
                        var out = x * d
                        when (kind) {
                            EffectCurve.Kind.ECHO, EffectCurve.Kind.DELAY ->
                                out += w * echo!!.process(ch, x * sn, echoFrames, feedback, damped)
                            EffectCurve.Kind.REVERB -> out += w * reverb!!.process(ch, x * sn)
                            else -> Unit
                        }
                        if (noiseOn) out += nl * noiseFilter[ch].run(noiseC, n)
                        write(output, float, out)
                    }
                    echo?.advance()
                    songFrame++
                }
                evalLeft -= count
            }
            done += count
        }
    }

    /**
     * Replaces [frame] (the song as it comes) with what the deck plays: the loop while it holds, the
     * record as it stops or spins back, silence once it has stopped.
     */
    private fun source(s: DeckSchedule, frame: FloatArray) {
        val t = songFrame
        val loop = loopBuffer
        if (loop != null && loopValid) {
            if (t >= loopAnchorFrame - loop.crossFrames && !loop.isFull) {
                // The capture must start on the lead-in, or the loop would not line up with the bars; a
                // few frames lost to a clock correction are filled with silence, which only the first
                // crossing hears.
                if (loop.captured == 0) {
                    val late = t - (loopAnchorFrame - loop.crossFrames)
                    if (late > loop.crossFrames) loopValid = false else repeat(late.toInt()) { loop.capture(silence()) }
                }
                if (loopValid) loop.capture(frame)
            }
            if (loopValid && t >= loopAnchorFrame) {
                val beat = (t - mixStartFrame) / beatFrames
                val lengthBeats = TransitionEffects.loopLengthBeats(s.recipe.loop, beat, s.mixBeats)
                if (lengthBeats > 0) {
                    val length = (lengthBeats * beatFrames).toInt()
                    val position = t - loopAnchorFrame
                    for (ch in 0 until channels) frame[ch] = loop.looped(position, length, ch).toFloat()
                }
            }
        }
        val kept = history ?: return
        kept.write(frame)
        if (t < jogStartFrame) return
        val u = (t - jogStartFrame) / jogFrames
        if (jogPosition.isNaN()) jogPosition = (kept.written - 1).toDouble()
        val level = TransitionEffects.jogLevel(s.recipe.jogwheel, u)
        for (ch in 0 until channels) frame[ch] = (kept.read(jogPosition, ch) * level).toFloat()
        if (u < 1.0) jogPosition += TransitionEffects.jogRate(s.recipe.jogwheel, u)
    }

    private fun silence(): FloatArray {
        if (silentFrame.size != channels) silentFrame = FloatArray(channels)
        return silentFrame
    }

    /** Reads the schedule at the current song frame: the outgoing deck's settings and the effects' levels. */
    private fun evaluate(s: DeckSchedule) {
        val p = s.progress(songFrame * 1_000.0 / sampleRate)
        val deck = TransitionCurves.frame(s.recipe, p, s.outgoingGain, s.swapAt, s.swapWidth).outgoing
        target = deck.filter.copy(gain = deck.filter.gain * deck.volume)
        val fx = TransitionEffects.effect(s.recipe.effect, p, s.beatShare, s.tailShare)
        dryWant = fx.dry.toDouble()
        sendWant = fx.send.toDouble()
        wetWant = fx.wet.toDouble()
        noiseWant = fx.noise.toDouble()
        logNoiseHzWant = ln(fx.noiseHz.toDouble())
    }

    /** Builds what [schedule] needs on the audio thread, and places its moments on this song's frames. */
    private fun prepare(schedule: DeckSchedule?) {
        active = schedule
        releaseEffects()
        if (schedule == null || sampleRate <= 0 || channels <= 0) {
            if (schedule == null) target = DjFilterSettings.NEUTRAL
            return
        }
        val rate = sampleRate
        frameScratch = FloatArray(channels)
        mixStartFrame = schedule.startMs * rate / 1_000.0
        beatFrames = (schedule.beatMs * rate / 1_000.0).coerceAtLeast(1.0)
        val mixFrames = schedule.mixMs * rate / 1_000.0
        val recipe = schedule.recipe
        when (recipe.effect.kind) {
            EffectCurve.Kind.ECHO, EffectCurve.Kind.DELAY -> {
                echoFrames = (recipe.effect.beats * beatFrames).toInt().coerceAtLeast(1)
                val capacity = maxOf(echoFrames + 2, (MAX_ECHO_SECONDS * rate).toInt())
                echoFrames = echoFrames.coerceAtMost(capacity - 2)
                echo = EchoLine(channels, capacity, rate)
            }
            EffectCurve.Kind.REVERB -> reverb = Reverb(channels, rate)
            EffectCurve.Kind.NOISE -> {
                noise = NoiseSource()
                noiseFilter = Array(channels) { BiquadState() }
            }
            EffectCurve.Kind.NONE -> Unit
        }
        TransitionEffects.jogSpan(recipe.jogwheel, schedule.beatShare)?.let { (start, length) ->
            jogStartFrame = (mixStartFrame + start * mixFrames).toLong()
            jogFrames = (length * mixFrames).coerceAtLeast(1.0)
            val reach = TransitionEffects.jogReachBeats(recipe.jogwheel) * beatFrames
            history = DeckHistory(channels, (jogFrames + reach + HISTORY_MARGIN_SECONDS * rate).toInt())
        }
        if (recipe.loop != LoopCurve.NONE) {
            val anchor = TransitionEffects.loopAnchorBeats(recipe.loop, schedule.mixBeats)
            loopAnchorFrame = (mixStartFrame + anchor * beatFrames).toLong()
            val length = (TransitionEffects.loopReachBeats(recipe.loop) * beatFrames).toInt().coerceAtLeast(1)
            loopBuffer = LoopBuffer(channels, length, (LOOP_CROSS_SECONDS * rate).toInt().coerceAtLeast(1))
        }
        evalLeft = 0
    }

    private fun clearEffects() {
        echo?.clear()
        reverb?.clear()
        noiseFilter.forEach(BiquadState::clear)
        history?.clear()
        loopBuffer?.clear()
        loopValid = true
        jogPosition = Double.NaN
        dry = 1.0
        send = 0.0
        wet = 1.0
        noiseLevel = 0.0
    }

    private fun releaseEffects() {
        echo = null
        reverb = null
        noise = null
        noiseFilter = emptyArray()
        history = null
        loopBuffer = null
        jogStartFrame = Long.MAX_VALUE
        loopAnchorFrame = Long.MAX_VALUE
        clearEffects()
    }

    private fun isSettled(want: DjFilterSettings): Boolean {
        if (want != DjFilterSettings.NEUTRAL) return false
        if (abs(gain - 1.0) > SNAP || abs(bassDb) > SNAP || abs(midDb) > SNAP || abs(highDb) > SNAP) return false
        if (logHighPass > ln(DjFilterMath.OPEN_HIGH_PASS_HZ) + SNAP) return false
        if (logLowPass < ln(DjFilterMath.OPEN_LOW_PASS_FRACTION * sampleRate) - SNAP) return false
        gain = 1.0
        bassDb = 0.0
        midDb = 0.0
        highDb = 0.0
        q = DjFilterMath.FLAT_Q
        return true
    }

    private fun glide(current: Double, wanted: Double, step: Double): Double {
        val next = current + (wanted - current) * step
        return if (abs(wanted - next) < SNAP) wanted else next
    }

    /** Resonance stays where a sweep can sing without whistling or going unstable. */
    private fun qOf(s: DjFilterSettings): Double = s.resonance.toDouble().coerceIn(DjFilterMath.FLAT_Q, MAX_Q)

    /** An open high-pass sits at its open frequency, so a sweep starts from where it is inaudible. */
    private fun highPassOf(s: DjFilterSettings): Double =
        s.highPassHz.toDouble().coerceIn(DjFilterMath.OPEN_HIGH_PASS_HZ, DjFilterMath.OPEN_LOW_PASS_FRACTION * sampleRate)

    private fun lowPassOf(s: DjFilterSettings): Double {
        val open = DjFilterMath.OPEN_LOW_PASS_FRACTION * sampleRate
        return if (s.lowPassHz <= 0f) open else s.lowPassHz.toDouble().coerceIn(DjFilterMath.OPEN_HIGH_PASS_HZ, open)
    }

    private fun read(buffer: ByteBuffer, float: Boolean): Double =
        if (float) buffer.getFloat().toDouble() else buffer.getShort() / 32_768.0

    private fun write(buffer: ByteBuffer, float: Boolean, value: Double) {
        if (float) {
            buffer.putFloat(value.coerceIn(-1.0, 1.0).toFloat())
        } else {
            buffer.putShort((value * 32_768.0).toInt().coerceIn(-32_768, 32_767).toShort())
        }
    }
}

/** Builds players whose audio passes through a [DjFilterProcessor] before the speed changer. */
@OptIn(UnstableApi::class)
internal object DjAudio {
    /** The deck of the main player, the one the session knows. Null while the service is down. */
    @Volatile
    var main: DjFilterProcessor? = null

    /**
     * Tempo changes go to the platform's AudioTrack, which stretches time as it plays: ExoPlayer's own
     * stretcher would drain its buffer on every change and leave a tiny gap, audible when Automix
     * nudges the tempo during a mix or eases a song back to its own tempo.
     */
    fun renderersFactory(context: Context, processor: DjFilterProcessor): RenderersFactory =
        (object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = StampingAudioSink(
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                    .setAudioTrackProvider(TrackingAudioTrackProvider(processor))
                    .build(),
                processor
            )
        }).setEnableAudioTrackPlaybackParams(true)
}

/** Makes the platform's AudioTracks as usual and hands each one to the deck, which reads its timestamps. */
@OptIn(UnstableApi::class)
private class TrackingAudioTrackProvider(private val deck: DjFilterProcessor) : DefaultAudioSink.AudioTrackProvider {
    private val platform = DefaultAudioTrackProvider()

    override fun getAudioTrack(
        audioTrackConfig: AudioSink.AudioTrackConfig,
        audioAttributes: androidx.media3.common.AudioAttributes,
        audioSessionId: Int,
        context: Context?
    ): AudioTrack = platform.getAudioTrack(audioTrackConfig, audioAttributes, audioSessionId, context).also(deck::attach)

    override fun getAudioTrackChannelConfig(channelCount: Int): Int = platform.getAudioTrackChannelConfig(channelCount)
}

/**
 * Tells the deck the song time of each buffer before it is processed, which a processor is not told
 * otherwise. The renderer's times carry a stream offset, taken off here.
 */
@OptIn(UnstableApi::class)
private class StampingAudioSink(sink: AudioSink, private val deck: DjFilterProcessor) : ForwardingAudioSink(sink) {
    private var offsetUs = 0L

    /** The buffer being handed over; a retry of the same buffer is not stamped again. */
    private var current: ByteBuffer? = null

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        offsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (current !== buffer) {
            current = buffer
            deck.stamp(presentationTimeUs - offsetUs)
        }
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (handled) current = null
        return handled
    }

    override fun flush() {
        current = null
        super.flush()
    }
}
