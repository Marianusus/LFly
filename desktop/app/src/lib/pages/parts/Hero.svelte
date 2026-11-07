<script lang="ts">
  // A collection's header, after the phone's PlaylistHero: the artwork, the eyebrow, the title,
  // who made it and the facts, then one row of actions with Play first. The cover's own colors
  // glow behind it and fade into the page.
  import type { Snippet } from "svelte";
  import Icon from "../../Icon.svelte";
  import Glass from "../../glass/Glass.svelte";
  import Cover from "../../components/Cover.svelte";
  import { cover } from "../../connect.svelte";

  let {
    kind,
    title,
    coverUrl,
    round = false,
    art,
    byline,
    meta = "",
    description = "",
    playable = true,
    onplay,
    onshuffle,
    actions,
    tone,
  }: {
    kind: string;
    title: string;
    coverUrl?: string | null;
    round?: boolean;
    art?: Snippet;
    byline?: Snippet;
    meta?: string;
    description?: string;
    playable?: boolean;
    onplay?: () => void;
    onshuffle?: () => void;
    actions?: Snippet;
    /** A color for the glow when there is no cover (Liked, Downloads). */
    tone?: string;
  } = $props();

  let more = $state(false);
  const glow = $derived(coverUrl ? cover(coverUrl, 120) : undefined);
</script>

<header class="hero" class:round>
  <div class="glow" aria-hidden="true" style={tone ? `--tone:${tone}` : ""}>
    {#if glow}<img src={glow} alt="" draggable="false" />{:else}<span class="tone"></span>{/if}
  </div>

  <div class="art">
    {#if art}{@render art()}{:else}<Cover url={coverUrl} size={240} fluid {round} />{/if}
  </div>

  <div class="info">
    <span class="eyebrow">{kind}</span>
    <h1 class="title" {title}>{title || " "}</h1>
    {#if byline}<div class="byline">{@render byline()}</div>{/if}
    {#if meta}<p class="meta">{meta}</p>{/if}
    {#if description}
      <button class="desc" class:open={more} onclick={() => (more = !more)} title={more ? "Mostra meno" : "Mostra tutto"}>
        {description}
      </button>
    {/if}

    <div class="actions">
      {#if onplay}
        <button class="play" disabled={!playable} onclick={onplay} aria-label="Riproduci {title}">
          <Icon name="play" size={16} />Riproduci
        </button>
      {/if}
      {#if onshuffle}
        <Glass as="button" shape="pill" interactive class="shuffle" disabled={!playable} onclick={onshuffle} aria-label="Riproduzione casuale">
          <Icon name="shuffle" size={16} />Casuale
        </Glass>
      {/if}
      {#if actions}<div class="more-actions">{@render actions()}</div>{/if}
    </div>
  </div>
</header>

<style>
  .hero {
    position: relative;
    isolation: isolate;
    display: grid;
    grid-template-columns: clamp(180px, 22vw, 260px) minmax(0, 1fr);
    align-items: end;
    gap: clamp(20px, 3vw, 40px);
    padding: 28px var(--page-x, 28px) 28px;
  }
  .glow {
    position: absolute;
    z-index: -1;
    inset: -120px 0 -40px 0;
    overflow: hidden;
    pointer-events: none;
    -webkit-mask-image: linear-gradient(to bottom, #000 35%, transparent);
    mask-image: linear-gradient(to bottom, #000 35%, transparent);
  }
  .glow img {
    position: absolute;
    left: -10%;
    top: -20%;
    width: 120%;
    height: 140%;
    object-fit: cover;
    filter: blur(90px) saturate(1.8);
    opacity: 0.55;
  }
  .tone {
    position: absolute;
    inset: 0;
    background:
      radial-gradient(60% 80% at 15% 40%, color-mix(in srgb, var(--tone, var(--lfly-accent)) 55%, transparent), transparent 70%),
      radial-gradient(50% 70% at 70% 20%, color-mix(in srgb, var(--tone, var(--lfly-accent)) 25%, transparent), transparent 70%);
  }
  .art {
    width: 100%;
    aspect-ratio: 1;
  }
  .art :global(.lfly-cover) {
    border-radius: var(--lfly-radius-m, 12px);
    box-shadow: var(--lfly-shadow-2, 0 18px 48px rgba(0, 0, 0, 0.45));
  }
  .round .art :global(.lfly-cover) {
    border-radius: 50%;
  }
  .info {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
    min-width: 0;
    padding-bottom: 4px;
  }
  .eyebrow {
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--lfly-text-2);
  }
  .title {
    margin: 0;
    font-family: var(--lfly-font-display);
    font-size: clamp(30px, 4.4vw, 56px);
    line-height: 1.04;
    font-weight: 800;
    letter-spacing: -0.025em;
    color: var(--lfly-text);
    overflow: hidden;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    max-width: 100%;
    text-wrap: balance;
  }
  .byline {
    font-size: 19px;
    font-weight: 600;
    color: var(--lfly-accent);
    letter-spacing: -0.01em;
  }
  .byline :global(button:hover) {
    text-decoration: underline;
  }
  .meta {
    margin: 0;
    font-size: 13px;
    color: var(--lfly-text-2);
  }
  .desc {
    max-width: 680px;
    text-align: left;
    font-size: 13px;
    line-height: 1.5;
    color: var(--lfly-text-2);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    white-space: pre-line;
  }
  .desc.open {
    -webkit-line-clamp: unset;
    line-clamp: unset;
    display: block;
  }
  .desc:hover {
    color: var(--lfly-text);
  }
  .actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 14px;
  }
  .play,
  .actions :global(.shuffle) {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    height: 40px;
    min-width: 124px;
    padding: 0 22px;
    border-radius: 999px;
    font-size: 14px;
    font-weight: 650;
    transition:
      transform var(--lfly-dur-fast, 160ms) var(--lfly-ease, ease),
      filter var(--lfly-dur-fast, 160ms);
  }
  .play {
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
    box-shadow: 0 8px 24px color-mix(in srgb, var(--lfly-accent) 35%, transparent);
  }
  .play:hover:not(:disabled) {
    filter: brightness(1.08);
    transform: scale(1.03);
  }
  .play:active:not(:disabled),
  .actions :global(.shuffle:active) {
    transform: scale(0.97);
  }
  .play:disabled,
  .actions :global(.shuffle:disabled) {
    opacity: 0.45;
    color: var(--lfly-on-accent);
  }
  .actions :global(.shuffle) {
    color: var(--lfly-text);
  }
  .more-actions {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-left: 4px;
  }
  .more-actions :global(.icon-button),
  .more-actions :global(.hero-icon) {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    display: inline-grid;
    place-items: center;
    color: var(--lfly-text-2);
    transition: background var(--lfly-dur-fast, 160ms), color var(--lfly-dur-fast, 160ms);
  }
  .more-actions :global(.icon-button:hover),
  .more-actions :global(.hero-icon:hover) {
    background: var(--lfly-glass-tint);
    color: var(--lfly-text);
  }
  @media (max-width: 720px) {
    .hero {
      grid-template-columns: 1fr;
      justify-items: center;
      text-align: center;
    }
    .art {
      width: min(240px, 70vw);
    }
    .info {
      align-items: center;
    }
    .actions {
      justify-content: center;
    }
  }
</style>
