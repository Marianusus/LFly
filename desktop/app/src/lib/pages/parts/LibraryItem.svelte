<script lang="ts">
  // One thing in the library, as a tile in the grid or a row in the list.
  import Icon from "../../Icon.svelte";
  import Cover from "../../components/Cover.svelte";
  import Artwork from "./Artwork.svelte";

  let {
    title,
    subtitle = "",
    coverUrl,
    art,
    round = false,
    list = false,
    playing = false,
    onopen,
    onplay,
  }: {
    title: string;
    subtitle?: string;
    coverUrl?: string | null;
    art?: { icon: string; tone?: "accent" | "blue" | "violet"; glyph?: boolean };
    round?: boolean;
    list?: boolean;
    playing?: boolean;
    onopen: () => void;
    onplay?: () => void;
  } = $props();
</script>

<div class="item" class:list class:round class:playing>
  <button class="open" onclick={onopen} aria-label={title}></button>
  <div class="art">
    {#if art}
      <Artwork icon={art.icon} tone={art.tone} glyph={art.glyph} size={list ? 52 : 0} />
    {:else}
      <Cover url={coverUrl} size={list ? 52 : 200} fluid={!list} {round} />
    {/if}
    {#if onplay && !list}
      <button class="play" aria-label="Riproduci {title}" onclick={onplay}><Icon name="play" size={18} /></button>
    {/if}
  </div>
  <div class="text">
    <span class="t">{title}</span>
    {#if subtitle}<span class="s">{subtitle}</span>{/if}
  </div>
  {#if onplay && list}
    <button class="play inline" aria-label="Riproduci {title}" onclick={onplay}><Icon name="play" size={15} /></button>
  {/if}
</div>

<style>
  .item {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 10px;
    border-radius: var(--lfly-radius-l, 18px);
    min-width: 0;
    transition:
      background var(--lfly-dur-fast, 160ms),
      transform var(--lfly-dur-fast, 160ms) var(--lfly-ease, ease);
  }
  .item:hover,
  .item:focus-within {
    background: var(--lfly-glass-tint);
  }
  .item:active {
    transform: scale(0.985);
  }
  .open {
    position: absolute;
    inset: 0;
    border-radius: inherit;
  }
  .art {
    position: relative;
    pointer-events: none;
  }
  .art :global(.lfly-cover) {
    border-radius: var(--lfly-radius-m, 12px);
    box-shadow: 0 10px 26px rgba(0, 0, 0, 0.3);
  }
  .round .art :global(.lfly-cover) {
    border-radius: 50%;
  }
  .art :global(.artwork) {
    box-shadow: 0 10px 26px rgba(0, 0, 0, 0.3);
  }
  .text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    pointer-events: none;
  }
  .round .text {
    text-align: center;
  }
  .t {
    font-size: 14px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .playing .t {
    color: var(--lfly-accent);
  }
  .s {
    font-size: 12px;
    color: var(--lfly-text-2);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .play {
    position: absolute;
    right: 10px;
    bottom: 10px;
    width: 44px;
    height: 44px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    padding-left: 2px;
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
    box-shadow: 0 8px 20px rgba(0, 0, 0, 0.35);
    opacity: 0;
    transform: translateY(6px) scale(0.9);
    pointer-events: auto;
    z-index: 1;
    transition:
      opacity var(--lfly-dur-fast, 160ms),
      transform var(--lfly-dur-med, 320ms) var(--lfly-ease-spring, ease);
  }
  .item:hover .play,
  .item:focus-within .play {
    opacity: 1;
    transform: none;
  }
  .play:hover {
    filter: brightness(1.08);
  }
  .list {
    flex-direction: row;
    align-items: center;
    gap: 14px;
    padding: 6px 10px 6px 6px;
    border-radius: 14px;
  }
  .list .art :global(.lfly-cover),
  .list .art :global(.artwork) {
    border-radius: 10px;
    box-shadow: none;
  }
  .list.round .art :global(.lfly-cover) {
    border-radius: 50%;
  }
  .list .text {
    flex: 1;
  }
  .list.round .text {
    text-align: left;
  }
  .play.inline {
    position: relative;
    right: auto;
    bottom: auto;
    width: 34px;
    height: 34px;
    flex: none;
  }
</style>
