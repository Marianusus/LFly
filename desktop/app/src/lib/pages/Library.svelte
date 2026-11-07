<script lang="ts">
  import "./parts/page.css";
  import Icon from "../Icon.svelte";
  import Glass from "../glass/Glass.svelte";
  import TrackList from "../components/TrackList.svelte";
  import SpotifyImport from "../components/SpotifyImport.svelte";
  import Chips from "./parts/Chips.svelte";
  import Glyph from "./parts/Glyph.svelte";
  import LibraryItem from "./parts/LibraryItem.svelte";
  import { library } from "../library.svelte";
  import { nav } from "../nav.svelte";
  import { player } from "../player.svelte";
  import { account } from "../account.svelte";
  import { downloads } from "../downloads.svelte";
  import { openItem, playItem } from "../items";

  type Filter = "all" | "playlists" | "youtube" | "downloads" | "recent";

  let spotify = $state(false);
  let filter = $state<Filter>("all");
  let view = $state<"grid" | "list">(readView());

  function readView(): "grid" | "list" {
    try {
      return localStorage.getItem("lfly.library.view") === "list" ? "list" : "grid";
    } catch {
      return "grid";
    }
  }
  function setView(v: "grid" | "list") {
    view = v;
    try {
      localStorage.setItem("lfly.library.view", v);
    } catch {
      // Not kept: the grid comes back next time.
    }
  }

  function newPlaylist() {
    const p = library.createPlaylist(`La mia playlist n. ${library.playlists.length + 1}`);
    nav.go({ page: "playlist", id: p.id, name: p.name, local: true });
  }

  const filters = $derived(
    (
      [
        { id: "all", label: "Tutto" },
        { id: "playlists", label: "Playlist" },
        account.info ? { id: "youtube", label: "YouTube Music" } : null,
        { id: "downloads", label: "Scaricati" },
        library.history.length ? { id: "recent", label: "Recenti" } : null,
      ] as ({ id: Filter; label: string } | null)[]
    ).filter((f): f is { id: Filter; label: string } => f != null),
  );

  const show = (f: Filter) => filter === "all" || filter === f;
  const list = $derived(view === "list");
</script>

<div class="lfly-page library">
  <header class="head">
    <div class="title">
      <h1>Libreria</h1>
      <span class="count">{library.playlists.length} playlist · {library.favorites.length} brani che ti piacciono</span>
    </div>
    <div class="acts">
      <button class="p-pill" onclick={() => library.importBackup()}><Icon name="upload" size={15} />Importa dal telefono</button>
      <button class="p-pill" class:on={spotify} aria-expanded={spotify} onclick={() => (spotify = !spotify)}><Icon name="download" size={15} />Importa da Spotify</button>
      <button class="p-pill accent" onclick={newPlaylist}><Icon name="plus" size={15} />Nuova playlist</button>
    </div>
  </header>

  {#if spotify}<div class="spotify"><SpotifyImport onclose={() => (spotify = false)} /></div>{/if}

  <div class="toolbar">
    <Chips label="Mostra" options={filters} value={filter} onchange={(f) => (filter = f)} />
    {#if filter !== "downloads"}
      <Glass shape="pill" class="viewer">
        <div class="viewer-in" role="radiogroup" aria-label="Vista">
          <button role="radio" aria-checked={view === "grid"} aria-label="Griglia" title="Griglia" class:on={view === "grid"} onclick={() => setView("grid")}>
            <Glyph name="grid" size={16} />
          </button>
          <button role="radio" aria-checked={view === "list"} aria-label="Elenco" title="Elenco" class:on={view === "list"} onclick={() => setView("list")}>
            <Glyph name="list" size={16} />
          </button>
        </div>
      </Glass>
    {/if}
  </div>

  {#if filter === "downloads"}
    {#if downloads.tracks.length}
      <div class="dl-head">
        <span>{downloads.tracks.length} brani sul computer</span>
        <button class="p-pill accent" onclick={() => player.play(downloads.tracks, 0)}><Icon name="play" size={14} />Riproduci</button>
        <button class="p-pill" onclick={() => nav.go({ page: "downloads" })}>Apri Scaricati</button>
      </div>
      <TrackList tracks={downloads.tracks} />
    {:else}
      <div class="p-empty">
        <Icon name="download" size={28} />
        <strong>Nessun brano scaricato</strong>
        Scarica album, playlist o singoli brani (tasto destro › Scarica) per ascoltarli anche senza connessione.
      </div>
    {/if}
  {:else}
    {#if show("playlists")}
      <section>
        {#if filter === "all"}<h2 class="p-section-title">Le tue raccolte</h2>{/if}
        <div class="items" class:list>
          {#if filter === "all"}
            <LibraryItem
              {list}
              title="Brani che ti piacciono"
              subtitle="{library.favorites.length} brani"
              art={{ icon: "heart" }}
              onopen={() => nav.go({ page: "liked" })}
              onplay={library.favorites.length ? () => player.play(library.favorites, 0) : undefined}
            />
            {#if downloads.tracks.length}
              <LibraryItem
                {list}
                title="Scaricati"
                subtitle="{downloads.tracks.length} brani sul computer"
                art={{ icon: "download", tone: "blue" }}
                onopen={() => nav.go({ page: "downloads" })}
                onplay={() => player.play(downloads.tracks, 0)}
              />
            {/if}
          {/if}
          {#each library.playlists as p (p.id)}
            <LibraryItem
              {list}
              title={p.name}
              subtitle="Playlist · {p.tracks.length} brani"
              coverUrl={p.coverUrl ?? p.tracks[0]?.coverUrl}
              onopen={() => nav.go({ page: "playlist", id: p.id, name: p.name, local: true })}
              onplay={p.tracks.length ? () => player.play(p.tracks, 0) : undefined}
            />
          {/each}
        </div>
        {#if !library.playlists.length}
          <div class="p-empty small">
            <strong>Ancora nessuna playlist</strong>
            Crea la prima, importala dal telefono o da Spotify.
            <button class="p-pill accent" onclick={newPlaylist}><Icon name="plus" size={15} />Nuova playlist</button>
          </div>
        {/if}
      </section>
    {/if}

    {#if account.info && (show("youtube") || filter === "playlists")}
      <section>
        <h2 class="p-section-title">Da YouTube Music</h2>
        <div class="items" class:list>
          <LibraryItem
            {list}
            title="Mi piace"
            subtitle="I brani a cui hai messo mi piace su YouTube Music"
            art={{ icon: "heart", tone: "violet" }}
            onopen={() => nav.go({ page: "playlist", id: "LM", name: "Mi piace" })}
            onplay={() => playItem({ kind: "playlist", id: "LM", title: "Mi piace" })}
          />
          {#each account.playlists as p (p.id)}
            <LibraryItem {list} title={p.title} subtitle={p.subtitle} coverUrl={p.coverUrl} onopen={() => openItem(p)} onplay={() => playItem(p)} />
          {/each}
        </div>
      </section>
    {/if}

    {#if library.history.length && show("recent")}
      <section>
        <h2 class="p-section-title">Ascoltati di recente</h2>
        <div class="items" class:list>
          {#each library.history.slice(0, filter === "recent" ? 60 : 12) as t, i (t.id + ":" + i)}
            <LibraryItem
              {list}
              title={t.title}
              subtitle={t.artist}
              coverUrl={t.coverUrl}
              playing={player.track?.id === t.id}
              onopen={() => player.play(library.history, i)}
              onplay={() => player.play(library.history, i)}
            />
          {/each}
        </div>
      </section>
    {/if}
  {/if}
</div>

<style>
  .head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
    padding: 20px var(--page-x) 18px;
  }
  .title {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  h1 {
    margin: 0;
    font-family: var(--lfly-font-display);
    font-size: clamp(30px, 3.6vw, 44px);
    font-weight: 800;
    letter-spacing: -0.03em;
    line-height: 1.05;
  }
  .count {
    font-size: 13px;
    color: var(--lfly-text-3);
  }
  .acts {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .acts .p-pill.on {
    background: color-mix(in srgb, var(--lfly-text) 16%, transparent);
  }
  .spotify {
    padding: 0 var(--page-x) 16px;
  }
  .toolbar {
    position: relative;
    z-index: 3;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 0 var(--page-x) 20px;
  }
  .toolbar :global(.viewer) {
    flex: none;
    padding: 4px;
  }
  .viewer-in {
    display: flex;
    gap: 2px;
  }
  .viewer-in button {
    width: 36px;
    height: 32px;
    border-radius: 999px;
    display: grid;
    place-items: center;
    color: var(--lfly-text-2);
    transition: background var(--lfly-dur-fast, 160ms), color var(--lfly-dur-fast, 160ms);
  }
  .viewer-in button.on {
    background: var(--lfly-text);
    color: var(--lfly-bg, #000);
  }
  .viewer-in button:hover:not(.on) {
    color: var(--lfly-text);
  }
  section {
    padding: 0 var(--page-x) 28px;
  }
  .items {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
    gap: 4px;
    margin: 0 -10px;
  }
  .items.list {
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 360px), 1fr));
    gap: 2px 12px;
    margin: 0 -6px;
  }
  .p-empty.small {
    margin: 12px 0 0;
    padding: 24px;
  }
  .dl-head {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 0 var(--page-x) 16px;
    color: var(--lfly-text-2);
    font-size: 13px;
  }
  .dl-head span {
    margin-right: auto;
  }
</style>
