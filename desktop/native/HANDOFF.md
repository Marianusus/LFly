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
cd native && cargo build --release && cargo test --release && cargo clippy --release    # 28 tests, 27 warnings (baseline)
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
   - Playlist Mix, pure half (6abd389) in `lfly-mix`: `key.rs`, `order.rs`, `playlist.rs`,
     `plan_fixed`/`exit_choices`/`entry_choices`, with the phone's PlaylistMixTest.
   - Playlist Mix, kept and played (2026-11-14, uncommitted): `lfly-core/src/playlist_mix.rs`
     reads and writes `<data>/lfly/library/playlist_mixes.json` in the phone's Gson JSON
     (unknown fields such as a phone's jogwheel survive a write; the saved-JSON test is ported).
     The phone keeps that file on the device: Drive and Connect sync carry only favorites,
     playlists and history; only the phone's local backup holds it (`import_backup` does not read
     it yet). The player resolves the playing pair from the song's start (`update_pair`): a pair
     of an enabled mixed playlist runs Automix even with the switch off; NONE plays no fade (beats
     the 5 s fallback); beats on: `plan_with` (fixes, preset/custom recipe); beats off: a plan at
     rate 1 from the chosen exit/entry, its length the chosen bars at the ending's tempo.
   - Checked headless, muted, from the logs: BLEND with Automix off; BLEND 4 bars, exit 260 s
     (planned 4 bars, exit 259.0 s, the playlist's recipe); NONE with Automix on and off (no plan,
     no fade); beats off (exit 250 s, entry 9 s, rate 1, 26.2 s); no pair (unchanged). How to
     repeat it: in `tools/.headless`, write `data/lfly/library/playlists.json` with the pair, the
     mix file next to it, `config/lfly/state/playback.json` with `"source":"MONOCHROME"` (the same
     id gives another recording on YouTube), set `"automix"` in `audio.json` as needed, then
     `LFLY_AUTOPLAY="Coldplay The Scientist" LFLY_SEEK="15@205000"`. The search results change
     order between runs (the next song is `yt_mUB3yGWEa2s` or `yt_bllKLAiLo6g`): put both
     pairs in. Remove the files and restore `audio.json` afterwards.
   - Differences from the phone: when the beats cannot be matched the desktop plays its plain
     crossfade, not the playlist's ingredients off the beat; a beats-off change waits for both
     analyses; the jogwheel is kept in the file, not played. Not done: the editor, the analysis
     of a whole playlist (keys, Smart Reorder), the riser.
   - The switch (2026-11-14): a "Mixa"/"Mixata" pill in your playlist's hero row
     (`collection.rs` `mix_button`, the phone's PlaylistMixButton; dimmed under two songs);
     deleting a playlist forgets its Mix. The hero column now takes `flex_1` and its buttons
     wrap, so the row no longer runs out of the page with the right panel open; a page opened
     before the library was read no longer keeps "Questa playlist non c'è più". Shot headless
     on your playlist (off/on) and Liked at 1100 and 1500 px; not shot on an album page; the
     click itself never ran headless. On an album page with the panel open the four buttons now
     wrap to a second row; before, they overflowed the column by about 12 px into the page's
     margin.
   - Done on 2026-11-14 (3cd03b4): the mix point on both seek bars (lfly-core `MixPoint`,
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
11. **VirtualDJ** (asked on 2026-11-14 for a "real DJ"): see `VIRTUALDJ_NOTES.md`.
    - It is built from the public manual only.
    - REA was not run on the binary: the EULA forbids reverse engineering, VirtualDJ was never
      installed, and the download was deleted.
    - The user chose the phone's voice DJ (step 4) as the "real DJ".
12. **The voice DJ (step 4)**: `lfly-core/src/dj/` (pure half committed as `0944045`).
    - **The caption-only session (`6a43a49`):**
      - `dj/session.rs`: one task owns the session and takes start, stop, the local player's
        states and position ticks (from `NativeHost`) as messages, so it never calls the player
        while the player is calling it.
      - A block is built in its own task and dropped if the session changed meanwhile. It is
        queued with `play_queue` (the first) or `EDIT_ADD` when one song is left.
      - The line comes once the block's first song has played 0.6 s (a tick must first show
        the position going back or under 0.6 s, since the old song ticks while the new one
        loads), and is shown as a caption
        (`native/src/dj.rs`: `DjModel`, the island at the top). The Home card starts and stops
        the DJ; it is dimmed while another device is controlled.
      - It stops when the listener starts a queue (`Core::play`) or music arrives from another
        device. As a fallback it also stops on a state whose queue holds none of its songs, once
        its own queue was seen.
      - The DJ's songs always mix, Automix switch on or off (`LocalPlayer::set_dj_mixing`, the
        phone's `automixActive`).
      - When no first block can be built it says so in a toast.
      - "Top played" is favourites first, then history (no play counts on the desktop).
      - No tempo or key probing yet, so the flow goes by YouTube Music's order and variety.
      - Under shuffle the flow order is lost (not handled).
      - Checked headless with `LFLY_DJ=3` and a seeded favourites list: a FLOW block from Norah
        Jones, its line once the song played, the caption and the card; over a playing song
        (`LFLY_AUTOPLAY` + `LFLY_DJ=20`) the line came after the new song started.
      - Also checked live, from the log (uncommitted test hooks `LFLY_SKIP_LAST`, and `LFLY_SEEK`
        with a negative value):
        - the next block is built at the last song;
        - an Automix (ReverbFade) mixes into the second block's first song, entering at 3.05 s;
        - its line comes 2 s after the mix starts.
        This differs from the phone, which waits for the transition to end.
      - The stops are unit-tested only.
    - **Voice:** on 2026-11-14 the user handed the choice over ("finisci te"). Captions stay the
      default; the three options above remain open for the user. The probe cache in
      `~/.cache/lfly-rea` was deleted; versions and hashes are above.
    - **Pure half, done.** Ported from the phone's `playback/dj`, with its tests (37 pass):
      - `blocks.rs`: kinds, rotation, usable songs, blocks of 4–6, artist and mix blocks;
      - `flow.rs`: the flow where each song grows out of the one before, by tempo, key and variety;
      - `script.rs`: the lines in it/en/ro, title clean-up, never the same intro twice per session.
      It works on `TrackRef`, so the tempo comes through a groove function. The Android
      voice-choice tests are left out.
    - **What the session will need, and the gaps found:**
      - The desktop has favourites and history; "top played" must be counted from the history.
      - It has no followed artists, genre mixes or local dislikes, so the FOLLOWED_ARTIST and MIX
        blocks have no material.
      - Recommendations would be `yt.up_next` (the phone has its own engine).
      - `Analysis` has no key.
      - The player has no hook to supply the next block (only `play_on_with_similar`).
      - The engine has no command to play a voice clip or duck the music.
      - The phone shows the line as a caption when no voice speaks, so a caption-only session
        can ship first.
    - **The voice, measured on this PC** (probe outside the repo in `~/.cache/lfly-rea`):
      - There is no system voice on this PC: no speech-dispatcher, no espeak.
      - The `sherpa-onnx` crate 2.0.1 (the phone's version, Apache-2.0) builds next to `ort`
        when both link statically, their defaults. The binary then holds sherpa's ONNX Runtime
        1.28.2, and `ort` panics at start ("API version 31 not available"), which would break
        the Automix beat model. Untested: sherpa's `shared` feature, or `ort` on a lower `api-*`
        feature or with `load-dynamic`. The phone runs both as separate shared libraries.
      - sherpa-onnx ≥ 2.0.0 ignores `espeak-ng-data` and wants phonemes from outside (e.g.
        piper_phonemize, which is eSpeak NG, GPL-3.0). A Piper voice therefore cannot speak
        plain text with it.
      - The phone's `app/libs/sherpa-onnx-2.0.1-static.aar` says the same in its arm64 library
        ("--vits-data-dir is deprecated and ignored", "espeak-ng is no longer used"). The phone
        passes `dataDir`, so its Piper voice most likely never speaks and the system voice takes
        over. This was read from the binary's text, not seen on a device: look for "Piper could
        not speak" in logcat.
      - Options for the user: a separate voice helper process (an older sherpa-onnx with eSpeak
        inside, or Piper with eSpeak NG, both GPL for the phonemizer); system voices where they
        exist (`tts` crate: SAPI on Windows, speech-dispatcher on Linux); or captions first.
      - The Italian voice `it_IT-miro-high` is a 67 MB download, 79 MB unpacked.
      - Its README says CC BY-NC-SA 4.0: no commercial use, and the licence must travel with
        it. The other voices' licences and eSpeak NG's were not checked here: confirm them
        before choosing.
      - No synthesis ever ran on the desktop, so no time to the first line was measured.

## Where to look

- **Project map and the phone side:** `PROJECT_MAP.md` and `WALKTHROUGH.md` at the repository root.
- **Phone sources a port follows:**
  - `app/src/main/java/org/sonora/android/playback/automix/*` and `playback/Crossfade.kt`;
  - `api/lyrics/sources/*`;
  - `api/StreamResolver.kt` and `api/MonochromeApiClient.kt`.
- **Test hooks:** `LFLY_AUTOPLAY`, `LFLY_ROUTE`, `LFLY_SETTINGS`, `LFLY_SEEK="s@ms;s@ms"`, `LFLY_SPEED`, `LFLY_PANEL` and others, listed in README.md.
