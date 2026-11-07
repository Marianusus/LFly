<script lang="ts">
  // The phone's PlaylistHero, laid out for a window: the art on the left, lit from behind by its
  // own colours, and the title set large beside it.
  import type { Snippet } from "svelte";
  import Cover from "./Cover.svelte";
  import { cover } from "../connect.svelte";

  let {
    kind,
    title,
    coverUrl,
    art,
    round = false,
    details,
  }: { kind: string; title: string; coverUrl?: string | null; art?: Snippet; round?: boolean; details?: Snippet } = $props();

  const glow = $derived(coverUrl ? cover(coverUrl, 120) : undefined);
</script>

<header class="head" class:round>
  <div class="light" aria-hidden="true">
    {#if glow}<img src={glow} alt="" />{:else}<span class="tint"></span>{/if}
  </div>
  <div class="art">
    {#if art}{@render art()}{:else}<Cover url={coverUrl} size={232} {round} />{/if}
  </div>
  <div class="info">
    <span class="kind">{kind}</span>
    <h1 class="lfly-page-title" title={title}>{title}</h1>
    {#if details}<div class="details">{@render details()}</div>{/if}
  </div>
</header>

<style>
  .head {
    position: relative;
    display: flex;
    align-items: flex-end;
    gap: 32px;
    padding: 40px var(--page-x, 24px) 28px;
    isolation: isolate;
  }
  /* The cover's colours, blurred into a pool of light behind the header. */
  .light {
    position: absolute;
    inset: -80px 0 -40px;
    z-index: -1;
    overflow: hidden;
    pointer-events: none;
    mask-image: linear-gradient(to bottom, #000 30%, transparent);
  }
  .light img {
    position: absolute;
    left: 0;
    top: 0;
    width: min(70%, 640px);
    height: 140%;
    object-fit: cover;
    filter: blur(70px) saturate(1.5);
    opacity: 0.55;
  }
  .tint {
    position: absolute;
    left: 0;
    top: 0;
    width: 60%;
    height: 100%;
    background: radial-gradient(60% 60% at 30% 40%, color-mix(in srgb, var(--lfly-accent) 45%, transparent), transparent);
  }
  .art {
    flex: none;
  }
  .art :global(.lfly-cover) {
    border-radius: 20px;
    box-shadow:
      0 24px 60px rgba(0, 0, 0, 0.45),
      0 6px 16px rgba(0, 0, 0, 0.25);
  }
  .round .art :global(.lfly-cover) {
    border-radius: 50%;
  }
  .info {
    display: flex;
    flex-direction: column;
    gap: 10px;
    min-width: 0;
    padding-bottom: 6px;
  }
  .kind {
    font: 600 12px/1 var(--lfly-font);
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--lfly-text-2);
  }
  .lfly-page-title {
    margin: 0;
    font-family: var(--lfly-font-display);
    font-size: clamp(32px, 4.6vw, 64px);
    line-height: 1.04;
    font-weight: 800;
    letter-spacing: -0.03em;
    color: var(--lfly-text);
    overflow: hidden;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    text-wrap: balance;
  }
  .details {
    color: var(--lfly-text-2);
    font-size: 13.5px;
    line-height: 1.5;
  }
  .details :global(strong) {
    color: var(--lfly-text);
    font-weight: 600;
  }
  @media (max-width: 760px) {
    .head {
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: 20px;
      padding-top: 24px;
    }
    .info {
      align-items: center;
    }
    .art :global(.lfly-cover) {
      width: 200px !important;
      height: 200px !important;
    }
  }
</style>
