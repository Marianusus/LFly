<script lang="ts">
  import Card from "./Card.svelte";
  import Cover from "./Cover.svelte";
  import Icon from "../Icon.svelte";
  import { openItem, playItem } from "../items";
  import { player } from "../player.svelte";
  import { menu } from "../menu.svelte";
  import type { Item } from "../api";

  let { title, items, limit = 0 }: { title: string; items: Item[]; limit?: number } = $props();
  const shown = $derived(limit ? items.slice(0, limit) : items);
  /** Songs get the phone's shelf: glass tiles two rows deep. Everything else, the rail of cards. */
  const songs = $derived(shown.length > 0 && shown.every((i) => i.kind === "song"));

  let row = $state<HTMLElement>();
  let atStart = $state(true);
  let atEnd = $state(true);

  function edges() {
    if (!row) return;
    atStart = row.scrollLeft <= 4;
    atEnd = row.scrollLeft + row.clientWidth >= row.scrollWidth - 4;
  }
  $effect(() => {
    shown.length;
    if (!row) return;
    edges();
    const ro = new ResizeObserver(edges);
    ro.observe(row);
    return () => ro.disconnect();
  });

  function page(dir: 1 | -1) {
    row?.scrollBy({ left: dir * row.clientWidth * 0.85, behavior: "smooth" });
  }
</script>

{#if shown.length}
  <section class="lfly-shelf" aria-label={title}>
    <header>
      <h2>{title}</h2>
      {#if !(atStart && atEnd)}
        <div class="arrows">
          <button class="arrow" aria-label="Indietro in {title}" disabled={atStart} onclick={() => page(-1)}><Icon name="back" size={15} /></button>
          <button class="arrow" aria-label="Avanti in {title}" disabled={atEnd} onclick={() => page(1)}><Icon name="forward" size={15} /></button>
        </div>
      {/if}
    </header>
    <div class="row" class:songs class:fade-l={!atStart} class:fade-r={!atEnd} bind:this={row} onscroll={edges}>
      {#if songs}
        {#each shown as item (item.kind + item.id)}
          {@const current = player.track?.id === item.id}
          <div class="tile" class:current>
            <button
              class="tile-open"
              aria-label="Riproduci {item.title}{item.subtitle ? `, ${item.subtitle}` : ''}"
              onclick={() => openItem(item)}
              oncontextmenu={(e) => {
                if (!item.track) return;
                e.preventDefault();
                menu.openTrack(e, item.track);
              }}
            ></button>
            <span class="tile-art">
              <Cover url={item.coverUrl} size={56} />
              <span class="tile-play" aria-hidden="true"><Icon name={current && player.playing ? "pause" : "play"} size={16} /></span>
            </span>
            <span class="tile-text">
              <span class="tile-title">
                <span class="ellipsis">{item.title}</span>
                {#if current && player.playing}<span class="eq" aria-hidden="true"><i></i><i></i><i></i></span>{/if}
              </span>
              {#if item.subtitle}<span class="tile-sub ellipsis">{item.subtitle}</span>{/if}
            </span>
          </div>
        {/each}
      {:else}
        {#each shown as item (item.kind + item.id)}
          <Card
            title={item.title}
            subtitle={item.subtitle}
            coverUrl={item.coverUrl}
            round={item.kind === "artist"}
            onopen={() => openItem(item)}
            onplay={item.kind === "artist" ? undefined : () => playItem(item)}
          />
        {/each}
      {/if}
    </div>
  </section>
{/if}

<style>
  .lfly-shelf {
    margin-bottom: 36px;
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 0 var(--page-x, 24px);
    margin-bottom: 12px;
  }
  h2 {
    margin: 0;
    font: 700 22px/1.2 var(--lfly-font-display);
    letter-spacing: -0.015em;
    color: var(--lfly-text);
  }
  .arrows {
    display: flex;
    gap: 6px;
  }
  .arrow {
    width: 30px;
    height: 30px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: var(--lfly-text);
    background: var(--lfly-glass-tint);
    box-shadow: inset 0 0 0 1px var(--lfly-glass-border);
    transition:
      background var(--lfly-dur-fast) var(--lfly-ease),
      opacity var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-fast) var(--lfly-ease-spring);
  }
  .arrow:hover:not(:disabled) {
    background: color-mix(in srgb, var(--lfly-text) 16%, transparent);
  }
  .arrow:active:not(:disabled) {
    scale: 0.9;
  }
  .arrow:disabled {
    opacity: 0.3;
    cursor: default;
  }

  .row {
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: clamp(150px, 15vw, 196px);
    gap: 14px;
    overflow-x: auto;
    overflow-y: hidden;
    scroll-snap-type: x proximity;
    scroll-padding: 0 var(--page-x, 24px);
    padding: 6px var(--page-x, 24px) 14px;
    scrollbar-width: none;
    --fl: 0px;
    --fr: 0px;
    mask-image: linear-gradient(to right, transparent, #000 var(--fl), #000 calc(100% - var(--fr)), transparent);
  }
  .row::-webkit-scrollbar {
    display: none;
  }
  .row.fade-l {
    --fl: var(--page-x, 24px);
  }
  .row.fade-r {
    --fr: var(--page-x, 24px);
  }
  .row > :global(*) {
    scroll-snap-align: start;
  }
  .row.songs {
    grid-template-rows: repeat(2, auto);
    grid-auto-columns: clamp(250px, 26vw, 320px);
    gap: 10px 12px;
  }

  /* The phone's shelf tile (HomeShelf.kt): glass, a 56 px cover, the playing one ringed in accent. */
  .tile {
    position: relative;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px;
    min-width: 0;
    border-radius: 20px;
    background: var(--lfly-glass-tint);
    box-shadow:
      inset 0 0 0 1px var(--lfly-glass-border),
      inset 0 1px 0 color-mix(in srgb, var(--lfly-glass-highlight) 60%, transparent);
    transition:
      background var(--lfly-dur-fast) var(--lfly-ease),
      transform var(--lfly-dur-fast) var(--lfly-ease);
  }
  .tile:hover,
  .tile:focus-within {
    background: color-mix(in srgb, var(--lfly-text) 10%, var(--lfly-glass-tint));
  }
  .tile:active {
    transform: scale(0.97);
  }
  .tile.current {
    box-shadow:
      inset 0 0 0 1.5px var(--lfly-accent),
      0 6px 24px color-mix(in srgb, var(--lfly-accent) 22%, transparent);
  }
  .tile-open {
    position: absolute;
    inset: 0;
    border-radius: inherit;
    z-index: 1;
  }
  .tile-art {
    position: relative;
    flex: none;
  }
  .tile-art :global(.lfly-cover) {
    border-radius: 14px;
  }
  .tile-play {
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
  .tile:hover .tile-play,
  .tile:focus-within .tile-play {
    opacity: 1;
  }
  .tile-text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }
  .tile-title {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
    font: 600 14px/1.3 var(--lfly-font);
    color: var(--lfly-text);
  }
  .current .tile-title {
    color: var(--lfly-accent);
  }
  .tile-sub {
    font-size: 12px;
    color: var(--lfly-text-2);
  }
  .eq {
    display: inline-flex;
    align-items: flex-end;
    gap: 2px;
    height: 12px;
    flex: none;
  }
  .eq i {
    width: 2.5px;
    height: 100%;
    border-radius: 2px;
    background: var(--lfly-accent);
    transform-origin: bottom;
    animation: bars 0.9s ease-in-out infinite;
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
    }
    .row {
      scroll-behavior: auto;
    }
  }
</style>
