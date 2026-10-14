package org.sonora.android.api.lyrics

/**
 * Romanization of non-Latin lyrics (port of `crates/music/src/lyrics/romanize.rs` +
 * `japanese.rs`). Dormant for now: the engine carries the `romanized` field but our UI does
 * not display it yet, so this is a no-op placeholder to keep the pipeline faithful.
 */
internal object Romanize {

    fun apply(lines: MutableList<SheetLine>) {
        // Fase 5 (dormiente): implementazione del rilevamento scrittura + romanizzazione.
    }

    fun plain(text: String): RomanizedText? = null
}
