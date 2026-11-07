<script lang="ts">
  import Icon from "../Icon.svelte";
  import TrackRow from "./TrackRow.svelte";
  import { player, type Track } from "../player.svelte";

  let {
    tracks,
    showAlbum = true,
    showCover = true,
    onremove,
  }: { tracks: Track[]; showAlbum?: boolean; showCover?: boolean; onremove?: (index: number) => void } = $props();

  // Rows are drawn in pages, so a playlist of thousands opens at once.
  let limit = $state(150);
  const shown = $derived(tracks.slice(0, limit));
</script>

<div class="list" role="table" aria-label="Brani">
  <div class="cols" role="row" class:no-album={!showAlbum}>
    <span role="columnheader" class="c-num">#</span>
    <span role="columnheader">Titolo</span>
    {#if showAlbum}<span role="columnheader" class="album">Album</span>{/if}
    <span role="columnheader" class="dur" aria-label="Durata"><Icon name="clock" size={14} /></span>
  </div>
  {#each shown as t, i (i + ":" + t.id)}
    <TrackRow
      track={t}
      number={i + 1}
      {showAlbum}
      {showCover}
      playing={player.track?.id === t.id}
      onplay={() => player.play(tracks, i)}
      onremove={onremove ? () => onremove(i) : undefined}
    />
  {/each}
  {#if tracks.length > limit}
    <button class="more lfly-btn lfly-btn-secondary" onclick={() => (limit += 300)}>Mostra altri {tracks.length - limit} brani</button>
  {/if}
</div>

<style>
  .list {
    padding: 0 calc(var(--page-x, 24px) - 8px) 32px;
  }
  .cols {
    display: grid;
    grid-template-columns: 36px minmax(0, 4fr) minmax(0, 3fr) auto;
    gap: 14px;
    align-items: center;
    height: 34px;
    padding: 0 10px 0 8px;
    margin-bottom: 6px;
    border-bottom: 1px solid var(--lfly-separator);
    color: var(--lfly-text-3);
    font: 600 11px/1 var(--lfly-font);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .cols.no-album {
    grid-template-columns: 36px minmax(0, 1fr) auto;
  }
  .c-num {
    text-align: center;
  }
  .dur {
    width: 112px;
    display: flex;
    justify-content: flex-end;
    padding-right: 40px;
  }
  .more {
    margin: 16px 8px 0;
  }
  @media (max-width: 900px) {
    .cols {
      grid-template-columns: 36px minmax(0, 1fr) auto;
    }
    .album {
      display: none;
    }
  }
</style>
