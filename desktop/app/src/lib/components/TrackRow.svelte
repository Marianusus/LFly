<script lang="ts">
  import Icon from "../Icon.svelte";
  import Cover from "./Cover.svelte";
  import { time } from "../connect.svelte";
  import { library } from "../library.svelte";
  import { player, type Track } from "../player.svelte";
  import { nav } from "../nav.svelte";
  import { menu } from "../menu.svelte";
  import { downloads } from "../downloads.svelte";

  let {
    track,
    number,
    playing = false,
    showAlbum = true,
    showCover = true,
    onplay,
    onremove,
  }: {
    track: Track;
    number?: number;
    playing?: boolean;
    showAlbum?: boolean;
    showCover?: boolean;
    onplay: () => void;
    onremove?: () => void;
  } = $props();

  const liked = $derived(library.isFavorite(track.id));
  /** The row of the song that is playing pauses it, as on the phone; others start their song. */
  const live = $derived(playing && player.playing);
  /** Without a number the cover carries the play button; without either, an empty number column does. */
  const hasNum = $derived(number != null || !showCover);

  function openMenu(e: MouseEvent) {
    e.preventDefault();
    menu.openTrack(e, track, onremove);
  }

  function press() {
    if (playing) player.toggle();
    else onplay();
  }
</script>

<div
  class="lfly-row"
  class:playing
  class:no-album={!showAlbum}
  class:no-num={!hasNum}
  role="row"
  tabindex="0"
  aria-current={playing ? "true" : undefined}
  ondblclick={onplay}
  onkeydown={(e) => e.key === "Enter" && e.target === e.currentTarget && onplay()}
  oncontextmenu={openMenu}
>
  {#if hasNum}
    <div class="num" role="cell">
      <span class="n">
        {#if live}
          <span class="eq" aria-hidden="true"><i></i><i></i><i></i></span>
        {:else}{number}{/if}
      </span>
      <button class="play" aria-label={live ? "Pausa" : `Riproduci ${track.title}`} onclick={press}>
        <Icon name={live ? "pause" : "play"} size={13} />
      </button>
    </div>
  {/if}
  <div class="main" role="cell">
    {#if showCover}
      <span class="art">
        <Cover url={track.coverUrl} size={42} />
        {#if !hasNum}
          <button class="art-play" aria-label={live ? "Pausa" : `Riproduci ${track.title}`} onclick={press}>
            {#if live}
              <span class="eq light" aria-hidden="true"><i></i><i></i><i></i></span>
            {:else}
              <Icon name="play" size={14} />
            {/if}
          </button>
        {/if}
      </span>
    {/if}
    <div class="text">
      <span class="title ellipsis">{track.title}</span>
      <span class="artist ellipsis">
        {#if track.isVideo}<span class="badge">Video</span>{/if}
        {#if downloads.has(track.id)}<span class="saved" title="Scaricato"><Icon name="download" size={11} /></span>{/if}
        <button onclick={() => track.artistId && nav.go({ page: "artist", id: track.artistId, name: track.artist })}>
          {track.artist}
        </button>
      </span>
    </div>
  </div>
  {#if showAlbum}
    <div class="album ellipsis" role="cell">
      {#if track.album}
        <button onclick={() => track.albumId && nav.go({ page: "album", id: track.albumId, name: track.album })}>{track.album}</button>
      {/if}
    </div>
  {/if}
  <div class="end" role="cell">
    <button
      class="ic like"
      class:on={liked}
      aria-pressed={liked}
      aria-label={liked ? "Togli dai Brani che ti piacciono" : "Aggiungi ai Brani che ti piacciono"}
      onclick={() => library.toggleFavorite(track)}
    >
      <Icon name="heart" size={15} fill={liked} />
    </button>
    <span class="dur">{track.durationMs ? time(track.durationMs) : ""}</span>
    <button class="ic more" aria-label="Altre azioni per {track.title}" onclick={openMenu}>
      <Icon name="more" size={18} />
    </button>
  </div>
</div>

<style>
  .lfly-row {
    /* Rows off screen skip layout and paint (a playlist page holds hundreds). */
    content-visibility: auto;
    contain-intrinsic-size: auto var(--row-h, 58px);
    display: grid;
    grid-template-columns: 36px minmax(0, 4fr) minmax(0, 3fr) auto;
    align-items: center;
    gap: 14px;
    padding: 0 10px 0 8px;
    height: var(--row-h, 58px);
    border-radius: 16px;
    outline-offset: -2px;
    transition: background var(--lfly-dur-fast) var(--lfly-ease);
  }
  .lfly-row.no-album {
    grid-template-columns: 36px minmax(0, 1fr) auto;
  }
  .lfly-row.no-num {
    grid-template-columns: minmax(0, 4fr) minmax(0, 3fr) auto;
  }
  .lfly-row.no-num.no-album {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .lfly-row:hover,
  .lfly-row:focus-within {
    background: color-mix(in srgb, var(--lfly-text) 7%, transparent);
  }
  .lfly-row:focus-visible {
    outline: 2px solid var(--lfly-accent);
  }
  .lfly-row.playing {
    background: color-mix(in srgb, var(--lfly-accent) 12%, transparent);
  }
  .num {
    position: relative;
    display: grid;
    place-items: center;
    height: 100%;
    color: var(--lfly-text-3);
    font-variant-numeric: tabular-nums;
    font-size: 13px;
  }
  .num .play {
    position: absolute;
    inset: auto;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    padding-left: 1px;
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 12%, transparent);
    opacity: 0;
    scale: 0.7;
    transition:
      opacity var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-med) var(--lfly-ease-spring);
  }
  .num .play:hover {
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
  }
  .lfly-row:hover .num .n,
  .lfly-row:focus-within .num .n {
    visibility: hidden;
  }
  .lfly-row:hover .num .play,
  .lfly-row:focus-within .num .play {
    opacity: 1;
    scale: 1;
  }
  .main {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
  }
  .art {
    position: relative;
    flex: none;
  }
  .art :global(.lfly-cover) {
    border-radius: 10px;
  }
  .art-play {
    position: absolute;
    inset: 0;
    border-radius: 10px;
    display: grid;
    place-items: center;
    color: #fff;
    background: rgba(0, 0, 0, 0.42);
    opacity: 0;
    transition: opacity var(--lfly-dur-fast) var(--lfly-ease);
  }
  .playing .art-play,
  .lfly-row:hover .art-play,
  .lfly-row:focus-within .art-play {
    opacity: 1;
  }
  .text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }
  .title {
    font: 500 14px/1.3 var(--lfly-font);
    color: var(--lfly-text);
  }
  .playing .title {
    color: var(--lfly-accent);
    font-weight: 600;
  }
  .artist,
  .album {
    font-size: 12.5px;
    color: var(--lfly-text-2);
  }
  .album {
    font-size: 13px;
  }
  .dur {
    color: var(--lfly-text-3);
    font-size: 12.5px;
  }
  .artist button:hover,
  .album button:hover {
    color: var(--lfly-text);
    text-decoration: underline;
    text-underline-offset: 2px;
  }
  .badge {
    font-size: 9px;
    font-weight: 700;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--lfly-text-2);
    box-shadow: inset 0 0 0 1px currentColor;
    border-radius: 4px;
    padding: 1px 4px;
    margin-right: 6px;
    vertical-align: 1px;
  }
  .saved {
    display: inline-grid;
    place-items: center;
    width: 15px;
    height: 15px;
    margin-right: 5px;
    border-radius: 50%;
    color: var(--lfly-on-accent);
    background: var(--lfly-accent);
    vertical-align: -3px;
  }
  .end {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .ic {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: var(--lfly-text-2);
    transition:
      opacity var(--lfly-dur-fast) var(--lfly-ease),
      color var(--lfly-dur-fast) var(--lfly-ease),
      background var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-fast) var(--lfly-ease-spring);
  }
  .ic:hover {
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 10%, transparent);
  }
  .ic:active {
    scale: 0.88;
  }
  .like.on {
    color: var(--lfly-accent);
  }
  .like.on :global(svg) {
    animation: pop 0.42s var(--lfly-ease-spring);
  }
  @keyframes pop {
    40% {
      transform: scale(1.3);
    }
  }
  .like:not(.on),
  .more {
    opacity: 0;
  }
  .lfly-row:hover .like,
  .lfly-row:hover .more,
  .lfly-row:focus-within .like,
  .lfly-row:focus-within .more {
    opacity: 1;
  }
  .dur {
    min-width: 40px;
    text-align: right;
    font-variant-numeric: tabular-nums;
  }

  .eq {
    display: inline-flex;
    align-items: flex-end;
    gap: 2px;
    height: 13px;
  }
  .eq i {
    width: 3px;
    height: 100%;
    border-radius: 2px;
    background: var(--lfly-accent);
    transform-origin: bottom;
    animation: bars 0.9s ease-in-out infinite;
  }
  .eq.light i {
    background: #fff;
  }
  .eq i:nth-child(2) {
    animation-delay: -0.3s;
  }
  .eq i:nth-child(3) {
    animation-delay: -0.6s;
  }
  @keyframes bars {
    0%,
    100% {
      transform: scaleY(0.3);
    }
    50% {
      transform: scaleY(1);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .eq i {
      animation: none;
      transform: scaleY(0.7);
    }
  }
  @media (max-width: 900px) {
    .lfly-row {
      grid-template-columns: 36px minmax(0, 1fr) auto;
    }
    .lfly-row.no-num {
      grid-template-columns: minmax(0, 1fr) auto;
    }
    .album {
      display: none;
    }
  }
</style>
