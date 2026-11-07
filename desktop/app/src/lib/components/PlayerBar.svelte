<script lang="ts">
  import { fly } from "svelte/transition";
  import Icon from "../Icon.svelte";
  import Cover from "./Cover.svelte";
  import Slider from "./Slider.svelte";
  import Glass from "../glass/Glass.svelte";
  import { themes } from "../theme/theme.svelte";
  import { player } from "../player.svelte";
  import { connect, time, cover } from "../connect.svelte";
  import { nav } from "../nav.svelte";
  import { ui } from "../ui.svelte";
  import { menu } from "../menu.svelte";
  import { library } from "../library.svelte";

  const t = $derived(player.track);
  const liked = $derived(t ? library.isFavorite(t.id) : false);
  const glow = $derived(t?.coverUrl ? cover(t.coverUrl, 96) : undefined);
  const reduce = $derived(themes.reduceMotion);

  let volumeDraft = $state<number | null>(null);
  let lastVolume = 60;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const volume = $derived(volumeDraft ?? player.volume);

  function onVolume(v: number) {
    volumeDraft = v;
    clearTimeout(timer);
    timer = setTimeout(() => player.setVolume(v), 100);
  }
  function settleVolume() {
    setTimeout(() => (volumeDraft = null), 600);
  }
  function wheelVolume(e: WheelEvent) {
    e.preventDefault();
    onVolume(Math.min(100, Math.max(0, Math.round(volume - Math.sign(e.deltaY) * 5))));
    settleVolume();
  }

  function toggleMute() {
    if (volume > 0) {
      lastVolume = volume;
      player.setVolume(0);
    } else {
      player.setVolume(lastVolume || 60);
    }
  }

  function openPlayer() {
    if (t) nav.go({ page: "nowplaying" });
  }

  function trackMenu(e: MouseEvent) {
    if (!t) return;
    e.preventDefault();
    menu.openTrack(e, t);
  }
</script>

<div class="dock">
  {#if player.where}
    <button class="where" onclick={() => ui.togglePanel("devices")} transition:fly={{ y: 8, duration: reduce ? 0 : 220 }}>
      <span class="where-dot" aria-hidden="true"></span>
      <Icon name={connect.following?.kind ?? "phone"} size={13} />
      In riproduzione su {player.where}
    </button>
  {/if}

  <Glass as="section" shape="pill" class="lfly-player" aria-label="Player" style="width:100%">
    <div class="bar" class:empty={!t}>
      {#if glow}<img class="glow" src={glow} alt="" aria-hidden="true" />{/if}

      <div class="now" oncontextmenu={trackMenu} role="group" aria-label="In riproduzione">
        <button class="art" aria-label={t ? `Apri il player: ${t.title}` : "Niente in riproduzione"} disabled={!t} onclick={openPlayer}>
          <Cover url={t?.coverUrl} size={52} alt="" />
          {#if t}<span class="art-hint" aria-hidden="true"><Icon name="chevron-up" size={18} /></span>{/if}
        </button>
        <div class="text">
          {#if t}
            {#key t.id}
              <div class="text-in" in:fly={{ y: -8, duration: reduce ? 0 : 260 }}>
                <button class="title ellipsis" title="Apri il player" onclick={openPlayer}>{t.title}</button>
                <button
                  class="artist ellipsis"
                  disabled={!t.artistId}
                  onclick={() => t.artistId && nav.go({ page: "artist", id: t.artistId, name: t.artist })}
                >
                  {t.artist}
                </button>
              </div>
            {/key}
          {:else}
            <span class="title idle">Niente in riproduzione</span>
            <span class="artist idle">Scegli un brano per iniziare</span>
          {/if}
        </div>
        {#if t}
          <div class="acts">
            <button
              class="ctl mini like"
              class:on={liked}
              aria-pressed={liked}
              aria-label={liked ? "Togli dai Brani che ti piacciono" : "Aggiungi ai Brani che ti piacciono"}
              title={liked ? "Togli dai Brani che ti piacciono" : "Aggiungi ai Brani che ti piacciono"}
              onclick={() => library.toggleFavorite(t)}
            >
              <Icon name="heart" size={16} fill={liked} />
            </button>
            <button class="ctl mini more" aria-label="Altre azioni sul brano" title="Altro" onclick={(e) => menu.openTrack(e, t)}>
              <Icon name="more" size={18} />
            </button>
            <button class="open" aria-label="Apri il player" title="Apri il player" onclick={openPlayer}>
              <Icon name="chevron-up" size={16} />
            </button>
          </div>
        {/if}
      </div>

      <div class="center">
        <div class="controls">
          <button
            class="ctl side"
            class:on={player.state?.shuffle}
            aria-pressed={player.state?.shuffle ?? false}
            aria-label="Riproduzione casuale"
            title="Riproduzione casuale"
            disabled={!t}
            onclick={() => player.setShuffle(!player.state?.shuffle)}
          >
            <Icon name="shuffle" size={16} />
          </button>
          <button class="ctl skip" aria-label="Precedente" title="Precedente (Ctrl+←)" disabled={!t} onclick={() => player.previous()}>
            <Icon name="previous" size={19} />
          </button>
          <button
            class="play"
            aria-label={player.playing ? "Pausa" : "Riproduci"}
            title={player.playing ? "Pausa (Spazio)" : "Riproduci (Spazio)"}
            disabled={!t}
            onclick={() => player.toggle()}
          >
            <Icon name={player.playing ? "pause" : "play"} size={18} />
          </button>
          <button class="ctl skip" aria-label="Successivo" title="Successivo (Ctrl+→)" disabled={!t} onclick={() => player.next()}>
            <Icon name="next" size={19} />
          </button>
          <button
            class="ctl side repeat"
            class:on={player.state && player.state.repeat !== "off"}
            aria-label={player.state?.repeat === "one" ? "Ripeti un brano" : player.state?.repeat === "all" ? "Ripeti tutto" : "Ripeti"}
            title="Ripeti"
            disabled={!t}
            onclick={() => player.cycleRepeat()}
          >
            <Icon name="repeat" size={16} />
            {#if player.state?.repeat === "one"}<span class="one">1</span>{/if}
          </button>
        </div>
        <div class="lfly-progress">
          <span class="time">{time(player.position)}</span>
          <Slider
            class="seek"
            value={player.position}
            max={player.duration || 1}
            step={1000}
            keyStep={5000}
            label="Posizione nel brano"
            valuetext="{time(player.position)} di {time(player.duration)}"
            disabled={!t || !player.duration}
            oninput={(v) => (player.scrubbing = v)}
            onchange={(v) => {
              player.seek(v);
              player.scrubbing = null;
            }}
          />
          <span class="time end">{time(player.duration)}</span>
        </div>
        <div class="line" aria-hidden="true">
          <span style="width:{player.duration > 0 ? (player.position / player.duration) * 100 : 0}%"></span>
        </div>
      </div>

      <div class="right">
        <button
          class="ctl extra"
          class:on={ui.panel === "lyrics" || nav.route.page === "lyrics"}
          aria-pressed={ui.panel === "lyrics" || nav.route.page === "lyrics"}
          aria-label="Testo"
          title="Testo"
          disabled={!t}
          onclick={() => (nav.route.page === "lyrics" ? nav.back() : ui.togglePanel("lyrics"))}
        >
          <Icon name="lyrics" size={17} />
        </button>
        <button
          class="ctl"
          class:on={ui.panel === "queue"}
          aria-pressed={ui.panel === "queue"}
          aria-label="Coda"
          title="Coda"
          onclick={() => ui.togglePanel("queue")}
        >
          <Icon name="queue" size={17} />
        </button>
        <button
          class="ctl"
          class:on={ui.panel === "devices" || player.remote}
          aria-pressed={ui.panel === "devices"}
          aria-label="Dispositivi"
          title="Connetti a un dispositivo"
          onclick={() => ui.togglePanel("devices")}
        >
          <Icon name="devices" size={17} />
        </button>
        <div class="vol" onwheel={wheelVolume}>
          <button class="ctl" aria-label={volume > 0 ? "Silenzia" : "Riattiva l'audio"} title="Volume" onclick={toggleMute}>
            <Icon name={volume === 0 ? "volume-mute" : volume < 50 ? "speaker-wave" : "volume"} size={17} />
          </button>
          <Slider class="volume" value={volume} max={100} step={1} keyStep={5} label="Volume" valuetext="{Math.round(volume)}%" oninput={onVolume} onchange={settleVolume} />
        </div>
      </div>
    </div>
  </Glass>
</div>

<style>
  .dock {
    position: relative;
    container: bar / inline-size;
    display: flex;
    flex-direction: column;
    align-items: center;
  }
  .bar {
    position: relative;
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(300px, 1.25fr) minmax(0, 1fr);
    align-items: center;
    gap: 16px;
    height: 76px;
    padding: 0 18px 0 12px;
    border-radius: 999px;
    overflow: hidden;
    isolation: isolate;
    /* Pages scroll under the bar: a little of the canvas colour keeps the controls legible. */
    background: color-mix(in srgb, var(--lfly-bg) 58%, transparent);
  }
  /* The cover's light, spilled into the glass. */
  .glow {
    position: absolute;
    left: -40px;
    top: 50%;
    width: 260px;
    height: 260px;
    translate: 0 -50%;
    object-fit: cover;
    filter: blur(42px) saturate(1.6);
    opacity: 0.3;
    z-index: -1;
    pointer-events: none;
  }

  .now {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
  }
  .art {
    position: relative;
    flex: none;
    border-radius: 14px;
    transition: scale var(--lfly-dur-fast) var(--lfly-ease-spring);
  }
  .art :global(.lfly-cover) {
    border-radius: 14px;
    box-shadow: 0 4px 14px rgba(0, 0, 0, 0.35);
  }
  .art-hint {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    border-radius: 14px;
    color: #fff;
    background: rgba(0, 0, 0, 0.42);
    opacity: 0;
    transition: opacity var(--lfly-dur-fast) var(--lfly-ease);
  }
  .art:hover:not(:disabled) {
    scale: 1.04;
  }
  .art:active:not(:disabled) {
    scale: 0.96;
  }
  .art:hover .art-hint,
  .art:focus-visible .art-hint {
    opacity: 1;
  }
  .text {
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .text-in {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    min-width: 0;
  }
  .title {
    max-width: 100%;
    text-align: left;
    font: 600 14px/1.3 var(--lfly-font);
    color: var(--lfly-text);
  }
  .artist {
    max-width: 100%;
    text-align: left;
    font-size: 12.5px;
    line-height: 1.35;
    color: var(--lfly-text-2);
  }
  button.title:hover,
  button.artist:hover:not(:disabled) {
    text-decoration: underline;
    text-underline-offset: 2px;
  }
  button.artist:disabled {
    color: var(--lfly-text-2);
    cursor: default;
  }
  .idle {
    color: var(--lfly-text-2);
  }
  .idle.artist {
    color: var(--lfly-text-3);
  }

  .ctl {
    position: relative;
    display: inline-grid;
    place-items: center;
    width: 34px;
    height: 34px;
    flex: none;
    border-radius: 50%;
    color: var(--lfly-text-2);
    transition:
      color var(--lfly-dur-fast) var(--lfly-ease),
      background var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-fast) var(--lfly-ease-spring);
  }
  .ctl:hover:not(:disabled) {
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 9%, transparent);
  }
  .ctl:active:not(:disabled) {
    scale: 0.9;
  }
  .ctl:disabled {
    opacity: 0.35;
  }
  .ctl.on {
    color: var(--lfly-accent);
  }
  .ctl.on::after {
    content: "";
    position: absolute;
    bottom: 3px;
    left: 50%;
    width: 4px;
    height: 4px;
    margin-left: -2px;
    border-radius: 50%;
    background: currentColor;
  }
  .like.on::after {
    display: none;
  }
  .like.on :global(svg) {
    animation: pop 0.42s var(--lfly-ease-spring);
  }
  @keyframes pop {
    40% {
      transform: scale(1.3);
    }
  }
  /* Like, more and open: a compact cluster after the title. */
  .acts {
    display: flex;
    align-items: center;
    gap: 2px;
    flex: none;
    margin-left: auto;
  }
  .mini {
    width: 30px;
    height: 30px;
  }
  .open {
    display: inline-grid;
    place-items: center;
    width: 30px;
    height: 30px;
    margin-left: 2px;
    border-radius: 50%;
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 10%, transparent);
    box-shadow: inset 0 1px 0 color-mix(in srgb, var(--lfly-text) 14%, transparent);
    transition:
      background var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-fast) var(--lfly-ease-spring),
      translate var(--lfly-dur-med, 260ms) var(--lfly-ease-spring);
  }
  .open:hover {
    background: color-mix(in srgb, var(--lfly-text) 18%, transparent);
    translate: 0 -2px;
  }
  .open:active {
    scale: 0.9;
  }
  .skip {
    color: var(--lfly-text);
    width: 38px;
    height: 38px;
  }
  .one {
    position: absolute;
    top: 4px;
    right: 4px;
    font-size: 8px;
    font-weight: 800;
  }

  .center {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    min-width: 0;
    padding-top: 4px;
  }
  .controls {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .play {
    width: 40px;
    height: 40px;
    margin: 0 4px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    background: var(--lfly-text);
    color: var(--lfly-bg);
    box-shadow: 0 4px 14px rgba(0, 0, 0, 0.25);
    transition:
      scale var(--lfly-dur-fast) var(--lfly-ease-spring),
      opacity var(--lfly-dur-fast) var(--lfly-ease);
  }
  .play:hover:not(:disabled) {
    scale: 1.07;
  }
  .play:active:not(:disabled) {
    scale: 0.92;
  }
  .play:disabled {
    opacity: 0.4;
  }
  .lfly-progress {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .lfly-progress :global(.seek) {
    flex: 1;
  }
  .time {
    font-size: 11px;
    color: var(--lfly-text-3);
    font-variant-numeric: tabular-nums;
    min-width: 34px;
    text-align: right;
  }
  .time.end {
    text-align: left;
  }
  .line {
    display: none;
  }

  .right {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 4px;
    min-width: 0;
  }
  .vol {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .vol :global(.volume) {
    width: clamp(64px, 9vw, 112px);
  }

  .where {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    margin-bottom: 8px;
    padding: 6px 14px 6px 10px;
    border-radius: 999px;
    font: 600 12px/1 var(--lfly-font);
    color: var(--lfly-on-accent);
    background: var(--lfly-accent);
    box-shadow:
      0 6px 20px color-mix(in srgb, var(--lfly-accent) 40%, transparent),
      inset 0 1px 0 rgba(255, 255, 255, 0.3);
    transition: scale var(--lfly-dur-fast) var(--lfly-ease-spring);
  }
  .where:hover {
    scale: 1.03;
  }
  .where-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: currentColor;
    animation: breathe 1.8s ease-in-out infinite;
  }
  @keyframes breathe {
    50% {
      opacity: 0.35;
    }
  }

  /* Medium: the volume slider goes, the controls stay. */
  @container bar (max-width: 900px) {
    .bar {
      grid-template-columns: minmax(0, 1fr) minmax(240px, 1.2fr) auto;
    }
    .vol :global(.volume),
    .side,
    .more {
      display: none;
    }
  }
  /* Narrow: the phone's mini player — cover, title, play and skip, a hairline of progress. */
  @container bar (max-width: 620px) {
    .bar {
      grid-template-columns: minmax(0, 1fr) auto auto;
      height: 64px;
      gap: 6px;
      padding: 0 10px 0 8px;
    }
    .art :global(.lfly-cover) {
      width: 46px !important;
      height: 46px !important;
    }
    .center {
      padding: 0;
    }
    .lfly-progress,
    .acts,
    .extra,
    .vol {
      display: none;
    }
    .line {
      display: block;
      position: absolute;
      left: 28px;
      right: 28px;
      bottom: 0;
      height: 2px;
      border-radius: 2px;
      background: color-mix(in srgb, var(--lfly-text) 14%, transparent);
      overflow: hidden;
    }
    .line span {
      display: block;
      height: 100%;
      background: var(--lfly-accent);
    }
    .center {
      position: static;
    }
  }
</style>
