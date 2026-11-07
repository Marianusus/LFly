<script lang="ts">
  import "./parts/page.css";
  import Shelf from "../components/Shelf.svelte";
  import Cover from "../components/Cover.svelte";
  import Icon from "../Icon.svelte";
  import QuickPicks from "./parts/QuickPicks.svelte";
  import Artwork from "./parts/Artwork.svelte";
  import { api, errorText, type Shelf as ShelfT, type Item } from "../api";
  import { library } from "../library.svelte";
  import { nav } from "../nav.svelte";
  import { player } from "../player.svelte";

  let shelves = $state<ShelfT[] | null>(null);
  let error = $state<string | null>(null);

  async function load() {
    error = null;
    try {
      shelves = await api.home();
    } catch (e) {
      error = errorText(e);
    }
  }
  load();

  const now = new Date();
  const hour = now.getHours();
  const greeting = hour < 5 ? "Buonanotte" : hour < 13 ? "Buongiorno" : hour < 18 ? "Buon pomeriggio" : "Buonasera";
  const today = now.toLocaleDateString("it-IT", { weekday: "long", day: "numeric", month: "long" });

  // The shortcuts to your playlists. What plays is in the player bar and the liked songs are in
  // the sidebar, so neither is repeated here.
  const quick = $derived([
    ...library.playlists.slice(0, 8).map((p) => ({
      id: p.id,
      title: p.name,
      sub: `${p.tracks.length} brani`,
      cover: p.coverUrl ?? p.tracks[0]?.coverUrl,
      liked: false,
    })),
  ]);

  const recent = $derived<Item[]>(
    library.history.slice(0, 12).map((t) => ({ kind: "song", id: t.id, title: t.title, subtitle: t.artist, coverUrl: t.coverUrl, track: t })),
  );

  function openQuick(q: (typeof quick)[number]) {
    if (q.liked) nav.go({ page: "liked" });
    else nav.go({ page: "playlist", id: q.id, name: q.title, local: true });
  }

  function playQuick(q: (typeof quick)[number]) {
    const tracks = q.liked ? library.favorites : (library.playlist(q.id)?.tracks ?? []);
    if (tracks.length) player.play(tracks, 0);
  }

  const songsOnly = (s: ShelfT) => s.items.length > 0 && s.items.every((i) => i.kind === "song");
</script>

<div class="lfly-page home">
  <header class="greet">
    <span class="date">{today}</span>
    <h1>{greeting}</h1>
  </header>

  {#if quick.length}
  <div class="top solo">
    <div class="quick">
      {#each quick as q (q.id)}
        <div class="q">
          <button class="q-open" onclick={() => openQuick(q)}>
            <span class="q-art">
              {#if q.liked}<Artwork icon="heart" size={56} />{:else}<Cover url={q.cover} size={56} />{/if}
            </span>
            <span class="q-text">
              <span class="q-title">{q.title}</span>
              <span class="q-sub">{q.sub}</span>
            </span>
          </button>
          <button class="q-play" aria-label="Riproduci {q.title}" onclick={() => playQuick(q)}><Icon name="play" size={15} /></button>
        </div>
      {/each}
    </div>
  </div>
  {/if}

  {#if recent.length}<Shelf title="Ascoltati di recente" items={recent} />{/if}

  {#if error}
    <div class="p-empty">
      <strong>Home non disponibile</strong>
      {error}
      <button class="p-pill" onclick={load}>Riprova</button>
    </div>
  {:else if shelves == null}
    <div class="skeleton" aria-busy="true" aria-label="Caricamento">
      {#each Array(3) as _}
        <div class="sk-title"></div>
        <div class="sk-row">{#each Array(6) as _}<div class="sk-card"><span></span><i></i></div>{/each}</div>
      {/each}
    </div>
  {:else}
    {#each shelves as s (s.title)}
      {#if songsOnly(s)}
        <QuickPicks title={s.title} items={s.items} />
      {:else}
        <Shelf title={s.title} items={s.items} />
      {/if}
    {/each}
  {/if}
</div>

<style>
  .home {
    container-type: inline-size;
  }
  .greet {
    padding: 20px var(--page-x) 18px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .date {
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--lfly-text-3);
  }
  h1 {
    margin: 0;
    font-family: var(--lfly-font-display);
    font-size: clamp(30px, 3.6vw, 44px);
    font-weight: 800;
    letter-spacing: -0.03em;
    line-height: 1.05;
  }
  .top {
    display: grid;
    gap: 18px;
    padding: 0 var(--page-x);
    margin-bottom: 36px;
  }
  @container (min-width: 1020px) {
    .top:not(.solo) {
      grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
      align-items: stretch;
    }
    .top:not(.solo) .quick {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      align-content: start;
    }
  }
  .quick {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
    gap: 10px;
  }
  .q {
    position: relative;
    display: flex;
    align-items: center;
    border-radius: 14px;
    background: color-mix(in srgb, var(--lfly-surface) 62%, transparent);
    border: 1px solid var(--lfly-separator);
    overflow: hidden;
    transition:
      background var(--lfly-dur-fast, 160ms),
      transform var(--lfly-dur-fast, 160ms) var(--lfly-ease, ease);
  }
  .q:hover {
    background: color-mix(in srgb, var(--lfly-surface-2) 85%, transparent);
  }
  .q:active {
    transform: scale(0.985);
  }
  .q-open {
    display: flex;
    align-items: center;
    gap: 12px;
    flex: 1;
    min-width: 0;
    text-align: left;
    padding: 0 48px 0 0;
  }
  .q-art {
    flex: none;
    display: grid;
  }
  .q-art :global(.lfly-cover),
  .q-art :global(.artwork) {
    border-radius: 0;
    box-shadow: none;
  }
  .q-text {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  .q-title {
    font-weight: 650;
    font-size: 13.5px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .q-sub {
    font-size: 11.5px;
    color: var(--lfly-text-3);
  }
  .q-play {
    position: absolute;
    right: 10px;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    padding-left: 2px;
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
    box-shadow: 0 6px 16px rgba(0, 0, 0, 0.35);
    opacity: 0;
    transform: scale(0.85);
    transition:
      opacity var(--lfly-dur-fast, 160ms),
      transform var(--lfly-dur-fast, 160ms) var(--lfly-ease-spring, ease);
  }
  .q:hover .q-play,
  .q:focus-within .q-play {
    opacity: 1;
    transform: none;
  }
  .skeleton {
    padding: 0 var(--page-x);
  }
  .sk-title {
    width: 220px;
    height: 22px;
    border-radius: 8px;
    background: var(--lfly-glass-tint);
    margin: 8px 0 16px;
    animation: lfly-pulse 1.4s ease-in-out infinite;
  }
  .sk-row {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: 20px;
    margin-bottom: 32px;
    height: 220px;
    overflow: hidden;
  }
  .sk-card {
    display: flex;
    flex-direction: column;
    gap: 10px;
    animation: lfly-pulse 1.4s ease-in-out infinite;
  }
  .sk-card span {
    aspect-ratio: 1;
    border-radius: var(--lfly-radius-m, 12px);
    background: var(--lfly-glass-tint);
  }
  .sk-card i {
    height: 12px;
    width: 70%;
    border-radius: 6px;
    background: var(--lfly-glass-tint);
  }
</style>
