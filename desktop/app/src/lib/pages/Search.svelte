<script lang="ts">
  import "./parts/page.css";
  import Icon from "../Icon.svelte";
  import Glass from "../glass/Glass.svelte";
  import Cover from "../components/Cover.svelte";
  import Card from "../components/Card.svelte";
  import Shelf from "../components/Shelf.svelte";
  import TrackRow from "../components/TrackRow.svelte";
  import Chips from "./parts/Chips.svelte";
  import { api, errorText, type Item, type SearchResults } from "../api";
  import { nav } from "../nav.svelte";
  import { openItem, playItem, playSongWithRadio } from "../items";
  import { player, type Track } from "../player.svelte";
  import { cover } from "../connect.svelte";
  import { addRecent, clearRecents, loadRecents, removeRecent, type Recent } from "./parts/recents";

  type Cat = "all" | "songs" | "videos" | "artists" | "albums" | "playlists";

  const initial = nav.route.page === "search" ? (nav.route.query ?? "") : "";
  let query = $state(initial);
  let results = $state<SearchResults | null>(null);
  let suggestions = $state<string[]>([]);
  let loading = $state(false);
  let error = $state<string | null>(null);
  let cat = $state<Cat>("all");
  let recents = $state<Recent[]>(loadRecents());
  let focused = $state(false);
  let seq = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;

  async function run(q: string) {
    const mine = ++seq;
    if (!q.trim()) {
      results = null;
      suggestions = [];
      loading = false;
      return;
    }
    loading = true;
    error = null;
    try {
      const r = await api.search(q.trim());
      if (mine === seq) results = r;
    } catch (e) {
      if (mine === seq) error = errorText(e);
    } finally {
      if (mine === seq) loading = false;
    }
  }

  function onInput() {
    nav.replace({ page: "search", query });
    clearTimeout(timer);
    timer = setTimeout(() => run(query), 350);
    const q = query;
    if (q.trim().length > 1) api.suggestions(q).then((s) => q === query && (suggestions = s.slice(0, 6))).catch(() => {});
    else suggestions = [];
  }

  function pick(s: string) {
    query = s;
    suggestions = [];
    cat = "all";
    nav.replace({ page: "search", query });
    if (s.trim()) recents = addRecent(recents, { query: s.trim() });
    run(s);
  }

  function open(item: Item) {
    recents = addRecent(recents, { item: $state.snapshot(item) as Item });
    openItem(item);
  }

  function playSong(t: Track) {
    recents = addRecent(recents, {
      item: { kind: "song", id: t.id, title: t.title, subtitle: t.artist, coverUrl: t.coverUrl, track: $state.snapshot(t) as Track },
    });
    playSongWithRadio(t);
  }

  if (initial) run(initial);

  const songs = $derived(results?.songs.filter((t) => !t.isVideo) ?? []);
  const videos = $derived(results?.songs.filter((t) => t.isVideo) ?? []);
  const cats = $derived(
    (
      [
        { id: "all", label: "Tutto", n: 1 },
        { id: "songs", label: "Brani", n: songs.length },
        { id: "videos", label: "Video", n: videos.length },
        { id: "artists", label: "Artisti", n: results?.artists.length ?? 0 },
        { id: "albums", label: "Album", n: results?.albums.length ?? 0 },
        { id: "playlists", label: "Playlist", n: results?.playlists.length ?? 0 },
      ] as { id: Cat; label: string; n: number }[]
    ).filter((c) => c.n > 0),
  );
  const grid = $derived(cat === "artists" ? results?.artists : cat === "albums" ? results?.albums : cat === "playlists" ? results?.playlists : null);
  const kindName = { song: "Brano", album: "Album", playlist: "Playlist", artist: "Artista" } as const;

  // Moods and genres to start from, like the phone's Browse tiles.
  const browse = [
    { q: "Pop italiano", c1: "#ff5f6d", c2: "#c2185b" },
    { q: "Hip hop", c1: "#f7971e", c2: "#b2401c" },
    { q: "Rock", c1: "#e53935", c2: "#5d1010" },
    { q: "Elettronica", c1: "#00c6ff", c2: "#0049b7" },
    { q: "Chill", c1: "#43cea2", c2: "#185a9d" },
    { q: "Allenamento", c1: "#f12711", c2: "#f5af19" },
    { q: "Concentrazione", c1: "#7f7fd5", c2: "#3a3a8c" },
    { q: "Anni '80", c1: "#fc466b", c2: "#3f5efb" },
    { q: "Jazz", c1: "#c79081", c2: "#5b3a29" },
    { q: "Indie", c1: "#a8e063", c2: "#2f7336" },
    { q: "Lo-fi", c1: "#b993d6", c2: "#5b4a8a" },
    { q: "Festa", c1: "#ff0099", c2: "#493240" },
  ];
</script>

<div class="lfly-page search">
  <header class="head">
    <h1>Cerca</h1>
    <div class="field-wrap">
      <Glass shape="pill" class="search-field {focused ? 'focused' : ''}">
        <label class="field">
          <Icon name="search" size={20} />
          <!-- svelte-ignore a11y_autofocus -->
          <input
            id="search-field"
            type="search"
            placeholder="Artisti, brani, album e playlist"
            bind:value={query}
            oninput={onInput}
            onfocus={() => (focused = true)}
            onblur={() => setTimeout(() => (focused = false), 150)}
            onkeydown={(e) => {
              if (e.key === "Enter") pick(query);
              else if (e.key === "Escape") suggestions = [];
            }}
            autocomplete="off"
            spellcheck="false"
            autofocus
          />
          {#if loading}<span class="spin" aria-label="Cerco…"></span>{/if}
          {#if query}
            <button class="clear" aria-label="Cancella" onclick={() => pick("")}><Icon name="close" size={14} /></button>
          {/if}
        </label>
      </Glass>
      {#if suggestions.length && focused}
        <Glass class="sugg" radius={20}>
          <ul>
            {#each suggestions as s}
              <li><button onmousedown={(e) => e.preventDefault()} onclick={() => pick(s)}><Icon name="search" size={14} /><span>{s}</span></button></li>
            {/each}
          </ul>
        </Glass>
      {/if}
    </div>
    {#if results && cats.length > 1}
      <div class="chips"><Chips label="Tipo di risultato" options={cats} value={cat} onchange={(c) => (cat = c)} /></div>
    {/if}
  </header>

  {#if error}
    <div class="p-empty">
      <strong>La ricerca non ha funzionato</strong>
      {error}
      <button class="p-pill" onclick={() => run(query)}>Riprova</button>
    </div>
  {:else if loading && !results}
    <div class="sk" aria-busy="true" aria-label="Cerco…">
      <div class="sk-top"></div>
      <div class="sk-list">{#each Array(5) as _}<div></div>{/each}</div>
    </div>
  {:else if results}
    {#if !results.top && !results.songs.length && !results.albums.length && !results.artists.length && !results.playlists.length}
      <div class="p-empty"><strong>Nessun risultato per “{query}”</strong>Controlla come è scritto o prova con altre parole.</div>
    {:else if cat === "all"}
      <div class="top-row">
        {#if results.top}
          {@const top = results.top}
          <section class="top">
            <h2 class="p-section-title">Risultato principale</h2>
            <div class="top-card" class:artist={top.kind === "artist"}>
              {#if top.coverUrl}<img class="top-glow" src={cover(top.coverUrl, 120)} alt="" aria-hidden="true" />{/if}
              <button class="top-open" onclick={() => open(top)} aria-label={top.title}></button>
              <Cover url={top.coverUrl} size={112} round={top.kind === "artist"} />
              <div class="top-text">
                <span class="top-title">{top.title}</span>
                <span class="top-sub"><span class="kind">{kindName[top.kind]}</span>{top.subtitle ?? ""}</span>
              </div>
              {#if top.kind !== "artist"}
                <button class="top-play" aria-label="Riproduci {top.title}" onclick={() => playItem(top)}><Icon name="play" size={22} /></button>
              {/if}
            </div>
          </section>
        {/if}
        {#if results.songs.length}
          <section class="songs">
            <div class="sec-head">
              <h2 class="p-section-title">Brani</h2>
              {#if results.songs.length > 5}<button class="see" onclick={() => (cat = songs.length ? "songs" : "videos")}>Mostra tutti</button>{/if}
            </div>
            {#each results.songs.slice(0, 5) as t (t.id)}
              <TrackRow track={t} showAlbum={false} playing={player.track?.id === t.id} onplay={() => playSong(t)} />
            {/each}
          </section>
        {/if}
      </div>
      <Shelf title="Artisti" items={results.artists} />
      <Shelf title="Album" items={results.albums} />
      <Shelf title="Playlist" items={results.playlists} />
      {#if results.songs.length > 5}
        <section class="list">
          <h2 class="p-section-title">Altri brani</h2>
          {#each results.songs.slice(5, 20) as t (t.id)}
            <TrackRow track={t} playing={player.track?.id === t.id} onplay={() => playSong(t)} />
          {/each}
        </section>
      {/if}
    {:else if cat === "songs" || cat === "videos"}
      <section class="list">
        {#each cat === "songs" ? songs : videos as t (t.id)}
          <TrackRow track={t} playing={player.track?.id === t.id} onplay={() => playSong(t)} />
        {/each}
      </section>
    {:else if grid}
      <div class="grid">
        {#each grid as item (item.kind + item.id)}
          <Card
            title={item.title}
            subtitle={item.subtitle}
            coverUrl={item.coverUrl}
            round={item.kind === "artist"}
            onopen={() => open(item)}
            onplay={item.kind === "artist" ? undefined : () => playItem(item)}
          />
        {/each}
      </div>
    {/if}
  {:else}
    {#if recents.length}
      <section class="recents">
        <div class="sec-head">
          <h2 class="p-section-title">Ricerche recenti</h2>
          <button class="see" onclick={() => (recents = clearRecents())}>Cancella</button>
        </div>
        <div class="recent-list">
          {#each recents as r ("query" in r ? "q:" + r.query : r.item.kind + r.item.id)}
            <div class="recent">
              {#if "query" in r}
                <button class="recent-open" onclick={() => pick(r.query)}>
                  <span class="recent-icon"><Icon name="search" size={18} /></span>
                  <span class="recent-text"><span class="rt">{r.query}</span><span class="rs">Ricerca</span></span>
                </button>
              {:else}
                {@const it = r.item}
                <button class="recent-open" onclick={() => open(it)}>
                  <Cover url={it.coverUrl} size={48} round={it.kind === "artist"} />
                  <span class="recent-text">
                    <span class="rt">{it.title}</span>
                    <span class="rs">{kindName[it.kind]}{it.subtitle ? ` · ${it.subtitle}` : ""}</span>
                  </span>
                </button>
              {/if}
              <button class="recent-x" aria-label="Togli dalle ricerche recenti" onclick={() => (recents = removeRecent(recents, r))}>
                <Icon name="close" size={14} />
              </button>
            </div>
          {/each}
        </div>
      </section>
    {/if}
    <section class="browse">
      <h2 class="p-section-title">Sfoglia</h2>
      <div class="tiles">
        {#each browse as b (b.q)}
          <button class="tile" style="--c1:{b.c1};--c2:{b.c2}" onclick={() => pick(b.q)}>
            <span>{b.q}</span>
            <i aria-hidden="true"><Icon name="music" size={44} /></i>
          </button>
        {/each}
      </div>
    </section>
  {/if}
</div>

<style>
  .head {
    position: relative;
    z-index: 3;
    padding: 20px var(--page-x) 20px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  h1 {
    margin: 0;
    font-family: var(--lfly-font-display);
    font-size: clamp(30px, 3.6vw, 44px);
    font-weight: 800;
    letter-spacing: -0.03em;
  }
  .field-wrap {
    position: relative;
    max-width: 640px;
  }
  .field-wrap :global(.search-field) {
    transition:
      box-shadow var(--lfly-dur-med, 320ms) var(--lfly-ease, ease),
      transform var(--lfly-dur-med, 320ms) var(--lfly-ease-spring, ease);
  }
  .field-wrap :global(.search-field.focused) {
    box-shadow:
      0 0 0 2px color-mix(in srgb, var(--lfly-accent) 70%, transparent),
      0 14px 40px rgba(0, 0, 0, 0.35);
    transform: scale(1.01);
  }
  .field {
    display: flex;
    align-items: center;
    gap: 12px;
    height: 54px;
    padding: 0 10px 0 20px;
    color: var(--lfly-text-2);
    cursor: text;
  }
  input {
    flex: 1;
    min-width: 0;
    background: transparent;
    border: 0;
    outline: none;
    font-size: 17px;
    color: var(--lfly-text);
    user-select: text;
  }
  input::placeholder {
    color: var(--lfly-text-3);
  }
  input::-webkit-search-cancel-button {
    display: none;
  }
  .clear {
    width: 30px;
    height: 30px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    background: color-mix(in srgb, var(--lfly-text) 14%, transparent);
    color: var(--lfly-text);
  }
  .spin {
    width: 16px;
    height: 16px;
    border-radius: 50%;
    border: 2px solid color-mix(in srgb, var(--lfly-text) 20%, transparent);
    border-top-color: var(--lfly-text);
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .field-wrap :global(.sugg) {
    position: absolute;
    left: 0;
    right: 0;
    top: calc(100% + 8px);
    padding: 6px;
    box-shadow: var(--lfly-shadow-2);
    animation: pop 220ms var(--lfly-ease, ease) both;
  }
  @keyframes pop {
    from {
      opacity: 0;
      transform: translateY(-6px) scale(0.98);
    }
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  li button {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 14px;
    border-radius: 14px;
    text-align: left;
    font-size: 14px;
    color: var(--lfly-text);
  }
  li button :global(svg) {
    color: var(--lfly-text-3);
  }
  li button:hover {
    background: var(--lfly-glass-tint);
  }
  .chips {
    margin-top: 2px;
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
  .top-row {
    display: grid;
    grid-template-columns: minmax(280px, 2fr) minmax(0, 3fr);
    gap: 28px;
    padding: 0 var(--page-x) 32px;
  }
  .top-card {
    position: relative;
    isolation: isolate;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    gap: 18px;
    padding: 22px;
    min-height: 250px;
    border-radius: var(--lfly-radius-l, 18px);
    background: color-mix(in srgb, var(--lfly-surface) 65%, transparent);
    border: 1px solid var(--lfly-separator);
    transition: background var(--lfly-dur-fast, 160ms), transform var(--lfly-dur-fast, 160ms) var(--lfly-ease, ease);
  }
  .top-card:hover {
    background: color-mix(in srgb, var(--lfly-surface-2) 80%, transparent);
  }
  .top-card:active {
    transform: scale(0.99);
  }
  .top-glow {
    position: absolute;
    z-index: -1;
    inset: -30%;
    width: 160%;
    height: 160%;
    object-fit: cover;
    filter: blur(70px) saturate(1.7);
    opacity: 0.3;
    pointer-events: none;
  }
  .top-card :global(.lfly-cover) {
    box-shadow: 0 12px 30px rgba(0, 0, 0, 0.4);
    border-radius: var(--lfly-radius-m, 12px);
  }
  .top-card.artist :global(.lfly-cover) {
    border-radius: 50%;
  }
  .top-open {
    position: absolute;
    inset: 0;
    z-index: 1;
  }
  .top-text {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-width: 0;
    pointer-events: none;
  }
  .top-title {
    font-family: var(--lfly-font-display);
    font-size: 30px;
    font-weight: 800;
    letter-spacing: -0.025em;
    line-height: 1.1;
    overflow: hidden;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
  }
  .top-sub {
    color: var(--lfly-text-2);
    font-size: 14px;
  }
  .kind {
    display: inline-block;
    padding: 3px 10px;
    margin-right: 8px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 700;
    color: var(--lfly-text);
    background: var(--lfly-glass-tint);
    border: 1px solid var(--lfly-glass-border);
  }
  .top-play {
    position: absolute;
    z-index: 2;
    right: 20px;
    bottom: 20px;
    width: 54px;
    height: 54px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    padding-left: 3px;
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
    box-shadow: 0 10px 26px color-mix(in srgb, var(--lfly-accent) 38%, transparent);
    opacity: 0;
    transform: translateY(8px) scale(0.92);
    transition:
      opacity var(--lfly-dur-fast, 160ms),
      transform var(--lfly-dur-med, 320ms) var(--lfly-ease-spring, ease);
  }
  .top-card:hover .top-play,
  .top-card:focus-within .top-play {
    opacity: 1;
    transform: none;
  }
  .list {
    padding: 0 var(--page-x) 32px;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
    gap: 8px;
    padding: 0 var(--page-x) 32px;
  }
  .recents,
  .browse {
    padding: 0 var(--page-x) 32px;
  }
  .recent-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 4px 16px;
  }
  .recent {
    position: relative;
    display: flex;
    align-items: center;
    border-radius: 12px;
    transition: background var(--lfly-dur-fast, 160ms);
  }
  .recent:hover {
    background: var(--lfly-glass-tint);
  }
  .recent-open {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px;
    text-align: left;
  }
  .recent-icon {
    width: 48px;
    height: 48px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 10px;
    background: var(--lfly-glass-tint);
    color: var(--lfly-text-2);
  }
  .recent-text {
    display: flex;
    flex-direction: column;
    min-width: 0;
    gap: 2px;
  }
  .rt {
    font-size: 14px;
    font-weight: 550;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .rs {
    font-size: 12px;
    color: var(--lfly-text-2);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .recent-x {
    width: 32px;
    height: 32px;
    margin-right: 8px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: var(--lfly-text-3);
    opacity: 0;
  }
  .recent:hover .recent-x,
  .recent-x:focus-visible {
    opacity: 1;
  }
  .recent-x:hover {
    color: var(--lfly-text);
    background: var(--lfly-glass-tint);
  }
  .tiles {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
    gap: 14px;
  }
  .tile {
    position: relative;
    overflow: hidden;
    aspect-ratio: 16 / 9;
    border-radius: var(--lfly-radius-l, 18px);
    padding: 16px;
    text-align: left;
    display: flex;
    align-items: flex-start;
    color: #fff;
    background: linear-gradient(135deg, var(--c1), var(--c2));
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25);
    transition: transform var(--lfly-dur-med, 320ms) var(--lfly-ease-spring, ease), box-shadow var(--lfly-dur-fast, 160ms);
  }
  .tile::before {
    content: "";
    position: absolute;
    inset: 0;
    background: radial-gradient(90% 70% at 10% 0%, rgba(255, 255, 255, 0.28), transparent 60%);
  }
  .tile span {
    position: relative;
    font-family: var(--lfly-font-display);
    font-size: 19px;
    font-weight: 750;
    letter-spacing: -0.015em;
    text-shadow: 0 1px 8px rgba(0, 0, 0, 0.2);
  }
  .tile i {
    position: absolute;
    right: -6px;
    bottom: -10px;
    transform: rotate(18deg);
    opacity: 0.35;
    transition: transform var(--lfly-dur-med, 320ms) var(--lfly-ease-spring, ease);
  }
  .tile:hover {
    transform: translateY(-2px) scale(1.02);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.25),
      0 14px 30px rgba(0, 0, 0, 0.35);
  }
  .tile:hover i {
    transform: rotate(8deg) scale(1.15);
  }
  .tile:active {
    transform: scale(0.98);
  }
  .sk {
    display: grid;
    grid-template-columns: minmax(280px, 2fr) minmax(0, 3fr);
    gap: 28px;
    padding: 0 var(--page-x);
  }
  .sk-top {
    height: 250px;
    border-radius: var(--lfly-radius-l, 18px);
    background: var(--lfly-glass-tint);
    animation: lfly-pulse 1.4s ease-in-out infinite;
  }
  .sk-list {
    display: grid;
    gap: 10px;
    align-content: start;
    padding-top: 40px;
  }
  .sk-list div {
    height: 44px;
    border-radius: 10px;
    background: var(--lfly-glass-tint);
    animation: lfly-pulse 1.4s ease-in-out infinite;
  }
  @media (max-width: 900px) {
    .top-row,
    .sk {
      grid-template-columns: 1fr;
    }
  }
</style>
