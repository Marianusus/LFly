<!--
  The app canvas, once, behind everything: Nero AMOLED, Bianco, the dynamic gradients from the
  playing cover, or the cover itself blurred and flowing (Copertina dinamica). The glass lenses
  refract this same picture (scene.ts).
-->
<script lang="ts">
  import { onMount } from "svelte";
  import { scene } from "./scene";
  import { themes } from "../theme/theme.svelte";

  let host: HTMLDivElement;

  onMount(() => {
    const canvas = scene().canvas;
    canvas.className = "lfly-backdrop-canvas";
    host.prepend(canvas);
    return () => canvas.remove();
  });

  const grain = $derived(themes.active == null && themes.mode !== "AMOLED_BLACK" && themes.mode !== "PURE_WHITE");
</script>

<div class="lfly-backdrop" bind:this={host} aria-hidden="true">
  {#if grain}<div class="grain"></div>{/if}
</div>

<style>
  .lfly-backdrop {
    position: fixed;
    inset: 0;
    z-index: -1;
    pointer-events: none;
    overflow: hidden;
    background: var(--lfly-bg);
    contain: strict;
  }
  .lfly-backdrop :global(.lfly-backdrop-canvas) {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    display: block;
  }
  /* A whisper of grain: no banding in the soft gradients, and the canvas reads as a material. */
  .grain {
    position: absolute;
    inset: 0;
    opacity: 0.05;
    mix-blend-mode: overlay;
    background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='160' height='160'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 0.5 0 0 0 0 0.5 0 0 0 0 0.5 0 0 0 1.4 -0.2'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>");
  }
</style>
