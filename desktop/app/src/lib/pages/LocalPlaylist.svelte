<script lang="ts">
  import "./parts/page.css";
  import DownloadButton from "../components/DownloadButton.svelte";
  import TrackList from "../components/TrackList.svelte";
  import Hero from "./parts/Hero.svelte";
  import Icon from "../Icon.svelte";
  import { library } from "../library.svelte";
  import { player } from "../player.svelte";
  import { nav } from "../nav.svelte";
  import { minutes, shuffle } from "./parts/collection";

  let { id }: { id: string } = $props();
  const p = $derived(library.playlist(id));
  let renaming = $state(false);

  const facts = $derived(p ? [`${p.tracks.length} brani`, minutes(p.tracks) && `circa ${minutes(p.tracks)}`].filter(Boolean).join(" · ") : "");

  function rename(e: Event) {
    const v = (e.target as HTMLInputElement).value.trim();
    if (p && v) library.renamePlaylist(p.id, v);
    renaming = false;
  }
</script>

<div class="lfly-page">
  {#if p}
    <Hero
      kind="Playlist"
      title={p.name}
      coverUrl={p.coverUrl ?? p.tracks[0]?.coverUrl}
      meta={facts}
      description={p.description ?? ""}
      playable={p.tracks.length > 0}
      onplay={() => player.play(p.tracks, 0)}
      onshuffle={() => shuffle(p.tracks)}
    >
      {#snippet byline()}<span>La tua playlist</span>{/snippet}
      {#snippet actions()}
        {#if p.tracks.length}<DownloadButton tracks={p.tracks} />{/if}
        {#if renaming}
          <!-- svelte-ignore a11y_autofocus -->
          <input class="rename" value={p.name} autofocus onchange={rename} onblur={rename} aria-label="Nuovo nome" />
        {:else}
          <button class="hero-icon" aria-label="Rinomina" title="Rinomina" onclick={() => (renaming = true)}><Icon name="edit" size={18} /></button>
        {/if}
        <button
          class="hero-icon"
          aria-label="Elimina la playlist"
          title="Elimina"
          onclick={() => {
            if (confirm(`Eliminare "${p.name}"? Non si può annullare.`)) {
              library.deletePlaylist(p.id);
              nav.go({ page: "library" });
            }
          }}><Icon name="trash" size={18} /></button
        >
      {/snippet}
    </Hero>
    {#if p.tracks.length}
      <TrackList tracks={p.tracks} onremove={(i) => library.removeFromPlaylist(p.id, i)} />
      <p class="p-footer">{facts}</p>
    {:else}
      <div class="p-empty">
        <strong>Playlist vuota</strong>
        Aggiungi brani dal menu ⋯ di qualsiasi canzone.
        <button class="p-pill" onclick={() => nav.go({ page: "search" })}><Icon name="search" size={15} />Cerca qualcosa da aggiungere</button>
      </div>
    {/if}
  {:else}
    <div class="p-empty" style="margin-top:48px">
      <strong>Questa playlist non c'è più</strong>
      <button class="p-pill" onclick={() => nav.go({ page: "library" })}>Vai alla libreria</button>
    </div>
  {/if}
</div>

<style>
  .rename {
    height: 36px;
    min-width: 220px;
    background: var(--lfly-glass-tint);
    border: 1px solid var(--lfly-glass-border);
    border-radius: 999px;
    padding: 0 14px;
    font-size: 14px;
    color: var(--lfly-text);
    outline: none;
    user-select: text;
  }
  .rename:focus {
    border-color: var(--lfly-accent);
  }
</style>
