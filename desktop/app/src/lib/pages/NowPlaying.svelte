<script lang="ts">
  // The full-window player, after the phone's PlayerScreenV2: the cover large over its own blurred
  // light, the song, the progress, the transport, then the lyrics or the queue beside it.
  import { fly } from "svelte/transition";
  import { cubicOut } from "svelte/easing";
  import Icon from "../Icon.svelte";
  import Glass from "../glass/Glass.svelte";
  import Cover from "../components/Cover.svelte";
  import Ambient from "./parts/Ambient.svelte";
  import Glyph from "./parts/Glyph.svelte";
  import Slider from "./parts/Slider.svelte";
  import LyricsLines from "./parts/LyricsLines.svelte";
  import QueueList from "./parts/QueueList.svelte";
  import OutputMenu from "./parts/OutputMenu.svelte";
  import { portal } from "./parts/portal";
  import { player } from "../player.svelte";
  import { library } from "../library.svelte";
  import { nav } from "../nav.svelte";
  import { menu } from "../menu.svelte";
  import { time } from "../connect.svelte";

  type Panel = "lyrics" | "queue" | null;

  function readPanel(): Panel {
    try {
      const v = localStorage.getItem("lfly.nowplaying.panel");
      return v === "queue" ? "queue" : v === "none" ? null : "lyrics";
    } catch {
      return "lyrics";
    }
  }
  let panel = $state<Panel>(readPanel());
  let outputOpen = $state(false);

  function setPanel(p: Exclude<Panel, null>) {
    panel = panel === p ? null : p;
    try {
      localStorage.setItem("lfly.nowplaying.panel", panel ?? "none");
    } catch {
      // Not kept.
    }
  }

  const t = $derived(player.track);
  const liked = $derived(t ? library.isFavorite(t.id) : false);
  const repeat = $derived(player.state?.repeat ?? "off");
  const shuffleOn = $derived(player.state?.shuffle ?? false);

  // Volume follows the hand at once and reaches the player shortly after.
  let volumeDraft = $state<number | null>(null);
  let volTimer: ReturnType<typeof setTimeout> | undefined;
  const volume = $derived(volumeDraft ?? player.volume);
  function onVolume(v: number) {
    volumeDraft = v;
    clearTimeout(volTimer);
    volTimer = setTimeout(() => {
      player.setVolume(v);
      volumeDraft = null;
    }, 120);
  }

  function close() {
    if (nav.canBack) nav.back();
    else nav.go({ page: "home" });
  }

  function onKey(e: KeyboardEvent) {
    if (e.key !== "Escape") return;
    if (outputOpen) outputOpen = false;
    else close();
  }

  function go(route: Parameters<typeof nav.go>[0]) {
    nav.go(route);
  }

  const reduce = typeof matchMedia !== "undefined" && matchMedia("(prefers-reduced-motion: reduce)").matches;
</script>

<svelte:window onkeydown={onKey} />

<div
  class="np"
  class:with-panel={!!panel && !!t}
  use:portal
  role="dialog"
  aria-modal="true"
  aria-label="In riproduzione"
  transition:fly|global={{ y: 60, duration: reduce ? 0 : 420, easing: cubicOut, opacity: 0 }}
>
  <Ambient url={t?.coverUrl} dim={0.42} blur={80} />

  <header class="bar">
    <Glass as="button" shape="circle" variant="clear" interactive class="np-close" aria-label="Chiudi il player (Esc)" title="Chiudi (Esc)" onclick={close}>
      <Glyph name="chevron-down" size={20} />
    </Glass>
    <div class="where">
      <span class="eyebrow">{player.remote && player.where ? `In riproduzione su ${player.where}` : "In riproduzione"}</span>
      {#if t?.album}<span class="from">{t.album}</span>{/if}
    </div>
    <Glass shape="pill" variant="clear" class="np-toggles">
      <div class="toggles">
        <button class:on={panel === "lyrics"} aria-pressed={panel === "lyrics"} onclick={() => setPanel("lyrics")} title="Testo">
          <Icon name="lyrics" size={17} /><span>Testo</span>
        </button>
        <button class:on={panel === "queue"} aria-pressed={panel === "queue"} onclick={() => setPanel("queue")} title="Coda">
          <Icon name="queue" size={17} /><span>Coda</span>
        </button>
      </div>
    </Glass>
  </header>

  {#if t}
    <main class="stage">
      <section class="player">
        <div class="art" class:paused={!player.playing}>
          {#key t.coverUrl}
            <div class="art-in"><Cover url={t.coverUrl} size={560} fluid alt="Copertina di {t.album ?? t.title}" /></div>
          {/key}
        </div>

        <div class="meta">
          <div class="names">
            <button class="title" title={t.title} onclick={() => t.albumId && go({ page: "album", id: t.albumId, name: t.album })}>{t.title}</button>
            <button class="artist" onclick={() => t.artistId && go({ page: "artist", id: t.artistId, name: t.artist })}>{t.artist}</button>
          </div>
          <button
            class="round"
            class:liked
            aria-pressed={liked}
            aria-label={liked ? "Togli dai Brani che ti piacciono" : "Aggiungi ai Brani che ti piacciono"}
            onclick={() => library.toggleFavorite(t)}
          >
            <Icon name="heart" size={20} fill={liked} />
          </button>
          <button class="round" aria-label="Altre azioni" onclick={(e) => menu.openTrack(e, t)}><Icon name="more" size={20} /></button>
        </div>

        <div class="seek">
          <Slider
            value={player.position}
            max={player.duration || 1}
            step={1000}
            label="Posizione nel brano"
            valuetext="{time(player.position)} di {time(player.duration)}"
            disabled={!player.duration}
            oninput={(v) => (player.scrubbing = v)}
            onchange={(v) => {
              player.seek(v);
              player.scrubbing = null;
            }}
          />
          <div class="times"><span>{time(player.position)}</span><span>-{time(Math.max(0, player.duration - player.position))}</span></div>
        </div>

        <div class="transport">
          <button class="small" class:on={shuffleOn} aria-pressed={shuffleOn} aria-label="Riproduzione casuale" title="Casuale" onclick={() => player.setShuffle(!shuffleOn)}>
            <Icon name="shuffle" size={20} />
          </button>
          <button class="big" aria-label="Precedente" onclick={() => player.previous()}><Icon name="previous" size={34} /></button>
          <button class="huge" aria-label={player.playing ? "Pausa" : "Riproduci"} onclick={() => player.toggle()}>
            {#key player.playing}<span class="pp"><Icon name={player.playing ? "pause" : "play"} size={44} /></span>{/key}
          </button>
          <button class="big" aria-label="Successivo" onclick={() => player.next()}><Icon name="next" size={34} /></button>
          <button
            class="small"
            class:on={repeat !== "off"}
            aria-label={repeat === "one" ? "Ripeti un brano" : repeat === "all" ? "Ripeti tutto" : "Ripeti"}
            title="Ripeti"
            onclick={() => player.cycleRepeat()}
          >
            <Icon name="repeat" size={20} />
            {#if repeat === "one"}<span class="one">1</span>{/if}
          </button>
        </div>

        <div class="volume">
          <button class="vol-ic" aria-label="Volume al minimo" onclick={() => onVolume(0)}><Glyph name="volume-low" size={16} /></button>
          <Slider value={volume} max={100} label="Volume" valuetext="{Math.round(volume)}%" oninput={onVolume} onchange={onVolume} />
          <button class="vol-ic" aria-label="Volume al massimo" onclick={() => onVolume(100)}><Glyph name="volume-high" size={16} /></button>
        </div>

        <div class="device"><OutputMenu bind:open={outputOpen} /></div>
      </section>

      {#if panel}
        <section class="side" class:queue={panel === "queue"} class:lyr={panel === "lyrics"}>
          {#key panel}
            <div class="side-in">
              {#if panel === "lyrics"}<LyricsLines variant="panel" />{:else}<QueueList />{/if}
            </div>
          {/key}
        </section>
      {/if}
    </main>
  {:else}
    <main class="empty">
      <Icon name="music" size={40} />
      <p>Niente in riproduzione.</p>
      <button class="go" onclick={() => go({ page: "search" })}>Cerca qualcosa da ascoltare</button>
    </main>
  {/if}
</div>

<style>
  .np {
    position: fixed;
    inset: 0;
    z-index: 50;
    display: flex;
    flex-direction: column;
    color: #fff;
    overflow: hidden;
    --lyrics-ink: #fff;
    --slider-ink: #fff;
    font-family: var(--lfly-font, inherit);
  }
  .bar {
    position: relative;
    z-index: 2;
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: center;
    gap: 16px;
    padding: 18px 24px 0;
  }
  .bar :global(.np-close) {
    width: 40px;
    height: 40px;
    display: grid;
    place-items: center;
    color: #fff;
    justify-self: start;
  }
  .where {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    min-width: 0;
    text-align: center;
  }
  .eyebrow {
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: rgba(255, 255, 255, 0.65);
  }
  .from {
    font-size: 13px;
    font-weight: 600;
    max-width: 40vw;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .bar :global(.np-toggles) {
    justify-self: end;
    padding: 4px;
  }
  .toggles {
    display: flex;
    gap: 2px;
  }
  .toggles button {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    height: 32px;
    padding: 0 14px;
    border-radius: 999px;
    font-size: 13px;
    font-weight: 600;
    color: rgba(255, 255, 255, 0.75);
    transition:
      background 200ms var(--lfly-ease, ease),
      color 200ms;
  }
  .toggles button:hover {
    color: #fff;
  }
  .toggles button.on {
    background: rgba(255, 255, 255, 0.92);
    color: #111;
  }

  .stage {
    position: relative;
    z-index: 1;
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    justify-items: center;
    align-items: center;
    padding: 8px clamp(24px, 5vw, 80px) 28px;
    gap: clamp(32px, 5vw, 96px);
  }
  .with-panel .stage {
    grid-template-columns: minmax(320px, 0.9fr) minmax(0, 1.1fr);
  }
  .player {
    --w: min(54vh, 460px);
    width: var(--w);
    max-width: 100%;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .with-panel .player {
    --w: min(50vh, 420px);
    justify-self: end;
  }
  .art {
    width: 100%;
    aspect-ratio: 1;
    margin-bottom: 10px;
    transition: transform 700ms var(--lfly-ease-spring, cubic-bezier(0.34, 1.56, 0.64, 1));
  }
  .art.paused {
    transform: scale(0.86);
  }
  .art-in {
    width: 100%;
    height: 100%;
    animation: art-in 600ms var(--lfly-ease, ease) both;
  }
  @keyframes art-in {
    from {
      opacity: 0;
      transform: scale(0.96);
    }
  }
  .art :global(.lfly-cover) {
    border-radius: 14px;
    box-shadow:
      0 30px 80px rgba(0, 0, 0, 0.55),
      0 0 0 1px rgba(255, 255, 255, 0.06);
    transition: box-shadow 700ms;
  }
  .art.paused :global(.lfly-cover) {
    box-shadow: 0 14px 36px rgba(0, 0, 0, 0.4);
  }
  .meta {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .names {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
  }
  .title {
    max-width: 100%;
    font-family: var(--lfly-font-display);
    font-size: 22px;
    font-weight: 750;
    letter-spacing: -0.02em;
    line-height: 1.2;
    text-align: left;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .artist {
    max-width: 100%;
    font-size: 17px;
    color: rgba(255, 255, 255, 0.68);
    text-align: left;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .title:hover,
  .artist:hover {
    text-decoration: underline;
  }
  .round {
    width: 38px;
    height: 38px;
    flex: none;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: rgba(255, 255, 255, 0.85);
    background: rgba(255, 255, 255, 0.12);
    transition:
      background 160ms,
      transform 300ms var(--lfly-ease-spring, ease);
  }
  .round:hover {
    background: rgba(255, 255, 255, 0.2);
  }
  .round:active {
    transform: scale(0.9);
  }
  .round.liked {
    color: var(--lfly-accent);
  }
  .times {
    display: flex;
    justify-content: space-between;
    margin-top: 2px;
    font-size: 11.5px;
    font-weight: 600;
    color: rgba(255, 255, 255, 0.6);
    font-variant-numeric: tabular-nums;
  }
  .transport {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin: 2px -6px;
  }
  .transport button {
    position: relative;
    display: grid;
    place-items: center;
    border-radius: 50%;
    color: #fff;
    transition:
      transform 300ms var(--lfly-ease-spring, ease),
      background 160ms,
      color 160ms;
  }
  .transport button:hover {
    background: rgba(255, 255, 255, 0.1);
  }
  .transport button:active {
    transform: scale(0.88);
  }
  .small {
    width: 40px;
    height: 40px;
    color: rgba(255, 255, 255, 0.6) !important;
  }
  .small.on {
    color: var(--lfly-accent) !important;
  }
  .small.on::after {
    content: "";
    position: absolute;
    bottom: 3px;
    width: 4px;
    height: 4px;
    border-radius: 50%;
    background: currentColor;
  }
  .one {
    position: absolute;
    top: 6px;
    right: 6px;
    font-size: 9px;
    font-weight: 800;
  }
  .big {
    width: 60px;
    height: 60px;
  }
  .huge {
    width: 76px;
    height: 76px;
  }
  .pp {
    display: grid;
    animation: pp 260ms var(--lfly-ease-spring, ease) both;
  }
  @keyframes pp {
    from {
      opacity: 0;
      transform: scale(0.6);
    }
  }
  .volume {
    display: flex;
    align-items: center;
    gap: 10px;
    --slider-ink: rgba(255, 255, 255, 0.9);
  }
  .volume :global(.slider) {
    flex: 1;
  }
  .vol-ic {
    display: grid;
    color: rgba(255, 255, 255, 0.6);
  }
  .vol-ic:hover {
    color: #fff;
  }
  .device {
    display: flex;
    justify-content: center;
  }

  .side {
    width: 100%;
    max-width: 720px;
    height: 100%;
    min-height: 0;
    justify-self: start;
    overflow-y: auto;
    scrollbar-width: none;
    -webkit-mask-image: linear-gradient(to bottom, transparent, #000 14%, #000 82%, transparent);
    mask-image: linear-gradient(to bottom, transparent, #000 14%, #000 82%, transparent);
  }
  /* The lyrics scroll and fade inside themselves. */
  .side.lyr {
    overflow: hidden;
    -webkit-mask-image: none;
    mask-image: none;
  }
  .side.lyr .side-in {
    height: 100%;
    padding: 0 8px;
  }
  .side::-webkit-scrollbar {
    display: none;
  }
  .side-in {
    padding: 22vh 8px 40vh;
    animation: side-in 420ms var(--lfly-ease, ease) both;
  }
  .side.queue .side-in {
    padding: 10vh 8px 20vh;
    max-width: 520px;
  }
  @keyframes side-in {
    from {
      opacity: 0;
      transform: translateX(16px);
    }
  }

  .empty {
    position: relative;
    z-index: 1;
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    color: rgba(255, 255, 255, 0.75);
    font-size: 15px;
  }
  .go {
    height: 36px;
    padding: 0 18px;
    border-radius: 999px;
    background: #fff;
    color: #111;
    font-weight: 650;
  }

  @media (max-width: 900px) {
    .with-panel .stage {
      grid-template-columns: minmax(0, 1fr);
    }
    .with-panel .player {
      display: none;
    }
    .with-panel .side {
      justify-self: center;
    }
    .toggles span {
      display: none;
    }
  }
  @media (max-height: 640px) {
    .player {
      --w: min(46vh, 360px);
    }
    .with-panel .player {
      --w: min(42vh, 340px);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .art,
    .art-in,
    .pp,
    .side-in {
      transition: none;
      animation: none;
    }
  }
</style>
