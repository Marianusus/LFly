<script lang="ts">
  import "./parts/page.css";
  import TrackList from "../components/TrackList.svelte";
  import Hero from "./parts/Hero.svelte";
  import Artwork from "./parts/Artwork.svelte";
  import Icon from "../Icon.svelte";
  import { downloads } from "../downloads.svelte";
  import { player } from "../player.svelte";
  import { minutes, shuffle } from "./parts/collection";

  const size = $derived(
    downloads.bytes > 1e9 ? `${(downloads.bytes / 1e9).toFixed(1)} GB` : `${Math.round(downloads.bytes / 1e6)} MB`,
  );
  const facts = $derived([`${downloads.tracks.length} brani`, minutes(downloads.tracks), size].filter(Boolean).join(" · "));
</script>

<div class="lfly-page">
  <Hero
    kind="Sul computer"
    title="Scaricati"
    meta={facts}
    tone="#3d8bfd"
    playable={downloads.tracks.length > 0}
    onplay={() => player.play(downloads.tracks, 0)}
    onshuffle={() => shuffle(downloads.tracks)}
  >
    {#snippet art()}<Artwork icon="download" tone="blue" />{/snippet}
    {#snippet byline()}<span>Disponibili anche offline</span>{/snippet}
  </Hero>

  {#if downloads.tracks.length}
    <TrackList tracks={downloads.tracks} />
    <p class="p-footer">{facts}</p>
  {:else}
    <div class="p-empty">
      <Icon name="download" size={28} />
      <strong>Nessun brano scaricato</strong>
      Scarica album, playlist o singoli brani (tasto destro › Scarica) per ascoltarli anche senza connessione.
    </div>
  {/if}
</div>
