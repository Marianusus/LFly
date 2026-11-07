<script lang="ts">
  // A collection without a cover of its own (Liked, Downloads): a symbol on the theme's colors,
  // with a soft sheen like the phone's pinned collections.
  import Icon from "../../Icon.svelte";
  import Glyph from "./Glyph.svelte";

  let { icon, glyph = false, tone = "accent", size = 0 }: { icon: string; glyph?: boolean; tone?: "accent" | "blue" | "violet"; size?: number } = $props();
  const px = $derived(size ? `width:${size}px;height:${size}px` : "");
</script>

<div class="artwork {tone}" style={px}>
  <span class="sym">
    {#if glyph}<Glyph name={icon} size={size ? Math.round(size * 0.36) : 72} />{:else}<Icon name={icon} size={size ? Math.round(size * 0.36) : 72} fill={icon === "heart"} />{/if}
  </span>
</div>

<style>
  .artwork {
    position: relative;
    width: 100%;
    aspect-ratio: 1;
    border-radius: var(--lfly-radius-m, 12px);
    display: grid;
    place-items: center;
    overflow: hidden;
    color: #fff;
    background: linear-gradient(140deg, color-mix(in srgb, var(--lfly-accent) 92%, #fff 8%), color-mix(in srgb, var(--lfly-accent) 45%, #2a1650));
    box-shadow: var(--lfly-shadow-2, 0 18px 48px rgba(0, 0, 0, 0.45));
  }
  .artwork.blue {
    background: linear-gradient(140deg, #3d8bfd, #1d2f8f);
  }
  .artwork.violet {
    background: linear-gradient(140deg, #9b6bff, #3a1d7a);
  }
  .artwork::before {
    content: "";
    position: absolute;
    inset: 0;
    background:
      radial-gradient(80% 60% at 20% 0%, rgba(255, 255, 255, 0.35), transparent 60%),
      linear-gradient(to top, rgba(0, 0, 0, 0.18), transparent 50%);
  }
  .sym {
    position: relative;
    display: grid;
    filter: drop-shadow(0 6px 18px rgba(0, 0, 0, 0.3));
  }
</style>
