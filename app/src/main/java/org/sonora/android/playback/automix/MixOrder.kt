package org.sonora.android.playback.automix

import kotlin.math.abs
import kotlin.math.ln

/** What the reorder needs to know about one song: its tempo at both ends and its key. */
data class MixTrackInfo(
    /** Tempo of the song's start, as the next song after another one would meet it. */
    val headBpm: Double? = null,
    /** Tempo of the song's ending. */
    val tailBpm: Double? = null,
    val key: MusicalKey? = null
) {
    /** The tempo to show: the start's, else the ending's. */
    val bpm: Double? get() = headBpm ?: tailBpm
}

/**
 * Spotify's "Smart Reorder": puts a playlist in an order where each song flows into the next, by
 * tempo first (so the beats can be matched) and key second (so the harmonies do not clash). Pure.
 *
 * The first song stays first, as the listener chose how the set opens. From there a greedy walk
 * takes the closest song each time, then 2-opt passes undo the crossings the greedy walk leaves.
 * Songs nothing is known about keep their order at the end.
 */
object MixOrder {
    /** Past this tempo gap the beats cannot be matched: such a step costs a lot more. */
    private const val MAX_STRETCH = AutomixPlanner.MAX_STRETCH
    private const val UNMATCHABLE = 4.0
    private const val KEY_STEP = 0.35
    private const val UNKNOWN_KEY = 0.6
    private const val UNKNOWN_TEMPO = 1.5
    private const val PASSES = 4

    /** How hard it is to mix [from] into [to]: 0 for the same tempo and key. */
    fun cost(from: MixTrackInfo, to: MixTrackInfo): Double {
        val a = from.tailBpm ?: from.headBpm
        val b = to.headBpm ?: to.tailBpm
        val tempo = if (a == null || b == null || a <= 0 || b <= 0) {
            UNKNOWN_TEMPO
        } else {
            val gap = abs(ln(b / a))
            gap / MAX_STRETCH + if (gap > MAX_STRETCH) UNMATCHABLE else 0.0
        }
        val keyA = from.key
        val keyB = to.key
        val key = if (keyA == null || keyB == null) UNKNOWN_KEY else keyA.distance(keyB) * KEY_STEP
        return tempo + key
    }

    /** The suggested order, as indexes into [infos] (null for songs not analysed). */
    fun suggest(infos: List<MixTrackInfo?>): List<Int> {
        if (infos.size < 3) return infos.indices.toList()
        val known = infos.indices.filter { infos[it]?.bpm != null }
        val unknown = infos.indices.filter { infos[it]?.bpm == null }
        if (known.size < 3) return infos.indices.toList()

        // The opening song stays where it is when it is known; otherwise the first known one opens.
        val remaining = known.toMutableList()
        val order = mutableListOf(remaining.removeAt(0))
        while (remaining.isNotEmpty()) {
            val last = infos[order.last()]!!
            val next = remaining.minBy { cost(last, infos[it]!!) }
            remaining.remove(next)
            order += next
        }
        improve(order) { a, b -> cost(infos[a]!!, infos[b]!!) }
        return order + unknown
    }

    /** Total cost of playing the songs in [order]. */
    fun totalCost(infos: List<MixTrackInfo?>, order: List<Int>): Double =
        order.zipWithNext().sumOf { (a, b) ->
            val from = infos[a]
            val to = infos[b]
            if (from == null || to == null) UNKNOWN_TEMPO + UNKNOWN_KEY else cost(from, to)
        }

    /** 2-opt on an open path with a fixed start: reverses any stretch whose reversal makes the path cheaper. */
    private fun improve(order: MutableList<Int>, cost: (Int, Int) -> Double) {
        repeat(PASSES) {
            var changed = false
            for (i in 1 until order.size - 1) {
                for (j in i + 1 until order.size) {
                    val before = cost(order[i - 1], order[i]) + if (j + 1 < order.size) cost(order[j], order[j + 1]) else 0.0
                    val after = cost(order[i - 1], order[j]) + if (j + 1 < order.size) cost(order[i], order[j + 1]) else 0.0
                    // Reversing changes the direction of the inner steps too: count them.
                    val inner = (i until j).sumOf { cost(order[it], order[it + 1]) }
                    val innerReversed = (i until j).sumOf { cost(order[it + 1], order[it]) }
                    if (after + innerReversed + 1e-9 < before + inner) {
                        order.subList(i, j + 1).reverse()
                        changed = true
                    }
                }
            }
            if (!changed) return
        }
    }
}
