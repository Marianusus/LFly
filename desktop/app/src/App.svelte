<script lang="ts">
  import { onMount, untrack } from "svelte";
  import { fade, fly, scale } from "svelte/transition";
  import { cubicOut } from "svelte/easing";
  import Icon from "./lib/Icon.svelte";
  import Glass from "./lib/glass/Glass.svelte";
  import Backdrop from "./lib/glass/Backdrop.svelte";
  import Sidebar from "./lib/components/Sidebar.svelte";
  import PlayerBar from "./lib/components/PlayerBar.svelte";
  import RightPanel from "./lib/components/RightPanel.svelte";
  import ContextMenu from "./lib/components/ContextMenu.svelte";
  import Settings from "./lib/pages/Settings.svelte";
  import Liked from "./lib/pages/Liked.svelte";
  import Downloads from "./lib/pages/Downloads.svelte";
  import { downloads } from "./lib/downloads.svelte";
  import LocalPlaylist from "./lib/pages/LocalPlaylist.svelte";
  import Library from "./lib/pages/Library.svelte";
  import Home from "./lib/pages/Home.svelte";
  import Search from "./lib/pages/Search.svelte";
  import Artist from "./lib/pages/Artist.svelte";
  import Album from "./lib/pages/Album.svelte";
  import RemotePlaylist from "./lib/pages/RemotePlaylist.svelte";
  import Lyrics from "./lib/pages/Lyrics.svelte";
  import NowPlaying from "./lib/pages/NowPlaying.svelte";
  import { connect } from "./lib/connect.svelte";
  import { player } from "./lib/player.svelte";
  import { nav, type Route } from "./lib/nav.svelte";
  import { ui } from "./lib/ui.svelte";
  import { menu } from "./lib/menu.svelte";
  import { themes } from "./lib/theme/theme.svelte";
  import { library } from "./lib/library.svelte";
  import { account } from "./lib/account.svelte";
  import { audio } from "./lib/audio.svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { listen } from "@tauri-apps/api/event";

  let main = $state<HTMLElement>();
  let scrolled = $state(false);

  onMount(() => {
    // The keyboard's media keys and the system's media widgets.
    listen<{ action: string; value: number }>("media:command", (e) => {
      const { action, value } = e.payload;
      if (action === "play" && !player.playing) player.toggle();
      else if (action === "pause" && player.playing) player.toggle();
      else if (action === "toggle") player.toggle();
      else if (action === "next") player.next();
      else if (action === "previous") player.previous();
      else if (action === "seek") player.seek(value);
      else if (action === "seek_by") player.seek(player.position + value);
    }).catch(() => {});
    themes.start();
    ui.start();
    library.start();
    account.load();
    downloads.start();
    connect.start();
    player.start().then(() => audio.start());
  });

  // The cover's colors follow the song, for the dynamic theme modes.
  $effect(() => {
    themes.readCover(player.track?.coverUrl);
  });

  // The system's media widgets show the same song, here or on the device this computer controls.
  $effect(() => {
    const t = player.track;
    const now = {
      title: t?.title ?? null,
      artist: t?.artist ?? null,
      album: t?.album ?? null,
      coverUrl: t?.coverUrl ?? null,
      durationMs: player.duration || null,
      // Read without subscribing: the widgets need the position on changes, not 4 times a second.
      positionMs: Math.round(untrack(() => player.position)),
      playing: player.playing,
      remote: !!player.remote,
    };
    invoke("media_update", { now }).catch(() => {});
  });

  // What plays here goes into the history, as on the phone.
  let lastPlayed = "";
  $effect(() => {
    const t = player.track;
    if (!t || player.remote || !player.playing || t.id === lastPlayed) return;
    lastPlayed = t.id;
    library.played(t);
  });

  // The full-screen player opens over the page it was opened from, which stays as it was below.
  const fullPlayer = $derived(nav.route.page === "nowplaying");
  let below = $state.raw<Route>(nav.route.page === "nowplaying" ? { page: "home" } : nav.route);
  $effect(() => {
    const r = nav.route;
    if (r.page !== "nowplaying") below = r;
  });

  // A new page starts at the top.
  $effect(() => {
    below;
    main?.scrollTo({ top: 0 });
    scrolled = false;
  });

  const reduce = $derived(themes.reduceMotion);
  /** Glass that carries reading over content (dialogs, notices) keeps most of its tint even when the blur is not drawn. */
  const solid = "--g-tint:color-mix(in srgb, var(--lfly-surface) 80%, transparent)";

  function typing(target: EventTarget | null) {
    const el = target as HTMLElement | null;
    return !!el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.tagName === "SELECT" || el.isContentEditable);
  }

  function onKey(e: KeyboardEvent) {
    const mod = e.metaKey || e.ctrlKey;
    if (mod && (e.key === "k" || e.key === "l" || e.key === "f")) {
      e.preventDefault();
      nav.go({ page: "search" });
      setTimeout(() => document.querySelector<HTMLInputElement>("#search-field")?.focus(), 30);
      return;
    }
    if (e.altKey && e.key === "ArrowLeft") return nav.back();
    if (e.altKey && e.key === "ArrowRight") return nav.forward();
    if (typing(e.target)) return;
    if (e.key === " " && player.track && (e.target as HTMLElement).tagName !== "BUTTON") {
      e.preventDefault();
      player.toggle();
    } else if (mod && e.key === "ArrowRight") {
      e.preventDefault();
      player.next();
    } else if (mod && e.key === "ArrowLeft") {
      e.preventDefault();
      player.previous();
    } else if (e.shiftKey && e.key === "ArrowRight") {
      player.seek(player.position + 10_000);
    } else if (e.shiftKey && e.key === "ArrowLeft") {
      player.seek(player.position - 10_000);
    }
  }

  // The mouse's side buttons move back and forward, as in a browser.
  function onMouse(e: MouseEvent) {
    if (e.button === 3) nav.back();
    if (e.button === 4) nav.forward();
  }

  /** Dialogs take the focus to their main button, so Enter and Esc work at once. */
  function focusOn(node: HTMLElement) {
    setTimeout(() => node.focus(), 60);
  }

  /** The full-screen player rises out of the player bar. */
  function rise(_: Element, { duration }: { duration: number }) {
    return {
      duration,
      easing: cubicOut,
      css: (t: number) => `opacity:${Math.min(1, t * 1.6)};transform:translateY(${(1 - t) * 6}%) scale(${0.96 + 0.04 * t});border-radius:${(1 - t) * 40}px`,
    };
  }
</script>

<svelte:window onkeydown={onKey} onmouseup={onMouse} />

<Backdrop />

<div
  class="shell lfly-shell-ok"
  class:panel={ui.panel != null}
  class:narrow-side={!ui.sidebarWide || ui.compact}
  class:covered={fullPlayer}
  inert={fullPlayer}
>
  <div class="side"><Sidebar /></div>

  <div class="stage">
    <main class="lfly-main" bind:this={main} onscroll={() => (scrolled = (main?.scrollTop ?? 0) > 6)}>
      <div class="topbar" class:scrolled>
        <div class="edge" aria-hidden="true"></div>
        <Glass shape="pill" class="nav-pair">
          <button class="round" aria-label="Indietro" title="Indietro (Alt+←)" disabled={!nav.canBack} onclick={() => nav.back()}>
            <Icon name="back" size={16} />
          </button>
          <button class="round" aria-label="Avanti" title="Avanti (Alt+→)" disabled={!nav.canForward} onclick={() => nav.forward()}>
            <Icon name="forward" size={16} />
          </button>
        </Glass>
        {#if ui.phone}
          <Glass shape="pill" class="nav-pair">
            <button class="round" class:here={below.page === "home"} aria-label="Home" title="Home" onclick={() => nav.go({ page: "home" })}>
              <Icon name="home" size={16} fill={below.page === "home"} />
            </button>
            <button class="round" class:here={below.page === "library"} aria-label="La tua libreria" title="La tua libreria" onclick={() => nav.go({ page: "library" })}>
              <Icon name="library" size={16} />
            </button>
          </Glass>
        {/if}
        {#if below.page !== "search"}
          <Glass as="button" shape="pill" interactive class="search-pill" onclick={() => nav.go({ page: "search" })}>
            <Icon name="search" size={16} />
            <span class="ellipsis">Cosa vuoi ascoltare?</span>
            <kbd>Ctrl K</kbd>
          </Glass>
        {/if}
        <span class="spacer"></span>
        <Glass
          as="button"
          shape="circle"
          interactive
          class="theme-btn"
          aria-label="Aspetto"
          title="Temi"
          onclick={() => nav.go({ page: "settings", section: "appearance" })}
        >
          <Icon name="palette" size={17} />
        </Glass>
      </div>

      {#key below}
        {@const r = below}
        <div class="page" in:fly={{ y: 10, duration: reduce ? 0 : 280, easing: cubicOut }}>
          {#if r.page === "home"}
            <Home />
          {:else if r.page === "search"}
            <Search />
          {:else if r.page === "library"}
            <Library />
          {:else if r.page === "liked"}
            <Liked />
          {:else if r.page === "downloads"}
            <Downloads />
          {:else if r.page === "playlist" && r.local}
            <LocalPlaylist id={r.id} />
          {:else if r.page === "playlist"}
            <RemotePlaylist id={r.id} name={r.name} />
          {:else if r.page === "artist"}
            <Artist id={r.id} name={r.name} />
          {:else if r.page === "album"}
            <Album id={r.id} name={r.name} />
          {:else if r.page === "lyrics"}
            <Lyrics />
          {:else if r.page === "settings"}
            <Settings />
          {/if}
        </div>
      {/key}
    </main>

    <div class="bottom"><PlayerBar /></div>
  </div>

  {#if ui.panel}
    <div class="right" transition:fly={{ x: 24, duration: reduce ? 0 : 260, easing: cubicOut }}><RightPanel /></div>
  {/if}
</div>

{#if fullPlayer}
  <div class="full" transition:rise={{ duration: reduce ? 0 : 420 }}>
    <NowPlaying />
  </div>
{/if}

<ContextMenu />

{#if connect.pairing}
  <div class="scrim" transition:fade={{ duration: reduce ? 0 : 180 }}>
    <div class="sheet" transition:scale={{ start: 0.92, duration: reduce ? 0 : 260, easing: cubicOut }}>
      <Glass radius={30} class="dialog" style={solid} role="dialog" aria-modal="true" aria-labelledby="pair-title">
        <span class="badge" aria-hidden="true"><Icon name="devices" size={22} /></span>
        <h2 id="pair-title">Abbinamento con {connect.pairing.deviceName}</h2>
        <p>Accetta questo computer su {connect.pairing.deviceName}. Il codice deve essere lo stesso su entrambi gli schermi.</p>
        <div class="code" aria-label="Codice {connect.pairing.code.split('').join(' ')}">
          {#each connect.pairing.code.split("") as digit, i}<span style="animation-delay:{i * 50}ms">{digit}</span>{/each}
        </div>
        <div class="dialog-actions">
          <button class="lfly-btn lfly-btn-secondary wide" use:focusOn onclick={() => connect.disconnect()}>Annulla</button>
        </div>
      </Glass>
    </div>
  </div>
{/if}

{#if connect.request}
  <div class="scrim" transition:fade={{ duration: reduce ? 0 : 180 }}>
    <div class="sheet" transition:scale={{ start: 0.92, duration: reduce ? 0 : 260, easing: cubicOut }}>
      <Glass radius={30} class="dialog" style={solid} role="alertdialog" aria-modal="true" aria-labelledby="req-title" aria-describedby="req-text">
        <span class="badge" aria-hidden="true"><Icon name="phone" size={22} /></span>
        <h2 id="req-title">{connect.request.deviceName} vuole controllare questo computer</h2>
        <p id="req-text">Accetta solo se il codice è lo stesso che vedi su {connect.request.deviceName}.</p>
        <div class="code" aria-label="Codice {connect.request.code.split('').join(' ')}">
          {#each connect.request.code.split("") as digit, i}<span style="animation-delay:{i * 50}ms">{digit}</span>{/each}
        </div>
        <div class="dialog-actions">
          <button class="lfly-btn lfly-btn-secondary" onclick={() => connect.answer(false)}>Rifiuta</button>
          <button class="lfly-btn lfly-btn-primary" use:focusOn onclick={() => connect.answer(true)}>Accetta</button>
        </div>
      </Glass>
    </div>
  </div>
{/if}

{#if connect.notice}
  <div class="toast-wrap" class:raised={!fullPlayer} role="status" aria-live="polite">
    {#key connect.notice}
      <div class="toast-in" in:fly={{ y: 16, duration: reduce ? 0 : 320, easing: cubicOut }} out:fade={{ duration: reduce ? 0 : 160 }}>
        <Glass shape="pill" class="toast" style={solid}>
          <span class="toast-dot" aria-hidden="true"></span>{connect.notice}
        </Glass>
      </div>
    {/key}
  </div>
{/if}

<style>
  .shell {
    --side-w: clamp(232px, 19vw, 280px);
    --panel-w: clamp(288px, 24vw, 360px);
    --edge: 12px;
    --bar-space: 104px;
    position: relative;
    display: grid;
    grid-template-columns: var(--side-w) minmax(0, 1fr);
    height: 100%;
    transition:
      scale 420ms var(--lfly-ease),
      opacity 420ms var(--lfly-ease);
  }
  .shell.panel {
    grid-template-columns: var(--side-w) minmax(0, 1fr) var(--panel-w);
  }
  .shell.narrow-side {
    --side-w: 76px;
  }
  /* Behind the full-screen player the window recedes a little, as a sheet's parent does. */
  .shell.covered {
    scale: 0.97;
    opacity: 0.4;
  }
  .side {
    display: flex;
    min-height: 0;
    padding: var(--edge) 0 var(--edge) var(--edge);
  }
  .stage {
    position: relative;
    min-width: 0;
    min-height: 0;
  }
  .lfly-main {
    position: absolute;
    inset: 0;
    overflow-y: auto;
    overflow-x: hidden;
    scroll-padding-top: 72px;
  }
  .page {
    padding-bottom: var(--bar-space);
  }
  .right {
    display: flex;
    flex-direction: column;
    min-height: 0;
    padding: var(--edge) var(--edge) var(--edge) 0;
  }
  .right > :global(*) {
    flex: 1;
  }
  .bottom {
    position: absolute;
    left: var(--edge);
    right: var(--edge);
    bottom: var(--edge);
    z-index: 20;
    max-width: 1180px;
    margin: 0 auto;
    pointer-events: none;
  }
  .bottom > :global(*) {
    pointer-events: auto;
  }

  /* ---- Top bar: glass controls floating over the page, a soft blurred edge once it scrolls ---- */
  .topbar {
    position: sticky;
    top: 0;
    z-index: 10;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 14px var(--page-x, 24px) 10px;
  }
  .edge {
    position: absolute;
    inset: 0 0 -28px;
    z-index: -1;
    pointer-events: none;
    opacity: 0;
    background: linear-gradient(to bottom, color-mix(in srgb, var(--lfly-bg) 70%, transparent), transparent);
    -webkit-backdrop-filter: blur(14px);
    backdrop-filter: blur(14px);
    mask-image: linear-gradient(to bottom, #000 45%, transparent);
    -webkit-mask-image: linear-gradient(to bottom, #000 45%, transparent);
    transition: opacity var(--lfly-dur-med) var(--lfly-ease);
  }
  .topbar.scrolled .edge {
    opacity: 1;
  }
  .topbar :global(.nav-pair) {
    display: flex;
    gap: 2px;
    padding: 3px;
    flex: none;
  }
  .round {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: var(--lfly-text);
    transition:
      background var(--lfly-dur-fast) var(--lfly-ease),
      opacity var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-fast) var(--lfly-ease-spring);
  }
  .round:hover:not(:disabled) {
    background: color-mix(in srgb, var(--lfly-text) 12%, transparent);
  }
  .round:active:not(:disabled) {
    scale: 0.88;
  }
  .round:disabled {
    opacity: 0.32;
    cursor: default;
  }
  .round.here {
    color: var(--lfly-accent);
  }
  .topbar :global(.search-pill) {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 38px;
    padding: 0 8px 0 15px;
    min-width: 0;
    flex: 0 1 400px;
    color: var(--lfly-text-2);
    font-size: 13.5px;
  }
  .topbar :global(.search-pill:hover) {
    color: var(--lfly-text);
  }
  .spacer {
    flex: 1;
  }
  .topbar :global(.theme-btn) {
    width: 38px;
    height: 38px;
    display: grid;
    place-items: center;
    flex: none;
  }
  kbd {
    margin-left: auto;
    flex: none;
    font: 600 10.5px/1 var(--lfly-font);
    letter-spacing: 0.02em;
    color: var(--lfly-text-3);
    background: color-mix(in srgb, var(--lfly-text) 8%, transparent);
    border-radius: 999px;
    padding: 5px 9px;
  }

  /* ---- Full-screen player ---- */
  .full {
    position: fixed;
    inset: 0;
    z-index: 40;
    overflow: hidden;
    transform-origin: 50% 100%;
  }

  /* ---- Dialogs ---- */
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 90;
    display: grid;
    place-items: center;
    padding: 16px;
    background: rgba(0, 0, 0, 0.42);
    -webkit-backdrop-filter: blur(6px);
    backdrop-filter: blur(6px);
  }
  /* Under the glass, a body of colour: dialogs and notices are read over content. */
  .sheet {
    border-radius: 30px;
    background: color-mix(in srgb, var(--lfly-surface) 70%, transparent);
  }
  .toast-in {
    border-radius: 999px;
    background: color-mix(in srgb, var(--lfly-surface) 70%, transparent);
  }
  .scrim :global(.dialog) {
    width: min(400px, calc(100vw - 32px));
    padding: 28px 26px 22px;
    text-align: center;
    display: flex;
    flex-direction: column;
    align-items: center;
    box-shadow: var(--lfly-shadow-2);
  }
  .badge {
    width: 52px;
    height: 52px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    margin-bottom: 14px;
    color: var(--lfly-on-accent);
    background: var(--lfly-accent);
    box-shadow:
      0 8px 24px color-mix(in srgb, var(--lfly-accent) 40%, transparent),
      inset 0 1px 0 rgba(255, 255, 255, 0.35);
  }
  .scrim h2 {
    margin: 0 0 8px;
    font: 700 19px/1.25 var(--lfly-font-display);
    letter-spacing: -0.01em;
    color: var(--lfly-text);
    text-wrap: balance;
  }
  .scrim p {
    margin: 0 0 20px;
    color: var(--lfly-text-2);
    line-height: 1.5;
  }
  .code {
    display: flex;
    justify-content: center;
    gap: 8px;
    margin-bottom: 22px;
  }
  .code span {
    width: 50px;
    height: 62px;
    display: grid;
    place-items: center;
    font: 700 30px/1 var(--lfly-font-display);
    font-variant-numeric: tabular-nums;
    color: var(--lfly-text);
    border-radius: 16px;
    background: color-mix(in srgb, var(--lfly-text) 9%, transparent);
    box-shadow:
      inset 0 0 0 1px var(--lfly-glass-border),
      inset 0 1px 0 var(--lfly-glass-highlight);
    animation: digit 0.4s var(--lfly-ease-spring) backwards;
  }
  @keyframes digit {
    from {
      opacity: 0;
      transform: translateY(8px) scale(0.9);
    }
  }
  .dialog-actions {
    display: flex;
    justify-content: center;
    gap: 10px;
    width: 100%;
  }
  .dialog-actions :global(.lfly-btn) {
    flex: 1;
    min-height: 40px;
  }
  .dialog-actions .wide {
    flex: 0 1 200px;
  }

  /* ---- Toasts ---- */
  .toast-wrap {
    position: fixed;
    left: 50%;
    bottom: 28px;
    translate: -50% 0;
    z-index: 70;
    max-width: calc(100vw - 32px);
    pointer-events: none;
  }
  .toast-wrap.raised {
    bottom: 112px;
  }
  .toast-wrap :global(.toast) {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 11px 20px 11px 14px;
    font-weight: 600;
    font-size: 13px;
    box-shadow: var(--lfly-shadow-2);
  }
  .toast-dot {
    width: 8px;
    height: 8px;
    flex: none;
    border-radius: 50%;
    background: var(--lfly-accent);
    box-shadow: 0 0 10px var(--lfly-accent);
  }

  /* ---- Narrower windows ---- */
  @media (max-width: 900px) {
    .shell.panel {
      grid-template-columns: var(--side-w) minmax(0, 1fr);
    }
    .shell.panel .right {
      position: fixed;
      top: 0;
      right: 0;
      bottom: 0;
      width: min(372px, 100%);
      z-index: 30;
      padding: var(--edge);
      filter: drop-shadow(0 20px 40px rgba(0, 0, 0, 0.4));
    }
  }
  @media (max-width: 640px) {
    .shell,
    .shell.panel,
    .shell.narrow-side {
      grid-template-columns: minmax(0, 1fr);
    }
    .side {
      display: none;
    }
    .topbar :global(.search-pill) {
      flex: 1 1 auto;
    }
    kbd {
      display: none;
    }
    .shell {
      --bar-space: 92px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .shell {
      transition: none;
    }
    .code span {
      animation: none;
    }
  }
</style>
