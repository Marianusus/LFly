<script lang="ts">
  import Icon from "../Icon.svelte";
  import Cover from "./Cover.svelte";
  import Glass from "../glass/Glass.svelte";
  import { menu } from "../menu.svelte";
  import { player } from "../player.svelte";
  import { library } from "../library.svelte";
  import { nav } from "../nav.svelte";
  import { downloads } from "../downloads.svelte";

  let sub = $state(false);
  let box = $state<HTMLDivElement>();
  let pos = $state({ x: 0, y: 0 });
  /** Which corner the menu grows from, so it opens out of the pointer. */
  let origin = $state("top left");

  // Kept inside the window, flipping up or left near the edges.
  $effect(() => {
    const m = menu.open;
    if (!m || !box) return;
    sub = false;
    // Layout size, not the drawn one: the menu is still scaling in.
    const r = { width: box.offsetWidth, height: box.offsetHeight };
    const left = m.x + r.width > window.innerWidth - 8;
    const up = m.y + r.height > window.innerHeight - 8;
    pos = {
      x: left ? Math.max(8, m.x - r.width) : m.x,
      y: up ? Math.max(8, m.y - r.height) : m.y,
    };
    origin = `${up ? "bottom" : "top"} ${left ? "right" : "left"}`;
    box.querySelector<HTMLButtonElement>("button")?.focus();
  });

  function run(action: () => void) {
    action();
    menu.close();
  }

  function onKey(e: KeyboardEvent) {
    if (!menu.open) return;
    if (e.key === "Escape") menu.close();
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const items = [...(box?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? [])];
      const i = items.indexOf(document.activeElement as HTMLButtonElement);
      items[(i + (e.key === "ArrowDown" ? 1 : -1) + items.length) % items.length]?.focus();
    }
  }

  function newPlaylist() {
    const t = menu.open?.track;
    if (!t) return;
    const p = library.createPlaylist(`La mia playlist n. ${library.playlists.length + 1}`, [t]);
    nav.go({ page: "playlist", id: p.id, name: p.name, local: true });
  }
</script>

<svelte:window onkeydown={onKey} onclick={() => menu.close()} onblur={() => menu.close()} onresize={() => menu.close()} />

{#if menu.open}
  {@const t = menu.open.track}
  <div
    class="menu"
    role="menu"
    tabindex="-1"
    aria-label="Azioni per {t.title}"
    bind:this={box}
    style="left:{pos.x}px; top:{pos.y}px; transform-origin:{origin}"
    onclick={(e) => e.stopPropagation()}
    oncontextmenu={(e) => e.preventDefault()}
    onkeydown={() => {}}
  >
    <Glass radius={18} style="padding:6px;--g-tint:color-mix(in srgb, var(--lfly-surface) 82%, transparent)">
      <div class="head" aria-hidden="true">
        <Cover url={t.coverUrl} size={36} />
        <span class="head-text">
          <span class="ellipsis head-title">{t.title}</span>
          <span class="ellipsis head-sub">{t.artist}</span>
        </span>
      </div>
      <hr />
      <button role="menuitem" onclick={() => run(() => player.addToQueue([t]))}><Icon name="queue" size={15} />Aggiungi alla coda</button>
      <button role="menuitem" onclick={() => run(() => player.playNext(t))}><Icon name="next" size={14} />Riproduci dopo</button>
      <hr />
      <button role="menuitem" onclick={() => run(() => library.toggleFavorite(t))}>
        <Icon name="heart" size={15} fill={library.isFavorite(t.id)} />
        {library.isFavorite(t.id) ? "Togli dai Brani che ti piacciono" : "Aggiungi ai Brani che ti piacciono"}
      </button>
      <button role="menuitem" aria-haspopup="menu" aria-expanded={sub} class:open={sub} onclick={() => (sub = !sub)}>
        <Icon name="plus" size={15} />Aggiungi a playlist<span class="chev"><Icon name="chevron-down" size={13} /></span>
      </button>
      {#if sub}
        <div class="sub" role="menu" aria-label="Playlist">
          <button role="menuitem" onclick={() => run(newPlaylist)}><Icon name="plus" size={14} />Nuova playlist</button>
          {#each library.playlists as p (p.id)}
            <button role="menuitem" onclick={() => run(() => library.addToPlaylist(p.id, [t]))}><span class="ellipsis">{p.name}</span></button>
          {/each}
        </div>
      {/if}
      {#if downloads.has(t.id)}
        <button role="menuitem" onclick={() => run(() => downloads.remove([t.id]))}><Icon name="trash" size={15} />Rimuovi il download</button>
      {:else}
        <button role="menuitem" disabled={downloads.pending(t.id)} onclick={() => run(() => downloads.add([t]))}>
          <Icon name="download" size={15} />{downloads.pending(t.id) ? "Download in corso…" : "Scarica"}
        </button>
      {/if}
      {#if menu.open.remove}
        {@const remove = menu.open.remove}
        <button role="menuitem" class="danger" onclick={() => run(remove)}><Icon name="trash" size={15} />Rimuovi da questa playlist</button>
      {/if}
      <hr />
      <button role="menuitem" disabled={!t.artistId} onclick={() => run(() => nav.go({ page: "artist", id: t.artistId!, name: t.artist }))}>
        <Icon name="music" size={15} />Vai all'artista
      </button>
      <button role="menuitem" disabled={!t.albumId} onclick={() => run(() => nav.go({ page: "album", id: t.albumId!, name: t.album }))}>
        <Icon name="library" size={15} />Vai all'album
      </button>
    </Glass>
  </div>
{/if}

<style>
  .menu {
    position: fixed;
    z-index: 80;
    min-width: 248px;
    max-width: 320px;
    border-radius: 18px;
    box-shadow: var(--lfly-shadow-2);
    /* Under the glass: a menu is read over busy content, so it stays legible without the blur. */
    background: color-mix(in srgb, var(--lfly-surface) 86%, transparent);
    animation: open 0.26s var(--lfly-ease-spring);
  }
  @keyframes open {
    from {
      opacity: 0;
      transform: scale(0.88);
    }
  }
  .head {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 8px 8px;
  }
  .head :global(.lfly-cover) {
    border-radius: 8px;
  }
  .head-text {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  .head-title {
    font-weight: 600;
    color: var(--lfly-text);
  }
  .head-sub {
    font-size: 12px;
    color: var(--lfly-text-2);
  }
  button {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 32px;
    padding: 0 10px;
    border-radius: 10px;
    text-align: left;
    font-size: 13px;
    color: var(--lfly-text);
  }
  button :global(svg) {
    flex: none;
    color: var(--lfly-text-2);
  }
  button:hover:not(:disabled),
  button:focus-visible {
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
    outline: none;
  }
  button:hover:not(:disabled) :global(svg),
  button:focus-visible :global(svg) {
    color: inherit;
  }
  button:disabled {
    color: var(--lfly-text-3);
    opacity: 0.6;
    cursor: default;
  }
  .danger {
    color: #ff6b6b;
  }
  .chev {
    margin-left: auto;
    display: grid;
    transition: rotate var(--lfly-dur-fast) var(--lfly-ease);
  }
  .open .chev {
    rotate: 180deg;
  }
  .sub {
    max-height: 220px;
    overflow-y: auto;
    margin: 2px 0 4px 18px;
    padding-left: 8px;
    border-left: 1px solid var(--lfly-separator);
  }
  hr {
    border: 0;
    border-top: 1px solid var(--lfly-separator);
    margin: 5px 8px;
  }
  @media (prefers-reduced-motion: reduce) {
    .menu {
      animation: none;
    }
  }
</style>
