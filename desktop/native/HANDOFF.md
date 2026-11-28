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
- **CI:** `.github/workflows/native.yml` builds Linux on ubuntu-24.04 (the prebuilt ONNX Runtime needs glibc 2.38, so ubuntu-22.04 failed to link), Windows, and macOS on macos-latest (Apple Silicon: `tools/macos-bundle.sh` makes LFly.app and a .dmg, signed ad hoc only).

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

## What was done on 2026-11-25 (all committed)

- **Lyrics in Spicy Lyrics' look** (github.com/Spikerko/spicy-lyrics, AGPL-3.0: only its numbers are taken, the code is ours), here and on the phone (`SpicyLyrics.kt`):
  - `src/spicy.rs`: springs and curves. Each word sung grows (0.95 → 1.05 at 70% → 1), lifts and glows; words held 1 s or more go letter by letter (1.175); words not sung rest at 0.95.
  - `motion::line_blur` takes Spicy's depth (1.25 px a line, up to 6.8). The old `pop` is gone.
  - The lyrics page's ambient is saturated ×2.5 (`Ambient::saturation`).
  - Checked headless: `tools/headless.sh OUT.png 30 LFLY_AUTOPLAY="Coldplay Viva La Vida" LFLY_SEEK="12@60000" LFLY_ROUTE=lyrics`.

## What was done on 2026-11-28 (all committed)

- **Console mode:** the Console has the whole window (no sidebar, top bar or player bar), under a strip with "🎧 Flying back" at the top left (`Nav::leave_console`: back to the page it came from) and the window buttons (main.rs `console_strip`, `console::STRIP_H`).
- **VIDEO window** (`src/video.rs`): the cover's light behind (Ambient, saturated ×2.5), the line sung large and filled word by word (word timings, else spread by length), the line before and the next two, a new line rising in, dots in pauses, a progress line; the app's font. Tests for the timing.
- **KuGou:** its "纯音乐，请欣赏" card counts as an instrumental, not as lyrics.

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
    - Correction (2026-11-14, later): "real DJ" meant playing LFly from the user's own hardware
      DJ console. See item 13. The voice DJ (item 12) was a misreading, but it is kept.
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
13. **DJ console (the user's real aim, 2026-11-14), slice 1 uncommitted.**
    - **Done:**
      - `midir` 0.11.1, which shares `alsa-sys` 0.4 with cpal (0.10 clashed).
      - `native/src/midi_map.rs`, pure with tests: MIDI bytes to a control, actions that know
        nothing about MIDI, a learned mapping in the store key `midi`, soft takeover for faders.
      - `native/src/midi.rs`: a thread opens the first port that is not Midi Through, PipeWire or
        LFly, and looks again every 2 s for plug-in and plug-out.
      - Actions: play/pause, cue (CDJ style), next, previous, volume, tempo (±8%, applied once
        the fader rests 250 ms, because `set_speed` re-seeks; local only), Automix on/off.
      - Settings > Dispositivi > Console DJ: the connected console, and "Impara"/"Togli" per
        action.
    - **Test path, no root, never a real device:**
      - `examples/midi_send.rs` plugs in a pretend console (a virtual output port), sends, and
        unplugs.
      - With `LFLY_MIDI_TEST=<name>` a test run takes only a port whose name contains it, through
        the same scan a real console goes through. `LFLY_MIDI_LEARN=<action>@<s>` starts learning.
    - **Proven headless, from the log:**
      - learning bound note 11;
      - a saved mapping of all seven actions loaded, and each action arrived;
      - cue marked 4058 ms after play/pause had really paused (the cue took the paused branch);
      - tempo applied 1.020;
      - unplugging showed "none", and replugging found the console again;
      - an unmapped control did nothing.
      The Settings section was shot with and without a console.
    - **Known limits:** tempo faders that send a 14-bit CC pair or pitch bend are not read (pitch
      bend is not parsed; learning may bind the fine byte), so tempo is unproven on real
      hardware. Nothing has run with a real console.
    - **Not done, waiting for the console model:**
      - the jog wheel (relative encodings vary, and each seek drops the Automix plan);
      - browse/load, LEDs, presets, HID (`hidapi` may break the windows-gnu check).
    - **Fable 5.2's plan for what follows (read, not run):**
      - Slice 2, small core changes, each needing the user's approval:
        - a smooth tempo with `Stretch::glide_to`;
        - "mix now" over N beats;
        - a 3-band DJ EQ and filter in the output callback, reusing `DjFilterSettings`.
        Mapping knobs to the saved `set_equalizer` would overwrite the user's EQ.
      - Two real decks: weeks of work, effectively a second engine mode.
        - It needs two decode, stretch and DSP chains, a short ring (the engine keeps 2 s, so
          upstream changes are heard up to 2 s late), a manual crossfader, sync from
          `beat_grid.rs`, hot cues and loops (`LoopBuffer`).
        - Headphone cue needs a second cpal stream or the console's own channels 3-4.
        - Scratch needs a varispeed path: WSOLA cannot run backwards.
      - The question for the user: the console's make and model. Plugging it in once lets
        `aconnect -l` show it.
    - Two LFly instances with MIDI would both act on the console (ALSA lets both read it).
14. **macOS (2026-11-14, user has a MacBook Air M1, no paid Apple signing).**
    - `vendor/gpui-pre-apple` (the Metal renderer) gets an empty glass arm. `look.rs`
      `GLASS_RENDERER` is false on macOS, so surfaces are solid and the glass switch is disabled.
      Porting `glass.wgsl` to Metal is the next step.
    - CI job macos-latest (macos-14 is retired on GitHub) builds and bundles (`macos/Info.plist`: local network and Bonjour for LFly
      Connect). Compiling for macOS from Linux is not possible (the audio -sys crates need
      Apple's SDK), so CI is the only check. `build.rs` links clang's runtime (`clang_rt.osx`) for
      ONNX Runtime's `@available` checks; the app targets macOS 13.4, as the prebuilt ORT does.
    - On first open the user must allow the unsigned app: System Settings → Privacy & Security →
      "Open Anyway", or `xattr -dr com.apple.quarantine /Applications/LFly.app`.

15. **Console page (2026-11-15).** `src/console.rs` (page), `src/deck_model.rs` (every control, one
    method each, for a MIDI mapping later), `crates/lfly-audio/src/decks.rs` (engine),
    `crates/lfly-core/src/decks.rs` (grid and key). Sidebar item "Console" under Libreria,
    `LFLY_ROUTE=console`. Laid out after the user's VirtualDJ Pro screenshot, drawn with our own
    pieces (no VirtualDJ name, logo, skin or sounds). The user asked for REA on VirtualDJ twice;
    refused, its EULA forbids it. The function list comes from the maker's public manual.
    - First version (commit 9a56e3c): decks took turns on the player's queue. Replaced the same day
      (uncommitted) after the user asked for "all VirtualDJ's functions": two real decks,
      independent of the queue.
    - Engine (`decks.rs`, beside `engine.rs`, which it does not touch): each song decoded whole to
      16-bit memory at its own rate (20 min cap, ~230 MB at 48 kHz), read at any speed/direction
      with cubic interpolation, mixed in its own low-latency cpal stream (512-frame buffer asked) on
      the player's chosen device; the stream pauses after 8 s with nothing playing (a small buffer
      left running holds the whole PipeWire graph at its quantum) and any command wakes it. A
      stream still decoding: the deck waits where the decoding has got to. A load or eject resets
      slip; a hand jump (hot cue, overview) moves slip's timeline, a roll/censor/scratch returns. Callback: no locks taken by others, no allocation, buffers dropped
      off-thread (trash ring). Per deck: play/pause ramps, jumps crossfaded (96 frames), loops,
      loop roll, slip, reverse, censor, scratch, brake, spinback, gain, LR4 three-band kill EQ
      (bypassed at unity, 10 ms crossfade in), filter (LP left / HP right), 3 effect slots
      (echo, reverb, flanger, wahwah, cut, distortion, phaser, bitcrush; beat-synced), channel
      fader, crossfader curves (full, smooth, cut, scratch), master, limiter (0.98), meters, WAV
      recording of the master. `Mixer::silent()` for test runs (no device). 19 render tests and an
      ignored one on the real device (`the_real_output_runs_and_pauses_when_idle`, run once: ok).
    - Analysis: `lfly_core::decks::analyse` runs Beat This! on the whole song and the key detector,
      2-6 s per song. The constant grid scans tempos within 4% of the tracker's AND of the median
      beat gap's, 0.01 BPM steps, scored by phase agreement over 32-beat stretches. Found: lfly-mix
      `BeatGrid::tempo` miscounts when stray half beats are tracked (137.18 for a song whose gaps
      say 129.6; 107.20 for one at 102.9), which the Automix also uses: not changed here, worth a
      look. BPM ×2/÷2 and TAP pads fix a grid by hand.
    - Page: strips with real peaks, grid, cues, loop; overview per deck (click seeks, shows decode
      progress); key (click: Camelot), BPM with pitch %, time (click: remaining); platter (drag:
      scratch in VINYL, bend or search in CD), SLIP; pitch fader drag with ranges 6-100%, bend
      buttons held, double click resets; pads HOT CUE (right click deletes; saved in store key
      `console-cues`), ROLL, LOOP, JUMP, PERFORM (brake, spinback, censor, reverse, BPM ÷2 ×2, tap,
      eject); loop size ½ ×2, IN, OUT, RELOOP/EXIT, Q (quantize); STOP, CUE (CDJ: held while
      stopped previews), PLAY, SYNC (tempo, half/double aware, then beat phase; range widens if
      needed). Mixer knobs drag vertically, double click resets, right click kills a band.
      Browser rows have A and B; a double click loads the free deck. REC writes
      `~/Music/LFly/LFly Console <secs>.wav`.
    - A deck starting pauses the music player; the player starting pauses the decks. Decks feed no
      history and no Connect.
    - Second round (2026-11-15 afternoon, uncommitted; the user: "aggiungi un paio di funzioni",
      the advisor's gap list from the manual reports):
      - Engine: keylock (Stretch per deck, fed at 1× in 256-frame pieces, bypassed while
        scratching, reversed or braking; heard position = read position minus what the stretch
        holds; `Stretch::reserve` added so the callback never allocates), an 8-voice sampler
        (stretches of songs already in memory, one-shot or looping), Color FX on the filter knob,
        Mix FX on the crossfader (high-pass, echo, reverb, noise on the deck it moves away from),
        a master effect, crossfader assign (side or THRU), Noise and HighPass effects. Tests:
        440 Hz at +8% with keylock stays 440 (±6) while the position runs 1.08×; sampler; Color
        and Mix FX; assign.
      - Pads: 12 pages stepped with ‹ ›: HOT CUE (◀ ▶ previous/next cue), CUELOOP, SAVED LOOP
        (kept with the cues in `console-cues`, now `{"cues", "loops"}`; old arrays still read),
        LOOP ROLL, SLICER (eight 1-beat slices of the 8-beat window, held = roll), LOOP (on/off,
        hold or roll per OPTIONS), MANUAL LOOP (in, out, ends ±1/16 beat, move by length),
        BEATJUMP, SAMPLER (empty pad records the loop or 4 beats; right click clears; session
        only, not saved), BEAT GRID (±1 beat, ±1%, BPM ±0.05, TAP, first beat here), PERFORM
        (brake, spinback, censor, reverse, BPM ÷2 ×2, MATCH, EJECT/next), OPTIONS (CUE mode
        standard/hold-to-play/inverted, play-stutter, hot cue stutter, loop pad mode, loop back,
        auto BPM on load, auto sync on play, sampler mode).
      - Deck: MT (keylock), "0" gradual pitch reset (1.5 s), STOP when stopped = next cue, STOP
        on an empty deck = clone the other deck (same buffer, place, tempo, playing), EJECT on an
        empty deck = next song of the browser list, time cycles elapsed/remaining/length, phrase
        display (bar of 4 and beat; click = phrase sync), waveform zoom with the wheel.
      - Mixer: per-deck Color FX and side buttons, MIX FX, HAM (hamster), LOCK (pitch lock), FX
        (master effect) with its knob, SAMPLER volume.
      - Browser: a search box (typing filters the folder; Enter or "Cerca online" searches the
        catalogue into "Risultati «…»", kept across library changes, late answers dropped);
        "CARTELLE" with "Aggiungi cartella" (system dialog; store key `console-folders`; right
        click removes; scanned off-thread, links to folders not followed, 20 000 files and 12
        levels at most; a missing folder stays listed) - `src/local_music.rs`; local songs have
        the id `file:<path>`, open with `Source::file` and never reach the player, library,
        history or Connect; rows drag onto any deck piece; audio files dropped from the file
        manager load too. Formats checked through `TrackBuf::open`: mp3, flac, wav, ogg, opus,
        m4a, aac, webm (AIFF and WMA do not open; not listed).
      - Review fixes: a deck counts as quiet 3 s after it stops (effects left on no longer keep
        the output from pausing; test), leaving keylock goes on from the place heard (test),
        samples are copies of their stretch (`TrackBuf::slice`, a minute at most), the right
        button stops a playing sample and clears a stopped one, a 1% grid move across a beat
        turns the bar, local folders are not re-read on library changes, file names keep a
        leading number unless a separator follows it ("2Pac", "50 Cent", "1979"), pitch lock
        widens the other deck's range. `ui_tests`: a drop across two cached views arrives, and a
        draggable row still takes a double click.
      - Checked headless: everything above through `LFLY_CONSOLE_STEPS` (actions added),
        `LFLY_CONSOLE_FOLDER=<dir>` (the test formats folder: listed, loaded, played) and
        `LFLY_CONSOLE_SEARCH="<s>@<query>"` (50 results). Not tried on screen: mouse drags, a
        real drag and drop, the folder dialog.
    - Not built: PFL/headphones (a controller's sound card usually has channels 3-4: wait for the
      model), key shift and key match, stems, video, VST, DMX, scratch DNA, broadcast, mic, four
      decks, the sampler's saved banks and its sync.
    - Tests: `LFLY_CONSOLE_LOAD=<s>` loads the folder's first two songs on A and B and plays A;
      `LFLY_CONSOLE_STEPS="<s>:<action>:<deck>;…"` presses controls and logs both decks (see
      `run_steps`). Checked headless: SYNC matched tempo and beat phase (fractions equal to 0.001
      after 8 s), loop, half, roll with slip return, hot cue, beat jump, brake, CUE preview.
      Mouse drags (knobs, faders, platter) are unverified: the user tries them. CPU (headless):
      decks loaded and stopped 0.1% of a core, one or two playing 10-12% (mostly the 30 fps
      redraw). Waveform and BPM appear once a song is fully decoded.

16. **English and Romanian (2026-11-15, committed in de4b755; the user asked for them and went to sleep).**
    See README "Languages". About 450 rows; the English and Romanian beyond the phone's own
    strings (res/values-en, values-ro: 131 exact matches) are mine and need the user's read.
    Default stays Italian (this PC's LANG is en_US, so following the system would have switched
    the user to English). Known gaps: a few Romanian counts inside sentences may miss "de" for 20+
    (song counts use `i18n::songs`/`count`, which handle it); YouTube Music's own shelf titles come in
    whatever language YouTube sends (`hl` untouched); the Tauri app is Italian only; text a page
    stored at load updates on its next load. Scratchpad snapshots of the diff were taken.

17. **Console round 3 (2026-11-17/18, uncommitted; the user asked, then went to sleep: "do it all").**
    Installed to `~/Applications/LFly-nativa`. Nothing committed or pushed.
    - Review fixes (deck_model/decks): TAP under 90 BPM, BPM± bar sign, slicer on bars, MATCH/LOCK
      half-double aware (`set_ratio`), ½/×2 on any loop, STOP to the cue, `TrackBuf` room = 20 min cap
      (a short container length no longer cuts songs), jumps under keylock drop the stretch's
      read-ahead, reverse stops at 0, narrow deck layout (< 330 px: no platter, rows stack).
    - Wrong songs: Monochrome only for the same recording (`lfly-yt monochrome::same_song`); songs
      known by name (Spotify imports, id `spotify_*`) matched by `lfly-yt matching.rs` (length, exact
      title, artist, version words), 2nd query + fallback, never "first result"; their audio cache
      key is `<id>#match3` (old wrong copies ignored). Phone: `MonochromeApiClient.scoreCandidate`
      and `StreamResolver.youtubeCandidateMatches` fixed the same way; tests pass; APK NOT installed
      (no phone attached).
    - Browser: Online folder (search as you type), Up Next panel (store `console-upnext`; toggle
      button and header draggable to left/right; drag songs in/reorder), right-click menu (load A/B,
      Up Next, stems) instead of A/B buttons, "load on a playing deck?" question
      (`DjDecks.pending`), STEMS folder (the prep list). Master FX removed from the mixer.
    - AUTOPILOT (`src/autopilot.rs`, bar `ApBar` in console.rs): Automix planner (exit on outro,
      entry with the rise landing, bars) + `curves::resolve` AUTO styles applied to faders/gain/EQ/
      filter, send effect on FX slot 0, loop roll (Halving), loudness match, continuous beat lock
      (~1 ms), crossfader at 0.5; performance moves between mixes (FILTER BUILD before a louder
      phrase, ECHO THROW, BASS KILL, LOOP ROLL, FILTER WOBBLE); with stems one voice at a time in
      the mix; prepares the next song's stems if the model is there. Lit controls (`Lamp`), green
      mix band on waveforms. Any user control disengages it (`user_touch`; `ap_driving` guards).
    - VIDEO (`src/video.rs`): second window with synced lyrics of the deck heard most; F/double
      click full screen.
    - STEMS: HT-Demucs fp16 ONNX (adowu/htdemucs-onnx, MIT, 166 MB, SHA-256 checked) in
      `~/.local/share/lfly/models/`, downloaded on first use (`lfly-core stems.rs`); split by
      `lfly-beat stems.rs` (shared ORT environment `lfly_beat::env()`: ORT allows one per process);
      kept in `~/.cache/lfly/stems` (6 GB LRU, ~0.5 MB/s of song); engine `Stems` + per-deck stem
      gains (`read()` in decks.rs: mix·other + Σ stem·(g−other)); `stems_model.rs` queue (store
      `console-stems`). Split speed here: 96 s of song in 32 s (8 threads).
    - Mixer tidied: EQ, stems row, crossfader, master/Q/REC/VIDEO/⋯ (settings card: color FX,
      sides, curve, mix FX, hamster, pitch lock, sampler level); tooltips; the side panel closes on
      the Console and comes back after.
    - Test hooks added: LFLY_CONSOLE_STEPS actions `ap`, `stems`, `novox`; LFLY_CONSOLE_UPNEXT,
      LFLY_CONSOLE_VIDEO, LFLY_CONSOLE_MIXMENU (seconds); LFLY_STEMS_MODEL (model path for test runs,
      whose data folder is a copy). zsh: write `${t}:report` in loops (`$t:r` is a modifier).
    - Unverified by ear/hand: real songs through the autopilot and stems, mouse drags of the Up
      Next panel, the video window on a real second screen.
18. **Round 4 (2026-11-18, uncommitted, installed).** From the user's logs: a perform LOOP ROLL left
    engaged when a hand disengaged the autopilot (stop now releases rolls and puts moves back; the
    roll move is gone, moves are rarer and never in the 20 s after engaging or a mix); Up Next now
    lives in `DjDecks.upnext` (store `console-upnext-songs`; the browser keeps only shown/side in
    `console-upnext`) and the autopilot reads it live, taking each song off as it loads (songs added
    while it flies are played); engaging keeps the song waiting on the other deck as the next one
    instead of ejecting it. Look: waveforms coloured by band (`TrackBuf::peaks` → [peak, low, mid,
    high]), deck-colour edge on the strips, progress ring round each platter (last 30 s red,
    blinking), bigger CUE/PLAY/SYNC, KEY column (Camelot) and analysed BPM in the browser. The user
    asked to "copy VirtualDJ's interface": its skin/art is not copied (EULA, their artwork); the
    layout conventions are generic DJ software and were kept, drawn with our pieces.
19. **macOS and accounts (2026-11-18, released as desktop-v0.1.2).** Built and tested on the
    user's MacBook Air M1 over SSH (see the memory notes: build in `~/lfly-src`, sign with the local
    "LFly Local Signing" certificate from `tools/macos-signing-setup.sh`, screenshots with
    `screencapture`; no keystrokes).
    - Window: on macOS the system traffic lights sit in the sidebar card (`MAC_TRAFFIC_LIGHTS`),
      our −□× are hidden (`SYSTEM_WINDOW_BUTTONS`), the full player clears them, the palette button
      is gone from the toolbar (Settings is ⌘,), the light strip goes in full screen.
    - `src/app_menu.rs`: the Mac menu bar in Apple's order (LFly, Archivio, Modifica, Vista,
      Riproduzione, Vai, Finestra), Dock menu with the song and controls (rebuilt only on change),
      red light/⌘W hide the app. Linux/Windows get the same actions as Ctrl keys (Ctrl+Q, Ctrl+,,
      Ctrl+1…4, Ctrl+Alt+S, F11), listed in Settings.
    - Liquid Glass on Metal: `vendor/gpui-pre-apple/src/glass.metal` + `glass_metal.rs`, a port of
      glass.hlsl; `look::GLASS_RENDERER` is now true everywhere.
    - `web/` (`lfly-web`, tao + wry, built with `-p lfly-web`, shipped next to the app): one page,
      one JSON line on stdout. Modes: `ytmusic-login` (Google sign-in → account.json, Settings ›
      Account › Accedi), `spotify-login` and `spotify-token` (the phone's Spotify account import:
      sp_dc cookie + the web player's own token + its private API; the user chose this over the
      official OAuth API). `lfly-core spotify_account.rs` imports playlists as `spl_<id>` and Liked
      Songs into favourites. `LFLY_WEB_DEBUG=1` prints what the token page answered.
    - Not yet tried with real accounts: the Google and Spotify sign-ins.
