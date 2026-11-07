<script lang="ts">
  import "./parts/page.css";
  import Icon from "../Icon.svelte";
  import Glass from "../glass/Glass.svelte";
  import Shelf from "../components/Shelf.svelte";
  import TrackRow from "../components/TrackRow.svelte";
  import Cover from "../components/Cover.svelte";
  import { api, errorText, type ArtistPage } from "../api";
  import { player } from "../player.svelte";
  import { cover } from "../connect.svelte";
  import { shuffle } from "./parts/collection";

  let { id, name }: { id: string; name?: string } = $props();
  let page = $state<ArtistPage | null>(null);
  let error = $state<string | null>(null);
  let all = $state(false);
  let about = $state(false);
  let loaded = $state(false);

  async function load() {
    error = null;
    try {
      page = await api.artist(id);
    } catch (e) {
      error = errorText(e);
    }
  }
  load();

  const banner = $derived(page?.coverUrl ? cover(page.coverUrl, 1200) : undefined);
  const title = $derived(page?.name ?? name ?? "");
</script>

<div class="lfly-page artist">
  <header class="banner" class:has-art={!!banner}>
    <div class="media" aria-hidden="true">
      {#if banner}
        <img src={banner} alt="" class:loaded draggable="false" onload={() => (loaded = true)} />
      {:else}
        <span class="tone"></span>
      {/if}
    </div>
    <div class="head">
      <span class="eyebrow">Artista</span>
      <h1 class="name" title={title}>{title || " "}</h1>
      {#if page?.subscribers}<p class="subs">{page.subscribers}</p>{/if}
      {#if page?.songs.length}
        <div class="acts">
          <button class="play" aria-label="Riproduci {title}" title="Riproduci" onclick={() => player.play(page!.songs, 0)}>
            <Icon name="play" size={22} />
          </button>
          <Glass as="button" shape="circle" variant="clear" interactive class="round-btn" aria-label="Riproduzione casuale" title="Casuale" onclick={() => shuffle(page!.songs)}>
            <Icon name="shuffle" size={19} />
          </Glass>
        </div>
      {/if}
    </div>
  </header>

  {#if error}
    <p class="p-msg">{error} <button class="p-pill" onclick={load}>Riprova</button></p>
  {:else if !page}
    <div class="sk" aria-busy="true" aria-label="Caricamento">
      {#each Array(5) as _}<div class="sk-row"></div>{/each}
    </div>
  {:else}
    {#if page.songs.length}
      <section class="popular">
        <div class="sec-head">
          <h2 class="p-section-title">Brani popolari</h2>
          {#if page.songs.length > 6}
            <button class="see" onclick={() => (all = !all)}>{all ? "Mostra meno" : "Mostra altro"}</button>
          {/if}
        </div>
        <div class="songs">
          {#each page.songs.slice(0, all ? 16 : 6) as t, i (t.id)}
            <TrackRow track={t} number={i + 1} showAlbum={false} playing={player.track?.id === t.id} onplay={() => player.play(page!.songs, i)} />
          {/each}
        </div>
      </section>
    {/if}
    <Shelf title="Album" items={page.albums} />
    <Shelf title="Singoli ed EP" items={page.singles} />
    <Shelf title="Ai fan piacciono anche" items={page.related} />
    {#if page.description}
      <section class="about-wrap">
        <h2 class="p-section-title">Informazioni</h2>
        <button class="about" class:open={about} onclick={() => (about = !about)}>
          <span class="about-art"><Cover url={page.coverUrl} size={120} round /></span>
          <span class="about-text">
            <strong>{page.name}</strong>
            {#if page.subscribers}<span class="about-subs">{page.subscribers}</span>{/if}
            <span class="about-desc">{page.description}</span>
            <span class="about-more">{about ? "Mostra meno" : "Altro"}</span>
          </span>
        </button>
      </section>
    {/if}
  {/if}
</div>

<style>
  .banner {
    position: relative;
    isolation: isolate;
    height: clamp(300px, 48vh, 500px);
    margin-top: calc(-1 * var(--lfly-topbar-h, 56px));
    display: flex;
    align-items: flex-end;
    margin-bottom: 12px;
  }
  .media {
    position: absolute;
    inset: 0;
    z-index: -1;
    overflow: hidden;
    -webkit-mask-image: linear-gradient(to bottom, #000 55%, transparent 100%);
    mask-image: linear-gradient(to bottom, #000 55%, transparent 100%);
  }
  .media img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: center 28%;
    opacity: 0;
    transform: scale(1.04);
    transition:
      opacity 600ms var(--lfly-ease, ease),
      transform 1200ms var(--lfly-ease, ease);
  }
  .media img.loaded {
    opacity: 1;
    transform: none;
  }
  .has-art .media::after {
    content: "";
    position: absolute;
    inset: 0;
    background:
      linear-gradient(to top, rgba(0, 0, 0, 0.55), transparent 55%),
      linear-gradient(to right, rgba(0, 0, 0, 0.35), transparent 60%);
  }
  .tone {
    position: absolute;
    inset: 0;
    background:
      radial-gradient(70% 90% at 20% 30%, color-mix(in srgb, var(--lfly-accent) 50%, transparent), transparent 70%),
      radial-gradient(50% 70% at 80% 10%, color-mix(in srgb, var(--lfly-accent) 22%, transparent), transparent 70%);
  }
  .head {
    padding: 0 var(--page-x) 8px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
    width: 100%;
  }
  .eyebrow {
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: rgba(255, 255, 255, 0.78);
  }
  .name {
    margin: 0;
    font-family: var(--lfly-font-display);
    font-size: clamp(40px, 7vw, 92px);
    font-weight: 800;
    line-height: 0.98;
    letter-spacing: -0.035em;
    color: #fff;
    text-shadow: 0 4px 30px rgba(0, 0, 0, 0.35);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .banner:not(.has-art) .name {
    color: var(--lfly-text);
    text-shadow: none;
  }
  .subs {
    margin: 2px 0 0;
    color: rgba(255, 255, 255, 0.78);
    font-size: 14px;
  }
  .banner:not(.has-art) .subs,
  .banner:not(.has-art) .eyebrow {
    color: var(--lfly-text-2);
  }
  .acts {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 16px;
  }
  .play {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    padding-left: 3px;
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
    box-shadow: 0 10px 28px color-mix(in srgb, var(--lfly-accent) 40%, transparent);
    transition: transform var(--lfly-dur-fast, 160ms) var(--lfly-ease, ease), filter var(--lfly-dur-fast, 160ms);
  }
  .play:hover {
    transform: scale(1.05);
    filter: brightness(1.08);
  }
  .play:active {
    transform: scale(0.96);
  }
  .acts :global(.round-btn) {
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    color: #fff;
  }
  .banner:not(.has-art) .acts :global(.round-btn) {
    color: var(--lfly-text);
  }
  .popular,
  .about-wrap {
    padding: 8px var(--page-x) 28px;
  }
  .sec-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
  }
  .see {
    font-size: 13px;
    font-weight: 600;
    color: var(--lfly-accent);
  }
  .see:hover {
    text-decoration: underline;
  }
  .songs {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 440px), 1fr));
    column-gap: 24px;
  }
  .about {
    display: flex;
    gap: 24px;
    align-items: flex-start;
    width: 100%;
    max-width: 920px;
    padding: 22px;
    border-radius: var(--lfly-radius-l, 18px);
    background: color-mix(in srgb, var(--lfly-surface) 60%, transparent);
    border: 1px solid var(--lfly-separator);
    text-align: left;
    transition: background var(--lfly-dur-fast, 160ms);
  }
  .about:hover {
    background: color-mix(in srgb, var(--lfly-surface-2) 70%, transparent);
  }
  .about-art {
    flex: none;
  }
  .about-text {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }
  .about-text strong {
    font-size: 17px;
    font-family: var(--lfly-font-display);
  }
  .about-subs {
    font-size: 12px;
    color: var(--lfly-text-3);
  }
  .about-desc {
    margin-top: 6px;
    color: var(--lfly-text-2);
    font-size: 14px;
    line-height: 1.6;
    white-space: pre-line;
    display: -webkit-box;
    -webkit-line-clamp: 4;
    line-clamp: 4;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .about.open .about-desc {
    display: block;
  }
  .about-more {
    margin-top: 4px;
    font-weight: 600;
    font-size: 13px;
    color: var(--lfly-accent);
  }
  .sk {
    padding: 8px var(--page-x);
    display: grid;
    gap: 10px;
  }
  .sk-row {
    height: 48px;
    border-radius: var(--lfly-radius-s, 8px);
    background: var(--lfly-glass-tint);
    animation: lfly-pulse 1.4s ease-in-out infinite;
  }
  @media (max-width: 640px) {
    .about {
      flex-direction: column;
    }
  }
</style>
