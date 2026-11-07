<script lang="ts">
  import { tick } from "svelte";
  import { fade } from "svelte/transition";
  import { themes } from "../theme/theme.svelte";
  import Icon from "../Icon.svelte";
  import Cover from "./Cover.svelte";
  import LflyMark from "./LflyMark.svelte";
  import { nativeControls } from "./WindowControls.svelte";
  import Glass from "../glass/Glass.svelte";
  import SelectionPill from "./SelectionPill.svelte";
  import { nav, type Route } from "../nav.svelte";
  import { ui } from "../ui.svelte";
  import { library } from "../library.svelte";
  import { account } from "../account.svelte";
  import { downloads } from "../downloads.svelte";

  const wide = $derived(ui.sideWide);
  const downloading = $derived(Object.keys(downloads.progress).length);
  const showDownloads = $derived(downloads.tracks.length > 0 || downloading > 0);

  /** Which item the route belongs to, so the pill knows where to stand. */
  function keyOf(r: Route): string {
    if (r.page === "playlist") return `playlist:${r.id}`;
    if (r.page === "settings") return "settings";
    return r.page;
  }
  const key = $derived(keyOf(nav.route));

  const tabs = [
    { key: "home", label: "Home", icon: "home", go: { page: "home" } as Route },
    { key: "search", label: "Cerca", icon: "search", go: { page: "search" } as Route },
    { key: "library", label: "Libreria", icon: "library", go: { page: "library" } as Route },
  ];

  /** The list is scrolled: its top darkens under a soft blur, like the window's top bar. */
  let listScrolled = $state(false);
  let tabBox = $state<HTMLElement>();
  let listBox = $state<HTMLElement>();
  let footBox = $state<HTMLElement>();
  let tabActive = $state<HTMLElement | null>(null);
  let listActive = $state<HTMLElement | null>(null);
  let footActive = $state<HTMLElement | null>(null);

  $effect(() => {
    const k = key;
    // Re-measure when the lists or the width change.
    library.playlists.length;
    account.playlists.length;
    showDownloads;
    wide;
    tick().then(() => {
      const find = (box?: HTMLElement) => box?.querySelector<HTMLElement>(`[data-key="${CSS.escape(k)}"]`) ?? null;
      tabActive = find(tabBox);
      listActive = find(listBox);
      footActive = find(footBox);
    });
  });
</script>

<Glass as="nav" radius={28} class="lfly-sidebar" aria-label="Navigazione" style="height:100%;width:100%;overflow:hidden">
  <div class="sb" class:narrow={!wide}>
    <div class="brand" class:mac={nativeControls} data-tauri-drag-region>
      <span class="mark" aria-hidden="true"><LflyMark size={wide ? 28 : 30} /></span>
      <span class="word" aria-hidden={!wide}>LFly</span>
      {#if !ui.compact && !(ui.sidebarWide && !ui.sideWide)}
        <button
          class="lfly-icon-btn toggle"
          aria-label={wide ? "Riduci la barra laterale" : "Allarga la barra laterale"}
          title={wide ? "Riduci la barra laterale" : "Allarga la barra laterale"}
          onclick={() => ui.toggleSidebar()}
        >
          <Icon name="sidebar" size={17} />
        </button>
      {/if}
    </div>

    <ul class="group tabs" bind:this={tabBox}>
      <SelectionPill active={tabActive} />
      {#each tabs as t (t.key)}
        <li>
          <button
            class="item"
            class:active={key === t.key}
            data-key={t.key}
            aria-current={key === t.key ? "page" : undefined}
            title={t.label}
            onclick={() => nav.go(t.go)}
          >
            <span class="ic"><Icon name={t.icon} size={20} fill={key === t.key && t.icon === "home"} /></span>
            <span class="label">{t.label}</span>
          </button>
        </li>
      {/each}
    </ul>

    <div class="section">
      <span class="caption">Playlist</span>
      <button class="lfly-icon-btn mini" tabindex={wide ? 0 : -1} aria-label="Apri la libreria" title="Apri la libreria" onclick={() => nav.go({ page: "library" })}>
        <Icon name="expand" size={13} />
      </button>
    </div>

    <div class="scroll" class:scrolled={listScrolled} onscroll={(e) => (listScrolled = e.currentTarget.scrollTop > 4)}>
      <div class="edge" class:on={listScrolled} aria-hidden="true"></div>
      <ul class="group entries" bind:this={listBox}>
        <SelectionPill active={listActive} />
        <li>
          <button
            class="entry"
            class:active={key === "liked"}
            data-key="liked"
            aria-current={key === "liked" ? "page" : undefined}
            onclick={() => nav.go({ page: "liked" })}
            title="Brani che ti piacciono"
          >
            <span class="art liked"><Icon name="heart" size={17} fill /></span>
              <span class="text">
                <span class="ellipsis name">Brani che ti piacciono</span>
                <span class="sub ellipsis">Playlist · {library.favorites.length} brani</span>
              </span>
          </button>
        </li>
        {#if showDownloads}
          <li>
            <button
              class="entry"
              class:active={key === "downloads"}
              data-key="downloads"
              aria-current={key === "downloads" ? "page" : undefined}
              onclick={() => nav.go({ page: "downloads" })}
              title="Scaricati"
            >
              <span class="art dl"><Icon name="download" size={17} /></span>
                <span class="text">
                  <span class="ellipsis name">Scaricati</span>
                  <span class="sub ellipsis">{downloading ? `In download: ${downloading}` : `${downloads.tracks.length} brani`}</span>
                </span>
            </button>
          </li>
        {/if}
        {#each library.playlists as p (p.id)}
          <li>
            <button
              class="entry"
              class:active={key === `playlist:${p.id}`}
              data-key="playlist:{p.id}"
              aria-current={key === `playlist:${p.id}` ? "page" : undefined}
              onclick={() => nav.go({ page: "playlist", id: p.id, name: p.name, local: true })}
              title={p.name}
            >
              <Cover url={p.coverUrl ?? p.tracks[0]?.coverUrl} size={40} />
                <span class="text">
                  <span class="ellipsis name">{p.name}</span>
                  <span class="sub ellipsis">Playlist · {p.tracks.length} brani</span>
                </span>
            </button>
          </li>
        {/each}
        {#each account.playlists as p (p.id)}
          <li>
            <button
              class="entry"
              class:active={key === `playlist:${p.id}`}
              data-key="playlist:{p.id}"
              aria-current={key === `playlist:${p.id}` ? "page" : undefined}
              onclick={() => nav.go({ page: "playlist", id: p.id, name: p.title })}
              title={p.title}
            >
              <Cover url={p.coverUrl} size={40} />
                <span class="text">
                  <span class="ellipsis name">{p.title}</span>
                  <span class="sub ellipsis">{p.subtitle ?? "Playlist"}</span>
                </span>
            </button>
          </li>
        {/each}
      </ul>

      {#if wide && library.playlists.length === 0 && !account.info}
        <div class="invite" transition:fade={{ duration: themes.reduceMotion ? 0 : 200 }}>
          <strong>Porta qui la tua musica</strong>
          <span>Importa il backup .lfly del telefono: playlist, preferiti e cronologia.</span>
          <button class="lfly-btn lfly-btn-primary" onclick={() => library.importBackup()}>Importa backup</button>
        </div>
      {/if}
    </div>

    <ul class="group foot" bind:this={footBox}>
      <SelectionPill active={footActive} />
      <li>
        <button
          class="item"
          class:active={key === "settings"}
          data-key="settings"
          aria-current={key === "settings" ? "page" : undefined}
          onclick={() => nav.go({ page: "settings" })}
          title="Impostazioni"
        >
          <span class="ic"><Icon name="settings" size={19} /></span>
          <span class="label">Impostazioni</span>
        </button>
      </li>
    </ul>
  </div>
</Glass>

<style>
  .sb {
    height: 100%;
    display: flex;
    flex-direction: column;
    padding: 14px 10px 10px;
    min-height: 0;
  }
  /* Collapsing keeps every icon where it is: the sidebar narrows over them and the words fade. */
  .sb {
    --fold: 460ms cubic-bezier(0.32, 0.72, 0, 1);
  }
  .brand {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 2px 8px 14px;
    min-height: 44px;
  }
  /* macOS draws its traffic lights over the top-left corner. */
  .brand.mac {
    padding-top: 30px;
  }
  .mark {
    width: 32px;
    height: 32px;
    flex: none;
    display: grid;
    place-items: center;
    color: var(--lfly-text);
    filter: drop-shadow(0 2px 8px color-mix(in srgb, var(--lfly-accent) 35%, transparent));
  }
  .mark {
    transition:
      opacity 180ms var(--lfly-ease),
      scale 180ms var(--lfly-ease);
  }
  .word {
    font: 700 20px/1 var(--lfly-font-display);
    letter-spacing: -0.02em;
    color: var(--lfly-text);
    white-space: nowrap;
  }
  .word,
  .label,
  .text,
  .caption,
  .section .mini {
    transition:
      opacity 260ms var(--lfly-ease) 120ms,
      translate var(--fold);
  }
  .narrow .word,
  .narrow .label,
  .narrow .text,
  .narrow .caption,
  .narrow .section .mini {
    opacity: 0;
    translate: -6px 0;
    pointer-events: none;
    transition:
      opacity 120ms var(--lfly-ease),
      translate var(--fold);
  }
  .toggle {
    margin-left: auto;
    color: var(--lfly-text-2);
    transition:
      background var(--lfly-dur-fast) var(--lfly-ease),
      color var(--lfly-dur-fast) var(--lfly-ease);
  }
  /* Folded, the toggle waits under the logo and shows on hover. */
  .narrow .toggle {
    position: absolute;
    left: 8px;
    top: 2px;
    width: 32px;
    height: 32px;
    margin: 0;
    opacity: 0;
    background: var(--lfly-glass-tint);
  }
  .narrow .brand:hover .toggle,
  .narrow .toggle:focus-visible {
    opacity: 1;
  }
  .narrow .brand:hover .mark {
    opacity: 0;
    scale: 0.8;
  }
  .toggle:hover,
  .mini:hover {
    background: var(--lfly-glass-tint);
    color: var(--lfly-text);
  }

  .group {
    position: relative;
    list-style: none;
    margin: 0;
    padding: 0 4px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .item,
  .entry {
    /* The pill measures the buttons against the list, so the rows stay unpositioned. */
    position: relative;
    z-index: 1;
    width: 100%;
    display: flex;
    align-items: center;
    text-align: left;
    color: var(--lfly-text-2);
    border-radius: 999px;
    transition:
      color var(--lfly-dur-fast) var(--lfly-ease),
      background var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-fast) var(--lfly-ease);
  }
  .item {
    gap: 12px;
    height: 40px;
    padding: 0 14px;
    font: 600 14px/1 var(--lfly-font);
  }
  .item:hover:not(.active),
  .entry:hover:not(.active) {
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 6%, transparent);
  }
  .item:active,
  .entry:active {
    scale: 0.97;
  }
  .item.active {
    color: var(--lfly-text);
  }
  .item.active .ic {
    color: var(--lfly-accent);
  }
  .ic {
    display: grid;
    place-items: center;
    width: 22px;
    flex: none;
  }
  .label {
    white-space: nowrap;
  }

  .section {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 18px 12px 6px 18px;
    white-space: nowrap;
  }
  .section::after {
    content: "";
    position: absolute;
    left: 14px;
    right: 14px;
    top: 50%;
    height: 1px;
    background: var(--lfly-separator);
    opacity: 0;
    transition: opacity 200ms var(--lfly-ease);
  }
  .narrow .section::after {
    opacity: 1;
  }
  .caption {
    font: 600 11px/1 var(--lfly-font);
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--lfly-text-3);
  }
  .mini {
    width: 26px;
    height: 26px;
    color: var(--lfly-text-3);
  }
  .scroll {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    overflow-x: hidden;
    margin: 0 -4px;
    padding: 2px 4px 8px;
    mask-image: linear-gradient(to bottom, transparent, #000 8px, #000 calc(100% - 16px), transparent);
  }
  /* Scrolled: the rows dissolve under the top of the list, as under the window's top bar. */
  .scroll.scrolled {
    -webkit-mask-image: linear-gradient(to bottom, transparent, #000 44px, #000 calc(100% - 16px), transparent);
    mask-image: linear-gradient(to bottom, transparent, #000 44px, #000 calc(100% - 16px), transparent);
  }
  /* Sticky and zero-height: the band hangs over the rows that scroll under it. */
  .edge {
    position: sticky;
    top: -2px;
    z-index: 3;
    height: 0;
    pointer-events: none;
  }
  .edge::before {
    content: "";
    position: absolute;
    left: -4px;
    right: -4px;
    top: 0;
    height: 46px;
    opacity: 0;
    background: linear-gradient(to bottom, color-mix(in srgb, var(--lfly-bg) 70%, transparent), transparent);
    -webkit-mask-image: linear-gradient(to bottom, #000 40%, transparent);
    mask-image: linear-gradient(to bottom, #000 40%, transparent);
    transition: opacity var(--lfly-dur-med, 260ms) var(--lfly-ease);
  }
  .edge.on::before {
    opacity: 1;
  }
  .scroll::-webkit-scrollbar {
    width: 0;
  }
  .entries {
    gap: 2px;
  }
  .entry {
    gap: 12px;
    padding: 6px;
    border-radius: 16px;
  }
  .entry.active .name {
    color: var(--lfly-text);
  }
  .art {
    flex: none;
    width: 40px;
    height: 40px;
    border-radius: 11px;
    display: grid;
    place-items: center;
    color: #fff;
  }
  .liked {
    background: linear-gradient(140deg, color-mix(in srgb, var(--lfly-accent) 85%, #fff) 0%, var(--lfly-accent) 45%, color-mix(in srgb, var(--lfly-accent) 55%, #3a0ca3) 100%);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.3);
  }
  .dl {
    background: linear-gradient(140deg, #34c3ff, #2563eb);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.3);
  }
  .entry :global(.lfly-cover) {
    border-radius: 11px;
  }
  .text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }
  .name {
    font: 500 13.5px/1.25 var(--lfly-font);
    color: var(--lfly-text);
  }
  .sub {
    font-size: 12px;
    color: var(--lfly-text-3);
  }

  .invite {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
    margin: 12px 4px 4px;
    padding: 16px;
    border-radius: 20px;
    background: var(--lfly-glass-tint);
    border: 1px solid var(--lfly-glass-border);
    line-height: 1.4;
  }
  .invite strong {
    font-size: 14px;
    color: var(--lfly-text);
  }
  .invite span {
    color: var(--lfly-text-2);
    font-size: 12.5px;
  }
  .invite button {
    margin-top: 4px;
  }

  .foot {
    padding-top: 8px;
    margin-top: 4px;
    border-top: 1px solid var(--lfly-separator);
  }
</style>
