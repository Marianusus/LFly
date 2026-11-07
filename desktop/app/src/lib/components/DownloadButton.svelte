<script lang="ts">
  import Icon from "../Icon.svelte";
  import { downloads } from "../downloads.svelte";
  import type { Track } from "../player.svelte";

  let { tracks }: { tracks: Track[] } = $props();

  const done = $derived(tracks.filter((t) => downloads.has(t.id)).length);
  const going = $derived(tracks.filter((t) => downloads.pending(t.id)).length);
  const all = $derived(tracks.length > 0 && done === tracks.length);
  const label = $derived(
    all ? "Rimuovi i download" : going ? `Download in corso: ${done} di ${tracks.length}` : "Scarica per ascoltare offline",
  );
  // A ring that fills as the songs land, like the phone's DownloadIconButton.
  const R = 19;
  const C = 2 * Math.PI * R;
  const frac = $derived(tracks.length ? done / tracks.length : 0);
</script>

<span class="wrap">
<button
  class="dl"
  class:all
  class:going={going > 0}
  aria-label={label}
  title={label}
  disabled={!tracks.length}
  onclick={() => (all ? downloads.remove(tracks.map((t) => t.id)) : downloads.add(tracks))}
>
  {#if going}
    <svg class="ring" viewBox="0 0 44 44" aria-hidden="true">
      <circle cx="22" cy="22" r={R} class="ring-bg" />
      <circle cx="22" cy="22" r={R} class="ring-fg" stroke-dasharray={C} stroke-dashoffset={C * (1 - frac)} />
    </svg>
  {/if}
  <Icon name={all ? "check" : "download"} size={20} />
</button>
{#if going}<span class="count" aria-hidden="true">{done}/{tracks.length}</span>{/if}
</span>

<style>
  .dl {
    position: relative;
    display: inline-grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: 50%;
    color: var(--lfly-text);
    background: var(--lfly-glass-tint);
    box-shadow:
      inset 0 0 0 1px var(--lfly-glass-border),
      inset 0 1px 0 color-mix(in srgb, var(--lfly-glass-highlight) 60%, transparent);
    transition:
      background var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-fast) var(--lfly-ease-spring);
  }
  .dl:hover:not(:disabled) {
    background: color-mix(in srgb, var(--lfly-text) 14%, transparent);
  }
  .dl:active:not(:disabled) {
    scale: 0.92;
  }
  .dl:disabled {
    opacity: 0.4;
  }
  .dl.all {
    color: var(--lfly-on-accent);
    background: var(--lfly-accent);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.3);
  }
  .ring {
    position: absolute;
    inset: 0;
    rotate: -90deg;
  }
  .ring circle {
    fill: none;
    stroke-width: 2.5;
  }
  .ring-bg {
    stroke: color-mix(in srgb, var(--lfly-text) 14%, transparent);
  }
  .ring-fg {
    stroke: var(--lfly-accent);
    stroke-linecap: round;
    transition: stroke-dashoffset var(--lfly-dur-med) var(--lfly-ease);
  }
  .wrap {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }
  .count {
    white-space: nowrap;
    font-size: 11.5px;
    font-variant-numeric: tabular-nums;
    color: var(--lfly-text-2);
  }
</style>
