<!--
  A choice of look with a small live picture of the window in it: one of LFly's appearance modes
  (with the accent and the playing cover), or a custom theme.
-->
<script lang="ts">
  import Icon from "../Icon.svelte";
  import { themes } from "../theme/theme.svelte";
  import { buildScheme, modeInfo, type AppearanceMode } from "../theme/palette";
  import type { Theme } from "../theme/themes";

  let {
    mode,
    theme,
    active = false,
    coverSrc = null,
    onpick,
  }: {
    mode?: AppearanceMode;
    theme?: Theme;
    active?: boolean;
    /** The blurred playing cover, for the Copertina dinamica picture. */
    coverSrc?: string | null;
    onpick: () => void;
  } = $props();

  const info = $derived(mode ? modeInfo(mode) : null);

  /** The colors the picture is drawn with. */
  const look = $derived.by(() => {
    if (mode) {
      const c = info!.dynamic ? themes.cover : null;
      const s = buildScheme(mode, c?.primary ?? themes.accentColor, c?.secondary ?? themes.accentColor);
      return {
        bg: `linear-gradient(180deg, ${s.gradient[0]}, ${s.gradient[1]} 55%, ${s.gradient[2]})`,
        glow: s.glow,
        light: s.light,
        text: s.text,
        text2: s.text2,
        accent: s.accent,
        card: s.light ? "rgba(21,21,26,0.07)" : "rgba(255,255,255,0.09)",
        glass: s.light ? "rgba(255,255,255,0.62)" : "rgba(20,20,24,0.42)",
        border: s.light ? "rgba(0,0,0,0.1)" : "rgba(255,255,255,0.16)",
      };
    }
    const c = theme!.colors;
    return {
      bg: `linear-gradient(180deg, ${c.sidebar}, ${c.main})`,
      glow: null,
      light: false,
      text: c.text,
      text2: c.subtext,
      accent: c.button,
      card: c.card,
      glass: c.player,
      border: "rgba(127,127,127,0.25)",
    };
  });

  const title = $derived(info?.title ?? theme?.name ?? "");
  const subtitle = $derived(info?.subtitle ?? (theme?.builtin ? (theme.author ?? "") : theme?.author === "Spicetify" ? "Da Spicetify" : "Tuo"));
  const showCover = $derived(mode === "DYNAMIC_COVER" && !!coverSrc);
</script>

<button class="card" class:active aria-pressed={active} onclick={onpick}>
  <span class="mini" style="background:{look.bg}; --t:{look.text}; --t2:{look.text2}; --a:{look.accent}; --c:{look.card}; --g:{look.glass}; --b:{look.border}">
    {#if showCover}
      <span class="m-cover" style="background-image:url({coverSrc})"></span>
      <span class="m-veil"></span>
    {/if}
    {#if look.glow}
      <span class="m-glow" style="background:radial-gradient(ellipse at 50% 25%, {look.accent}55, transparent 70%)"></span>
    {/if}
    <span class="m-side">
      <span class="m-dot"></span>
      <span class="m-line s"></span>
      <span class="m-line s"></span>
      <span class="m-line s"></span>
    </span>
    <span class="m-main">
      <span class="m-line t"></span>
      <span class="m-line t2"></span>
      <span class="m-cards"><span></span><span></span><span></span></span>
    </span>
    <span class="m-player">
      <span class="m-play"></span>
      <span class="m-bar"><span></span></span>
    </span>
    {#if active}
      <span class="badge"><Icon name="check" size={11} /></span>
    {/if}
  </span>
  <span class="label">
    <span class="name ellipsis">{title}</span>
    {#if subtitle}<span class="sub">{subtitle}</span>{/if}
  </span>
</button>

<style>
  .card {
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 0;
    text-align: left;
    min-width: 0;
    border-radius: var(--lfly-radius-m);
  }
  .card:focus-visible {
    outline: none;
  }
  .card:focus-visible .mini {
    box-shadow: 0 0 0 2px var(--lfly-bg), 0 0 0 4px var(--lfly-accent);
  }
  .mini {
    position: relative;
    display: grid;
    grid-template-columns: 26% 1fr;
    grid-template-rows: 1fr 20%;
    gap: 5%;
    padding: 6%;
    aspect-ratio: 16 / 10.5;
    border-radius: 12px;
    overflow: hidden;
    isolation: isolate;
    box-shadow: inset 0 0 0 0.5px var(--lfly-glass-border), var(--lfly-shadow-1);
    transition:
      transform 500ms var(--lfly-ease-spring),
      box-shadow var(--lfly-dur-fast) var(--lfly-ease);
  }
  .card:hover .mini {
    transform: translateY(-2px);
  }
  .card:active .mini {
    transform: scale(0.97);
    transition-duration: 120ms;
  }
  .card.active .mini {
    box-shadow: 0 0 0 2px var(--lfly-bg), 0 0 0 4px var(--lfly-accent), var(--lfly-shadow-1);
  }
  .m-cover {
    position: absolute;
    inset: -30%;
    z-index: -2;
    background-size: cover;
    background-position: center;
    filter: blur(10px) saturate(1.4);
    transform: rotate(18deg) scale(1.2);
  }
  .m-veil {
    position: absolute;
    inset: 0;
    z-index: -1;
    background: linear-gradient(180deg, rgba(0, 0, 0, 0.18), rgba(0, 0, 0, 0.5));
  }
  .m-glow {
    position: absolute;
    inset: 0;
    z-index: -1;
  }
  .m-side,
  .m-player {
    background: var(--g);
    box-shadow:
      inset 0 0 0 0.5px var(--b),
      inset 0 1px 0 rgba(255, 255, 255, 0.18);
    -webkit-backdrop-filter: blur(6px);
    backdrop-filter: blur(6px);
  }
  .m-side {
    border-radius: 7px;
    display: flex;
    flex-direction: column;
    gap: 9%;
    padding: 14% 12%;
  }
  .m-dot {
    width: 34%;
    aspect-ratio: 1;
    border-radius: 50%;
    background: var(--a);
    margin-bottom: 6%;
  }
  .m-main {
    display: flex;
    flex-direction: column;
    gap: 6%;
    padding: 2% 0;
    min-width: 0;
  }
  .m-line {
    height: 5px;
    border-radius: 3px;
    background: var(--t);
  }
  .m-line.s {
    width: 80%;
    height: 3px;
    background: var(--t2);
    opacity: 0.7;
  }
  .m-line.t {
    width: 55%;
    height: 7px;
  }
  .m-line.t2 {
    width: 32%;
    background: var(--t2);
  }
  .m-cards {
    flex: 1;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 7%;
    margin-top: 4%;
  }
  .m-cards span {
    border-radius: 4px;
    background: var(--c);
  }
  .m-player {
    grid-column: 1 / -1;
    border-radius: 999px;
    display: flex;
    align-items: center;
    gap: 6%;
    padding: 0 5%;
  }
  .m-play {
    width: 9px;
    height: 9px;
    flex: none;
    border-radius: 50%;
    background: var(--t);
  }
  .m-bar {
    flex: 1;
    height: 3px;
    border-radius: 2px;
    background: var(--t2);
    opacity: 0.8;
    overflow: hidden;
  }
  .m-bar span {
    display: block;
    width: 40%;
    height: 100%;
    background: var(--a);
  }
  .badge {
    position: absolute;
    top: 7px;
    right: 7px;
    width: 20px;
    height: 20px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
  }
  .label {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    padding: 0 2px;
  }
  .name {
    font-weight: 600;
    font-size: 13px;
    color: var(--lfly-text);
  }
  .sub {
    font-size: 12px;
    line-height: 1.35;
    color: var(--lfly-text-2);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
</style>
