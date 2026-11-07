<script lang="ts">
  import "./parts/page.css";
  import Cover from "../components/Cover.svelte";
  import Ambient from "./parts/Ambient.svelte";
  import LyricsLines from "./parts/LyricsLines.svelte";
  import Glyph from "./parts/Glyph.svelte";
  import { player } from "../player.svelte";
  import { nav } from "../nav.svelte";
</script>

<div class="lfly-page lfly-lyrics">
  <div class="amb-pin" aria-hidden="true"><div class="amb"><Ambient url={player.track?.coverUrl} dim={0.5} /></div></div>

  <div class="layout">
    {#if player.track}
      {@const t = player.track}
      <aside class="now">
        <button class="art" onclick={() => nav.go({ page: "nowplaying" })} aria-label="Apri il player">
          <Cover url={t.coverUrl} size={280} fluid />
          <span class="expand"><Glyph name="expand" size={16} /></span>
        </button>
        <span class="title">{t.title}</span>
        <button class="artist" onclick={() => t.artistId && nav.go({ page: "artist", id: t.artistId, name: t.artist })}>{t.artist}</button>
      </aside>
    {/if}
    <div class="text"><LyricsLines /></div>
  </div>
</div>

<style>
  .lfly-lyrics {
    --lyrics-ink: #fff;
    min-height: calc(100% + 0px);
    color: #fff;
  }
  .amb-pin {
    position: sticky;
    top: 0;
    height: 0;
    margin-top: calc(-1 * var(--lfly-topbar-h, 56px));
    z-index: 0;
  }
  .amb {
    position: absolute;
    left: 0;
    right: 0;
    top: 0;
    height: 100vh;
  }
  .layout {
    position: relative;
    z-index: 1;
    display: grid;
    grid-template-columns: minmax(200px, 300px) minmax(0, 1fr);
    gap: clamp(28px, 5vw, 80px);
    padding: calc(var(--lfly-topbar-h, 56px) + 16px) var(--page-x) 0;
    /* One screen tall: the lines scroll inside, the page itself stays put. */
    height: calc(100vh - var(--bar-space, 104px));
    box-sizing: border-box;
  }
  .now {
    position: sticky;
    top: calc(var(--lfly-topbar-h, 56px) + 16px);
    align-self: start;
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }
  .art {
    position: relative;
    margin-bottom: 14px;
    border-radius: var(--lfly-radius-m, 12px);
  }
  .art :global(.lfly-cover) {
    border-radius: var(--lfly-radius-m, 12px);
    box-shadow: 0 24px 60px rgba(0, 0, 0, 0.5);
  }
  .expand {
    position: absolute;
    right: 10px;
    bottom: 10px;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    background: rgba(0, 0, 0, 0.45);
    -webkit-backdrop-filter: blur(12px);
    backdrop-filter: blur(12px);
    opacity: 0;
    transition: opacity var(--lfly-dur-fast, 160ms);
  }
  .art:hover .expand,
  .art:focus-visible .expand {
    opacity: 1;
  }
  .title {
    font-family: var(--lfly-font-display);
    font-size: 20px;
    font-weight: 750;
    letter-spacing: -0.015em;
    line-height: 1.2;
  }
  .artist {
    align-self: flex-start;
    font-size: 15px;
    color: rgba(255, 255, 255, 0.7);
    text-align: left;
  }
  .artist:hover {
    color: #fff;
    text-decoration: underline;
  }
  .text {
    min-width: 0;
    max-width: 820px;
    height: 100%;
    min-height: 0;
  }
  @media (max-width: 900px) {
    .layout {
      grid-template-columns: 1fr;
    }
    .now {
      display: none;
    }
  }
</style>
