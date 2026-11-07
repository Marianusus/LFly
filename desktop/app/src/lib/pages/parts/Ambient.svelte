<script lang="ts">
  // The playing cover as light: two heavily blurred copies drifting slowly under a scrim,
  // like the phone's player and lyrics backgrounds. A small image is enough under the blur.
  import { cover } from "../../connect.svelte";

  let {
    url,
    dim = 0.45,
    blur = 70,
    still = false,
  }: { url?: string | null; dim?: number; blur?: number; still?: boolean } = $props();

  const src = $derived(url ? cover(url, 120) : undefined);
  let shown = $state<string[]>([]);

  // A new cover dissolves in over the old one instead of cutting.
  $effect(() => {
    const s = src;
    if (!s) return;
    if (shown[shown.length - 1] !== s) shown = [...shown.slice(-1), s];
  });
</script>

<div class="ambient" class:still style="--dim:{dim};--blur:{blur}px" aria-hidden="true">
  {#each shown as s (s)}
    <div class="layer">
      <img class="a" src={s} alt="" draggable="false" />
      <img class="b" src={s} alt="" draggable="false" />
    </div>
  {/each}
  <div class="scrim"></div>
</div>

<style>
  .ambient {
    position: absolute;
    inset: 0;
    overflow: hidden;
    pointer-events: none;
    background: var(--lfly-bg, #000);
  }
  .layer {
    position: absolute;
    inset: 0;
    animation: fade-in 900ms var(--lfly-ease, ease) both;
  }
  img {
    position: absolute;
    width: 75%;
    aspect-ratio: 1;
    object-fit: cover;
    filter: blur(var(--blur)) saturate(1.7);
    opacity: 0.9;
    will-change: transform;
  }
  .a {
    left: -12%;
    top: -28%;
    animation: drift-a 26s ease-in-out infinite alternate;
  }
  .b {
    right: -14%;
    bottom: -36%;
    transform: rotate(180deg);
    animation: drift-b 32s ease-in-out infinite alternate;
  }
  .scrim {
    position: absolute;
    inset: 0;
    background:
      radial-gradient(120% 90% at 50% 30%, rgba(0, 0, 0, calc(var(--dim) * 0.55)), rgba(0, 0, 0, calc(var(--dim) + 0.2))),
      rgba(0, 0, 0, calc(var(--dim) * 0.4));
  }
  .still img {
    animation: none;
  }
  @keyframes fade-in {
    from {
      opacity: 0;
    }
  }
  @keyframes drift-a {
    to {
      transform: translate(14%, 10%) scale(1.15) rotate(12deg);
    }
  }
  @keyframes drift-b {
    to {
      transform: translate(-12%, -8%) scale(1.2) rotate(160deg);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    img,
    .layer {
      animation: none !important;
    }
  }
</style>
