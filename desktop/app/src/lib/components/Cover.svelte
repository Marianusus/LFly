<script lang="ts">
  import Icon from "../Icon.svelte";
  import { cover } from "../connect.svelte";

  let {
    url,
    size = 48,
    alt = "",
    round = false,
    fluid = false,
  }: { url?: string | null; size?: number; alt?: string; round?: boolean; fluid?: boolean } = $props();

  // Remembered per address, so a new cover starts hidden and a failed one shows the note.
  let loadedSrc = $state<string>();
  let failedSrc = $state<string>();
  // Ask the image server for twice the size shown, for sharp covers on HiDPI screens.
  const src = $derived(url ? cover(url, Math.min(1200, Math.round(size * 2))) : undefined);
  // The corner grows with the cover, as on the phone: small thumbs 6–10, big art up to 20.
  const radius = $derived(round ? "50%" : `${Math.round(Math.min(20, Math.max(6, size * 0.16)))}px`);
  const loaded = $derived(!!src && loadedSrc === src);
  const failed = $derived(!!src && failedSrc === src);
</script>

<div
  class="lfly-cover"
  class:round
  class:fluid
  style="--cover-r:{radius};{fluid ? '' : `width:${size}px;height:${size}px`}"
>
  {#if src && !failed}
    <img {src} {alt} class:loaded loading="lazy" draggable="false" onload={() => (loadedSrc = src)} onerror={() => (failedSrc = src)} />
  {:else}
    <span class="empty"><Icon name="music" size={Math.max(14, Math.round(size / 3))} /></span>
  {/if}
</div>

<style>
  .lfly-cover {
    position: relative;
    flex: none;
    border-radius: var(--cover-r);
    overflow: hidden;
    background: linear-gradient(145deg, color-mix(in srgb, var(--lfly-text) 12%, transparent), color-mix(in srgb, var(--lfly-text) 5%, transparent));
    box-shadow: 0 2px 10px rgba(0, 0, 0, 0.22);
  }
  /* A hairline inside the edge keeps dark covers from melting into a dark canvas. */
  .lfly-cover::after {
    content: "";
    position: absolute;
    inset: 0;
    border-radius: inherit;
    box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
    pointer-events: none;
  }
  .fluid {
    width: 100%;
    aspect-ratio: 1;
  }
  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
    opacity: 0;
    transition: opacity var(--lfly-dur-med) var(--lfly-ease);
  }
  img.loaded {
    opacity: 1;
  }
  .empty {
    width: 100%;
    height: 100%;
    display: grid;
    place-items: center;
    color: var(--lfly-text-3);
  }
</style>
