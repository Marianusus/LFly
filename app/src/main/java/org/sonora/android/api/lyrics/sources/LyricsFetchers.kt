package org.sonora.android.api.lyrics.sources

import org.sonora.android.api.lyrics.LyricsHit

internal object LyricsFetchers {
    suspend fun fetch(
        source: LyricsSource,
        query: SourceQuery,
        bini: SourceBini.Hit? = null,
    ): LyricsHit? = when (source) {
        LyricsSource.LRC_RED -> SourceLrcRed.fetch(query)
        LyricsSource.BINI_LYRICS -> SourceBini.fetch(query, bini)
        LyricsSource.BETTER_LYRICS -> SourceBetterLyrics.fetch(query)
        LyricsSource.BETTER_LYRICS_PORTATO -> SourceBetterLyrics.fetch(query, portato = true)
        LyricsSource.PAXSENIX -> SourcePaxSenix.fetch(query)
        LyricsSource.PAXSENIX_SPOTIFY -> SourcePaxSenix.spotify(query)
        LyricsSource.PAXSENIX_MUSIXMATCH -> SourcePaxSenix.musixmatch(query)
        LyricsSource.LYRICS_PLUS -> SourceLyricsPlus.fetch(query)
        LyricsSource.SIMP_MUSIC -> SourceSimpMusic.fetch(query)
        LyricsSource.UNISON -> SourceUnison.fetch(query)
        LyricsSource.YOUTUBE_TRANSCRIPT -> SourceYouTube.transcript(query)
        LyricsSource.YOUTUBE_MUSIC -> SourceYouTube.music(query)
        LyricsSource.MEGALOBIZ -> SourceMegalobiz.fetch(query)
        LyricsSource.KUGOU -> SourceKugou.fetch(query)
        LyricsSource.LRCLIB -> SourceLrcLib.fetch(query)
        LyricsSource.MUSIXMATCH -> SourceMusixmatch.fetch(query)
        LyricsSource.GENIUS -> SourceGenius.fetch(query)
    }
}
