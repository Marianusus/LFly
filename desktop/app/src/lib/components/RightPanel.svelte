<script lang="ts">
  import { tick } from "svelte";
  import { fly } from "svelte/transition";
  import Icon from "../Icon.svelte";
  import Cover from "./Cover.svelte";
  import Glass from "../glass/Glass.svelte";
  import { themes } from "../theme/theme.svelte";
  import SelectionPill from "./SelectionPill.svelte";
  import WindowControls, { nativeControls } from "./WindowControls.svelte";
  import { ui } from "../ui.svelte";
  import { player } from "../player.svelte";
  import { connect, time, type ConnectDevice } from "../connect.svelte";
  import { menu } from "../menu.svelte";
  import LyricsLines from "../pages/parts/LyricsLines.svelte";

  const kindLabel: Record<string, string> = {
    phone: "Telefono",
    tablet: "Tablet",
    desktop: "Computer",
    tv: "TV",
    watch: "Orologio",
  };
  const reduce = $derived(themes.reduceMotion);

  const upcoming = $derived(player.queue.map((t, i) => ({ t, i })).filter(({ i }) => i > player.index));

  function pick(d: ConnectDevice) {
    if (connect.following?.id !== d.id) connect.follow(d);
  }

  /** The queue or devices list is scrolled: its top darkens under a soft blur, like the top bar. */
  let bodyScrolled = $state(false);
  let topH = $state(96);
  $effect(() => {
    void ui.panel;
    bodyScrolled = false;
  });
  let seg = $state<HTMLElement>();
  let segActive = $state<HTMLElement | null>(null);
  $effect(() => {
    const p = ui.panel;
    tick().then(() => (segActive = seg?.querySelector<HTMLElement>(`[data-key="${p}"]`) ?? null));
  });
</script>

<Glass as="aside" radius={28} class="lfly-panel" aria-label={ui.panel === "queue" ? "Coda" : ui.panel === "lyrics" ? "Testo" : "Dispositivi"} style="height:100%;overflow:hidden">
  <div class="panel">
    <!-- The header floats over the list, as the window's top bar over the page: rows scroll under
         it and it darkens and blurs them. -->
    <div class="top" class:scrolled={bodyScrolled && ui.panel !== "lyrics"} bind:clientHeight={topH}>
    <div class="fog" aria-hidden="true"></div>
    {#if !nativeControls}<div class="win" data-tauri-drag-region><WindowControls /></div>{/if}
    <header data-tauri-drag-region>
      <div class="seg" role="tablist" aria-label="Pannello" bind:this={seg}>
        <SelectionPill active={segActive} tone="neutral" />
        <button role="tab" data-key="queue" aria-selected={ui.panel === "queue"} class:on={ui.panel === "queue"} onclick={() => (ui.panel = "queue")}>
          Coda
        </button>
        <button role="tab" data-key="lyrics" aria-selected={ui.panel === "lyrics"} class:on={ui.panel === "lyrics"} onclick={() => (ui.panel = "lyrics")}>
          Testo
        </button>
        <button role="tab" data-key="devices" aria-selected={ui.panel === "devices"} class:on={ui.panel === "devices"} onclick={() => (ui.panel = "devices")}>
          Dispositivi
        </button>
      </div>
      <button class="lfly-icon-btn close" aria-label="Chiudi il pannello" title="Chiudi il pannello" onclick={() => (ui.panel = null)}><Icon name="close" size={15} /></button>
    </header>
    </div>

    <div class="body" style="--top:{topH}px" onscroll={(e) => (bodyScrolled = e.currentTarget.scrollTop > 4)}>
      {#key ui.panel}
        <div class="pane" in:fly={{ y: 10, duration: reduce ? 0 : 260 }}>
          {#if ui.panel === "lyrics"}
            <div class="lyr"><LyricsLines variant="side" /></div>
          {:else if ui.panel === "queue"}
            {#if player.track}
              <h3>In riproduzione</h3>
              <div class="now">
                <Cover url={player.track.coverUrl} size={56} />
                <div class="q-text">
                  <span class="ellipsis now-title">{player.track.title}</span>
                  <span class="ellipsis sub">{player.track.artist}</span>
                </div>
                {#if player.playing}
                  <span class="eq" aria-hidden="true"><i></i><i></i><i></i></span>
                {/if}
              </div>
              <h3>Prossimi brani <span class="count">{upcoming.length || ""}</span></h3>
              {#if upcoming.length === 0}
                <p class="hint">Niente in coda dopo questo brano.</p>
              {:else}
                <ol>
                  {#each upcoming as { t, i } (i + t.id)}
                    <li>
                      <div
                        class="q-row"
                        role="button"
                        tabindex="0"
                        title="Riproduci ora"
                        ondblclick={() => player.skipTo(i)}
                        onkeydown={(e) => e.key === "Enter" && player.skipTo(i)}
                        oncontextmenu={(e) => {
                          e.preventDefault();
                          menu.openTrack(e, t, () => player.removeAt(i));
                        }}
                      >
                        <button class="q-cover" aria-label="Riproduci {t.title}" onclick={() => player.skipTo(i)}>
                          <Cover url={t.coverUrl} size={42} />
                          <span class="q-play"><Icon name="play" size={15} /></span>
                        </button>
                        <div class="q-text">
                          <span class="ellipsis">{t.title}</span>
                          <span class="ellipsis sub">{t.artist}</span>
                        </div>
                        <span class="sub dur">{t.durationMs ? time(t.durationMs) : ""}</span>
                        <button class="rm" aria-label="Togli {t.title} dalla coda" title="Togli dalla coda" onclick={() => player.removeAt(i)}>
                          <Icon name="close" size={12} />
                        </button>
                      </div>
                    </li>
                  {/each}
                </ol>
              {/if}
            {:else}
              <div class="empty">
                <span class="empty-ic"><Icon name="queue" size={26} /></span>
                <p>La coda è vuota. Fai partire un brano o una playlist.</p>
              </div>
            {/if}
          {:else}
            <div class="device here" class:active={!connect.following}>
              <span class="d-icon"><Icon name="desktop" size={20} /></span>
              <span class="d-text">
                <span class="d-name">Questo computer</span>
                <span class="sub">
                  {connect.following
                    ? "La musica suona altrove"
                    : connect.controllers > 0
                      ? `In ascolto qui · ${connect.controllers === 1 ? "1 dispositivo" : `${connect.controllers} dispositivi`} ti controllano`
                      : "In ascolto qui"}
                </span>
              </span>
              {#if connect.following}
                <button class="lfly-btn lfly-btn-primary small" onclick={() => player.playHere()}>Ascolta qui</button>
              {:else if player.playing}
                <span class="eq" aria-hidden="true"><i></i><i></i><i></i></span>
              {/if}
            </div>

            {#if connect.following}
              <button class="link-out" onclick={() => connect.disconnect()}>Smetti di controllare {connect.following.name}</button>
            {/if}
            <h3>Altri dispositivi</h3>
            {#if connect.devices.length === 0}
              <p class="hint searching"><span class="pulse" aria-hidden="true"></span>Cerco LFly nella rete…</p>
              <p class="hint small">Apri LFly su un telefono o un computer collegato allo stesso Wi-Fi.</p>
            {:else}
              <ul>
                {#each connect.devices as d (d.id)}
                  <li>
                    <button class="device" class:active={connect.following?.id === d.id} onclick={() => pick(d)}>
                      <span class="d-icon"><Icon name={d.kind} size={20} /></span>
                      <span class="d-text">
                        <span class="d-name ellipsis">{d.name}</span>
                        <span class="sub">{kindLabel[d.kind] ?? "Dispositivo"}{d.sameAccount ? " · il tuo account" : ""}</span>
                      </span>
                      {#if connect.connecting === d.id}
                        <span class="spinner" role="status" aria-label="Collegamento in corso"></span>
                      {:else if connect.following?.id === d.id}
                        <span class="check"><Icon name="check" size={14} /></span>
                      {/if}
                    </button>
                  </li>
                {/each}
              </ul>
            {/if}
          {/if}
        </div>
      {/key}
    </div>
  </div>
</Glass>

<style>
  .panel {
    position: relative;
    height: 100%;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .top {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    z-index: 4;
  }
  /* The same veil as the window's top bar when the page scrolls under it. */
  .fog {
    position: absolute;
    inset: 0 0 -26px;
    z-index: -1;
    pointer-events: none;
    opacity: 0;
    background: linear-gradient(to bottom, color-mix(in srgb, var(--lfly-bg) 70%, transparent), transparent);
    -webkit-mask-image: linear-gradient(to bottom, #000 55%, transparent);
    mask-image: linear-gradient(to bottom, #000 55%, transparent);
    transition: opacity var(--lfly-dur-med, 260ms) var(--lfly-ease);
  }
  .top.scrolled .fog {
    opacity: 1;
  }
  .win {
    display: flex;
    justify-content: flex-end;
    padding: 10px 12px 0;
  }
  header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 12px 8px;
  }
  .seg {
    position: relative;
    flex: 1;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    padding: 3px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--lfly-text) 7%, transparent);
  }
  .seg button {
    position: relative;
    z-index: 1;
    height: 30px;
    min-width: 0;
    padding: 0 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    border-radius: 999px;
    font: 600 12.5px/1 var(--lfly-font);
    color: var(--lfly-text-2);
    transition: color var(--lfly-dur-fast) var(--lfly-ease);
  }
  .seg button.on,
  .seg button:hover {
    color: var(--lfly-text);
  }
  .close {
    flex: none;
    color: var(--lfly-text-2);
    background: color-mix(in srgb, var(--lfly-text) 7%, transparent);
  }
  .close:hover {
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 14%, transparent);
  }
  /* Rows dissolve as they pass under the header (a backdrop blur cannot be nested inside the
     panel's own glass, so the list fades itself), and the veil above darkens what is left. */
  .body {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: var(--top, 96px) 8px 16px;
    scroll-padding-top: var(--top, 96px);
    -webkit-mask-image: linear-gradient(to bottom, transparent calc(var(--top, 96px) - 40px), #000 calc(var(--top, 96px) + 10px));
    mask-image: linear-gradient(to bottom, transparent calc(var(--top, 96px) - 40px), #000 calc(var(--top, 96px) + 10px));
  }
  /* The lyrics scroll inside themselves: the panel body only gives them its height. */
  .body:has(.lyr) {
    overflow: hidden;
    padding: var(--top, 96px) 0 0;
    display: flex;
  }
  .body:has(.lyr) .pane {
    flex: 1;
    min-height: 0;
  }
  .lyr {
    height: 100%;
  }
  h3 {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font: 600 11px/1 var(--lfly-font);
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--lfly-text-3);
    margin: 18px 10px 10px;
  }
  .count {
    font-variant-numeric: tabular-nums;
  }
  ol,
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .now {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px;
    border-radius: 20px;
    background: color-mix(in srgb, var(--lfly-accent) 14%, var(--lfly-glass-tint));
    border: 1px solid color-mix(in srgb, var(--lfly-accent) 30%, transparent);
  }
  .now :global(.lfly-cover) {
    border-radius: 12px;
  }
  .now-title {
    font-weight: 600;
    color: var(--lfly-text);
  }
  .q-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 8px;
    border-radius: 16px;
    transition: background var(--lfly-dur-fast) var(--lfly-ease);
  }
  .q-row:hover,
  .q-row:focus-visible {
    background: color-mix(in srgb, var(--lfly-text) 7%, transparent);
  }
  .q-cover {
    position: relative;
    flex: none;
    border-radius: 10px;
  }
  .q-cover :global(.lfly-cover) {
    border-radius: 10px;
  }
  .q-play {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    background: rgba(0, 0, 0, 0.45);
    color: #fff;
    border-radius: 10px;
    opacity: 0;
    transition: opacity var(--lfly-dur-fast) var(--lfly-ease);
  }
  .q-row:hover .q-play,
  .q-cover:focus-visible .q-play {
    opacity: 1;
  }
  .q-text {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;
    flex: 1;
    color: var(--lfly-text);
  }
  .sub {
    color: var(--lfly-text-2);
    font-size: 12px;
  }
  .dur {
    font-variant-numeric: tabular-nums;
    color: var(--lfly-text-3);
  }
  .rm {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    flex: none;
    border-radius: 50%;
    color: var(--lfly-text-2);
    opacity: 0;
    transition:
      opacity var(--lfly-dur-fast) var(--lfly-ease),
      background var(--lfly-dur-fast) var(--lfly-ease);
  }
  .rm:hover {
    background: color-mix(in srgb, var(--lfly-text) 12%, transparent);
    color: var(--lfly-text);
  }
  .q-row:hover .rm,
  .rm:focus-visible {
    opacity: 1;
  }

  /* The playing animation: three bars, the phone's GraphicEq. */
  .eq {
    display: inline-flex;
    align-items: flex-end;
    gap: 2px;
    height: 14px;
    flex: none;
    margin-right: 4px;
  }
  .eq i {
    width: 3px;
    height: 100%;
    border-radius: 2px;
    background: var(--lfly-accent);
    transform-origin: bottom;
    animation: bars 0.9s ease-in-out infinite;
  }
  .eq i:nth-child(2) {
    animation-delay: -0.3s;
  }
  .eq i:nth-child(3) {
    animation-delay: -0.6s;
  }
  @keyframes bars {
    0%,
    100% {
      transform: scaleY(0.3);
    }
    50% {
      transform: scaleY(1);
    }
  }

  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    gap: 10px;
    padding: 48px 20px;
    color: var(--lfly-text-2);
    line-height: 1.45;
  }
  .empty-ic {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    background: var(--lfly-glass-tint);
    border: 1px solid var(--lfly-glass-border);
  }
  .empty p {
    margin: 0;
  }
  .hint {
    color: var(--lfly-text-2);
    margin: 8px 10px;
    line-height: 1.45;
  }
  .hint.small {
    font-size: 12px;
    color: var(--lfly-text-3);
  }
  .searching {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .pulse {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--lfly-accent);
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--lfly-accent) 60%, transparent);
    animation: ping 1.6s ease-out infinite;
  }
  @keyframes ping {
    100% {
      box-shadow: 0 0 0 10px transparent;
    }
  }

  .device {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px;
    border-radius: 18px;
    text-align: left;
    color: var(--lfly-text);
    transition:
      background var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-fast) var(--lfly-ease);
  }
  button.device:hover {
    background: color-mix(in srgb, var(--lfly-text) 7%, transparent);
  }
  button.device:active {
    scale: 0.98;
  }
  .here {
    margin-top: 6px;
    background: var(--lfly-glass-tint);
    border: 1px solid var(--lfly-glass-border);
  }
  .here.active {
    background: color-mix(in srgb, var(--lfly-accent) 14%, var(--lfly-glass-tint));
    border-color: color-mix(in srgb, var(--lfly-accent) 30%, transparent);
  }
  .d-icon {
    width: 40px;
    height: 40px;
    flex: none;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 9%, transparent);
  }
  .device.active .d-icon {
    color: var(--lfly-on-accent);
    background: var(--lfly-accent);
  }
  .d-text {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;
    flex: 1;
  }
  .d-name {
    font-weight: 600;
    font-size: 14px;
  }
  .check {
    width: 22px;
    height: 22px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    color: var(--lfly-on-accent);
    background: var(--lfly-accent);
  }
  .link-out {
    margin: 8px 10px 0;
    color: var(--lfly-text-2);
    font-size: 12px;
    text-decoration: underline;
    text-underline-offset: 2px;
  }
  .link-out:hover {
    color: var(--lfly-text);
  }
  .small {
    min-height: 28px;
    padding: 0 12px;
    font-size: 12px;
  }
  .spinner {
    width: 16px;
    height: 16px;
    border: 2px solid color-mix(in srgb, var(--lfly-text) 22%, transparent);
    border-top-color: var(--lfly-text);
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .eq i,
    .pulse {
      animation: none;
    }
  }
</style>
