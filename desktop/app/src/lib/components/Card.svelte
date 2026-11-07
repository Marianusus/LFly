<script lang="ts">
  import Icon from "../Icon.svelte";
  import Cover from "./Cover.svelte";

  let {
    title,
    subtitle = "",
    coverUrl,
    round = false,
    gradient = false,
    onopen,
    onplay,
  }: {
    title: string;
    subtitle?: string;
    coverUrl?: string | null;
    round?: boolean;
    gradient?: boolean;
    onopen: () => void;
    onplay?: () => void;
  } = $props();
</script>

<!-- The phone's rail card: a sheet of glass with the art set into it. Content cards use a light
     tint rather than live blur, which stays for the floating chrome. -->
<div class="lfly-card" class:round>
  <button class="open" onclick={onopen} aria-label={subtitle ? `${title}, ${subtitle}` : title}></button>
  <div class="art">
    {#if gradient}
      <div class="grad"><Icon name="heart" size={44} fill /></div>
    {:else}
      <Cover url={coverUrl} size={180} fluid {round} />
    {/if}
    {#if onplay}
      <button
        class="play"
        aria-label="Riproduci {title}"
        title="Riproduci"
        onclick={(e) => {
          e.stopPropagation();
          onplay();
        }}
      >
        <Icon name="play" size={18} />
      </button>
    {/if}
  </div>
  <span class="title">{title}</span>
  {#if subtitle}<span class="sub">{subtitle}</span>{/if}
</div>

<style>
  .lfly-card {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 8px 8px 12px;
    border-radius: 22px;
    min-width: 0;
    background: var(--lfly-glass-tint);
    box-shadow:
      inset 0 0 0 1px var(--lfly-glass-border),
      inset 0 1px 0 color-mix(in srgb, var(--lfly-glass-highlight) 60%, transparent);
    transition:
      transform var(--lfly-dur-med) var(--lfly-ease-spring),
      background var(--lfly-dur-fast) var(--lfly-ease),
      box-shadow var(--lfly-dur-med) var(--lfly-ease);
  }
  .lfly-card:hover,
  .lfly-card:focus-within {
    transform: translateY(-3px);
    background: color-mix(in srgb, var(--lfly-text) 10%, var(--lfly-glass-tint));
    box-shadow:
      inset 0 0 0 1px var(--lfly-glass-border),
      inset 0 1px 0 var(--lfly-glass-highlight),
      0 14px 34px rgba(0, 0, 0, 0.28);
  }
  .lfly-card:active {
    transform: translateY(-1px) scale(0.97);
  }
  .round {
    background: transparent;
    box-shadow: none;
  }
  .round:hover,
  .round:focus-within {
    box-shadow: none;
    background: var(--lfly-glass-tint);
  }
  .open {
    position: absolute;
    inset: 0;
    border-radius: inherit;
    z-index: 0;
  }
  .art {
    position: relative;
    margin-bottom: 8px;
    pointer-events: none;
  }
  .art :global(.lfly-cover) {
    border-radius: 15px;
    box-shadow: 0 6px 18px rgba(0, 0, 0, 0.28);
  }
  .round .art :global(.lfly-cover) {
    border-radius: 50%;
  }
  .grad {
    aspect-ratio: 1;
    border-radius: 15px;
    display: grid;
    place-items: center;
    color: #fff;
    background: linear-gradient(140deg, color-mix(in srgb, var(--lfly-accent) 80%, #fff) 0%, var(--lfly-accent) 45%, color-mix(in srgb, var(--lfly-accent) 55%, #3a0ca3) 100%);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.35),
      0 6px 18px rgba(0, 0, 0, 0.28);
  }
  .play {
    position: absolute;
    right: 8px;
    bottom: 8px;
    width: 44px;
    height: 44px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    padding-left: 2px;
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
    box-shadow:
      0 8px 20px rgba(0, 0, 0, 0.35),
      inset 0 1px 0 rgba(255, 255, 255, 0.35);
    opacity: 0;
    transform: translateY(8px) scale(0.8);
    transition:
      opacity var(--lfly-dur-fast) var(--lfly-ease),
      transform var(--lfly-dur-med) var(--lfly-ease-spring);
    pointer-events: auto;
    z-index: 1;
  }
  .lfly-card:hover .play,
  .lfly-card:focus-within .play {
    opacity: 1;
    transform: none;
  }
  .play:hover {
    transform: scale(1.08) !important;
  }
  .play:active {
    transform: scale(0.94) !important;
  }
  .title {
    position: relative;
    padding: 0 4px;
    font: 600 14px/1.3 var(--lfly-font);
    color: var(--lfly-text);
    pointer-events: none;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .sub {
    position: relative;
    padding: 0 4px;
    color: var(--lfly-text-2);
    font-size: 12px;
    line-height: 1.35;
    display: -webkit-box;
    -webkit-line-clamp: 1;
    line-clamp: 1;
    -webkit-box-orient: vertical;
    overflow: hidden;
    pointer-events: none;
  }
  .round .title,
  .round .sub {
    text-align: center;
  }
  @media (prefers-reduced-motion: reduce) {
    .lfly-card:hover,
    .lfly-card:focus-within,
    .lfly-card:active {
      transform: none;
    }
  }
</style>
