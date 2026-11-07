<script lang="ts">
  // The themes of the Spicetify Marketplace, inside LFly: the same catalogue (GitHub repositories
  // tagged spicetify-themes), read by the core (market.rs). Installing takes a theme's colour
  // schemes (color.ini) as LFly themes; its user.css and the Marketplace's extensions are made for
  // Spotify's page and stay out.
  import { invoke } from "@tauri-apps/api/core";
  import { onMount } from "svelte";
  import Icon from "../../Icon.svelte";
  import { themes } from "../../theme/theme.svelte";
  import { parseColorIni, themeFromSpicetify, themeFromScheme, type Theme } from "../../theme/themes";
  import { connect } from "../../connect.svelte";

  type MarketTheme = {
    name: string;
    description: string;
    author: string;
    repo: string;
    stars: number;
    preview?: string | null;
    schemes: string;
    outdated: boolean;
  };

  let list = $state<MarketTheme[]>([]);
  let status = $state<"loading" | "ready" | "error">("loading");
  let error = $state("");
  let query = $state("");
  let installing = $state("");
  let installed = $state<Set<string>>(new Set());

  const shown = $derived.by(() => {
    const q = query.trim().toLowerCase();
    const l = q ? list.filter((t) => `${t.name} ${t.author} ${t.description} ${t.repo}`.toLowerCase().includes(q)) : list;
    // The ones marked outdated last: their colours still work, the rest of them may not.
    return [...l].sort((a, b) => Number(a.outdated) - Number(b.outdated));
  });

  async function load(refresh = false) {
    status = "loading";
    try {
      list = (await invoke<MarketTheme[] | null>("market_themes", { refresh })) ?? [];
      status = "ready";
    } catch (e) {
      error = String(e);
      status = "error";
    }
  }

  onMount(() => load());

  function base(): Theme {
    return themes.active ? ($state.snapshot(themes.active) as Theme) : themeFromScheme($state.snapshot(themes.scheme) as typeof themes.scheme, "LFly");
  }

  async function install(t: MarketTheme) {
    installing = t.schemes;
    try {
      const ini = await invoke<string>("market_file", { url: t.schemes });
      const schemes = parseColorIni(ini);
      if (!schemes.length) throw new Error("il tema non ha schemi di colori");
      let last: Theme | null = null;
      for (const s of schemes) {
        const label = schemes.length > 1 ? `${t.name} · ${s.name}` : t.name;
        last = themeFromSpicetify(label, s.colors, base());
        themes.add(last);
      }
      installed = new Set([...installed, t.schemes]);
      connect.say(schemes.length > 1 ? `${t.name}: ${schemes.length} schemi aggiunti, in uso il primo` : `${t.name} installato e in uso`);
      // The first scheme in use, like picking it in the Marketplace.
      if (last && schemes.length > 1) themes.use(themes.all.find((x) => x.name === `${t.name} · ${schemes[0].name}`)?.id ?? last.id);
    } catch (e) {
      connect.say(`Non riesco a installare ${t.name}: ${e instanceof Error ? e.message : e}`);
    } finally {
      installing = "";
    }
  }
</script>

<div class="market">
  <div class="bar">
    <label class="search">
      <Icon name="search" size={15} />
      <input class="lfly-input" placeholder="Cerca un tema, un autore…" bind:value={query} aria-label="Cerca nel Marketplace" />
    </label>
    <button class="lfly-btn lfly-btn-secondary" disabled={status === "loading"} onclick={() => load(true)}>Aggiorna</button>
  </div>

  {#if status === "loading"}
    <div class="grid" aria-busy="true">
      {#each Array(8) as _}<div class="card skeleton"></div>{/each}
    </div>
  {:else if status === "error"}
    <p class="note">Il Marketplace non risponde: {error}</p>
  {:else}
    <p class="note">{shown.length} temi dal Marketplace di Spicetify. In LFly arrivano i colori; il CSS e le estensioni sono fatti per Spotify.</p>
    <div class="grid">
      {#each shown as t (t.schemes)}
        <article class="card" class:old={t.outdated}>
          <div class="shot">
            {#if t.preview}<img src={t.preview} alt="" loading="lazy" decoding="async" />{/if}
          </div>
          <div class="info">
            <strong class="ellipsis" title={t.name}>{t.name}</strong>
            <span class="by ellipsis">di {t.author} · ★ {t.stars >= 1000 ? `${(t.stars / 1000).toFixed(1).replace(".", ",")}k` : t.stars}</span>
          </div>
          <button
            class="lfly-btn {installed.has(t.schemes) ? 'lfly-btn-secondary' : 'lfly-btn-primary'} get"
            disabled={installing !== ""}
            onclick={() => install(t)}
          >
            {installing === t.schemes ? "Installo…" : installed.has(t.schemes) ? "Installato" : "Installa"}
          </button>
        </article>
      {/each}
    </div>
  {/if}
</div>

<style>
  .market {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .bar {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .search {
    flex: 1;
    position: relative;
    display: flex;
    align-items: center;
  }
  .search :global(svg) {
    position: absolute;
    left: 12px;
    color: var(--lfly-text-3);
    pointer-events: none;
  }
  .search input {
    width: 100%;
    padding-left: 34px;
  }
  .note {
    margin: 0 4px;
    color: var(--lfly-text-2);
    font-size: 12.5px;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 14px;
  }
  .card {
    display: flex;
    flex-direction: column;
    border-radius: 16px;
    overflow: hidden;
    background: var(--lfly-glass-tint);
    border: 1px solid var(--lfly-glass-border);
    content-visibility: auto;
    contain-intrinsic-size: auto 230px;
  }
  .card.old {
    opacity: 0.75;
  }
  .skeleton {
    height: 230px;
    animation: lfly-pulse 1.4s ease-in-out infinite;
  }
  .shot {
    aspect-ratio: 16 / 10;
    background: color-mix(in srgb, var(--lfly-text) 6%, transparent);
  }
  .shot img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  .info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 10px 12px 6px;
    min-width: 0;
  }
  .by {
    font-size: 12px;
    color: var(--lfly-text-3);
  }
  .get {
    margin: 4px 12px 12px;
    justify-content: center;
  }
</style>
