<script lang="ts">
  import "./parts/page.css";
  import DownloadButton from "../components/DownloadButton.svelte";
  import TrackList from "../components/TrackList.svelte";
  import Hero from "./parts/Hero.svelte";
  import Icon from "../Icon.svelte";
  import { api, errorText, type AlbumPage } from "../api";
  import { player } from "../player.svelte";
  import { nav } from "../nav.svelte";
  import { library } from "../library.svelte";
  import { connect } from "../connect.svelte";
  import { minutes, shuffle } from "./parts/collection";

  let { id, name }: { id: string; name?: string } = $props();
  let page = $state<AlbumPage | null>(null);
  let error = $state<string | null>(null);

  async function load() {
    error = null;
    try {
      page = await api.album(id);
    } catch (e) {
      error = errorText(e);
    }
  }
  load();

  const facts = $derived(page ? [page.year, `${page.tracks.length} brani`, minutes(page.tracks)].filter(Boolean).join(" · ") : "");
</script>

<div class="lfly-page">
  <Hero
    kind="Album"
    title={page?.title ?? name ?? ""}
    coverUrl={page?.coverUrl}
    meta={facts}
    playable={!!page?.tracks.length}
    onplay={page ? () => player.play(page!.tracks, 0) : undefined}
    onshuffle={page ? () => shuffle(page!.tracks) : undefined}
  >
    {#snippet byline()}
      {#if page}
        <button onclick={() => page?.artistId && nav.go({ page: "artist", id: page.artistId, name: page.artist })}>{page.artist}</button>
      {/if}
    {/snippet}
    {#snippet actions()}
      {#if page}
        <button
          class="hero-icon"
          aria-label="Salva nella libreria"
          title="Salva nella libreria"
          onclick={() => {
            library.createPlaylist(page!.title, page!.tracks, page!.coverUrl);
            connect.say(`"${page!.title}" salvato nella libreria`);
          }}><Icon name="plus" size={20} /></button
        >
        <DownloadButton tracks={page.tracks} />
      {/if}
    {/snippet}
  </Hero>

  {#if error}
    <p class="p-msg">{error} <button class="p-pill" onclick={load}>Riprova</button></p>
  {:else if !page}
    <p class="p-msg" aria-busy="true">Carico…</p>
  {:else}
    <TrackList tracks={page.tracks} showAlbum={false} showCover={false} />
    {#if page.tracks.length}<p class="p-footer">{facts}</p>{/if}
  {/if}
</div>
