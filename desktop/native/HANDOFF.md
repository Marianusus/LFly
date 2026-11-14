# Handoff: LFly for the desktop (GPUI), state on 2026-11-13

For the next agent picking this up. README.md in this folder describes the app, its test hooks
and its known differences from the Tauri app; this file covers where the work stands and how to
work here without hurting the user's setup.

## The user and the rules

- **Language:** replies and UI text are in **Italian**. Code, comments and commits are in English.
- **Commits:** commit only when the user asks.
  - **Never push, tag or release.** The branch is `gpui-native`, many commits ahead of origin.
  - No `Co-Authored-By` or Claude credit lines in commits or PRs.
  - Leave `.graphifyignore` alone: it is untracked on purpose.
- **Subagents:** use them only when the user asks; AGENTS.md allows 3–5 at most. The user picks the advisor model (Fable).
- **Never use the real screen:** no focus, no keys, no windows on the user's display. The user plays games on this PC.
  - UI checks run in a headless KWin: `tools/headless.sh OUT.png SECONDS [ENV=VALUE …]`.
  - Don't launch other GUI programs, even inside it: a konsole opened there may land on the real desktop.
- **Never touch the user's data in tests:**
  - `tools/headless.sh` points config, data and cache at copies in `tools/.headless/`.
  - Do the same for any other run (`XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_CACHE_HOME`).
  - Don't print `~/.config/lfly/account.json`, and don't read `google-oauth.json`.
  - No real Google Drive sign-in and no real phone sync.
- **Processes:** the user often runs `./target/release/lfly` themselves. Kill only processes you started, by their PID.
- **Building:**
  - `/tmp` is a 16 GB RAM tmpfs: never put a cargo target dir there.
  - Use `CARGO_BUILD_JOBS=4` and the existing target dirs (`desktop/target`, `desktop/native/target`).
  - Check `df -h /` first: everything was cleaned on 2026-11-13, so the first build starts from zero and takes 10–15 min.

## Building and checking (the gate before reporting)

```sh
cd desktop && cargo test --workspace --release && cargo clippy --workspace --release   # 1 old warning (stretch.rs)
cargo build --release -p lfly-desktop                                                   # the Tauri app still builds
cd native && cargo build --release && cargo test --release && cargo clippy --release    # 28 tests, 28 warnings (baseline)
CC_x86_64_pc_windows_gnu=clang CFLAGS_x86_64_pc_windows_gnu="--target=x86_64-pc-windows-gnu -isystem $PWD/tools/wininc" \
  LFLY_CHECK_NO_RC=1 cargo check --target x86_64-pc-windows-gnu                       # debug only: release fails on shaders
```

Notes on these checks:

- **Windows check:** it builds without the beat model, because ONNX Runtime ships no windows-gnu build. The real Windows CI uses MSVC, where it does.
- **CI:** nothing pushed since this work, so `.github/workflows/native.yml` (ubuntu-22.04, `--locked`) has not been run on it.

## What was done on 2026-11-13 (all committed)

- **Lyrics**, the phone's word-by-word look, with a GPUI patch for blurred and scaled glyphs (`vendor/PATCHES.md`):
  - the word being sung pops up and glows;
  - the other lines blur by their distance from it.
- **Lyrics sources** as the phone picks them (`crates/lfly-yt/src/lyrics/order.rs`):
  - read in order: word by word first, then lines, Genius last;
  - Settings > Testi > Fonti dei testi;
  - PaxSenix's keyless source is left out, because it scrapes Apple's web token.
- **Monochrome** raced with YouTube Music (`crates/lfly-yt/src/monochrome.rs`), choice in Settings > Riproduzione > Da dove suona:
  - formats come from the first bytes of the file;
  - downloads stay on YouTube.
- **Automix**, the phone's club Automix, for the queue (Settings > Riproduzione > Automix, off by default):
  - `crates/lfly-mix`: pure port of beat grid, energy, planner, curves, filters and loudness, with the phone's tests.
  - `crates/lfly-beat`: Beat This! on `ort`, behind feature `onnx`. The model is embedded from `app/src/main/assets`.
  - `crates/lfly-core/src/automix.rs`: analysis from 150 s before the end, saved in `<data>/lfly/automix`, then the plan.
  - `crates/lfly-audio/src/automix.rs` plus `engine.rs`: two decks, sample-exact.
- **Fixes:**
  - the lyrics and the position bar keep moving when the window is visible but unfocused;
  - the Home shelves no longer scroll sideways on a vertical wheel;
  - "Lex Amarni" was cut to "Le" (the artist split on "x "), which hid the word timings;
  - the lyrics clock follows Velocità.

## Open items, most useful first

1. **Ask the user what they still see** after restarting the app:
   - the lyrics lag when switching windows, and the Home shelf scroll. Both were fixed but not confirmed on the real screen;
   - how the Automix sounds. That is the user's judgement.
2. **Lines without word timings** light up word after word, 50 ms apart (Tauri's behaviour). The phone lights the whole line. The user was asked which they want and has not answered.
3. **Automix effects (2026-11-14):**
   - `crates/lfly-mix/src/effects.rs`: the phone's `TransitionEffects.kt` and its tests, plus the echo line, reverb and loop buffer from `DjEffects.kt`;
   - the mixer plays them on the outgoing deck: the loop before the EQ, the send after the fader;
   - the mixer lives on for the effect's tail (`outgoing_gone()` vs `finished()`), so the tempo return starts after it, unlike the phone;
   - ECHO_OUT is never picked: AUTO picks it off the beat, and the desktop only mixes on the beat. How REVERB_FADE and ROLL_DROP sound is the user's call.
4. **Automix, not ported:**
   - the jogwheel and the noise riser (only Playlist Mix uses them; `DeckHistory` not ported);
   - Playlist Mix and DJ (phone `ui/mix`, `playback/dj`);
   - key detection and reorder (`MusicalKey.kt`, `MixOrder.kt`).
   - Done on 2026-11-14 (uncommitted): the mix point on both seek bars (lfly-core `MixPoint`,
     `PlayerHost::mix_point`, never sent to devices). A ReverbFade ran through the engine headless
     (Coldplay "The Scientist" → its radio): mix 26.2 s, "mixed" logged about 29 s after it began,
     so the reverb tail lived on about 3 s. Read from the log of a muted run, not heard.
5. **Automix, phase corrector:** the phone corrects the phase during the mix; here the decks start on the sample with no correction.
   - Exit and entry are snapped to each grid's fitted line: on click tracks the decks stay within 9 ms.
   - Live recordings can have bad grids: one had a beat 523 ms off its line. The plan log prints each grid's worst residual. Port the corrector if mixes of live drummers drift.
6. **Monochrome:**
   - only `tracks.monochrome.st` answers;
   - it is slow (about 370 KB/s, about 0.9 s to the first byte) and returns 429;
   - its FLACs have no SEEKTABLE, so a seek in them takes 10–15 s;
   - Automix on Monochrome sources ran once (the ReverbFade above);
   - it matched the video "Coldplay - The Scientist in the Live Lounge" to what sounds like the
     studio recording (−12.1 LUFS, 73 BPM; YouTube's audio of the same id: −17.8 LUFS, 124 BPM).
7. **YouTube captions** as a lyrics source get HTTP 400 for the phone's request, so it finds nothing for now.
8. **The beat model makes the binary large:** 54 → 102 MB.
9. **Fable's old worktrees** in `.claude/worktrees/agent-*` have nothing left to take.
   - The Automix one was copied into `crates/lfly-mix` and finished here.
   - The Party one is empty, and the sync one was merged earlier.
   - Ask the user before removing them.
10. **Party** (play together with the phone) and the **DJ voice** are not started on the desktop.

## Where to look

- **Project map and the phone side:** `PROJECT_MAP.md` and `WALKTHROUGH.md` at the repository root.
- **Phone sources a port follows:**
  - `app/src/main/java/org/sonora/android/playback/automix/*` and `playback/Crossfade.kt`;
  - `api/lyrics/sources/*`;
  - `api/StreamResolver.kt` and `api/MonochromeApiClient.kt`.
- **Test hooks:** `LFLY_AUTOPLAY`, `LFLY_ROUTE`, `LFLY_SETTINGS`, `LFLY_SEEK="s@ms;s@ms"`, `LFLY_SPEED`, `LFLY_PANEL` and others, listed in README.md.
