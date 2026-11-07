<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { listen } from "@tauri-apps/api/event";
  import { onDestroy } from "svelte";
  import Icon from "../Icon.svelte";
  import { library } from "../library.svelte";
  import { nav } from "../nav.svelte";
  import { connect } from "../connect.svelte";
  import type { Track } from "../player.svelte";

  let { onclose }: { onclose: () => void } = $props();

  let link = $state("");
  let busy = $state(false);
  let progress = $state({ done: 0, total: 0 });
  let error = $state("");
  let missing = $state<string[]>([]);

  const unlisten = listen<{ done: number; total: number }>("spotify:progress", (e) => (progress = e.payload));
  onDestroy(() => unlisten.then((f) => f()).catch(() => {}));

  async function start(e: SubmitEvent) {
    e.preventDefault();
    if (!link.trim() || busy) return;
    busy = true;
    error = "";
    missing = [];
    progress = { done: 0, total: 0 };
    try {
      const r = await invoke<{ name: string; coverUrl?: string; tracks: Track[]; missing: string[]; truncated: boolean }>(
        "spotify_import",
        { link },
      );
      if (!r.tracks.length) throw new Error("Nessun brano trovato su YouTube Music");
      const p = library.createPlaylist(r.name, r.tracks, r.coverUrl);
      const notes = [
        r.missing.length ? `${r.missing.length} non trovati` : "",
        r.truncated ? "Spotify mostra solo i primi 100 brani" : "",
      ].filter(Boolean);
      connect.say(`"${r.name}" importata: ${r.tracks.length} brani${notes.length ? ` (${notes.join(", ")})` : ""}`);
      missing = r.missing;
      if (!missing.length) onclose();
      nav.go({ page: "playlist", id: p.id, name: p.name, local: true });
    } catch (err) {
      error = String(err instanceof Error ? err.message : err);
    } finally {
      busy = false;
    }
  }
</script>

<form class="box" onsubmit={start}>
  <div class="head">
    <span class="logo" aria-hidden="true"><Icon name="upload" size={17} /></span>
    <strong>Importa da Spotify</strong>
    <button type="button" class="close" aria-label="Chiudi" title="Chiudi" onclick={onclose}><Icon name="close" size={14} /></button>
  </div>
  <p class="help">
    Incolla il link di una playlist, di un album o di un brano (Spotify › Condividi › Copia link). LFly cerca ogni brano su YouTube
    Music e crea una playlist nella tua libreria. Non serve l'account Spotify; la playlist deve essere pubblica.
  </p>
  <div class="row">
    <!-- svelte-ignore a11y_autofocus -->
    <input
      type="url"
      placeholder="https://open.spotify.com/playlist/…"
      bind:value={link}
      disabled={busy}
      autofocus
      aria-label="Link di Spotify"
    />
    <button class="lfly-btn lfly-btn-primary go" type="submit" disabled={busy || !link.trim()}>
      {busy ? (progress.total ? `${progress.done} di ${progress.total}…` : "Leggo…") : "Importa"}
    </button>
  </div>
  {#if busy && progress.total}
    <div class="bar" role="progressbar" aria-label="Brani cercati" aria-valuemin={0} aria-valuemax={progress.total} aria-valuenow={progress.done}>
      <span style="width:{(progress.done / progress.total) * 100}%"></span>
    </div>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  {#if missing.length}
    <details>
      <summary>{missing.length} brani non trovati su YouTube Music</summary>
      <ul>{#each missing as m}<li>{m}</li>{/each}</ul>
    </details>
  {/if}
</form>

<style>
  .box {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 18px 20px 20px;
    margin-top: 16px;
    max-width: 720px;
    border-radius: 24px;
    background: var(--lfly-glass-tint);
    box-shadow:
      inset 0 0 0 1px var(--lfly-glass-border),
      inset 0 1px 0 var(--lfly-glass-highlight),
      var(--lfly-shadow-1);
    animation: rise 0.36s var(--lfly-ease-spring);
  }
  @keyframes rise {
    from {
      opacity: 0;
      transform: translateY(8px) scale(0.98);
    }
  }
  .head {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .head strong {
    font: 600 16px/1.2 var(--lfly-font-display);
    color: var(--lfly-text);
  }
  .logo {
    width: 30px;
    height: 30px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: #fff;
    background: linear-gradient(140deg, #1ed760, #128a3e);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.3);
  }
  .close {
    margin-left: auto;
    width: 28px;
    height: 28px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: var(--lfly-text-2);
    background: color-mix(in srgb, var(--lfly-text) 8%, transparent);
  }
  .close:hover {
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 14%, transparent);
  }
  .help {
    margin: 0;
    color: var(--lfly-text-2);
    line-height: 1.5;
  }
  .row {
    display: flex;
    gap: 8px;
  }
  input {
    flex: 1;
    min-width: 0;
    height: 40px;
    padding: 0 16px;
    border-radius: 999px;
    border: 0;
    outline: none;
    background: color-mix(in srgb, var(--lfly-text) 8%, transparent);
    box-shadow: inset 0 0 0 1px var(--lfly-glass-border);
    color: var(--lfly-text);
    user-select: text;
    transition: box-shadow var(--lfly-dur-fast) var(--lfly-ease);
  }
  input::placeholder {
    color: var(--lfly-text-3);
  }
  input:focus {
    box-shadow:
      inset 0 0 0 1.5px var(--lfly-accent),
      0 0 0 4px color-mix(in srgb, var(--lfly-accent) 22%, transparent);
  }
  .go {
    min-height: 40px;
    padding: 0 20px;
  }
  .go:disabled {
    opacity: 0.5;
  }
  .bar {
    height: 5px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--lfly-text) 12%, transparent);
    overflow: hidden;
  }
  .bar span {
    display: block;
    height: 100%;
    border-radius: inherit;
    background: var(--lfly-accent);
    transition: width var(--lfly-dur-med) var(--lfly-ease);
  }
  .error {
    margin: 0;
    color: #ff6b6b;
  }
  details {
    color: var(--lfly-text-2);
    font-size: 13px;
  }
  summary {
    cursor: pointer;
  }
  ul {
    margin: 6px 0 0;
    padding-left: 18px;
    max-height: 160px;
    overflow: auto;
    user-select: text;
  }
  @media (prefers-reduced-motion: reduce) {
    .box {
      animation: none;
    }
  }
</style>
