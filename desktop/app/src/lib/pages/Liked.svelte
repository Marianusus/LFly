<script lang="ts">
  import "./parts/page.css";
  import DownloadButton from "../components/DownloadButton.svelte";
  import TrackList from "../components/TrackList.svelte";
  import Hero from "./parts/Hero.svelte";
  import Artwork from "./parts/Artwork.svelte";
  import Icon from "../Icon.svelte";
  import { library } from "../library.svelte";
  import { player } from "../player.svelte";
  import { nav } from "../nav.svelte";
  import { minutes, shuffle } from "./parts/collection";

  const facts = $derived([`${library.favorites.length} brani`, minutes(library.favorites)].filter(Boolean).join(" · "));
</script>

<div class="lfly-page">
  <Hero
    kind="Playlist"
    title="Brani che ti piacciono"
    meta={facts}
    playable={library.favorites.length > 0}
    onplay={() => player.play(library.favorites, 0)}
    onshuffle={() => shuffle(library.favorites)}
  >
    {#snippet art()}<Artwork icon="heart" />{/snippet}
    {#snippet byline()}<span>La tua raccolta</span>{/snippet}
    {#snippet actions()}{#if library.favorites.length}<DownloadButton tracks={library.favorites} />{/if}{/snippet}
  </Hero>

  {#if library.favorites.length}
    <TrackList tracks={library.favorites} />
    <p class="p-footer">{facts}</p>
  {:else}
    <div class="p-empty">
      <Icon name="heart" size={28} />
      <strong>Ancora nessun brano</strong>
      I brani a cui metti il cuore compaiono qui.
      <button class="p-pill" onclick={() => nav.go({ page: "search" })}><Icon name="search" size={15} />Cerca qualcosa da ascoltare</button>
    </div>
  {/if}
</div>
