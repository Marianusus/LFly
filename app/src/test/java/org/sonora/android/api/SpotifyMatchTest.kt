package org.sonora.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMatchTest {

    @Test
    fun accentInsensitiveTitleEquality() {
        assertEquals(
            normalizeMatchText("O Sticla Doua Trei"),
            normalizeMatchText("O Sticlă, Două, Trei")
        )
    }

    @Test
    fun artistTokenOverlapAcceptsPlayAjBothSides() {
        val result = matchArtist(
            normalizeMatchText("Liviu Guta Play Aj"),
            normalizeMatchText("Liviu si Play Aj")
        )
        assertTrue(result.compatible)
        assertTrue(result.usedTokenFallback)
        assertEquals(setOf("liviu", "play", "aj"), result.commonTokens)
    }

    @Test
    fun differentArtistNamesRejected() {
        assertFalse(
            matchArtist(normalizeMatchText("Artist One"), normalizeMatchText("Artist Two")).compatible
        )
    }

    @Test
    fun djIsNonInformativeSoRejected() {
        assertFalse(
            matchArtist(normalizeMatchText("DJ Test"), normalizeMatchText("DJ Other")).compatible
        )
    }

    @Test
    fun singleCommonTokenRejected() {
        assertFalse(
            matchArtist(normalizeMatchText("Alpha Beta"), normalizeMatchText("Alpha Gamma")).compatible
        )
    }

    @Test
    fun differentTitleRejectedEvenWithCompatibleArtists() {
        val matched = youtubeCandidateMatches(
            wantTitle = normalizeMatchText("O Sticla Doua Trei"),
            wantArtist = normalizeMatchText("Liviu Guta Play Aj"),
            wantDurationMs = 246_000L,
            candidateId = "yt_abcdefghijk",
            candidateTitle = "Another Song",
            candidateArtist = "Liviu si Play Aj",
            candidateDurationMs = 246_000L
        )
        assertFalse("Different title must be rejected", matched)
    }

    @Test
    fun durationBeyondEightSecondsRejectedEvenWithCompatibleArtists() {
        val matched = youtubeCandidateMatches(
            wantTitle = normalizeMatchText("O Sticla Doua Trei"),
            wantArtist = normalizeMatchText("Liviu Guta Play Aj"),
            wantDurationMs = 246_000L,
            candidateId = "yt_abcdefghijk",
            candidateTitle = "O Sticlă, Două, Trei",
            candidateArtist = "Liviu si Play Aj",
            candidateDurationMs = 262_000L
        )
        assertFalse("Duration diff of 16s must be rejected", matched)
    }

    @Test
    fun reliableMatchAccepted() {
        val matched = youtubeCandidateMatches(
            wantTitle = normalizeMatchText("O Sticla Doua Trei"),
            wantArtist = normalizeMatchText("Liviu Guta Play Aj"),
            wantDurationMs = 246_000L,
            candidateId = "yt_PsfIzmlp5Q",
            candidateTitle = "O Sticlă, Două, Trei",
            candidateArtist = "Liviu si Play Aj",
            candidateDurationMs = 246_196L
        )
        assertTrue("Compatible title/artist and ±8s duration must match", matched)
    }
}
