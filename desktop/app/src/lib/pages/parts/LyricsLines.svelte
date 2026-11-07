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
  function saveOffset(id: string, ms: number) {
    if (!offsets) return;
    if (ms === 0) delete offsets[id];
    else offsets[id] = ms;
    invoke("store_set", { key: "lyrics-offsets", value: offsets }).catch(() => {});
  }
</script>

<script lang="ts">
  // The playing song's lyrics. The sung line is big and bright, the others dim and soften with
  // distance; with word timings the line fills word by word as it is sung. The lines scroll
  // inside this box only (never the page), and stop following while the listener scrolls.
  // Like Liner: the timing can be nudged per song and the nudge is remembered.
  import Icon from "../../Icon.svelte";
  import { api } from "../../api";
  import { player } from "../../player.svelte";
  import { nav } from "../../nav.svelte";

  let { variant = "page" }: { variant?: "page" | "panel" | "side" } = $props();

  let lyrics = $state<Lyrics | null>(null);
  let status = $state<"loading" | "none" | "ready">("loading");
  let box = $state<HTMLDivElement>();
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
    box?.scrollTo({ top: 0 });
  }

  function nudge(ms: number) {
    offset = ms === 0 ? 0 : Math.max(-10_000, Math.min(10_000, offset + ms));
    if (player.track) saveOffset(player.track.id, offset);
  }

  // A clock smoother than the player's quarter-second updates, for the word-by-word fill.
  let base = { pos: 0, at: 0 };
  let clock = $state(0);
  $effect(() => {
    base = { pos: player.position, at: performance.now() };
    clock = player.position;
  });
  $effect(() => {
    if (!player.playing || !words) return;
    let raf = 0;
    const tick = () => {
      clock = base.pos + Math.min(600, performance.now() - base.at);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  });

  const lines = $derived(lyrics?.synced?.length ? lyrics.synced : null);
  const words = $derived(!!lines?.some((l) => l.words?.length));
  /** Where the song is, as the lyrics count it. */
  const at = $derived((words ? clock : player.position) - offset);
  const current = $derived.by(() => {
    if (!lines) return -1;
    const pos = at + 150;
    let i = -1;
    for (let k = 0; k < lines.length; k++) {
      if (lines[k].ms <= pos) i = k;
      else break;
    }
    return i;
  });

  /** How much of a word is sung, 0..1. */
  function sung(w: { ms: number; endMs: number }): number {
    if (at <= w.ms) return 0;
    if (at >= w.endMs) return 1;
    return (at - w.ms) / Math.max(1, w.endMs - w.ms);
  }

  // The sung line stays a third of the way down the box, unless the listener is scrolling.
  let userScroll = 0;
  let programmatic = false;
  $effect(() => {
    const i = current;
    if (!box || Date.now() - userScroll < 3500) return;
    const el = box.querySelector<HTMLElement>(`[data-i="${Math.max(0, i)}"]`);
    if (!el) return;
    const top = el.offsetTop - box.clientHeight * 0.33 + el.offsetHeight / 2;
    programmatic = true;
    box.scrollTo({ top: Math.max(0, top), behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
    setTimeout(() => (programmatic = false), 700);
  });

  function onScroll() {
    if (!programmatic) userScroll = Date.now();
  }

  function seek(ms: number) {
    const target = Math.max(0, ms + offset);
    const last = player.duration > 0 ? Math.max(0, player.duration - 500) : target;
    player.seek(Math.min(target, last));
    userScroll = 0;
  }

  const offsetLabel = $derived(`${offset > 0 ? "+" : offset < 0 ? "−" : "±"}${(Math.abs(offset) / 1000).toFixed(1).replace(".", ",")} s`);
</script>

<div class="lyrics {variant}">
  <div class="scroller" bind:this={box} onscroll={onScroll}>
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
    {:else if lines}
      <div class="lines">
        {#each lines as line, i (i)}
          {@const d = Math.abs(i - current)}
          <button
            class="line"
            data-i={i}
            class:past={i < current}
            class:now={i === current}
            style="--d:{Math.min(d, 5)}"
            onclick={() => seek(line.ms)}
          >
            {#if !line.text.trim()}
              <span class="dots" aria-label="Strumentale"><i></i><i></i><i></i></span>
            {:else if i === current && line.words?.length}
              {#each line.words as w}<span class="w" style="--p:{sung(w)}">{w.text}</span>{/each}
            {:else}
              {line.text}
            {/if}
          </button>
        {/each}
      </div>
    {:else}
      <p class="plain">{lyrics?.plain}</p>
    {/if}
    {#if status === "ready" && lyrics?.source}<p class="src">Testo da {lyrics.source}</p>{/if}
  </div>

  {#if status === "ready" && lines}
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
  .lyrics {
    --ink: var(--lyrics-ink, var(--lfly-text));
    --size: clamp(26px, 2.6vw, 38px);
    position: relative;
    height: 100%;
    min-height: 0;
    container-type: size;
    color: var(--ink);
  }
  .panel {
    --size: clamp(22px, 2vw, 30px);
  }
  .side {
    --size: 21px;
  }
  .scroller {
    height: 100%;
    overflow-y: auto;
    overflow-x: hidden;
    scrollbar-width: none;
    padding: 30cqh 4px 60cqh;
    -webkit-mask-image: linear-gradient(to bottom, transparent, #000 12%, #000 80%, transparent);
    mask-image: linear-gradient(to bottom, transparent, #000 12%, #000 80%, transparent);
  }
  .side .scroller {
    padding: 35cqh 14px 70cqh;
  }
  .scroller::-webkit-scrollbar {
    display: none;
  }
  .lines {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
  .line {
    font-family: var(--lfly-font-display);
    font-size: var(--size);
    font-weight: 800;
    line-height: 1.22;
    letter-spacing: -0.02em;
    text-align: left;
    padding: 8px 14px;
    margin-left: -14px;
    border-radius: 14px;
    color: var(--ink);
    opacity: 0.32;
    filter: blur(calc(var(--d) * 0.5px));
    transform: scale(0.97);
    transform-origin: left center;
    text-wrap: balance;
    transition:
      opacity 420ms var(--lfly-ease, ease),
      filter 420ms var(--lfly-ease, ease),
      transform 600ms var(--lfly-ease-spring, cubic-bezier(0.34, 1.56, 0.64, 1)),
      background 160ms;
  }
  .line.past {
    opacity: 0.45;
  }
  .line.now {
    opacity: 1;
    filter: none;
    transform: none;
  }
  .scroller:hover .line {
    filter: none;
  }
  .line:hover {
    opacity: 0.85;
    background: color-mix(in srgb, var(--ink) 8%, transparent);
  }
  /* Word by word: each word fills with the full ink as it is sung. */
  .w {
    background: linear-gradient(
      90deg,
      var(--ink) calc(var(--p) * 100%),
      color-mix(in srgb, var(--ink) 38%, transparent) calc(var(--p) * 100%)
    );
    -webkit-background-clip: text;
    background-clip: text;
    -webkit-text-fill-color: transparent;
    white-space: pre-wrap;
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
    opacity: 0.6;
  }
  .now .dots i {
    animation: breathe 1.6s ease-in-out infinite;
  }
  .now .dots i:nth-child(2) {
    animation-delay: 0.2s;
  }
  .now .dots i:nth-child(3) {
    animation-delay: 0.4s;
  }
  @keyframes breathe {
    50% {
      opacity: 1;
      transform: scale(1.25);
    }
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
  }
  .loading span {
    height: calc(var(--size) * 0.8);
    border-radius: 10px;
    background: color-mix(in srgb, var(--ink) 12%, transparent);
    animation: lfly-pulse 1.4s ease-in-out infinite;
  }
  .src {
    color: color-mix(in srgb, var(--ink) 55%, transparent);
    font-size: 12px;
    margin-top: 28px;
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
    background: color-mix(in srgb, var(--lfly-glass-tint, rgba(30, 30, 34, 0.6)) 100%, transparent);
    border: 1px solid var(--lfly-glass-border, rgba(255, 255, 255, 0.12));
    -webkit-backdrop-filter: blur(18px) saturate(1.6);
    backdrop-filter: blur(18px) saturate(1.6);
    color: var(--lfly-text);
    font-size: 13px;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
    opacity: 0.55;
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
  @media (prefers-reduced-motion: reduce) {
    .line {
      transition: none;
      transform: none;
    }
  }
</style>
