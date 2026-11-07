<script lang="ts">
  // A shelf of songs as the phone's HomeShelf draws it: compact rows in columns, paged sideways.
  import Icon from "../../Icon.svelte";
  import Cover from "../../components/Cover.svelte";
  import type { Item } from "../../api";
  import { openItem } from "../../items";
  import { player } from "../../player.svelte";
  import { menu } from "../../menu.svelte";

  let { title, items }: { title: string; items: Item[] } = $props();
  let rail = $state<HTMLDivElement>();

  function page(dir: number) {
    rail?.scrollBy({ left: dir * rail.clientWidth * 0.9, behavior: "smooth" });
  }
</script>

{#if items.length}
  <section class="qp">
    <div class="head">
      <h2 class="p-section-title">{title}</h2>
      {#if items.length > 8}
        <div class="arrows">
          <button class="arrow" aria-label="Indietro" onclick={() => page(-1)}><Icon name="back" size={16} /></button>
          <button class="arrow" aria-label="Avanti" onclick={() => page(1)}><Icon name="forward" size={16} /></button>
        </div>
      {/if}
    </div>
    <div class="grid" bind:this={rail}>
      {#each items as item (item.kind + item.id)}
        {@const on = player.track?.id === item.id}
        <button
          class="pick"
          class:on
          onclick={() => openItem(item)}
          oncontextmenu={(e) => {
            if (item.track) {
              e.preventDefault();
              menu.openTrack(e, item.track);
            }
          }}
        >
          <span class="art">
            <Cover url={item.coverUrl} size={52} />
            <span class="hover"><Icon name={on && player.playing ? "volume" : "play"} size={18} /></span>
          </span>
          <span class="text">
            <span class="t">{item.title}</span>
            {#if item.subtitle}<span class="s">{item.subtitle}</span>{/if}
          </span>
        </button>
      {/each}
    </div>
  </section>
{/if}

<style>
  .qp {
    padding: 0 var(--page-x, 24px);
    margin-bottom: 32px;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
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
    background: var(--lfly-glass-tint);
    border: 1px solid var(--lfly-glass-border);
    color: var(--lfly-text);
  }
  .arrow:hover {
    background: color-mix(in srgb, var(--lfly-text) 14%, transparent);
  }
  .grid {
    display: grid;
    grid-template-rows: repeat(4, auto);
    grid-auto-flow: column;
    grid-auto-columns: minmax(280px, calc((100% - 24px) / 3));
    column-gap: 12px;
    row-gap: 2px;
    overflow-x: auto;
    scroll-snap-type: x mandatory;
    scrollbar-width: none;
  }
  .grid::-webkit-scrollbar {
    display: none;
  }
  .pick {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 6px;
    border-radius: 12px;
    text-align: left;
    min-width: 0;
    scroll-snap-align: start;
    transition: background var(--lfly-dur-fast, 160ms);
  }
  .pick:hover,
  .pick:focus-visible {
    background: var(--lfly-glass-tint);
  }
  .art {
    position: relative;
    flex: none;
  }
  .art :global(.lfly-cover) {
    border-radius: 8px;
  }
  .hover {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    border-radius: 8px;
    background: rgba(0, 0, 0, 0.45);
    color: #fff;
    opacity: 0;
    transition: opacity var(--lfly-dur-fast, 160ms);
  }
  .pick:hover .hover,
  .pick.on .hover {
    opacity: 1;
  }
  .text {
    display: flex;
    flex-direction: column;
    min-width: 0;
    gap: 2px;
  }
  .t {
    font-size: 14px;
    font-weight: 550;
    color: var(--lfly-text);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .on .t {
    color: var(--lfly-accent);
  }
  .s {
    font-size: 12px;
    color: var(--lfly-text-2);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  @media (max-width: 900px) {
    .grid {
      grid-auto-columns: minmax(240px, calc((100% - 12px) / 2));
    }
  }
</style>
