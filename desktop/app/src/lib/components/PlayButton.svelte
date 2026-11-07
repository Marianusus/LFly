<script lang="ts">
  import Icon from "../Icon.svelte";
  let { onclick, label = "Riproduci", size = 56 }: { onclick: () => void; label?: string; size?: number } = $props();
</script>

<button class="big-play" aria-label={label} title={label} style="width:{size}px;height:{size}px" {onclick}>
  <Icon name="play" size={Math.round(size * 0.42)} />
</button>

<style>
  .big-play {
    display: grid;
    place-items: center;
    padding-left: 3px;
    border-radius: 50%;
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
    box-shadow:
      0 10px 28px color-mix(in srgb, var(--lfly-accent) 38%, transparent),
      0 2px 6px rgba(0, 0, 0, 0.25),
      inset 0 1px 0 rgba(255, 255, 255, 0.35);
    transition:
      transform var(--lfly-dur-med) var(--lfly-ease-spring),
      filter var(--lfly-dur-fast) var(--lfly-ease),
      box-shadow var(--lfly-dur-med) var(--lfly-ease);
    flex: none;
  }
  .big-play:hover {
    transform: scale(1.06);
    filter: brightness(1.08);
    box-shadow:
      0 14px 36px color-mix(in srgb, var(--lfly-accent) 50%, transparent),
      0 2px 6px rgba(0, 0, 0, 0.25),
      inset 0 1px 0 rgba(255, 255, 255, 0.35);
  }
  .big-play:active {
    transform: scale(0.93);
  }
  .big-play:focus-visible {
    outline: 2px solid var(--lfly-text);
    outline-offset: 3px;
  }
  @media (prefers-reduced-motion: reduce) {
    .big-play:hover,
    .big-play:active {
      transform: none;
    }
  }
</style>
