# VirtualDJ: what it does, as input for a "real DJ" in LFly (2026-11-14)

Behaviour only, in our own words. This file names VirtualDJ to say where the facts come from;
LFly's code and UI take no code, skins, samples, names or text from it.

## Where this comes from, and where it stops

- **Public manual** on virtualdj.com, fetched on 2026-11-14 (the copies were temporary). Pages
  under `https://www.virtualdj.com/manuals/virtualdj/`:
  - `interface/browser/sideview/automix.html` (Automix, its modes and options);
  - `editors/automixeditor.html` (per-pair edits);
  - `editors/poieditor.html` (automix points and their order of preference);
  - `appendix/optionslist.html` (automix, stabiliser, key matching and smart options);
  - `interface/decks/deckbasics.html` (decks, sync, mix assist);
  - `interface/stems2.html` (stems);
  - `interface/database/geniusdj.html`, `interface/database/askthedj.html` and
    `interface/database/aiprompt.html`.
- **The installer:** `install_virtualdj_2026_b9746_pc.msi` (VirtualDJ 2026 build 9746, x64,
  591,769,600 bytes).
  - SHA-256 `8129d6c5405f742582b3160522e3769c9366afdd31a6504b8bfa31d9783ae0df`.
  - It was extracted with 7z only, never run. It holds a single 707 MB `virtualdj.exe` and a
    manifest.
- **The licence stops the binary work** (observed, the EULA inside the MSI, section 1).
  - Its list of things the licence does not allow includes decompiling, reverse engineering,
    disassembling, or otherwise reducing the software to a form a person can read.
  - VirtualDJ was never installed and its EULA never accepted.
  - So the binary was **not** analysed: no REA, no `strings` past the licence search, no
    imports or resources.
  - The installer and the extracted files were deleted afterwards.
  - Nothing below depends on the binary.
- Each point below is marked:
  - **[doc]** the vendor's manual says so;
  - **[inf]** our inference from it;
  - **[?]** not known.

## Automix

- **[doc] Analysis marks the mix points.** When a track is first analysed for BPM, VirtualDJ
  also marks up to eight automix points: a start and an end for each of four kinds.
  - **Tempo:** a beat-matched mix.
  - **Cut:** for tempos too far apart to match.
  - **Fade:** from the very start or to the very end of the file, silence included.
  - **Full:** where the audio actually starts or ends, silence excluded.
  - The listener can move or delete these points; analysing the track again restores them.
- **[doc] The mode picks which points to use, in order of preference:**

  | Mode | Points tried, in order |
  |---|---|
  | Smart | Tempo, then Cut, then Fade, then Full. The mix lasts about 4–8 s, from the outgoing outro and the incoming intro. |
  | Fade, intro and outro removed | Fade, then Full |
  | Fade, silence removed | Full |
  | Fade out, cut in | Full. The new song comes in at full level, like radio. |
  | Fade, nothing removed | No points: the whole song |
  | Back to back | No points: plain cut, gaps kept |

- **[doc] Length.** The fade modes use a length in seconds. A negative length leaves a gap
  between the songs.
- **[doc] Beat matching in fade mode.** An option beat-matches a fade when the two tempos are
  close.
- **[doc] Tempo after a mix.** An option chooses one of three things:
  - go back to 0% pitch;
  - keep the pitch;
  - keep the BPM, when that stays within ±10%.
- **[doc] Skip and limits.**
  - A forced skip ("mix now", double click) fades over a set number of beats.
  - A maximum play time per song makes Automix move on after it.
- **[doc] Single deck by default.** Automix plays on one deck so the others stay free for
  live mixing; a dual-deck mode uses the crossfader instead.
- **[doc] Per-pair edits.** The Automix Editor shows the list in pairs on one timeline, with
  waveforms, the beat grid and each mix area.
  - The listener drags the songs and stretches the mix area.
  - Match BPM can be switched per pair.
  - Edits are saved in the database for that ordered pair, and apply whenever those two songs
    follow each other in any later list.
- **[doc] Fluid tempos.** A BPM stabiliser holds the tempo of tracks with a drifting beat during
  a transition, within a set range. It can keep that BPM after the transition ends.
- **[doc] Key.**
  - Key matching can move a song by one semitone to fit the other ("fuzzy" also allows a
    major/minor change; "fuzzy full" allows two semitones).
  - Keys can be shown as names or as "Harmonic" codes (01A, 01B…). **[inf]** These are the
    Camelot wheel that `key.rs` already computes.

## Live DJ features (what a "real DJ" could mean)

- **[doc] Decks.** Each deck has:
  - a pitch slider and pitch bend;
  - a jog wheel in vinyl mode (scratch) or CD mode (nudge);
  - cue, play and sync. Sync matches the BPM and lines up the beat grid.
  - "Mix assist" snaps to the other deck's beat and switches on sync and key match when the
    songs fit.
- **[doc] Smart aids:**
  - a smart cue keeps a jump on the beat;
  - a smart loop makes loops seamless;
  - scratching can be quantised;
  - the pitch can match the other deck automatically at load (only within 10% in smart mode).
- **[doc] Stems.** Vocals, melody, bass and drums are separated by an AI model.
  - The parts can be muted from pads or through EQ modes: one with vocal/instrument/beat knobs,
    one with separate hi-hat and kick, and one with "the main five stems". **[?]** Which five:
    the page names four elsewhere.
  - Effects can be put on a single stem.
  - Stems can be prepared ahead of time into a file about four times the song's size.
- **[doc] Online helpers:**
  - GeniusDJ suggests what usually follows a song, from what other DJs play;
  - Ask the DJ takes requests from the audience through a web page;
  - AI Prompt returns a list from a typed request.

## What LFly has, and the gap

The desktop app only; the phone already has the club Automix this one ports.

| VirtualDJ ([doc]) | LFly desktop now | Gap |
|---|---|---|
| Analysis marks Tempo/Cut/Fade/Full points | Beat grid, energy, outro, rise and loudness per edge (`lfly-mix`, `lfly-core` automix) | No points for silence trimming; the plan uses the last beat as the music's end **[inf]** |
| Smart mode: tempo mix, else cut, else fade | Beat-matched plan, else a plain crossfade | No "cut" fallback **[inf]** |
| Per-pair edits, saved for that pair in any list | Playlist Mix transitions per pair, the first enabled playlist wins (`playlist_mix.rs`) | An editor UI; ours is keyed by playlist, theirs is global |
| Tempo after a mix: 0%, keep pitch, keep BPM | Eases back to the song's own tempo (`TempoReturn`) | "Keep BPM" across a set **[inf]** |
| Skip mixes over N beats | A skip jumps to the next song | Mix on skip |
| Maximum play time per song | — | Not present |
| BPM stabiliser for drifting tempos | Exit and entry snapped to the fitted line; no phase corrector (HANDOFF item 5) | The corrector |
| Key matching ±1–2 semitones | Key detection exists (`key.rs`) but is unused in playback; the stretch keeps the pitch | Pitch shift and key matching |
| Decks, sync, jog, cue, loops, hot cues | One engine with Automix decks inside; no manual deck UI | Everything, if "real DJ" means manual mixing |
| Stems | — | A separation model (large; licence and size to check) |

## Open questions for the user

1. Answered on 2026-11-14: driving LFly from the user's own DJ console (HANDOFF item 13). The
   options below are kept for the record.
   What does "real DJ" mean?
   - The phone's DJ (`playback/dj`, read on 2026-11-14), which the HANDOFF lists as step 4.
     It plays your music in themed blocks of a few songs and opens each block with a spoken
     line, like a radio host. The lines come from templates (`DjScript`, pure, no AI service and
     no key). The voice is Piper (sherpa-onnx voices downloaded from GitHub), else the system
     voice.
   - Or manual decks like the ones above?
2. If decks: two on screen with sync and cue, or Automix only with the extras (cut fallback,
   mix on skip, key matching)?
3. The binary analysis stops at the licence clause above.
   - Only the binary could show how the Tempo and Cut points are placed, how Smart picks its
     length, and how the stabiliser follows a drifting tempo.
   - No Ghidra or IDA is installed on this PC, so pseudocode would also need one installed first.
   - Do you want to stop here?
