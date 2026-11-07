<script lang="ts" module>
  import { invoke } from "@tauri-apps/api/core";
  import type { Lyrics } from "../../api";

  /** Lyrics already fetched, by track: the side panel, the page and the full player share them. */
  const cache = new Map<string, Lyrics | null>();

  /** Per-song timing correction in ms (positive: the words come later), remembered like Liner does. */
  let offsets: Record<string, number> | null = null;
  async function loadOffsets(): Promise<Record<string, number>> {
    if (!offsets) {
      offsets = ((await invoke<Record<string, number> | null>("store_get", { key: "lyrics-offsets" }).catch(() => null)) ?? {}) as Record<
        string,
        number
      >;
    }
    return offsets;
  }
  // Settings → Testi → Azzera: forget the ones read already.
  if (typeof window !== "undefined") window.addEventListener("lfly:lyrics-offsets-reset", () => (offsets = {}));
  function saveOffset(id: string, ms: number) {
    if (!offsets) return;
    if (ms === 0) delete offsets[id];
    else offsets[id] = ms;
    invoke("store_set", { key: "lyrics-offsets", value: offsets }).catch(() => {});
  }
</script>

<script lang="ts">
  // The playing song's lyrics, moving like Liner's: syllables light up with a soft sweeping
  // edge, held notes glow, the lines glide one after another to keep the sung one a third of
  // the way down, and the edges fade into fog. The list scrolls itself (never the page); the
  // wheel takes over for a few seconds. Everything that changes per frame is written straight
  // to the elements from one animation loop, which sleeps while the song is paused.
  import { onDestroy } from "svelte";
  import Icon from "../../Icon.svelte";
  import { api } from "../../api";
  import { player } from "../../player.svelte";
  import { nav } from "../../nav.svelte";
  import { themes } from "../../theme/theme.svelte";
  import { prefs } from "../../prefs.svelte";
  import { buildLines, EDGE_WIDTH, Follower, isLive, lightOf, maxScroll, Scroll, type Box, type LineModel } from "../../lyrics/motion";

  let { variant = "page" }: { variant?: "page" | "panel" | "side" } = $props();

  let lyrics = $state<Lyrics | null>(null);
  let status = $state<"loading" | "none" | "ready">("loading");
  let offset = $state(0);
  let loadedFor = "";

  $effect(() => {
    const t = player.track;
    if (!t || t.id === loadedFor) return;
    const id = t.id;
    loadedFor = id;
    offset = 0;
    loadOffsets().then((o) => loadedFor === id && (offset = o[id] ?? 0));
    if (cache.has(id)) {
      show(cache.get(id) ?? null);
      return;
    }
    status = "loading";
    lyrics = null;
    api
      .lyrics(t)
      .then((l) => {
        cache.set(id, l);
        if (loadedFor === id) show(l);
      })
      .catch(() => loadedFor === id && (status = "none"));
  });

  function show(l: Lyrics | null) {
    lyrics = l;
    status = l && (l.synced?.length || l.plain) ? "ready" : "none";
  }

  function nudge(ms: number) {
    offset = ms === 0 ? 0 : Math.max(-10_000, Math.min(10_000, offset + ms));
    if (player.track) saveOffset(player.track.id, offset);
  }

  const synced = $derived(lyrics?.synced?.length ? lyrics.synced : null);
  // Settings → Testi: whole lines instead of word by word, when asked.
  const model = $derived<LineModel[]>(
    synced ? buildLines(prefs.v.lyricsWords ? synced : synced.map((l) => ({ ...l, words: undefined })), player.duration) : [],
  );
  const scale = $derived({ s: 0.82, m: 1, l: 1.22 }[prefs.v.lyricsSize]);

  // --- the animation loop --------------------------------------------------------------------

  let view = $state<HTMLDivElement>();
  let stack = $state<HTMLDivElement>();
  let srcEl = $state<HTMLElement>();
  const scroll = new Scroll();
  const follower = new Follower();
  let lineEls: HTMLElement[] = [];
  let tokenEls: HTMLElement[][] = [];
  let painted: string[][] = [];
  let lineY: number[] = [];
  let lineOn: boolean[] = [];
  let boxes: Box[] = [];
  let viewH = 0;
  let placed = false;
  let pausedUntil = 0;
  let raf = 0;
  let last = 0;
  let wokeAt = 0;
  let edges = "";
  // A clock between the player's samples: carried forward by the frame time, re-anchored
  // only when it drifts, so the sweep never stutters.
  let anchorPos = 0;
  let anchorAt = 0;

  function clock(now: number): number {
    const raw = player.livePosition();
    if (!player.playing) {
      anchorPos = raw;
      anchorAt = now;
      return raw;
    }
    const est = anchorPos + (now - anchorAt);
    if (Math.abs(est - raw) > 150) {
      anchorPos = raw;
      anchorAt = now;
      return raw;
    }
    return est;
  }

  function collect() {
    if (!stack) return;
    lineEls = Array.from(stack.querySelectorAll<HTMLElement>(":scope > .line"));
    tokenEls = lineEls.map((el) => Array.from(el.querySelectorAll<HTMLElement>(".t")));
    painted = tokenEls.map((ts) => ts.map(() => ""));
    lineY = lineEls.map(() => NaN);
    lineOn = lineEls.map(() => false);
    lineEls.forEach((el) => el.classList.remove("on"));
  }

  function measure() {
    if (!view) return;
    boxes = lineEls.map((el) => ({ top: el.offsetTop, height: el.offsetHeight }));
    viewH = view.clientHeight;
    scroll.limit(maxScroll(boxes, viewH));
    follower.reset();
  }

  function kick() {
    wokeAt = performance.now();
    if (!raf && lineEls.length) {
      last = wokeAt;
      raf = requestAnimationFrame(frame);
    }
  }

  function frame(now: number) {
    raf = 0;
    const dt = Math.min(64, now - last);
    last = now;
    const at = clock(now) - offset;

    if (now >= pausedUntil) {
      const d = follower.decide(model, boxes, at, viewH);
      if (d) {
        if (!placed || themes.reduceMotion) scroll.jump(d.top);
        else scroll.glide(d.top, d.anchor, now);
        placed = true;
      }
    }
    scroll.step(now, dt);

    // Each line where the list is, plus how far it still trails behind the glide.
    for (let i = 0; i < lineEls.length; i++) {
      const y = Math.round((scroll.lag(i, now) - scroll.pos) * 2) / 2;
      if (y !== lineY[i]) {
        lineY[i] = y;
        lineEls[i].style.transform = `translate3d(0, ${y}px, 0)`;
      }
    }
    if (srcEl) srcEl.style.transform = `translate3d(0, ${lineY[lineY.length - 1] ?? 0}px, 0)`;

    const e = `${scroll.pos <= 4 ? "t" : ""}${scroll.max - scroll.pos <= 4 ? "b" : ""}`;
    if (e !== edges && view) {
      edges = e;
      view.classList.toggle("at-top", e.includes("t"));
      view.classList.toggle("at-bottom", e.includes("b"));
    }

    // The light of every syllable on the lines being sung or just left.
    for (let i = 0; i < model.length; i++) {
      const line = model[i];
      const on = at >= line.start && at < line.exit;
      if (on !== lineOn[i]) {
        lineOn[i] = on;
        lineEls[i]?.classList.toggle("on", on);
      }
      const els = tokenEls[i];
      if (!els?.length) continue;
      const live = isLive(line, at);
      if (!live && painted[i][0] === "" && painted[i][els.length - 1] === "") continue;
      let k = 0;
      for (const g of line.groups) {
        if (g.space) continue;
        for (const t of g.tokens) {
          const el = els[k];
          if (!el) break;
          let css = "";
          if (live) {
            const l = lightOf(t, line, at);
            if (l.alpha > 0) {
              const lit = (0.3 + 0.7 * l.alpha) * 100;
              const s = l.solid * 100;
              css =
                `background-image:linear-gradient(90deg,color-mix(in srgb,var(--ink) ${lit.toFixed(1)}%,transparent) ${s.toFixed(1)}%,var(--dim) ${(s + EDGE_WIDTH * 100).toFixed(1)}%)` +
                (l.glow > 0.01 ? `;text-shadow:0 0 ${(l.glow * 13).toFixed(1)}px color-mix(in srgb,var(--ink) 75%,transparent)` : "");
            }
          }
          if (painted[i][k] !== css) {
            painted[i][k] = css;
            el.style.cssText = css;
          }
          k++;
        }
      }
    }

    if (player.playing || scroll.moving || now - wokeAt < 900) raf = requestAnimationFrame(frame);
  }

  // A new sheet: find its elements, measure, place without gliding.
  $effect(() => {
    void model;
    if (!stack || !view) return;
    collect();
    measure();
    placed = false;
    kick();
  });

  $effect(() => {
    if (!view || !stack) return;
    const ro = new ResizeObserver(() => {
      measure();
      kick();
    });
    ro.observe(view);
    ro.observe(stack);
    // Wheel and trackpad move the list; following the song resumes a little later.
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      scroll.wheel(e.deltaY * (e.deltaMode === 1 ? 32 : e.deltaMode === 2 ? viewH : 1));
      pausedUntil = performance.now() + 3500;
      follower.reset();
      kick();
    };
    view.addEventListener("wheel", wheel, { passive: false });
    return () => {
      ro.disconnect();
      view?.removeEventListener("wheel", wheel);
    };
  });

  // Wake the loop when the song moves, plays or the timing is nudged.
  $effect(() => {
    void player.position;
    void player.playing;
    void offset;
    kick();
  });

  onDestroy(() => cancelAnimationFrame(raf));

  function seek(ms: number) {
    const to = Math.max(0, ms + offset);
    const end = player.duration > 0 ? Math.max(0, player.duration - 500) : to;
    player.seek(Math.min(to, end));
    pausedUntil = 0;
    follower.reset();
    kick();
  }

  const offsetLabel = $derived(`${offset > 0 ? "+" : offset < 0 ? "−" : "±"}${(Math.abs(offset) / 1000).toFixed(1).replace(".", ",")} s`);
</script>

<div class="lyrics {variant}" class:centered={prefs.v.lyricsAlign === "center"} style="--scale:{scale}">
  {#if !player.track}
    <p class="msg">Fai partire un brano per vederne il testo.</p>
  {:else if status === "loading"}
    <div class="loading" aria-busy="true" aria-label="Cerco il testo…">
      {#each Array(5) as _, i}<span style="width:{[72, 54, 80, 46, 64][i]}%"></span>{/each}
    </div>
  {:else if status === "none"}
    <div class="none">
      <Icon name="lyrics" size={30} />
      <p>Nessun testo trovato per “{player.track.title}”.</p>
    </div>
  {:else if synced}
    <div class="view at-top" bind:this={view}>
      <div class="stack" bind:this={stack}>
        {#each model as line, i (i)}
          <button class="line" class:inst={line.instrumental} onclick={() => seek(line.start)}>
            {#if line.instrumental}
              <span class="dots" aria-label="Strumentale"><i></i><i></i><i></i></span>
            {:else}
              <span class="main"
                >{#each line.groups as g}{#if g.space}{g.tokens[0].text}{:else}<span class="g"
                      >{#each g.tokens as t}<span class="t">{t.text}</span>{/each}</span
                    >{/if}{/each}</span
              >
              {#if line.bg}<span class="bgv">{line.bg}</span>{/if}
            {/if}
          </button>
        {/each}
        {#if lyrics?.source && prefs.v.lyricsSource}<p class="src" bind:this={srcEl}>Testo da {lyrics.source}</p>{/if}
      </div>
    </div>
  {:else}
    <div class="plain-box">
      <p class="plain">{lyrics?.plain}</p>
      {#if lyrics?.source && prefs.v.lyricsSource}<p class="src">Testo da {lyrics.source}</p>{/if}
    </div>
  {/if}

  {#if status === "ready" && synced}
    <div class="sync" role="group" aria-label="Sincronia del testo">
      <button onclick={() => nudge(-250)} aria-label="Testo prima di 0,25 secondi" title="Il testo arriva prima">−</button>
      <button class="val" class:moved={offset !== 0} onclick={() => nudge(0)} title={offset ? "Torna alla sincronia originale" : "Sincronia del testo per questo brano"}>
        {offsetLabel}
      </button>
      <button onclick={() => nudge(250)} aria-label="Testo dopo 0,25 secondi" title="Il testo arriva dopo">+</button>
      {#if variant === "side"}
        <span class="sep"></span>
        <button onclick={() => nav.go({ page: "lyrics" })} aria-label="Apri il testo a tutta pagina" title="A tutta pagina">
          <Icon name="expand" size={13} />
        </button>
      {/if}
    </div>
  {/if}
</div>

<style>
  @property --fog-top {
    syntax: "<percentage>";
    inherits: false;
    initial-value: 0%;
  }
  @property --fog-bottom {
    syntax: "<percentage>";
    inherits: false;
    initial-value: 26%;
  }
  .lyrics {
    --ink: var(--lyrics-ink, var(--lfly-text));
    --dim: color-mix(in srgb, var(--ink) 30%, transparent);
    --size: clamp(28px, 2.7vw, 40px);
    --pad-x: 4px;
    position: relative;
    height: 100%;
    min-height: 0;
    color: var(--ink);
  }
  .panel {
    --size: clamp(24px, 2.2vw, 34px);
  }
  .side {
    --size: 21px;
    --pad-x: 14px;
  }
  .view {
    position: relative;
    height: 100%;
    overflow: hidden;
    --fog-top: 22%;
    --fog-bottom: 26%;
    -webkit-mask-image: linear-gradient(to bottom, transparent, #000 var(--fog-top), #000 calc(100% - var(--fog-bottom)), transparent);
    mask-image: linear-gradient(to bottom, transparent, #000 var(--fog-top), #000 calc(100% - var(--fog-bottom)), transparent);
    transition:
      --fog-top 450ms cubic-bezier(0.22, 1, 0.36, 1),
      --fog-bottom 450ms cubic-bezier(0.22, 1, 0.36, 1);
  }
  .view:global(.at-top) {
    --fog-top: 0%;
  }
  .view:global(.at-bottom) {
    --fog-bottom: 0%;
  }
  .side .view {
    --fog-top: 14%;
    --fog-bottom: 18%;
  }
  .side .view:global(.at-top) {
    --fog-top: 0%;
  }
  .side .view:global(.at-bottom) {
    --fog-bottom: 0%;
  }
  .stack {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    padding: 24px var(--pad-x) 0;
  }
  .line {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
    font-family: var(--lfly-font-display);
    font-size: calc(var(--size) * var(--scale, 1));
    font-weight: 700;
    line-height: 1.3;
    letter-spacing: -0.012em;
    text-align: left;
    padding: calc(var(--size) * 0.36) 12px;
    margin: 0 -12px;
    border-radius: 14px;
    color: var(--dim);
    text-wrap: pretty;
    will-change: transform;
    transition: background 160ms;
  }
  .centered .line {
    align-items: center;
    text-align: center;
  }
  .centered .src {
    text-align: center;
  }
  .line:hover {
    background: color-mix(in srgb, var(--ink) 7%, transparent);
  }
  .main {
    display: block;
  }
  .g {
    white-space: nowrap;
  }
  /* A syllable's light is painted by the loop as a gradient clipped to its letters. */
  .t {
    -webkit-background-clip: text;
    background-clip: text;
  }
  .t:global([style*="background-image"]) {
    -webkit-text-fill-color: transparent;
  }
  .bgv {
    font-size: 0.72em;
    font-weight: 600;
    color: color-mix(in srgb, var(--ink) 23%, transparent);
    transition: color 330ms ease;
  }
  .line:global(.on) .bgv {
    color: color-mix(in srgb, var(--ink) 78%, transparent);
  }
  .dots {
    display: inline-flex;
    gap: 10px;
    padding: 10px 0;
  }
  .dots i {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: currentColor;
    transition:
      background 330ms ease,
      transform 330ms ease;
  }
  .line:global(.on) .dots i {
    background: var(--ink);
    animation: breathe 1.6s ease-in-out infinite;
  }
  .line:global(.on) .dots i:nth-child(2) {
    animation-delay: 0.2s;
  }
  .line:global(.on) .dots i:nth-child(3) {
    animation-delay: 0.4s;
  }
  @keyframes breathe {
    50% {
      transform: scale(1.3);
    }
  }
  .plain-box {
    height: 100%;
    overflow-y: auto;
    scrollbar-width: none;
    padding: 24px var(--pad-x) 40%;
  }
  .plain {
    font-family: var(--lfly-font-display);
    font-size: calc(var(--size) * 0.7);
    font-weight: 700;
    line-height: 1.55;
    white-space: pre-line;
    margin: 0;
  }
  .msg,
  .none {
    color: color-mix(in srgb, var(--ink) 70%, transparent);
    font-size: 15px;
    padding: 24px var(--pad-x);
  }
  .none {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    text-align: center;
  }
  .loading {
    display: flex;
    flex-direction: column;
    gap: 18px;
    padding: 24px var(--pad-x);
  }
  .loading span {
    height: calc(var(--size) * 0.8);
    border-radius: 10px;
    background: color-mix(in srgb, var(--ink) 12%, transparent);
    animation: lfly-pulse 1.4s ease-in-out infinite;
  }
  .src {
    color: color-mix(in srgb, var(--ink) 50%, transparent);
    font-size: 12px;
    margin: 24px 0 0;
    will-change: transform;
  }
  /* Liner's timing nudge: a small capsule at the bottom of the box. */
  .sync {
    position: absolute;
    left: 50%;
    bottom: 10px;
    translate: -50% 0;
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 3px;
    border-radius: 999px;
    background: var(--lfly-glass-tint, rgba(30, 30, 34, 0.6));
    border: 1px solid var(--lfly-glass-border, rgba(255, 255, 255, 0.12));
    -webkit-backdrop-filter: blur(18px) saturate(1.6);
    backdrop-filter: blur(18px) saturate(1.6);
    color: var(--lfly-text);
    font-size: 13px;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
    opacity: 0;
    transition: opacity var(--lfly-dur-fast, 160ms);
  }
  .lyrics:hover .sync,
  .sync:focus-within {
    opacity: 1;
  }
  .sync button {
    min-width: 30px;
    height: 28px;
    padding: 0 8px;
    border-radius: 999px;
    display: grid;
    place-items: center;
  }
  .sync button:hover {
    background: var(--lfly-fill, rgba(255, 255, 255, 0.1));
  }
  .sync .val {
    min-width: 64px;
  }
  .sync .val.moved {
    color: var(--lfly-accent);
  }
  .sep {
    width: 1px;
    height: 16px;
    background: var(--lfly-separator, rgba(255, 255, 255, 0.15));
    margin: 0 2px;
  }
</style>
