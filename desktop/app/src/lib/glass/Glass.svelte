<!--
  Liquid Glass: a floating surface that blurs and saturates what is behind it, bends the app canvas
  at its rim like a lens (lens.ts), and catches the light along its edge. Interactive glass follows
  the pointer with a specular highlight and gives under the finger with a spring.

  Content goes above the glass and keeps the element's own layout: the effect layers are placed
  absolutely behind it, so `display: flex` or `grid` on `class` work on the children as usual.
-->
<script lang="ts">
  import type { Snippet } from "svelte";
  import { themes } from "../theme/theme.svelte";
  import { lensEngine } from "./lens";

  type Props = {
    variant?: "regular" | "clear" | "tinted";
    shape?: "rect" | "pill" | "circle";
    /** Corner radius in px, for `rect`. */
    radius?: number;
    /** Lens refraction, dispersion and rim light (off by the person's setting or reduced transparency). */
    lens?: boolean;
    /** Pointer highlight and press response, for glass that is pressed. */
    interactive?: boolean;
    as?: "div" | "button" | "nav" | "header" | "aside" | "section";
    class?: string;
    style?: string;
    children?: Snippet;
    [key: string]: unknown;
  };

  let {
    variant = "regular",
    shape = "rect",
    radius = 22,
    lens = true,
    interactive = false,
    as = "div",
    class: cls = "",
    style = "",
    children,
    ...rest
  }: Props = $props();

  let el = $state<HTMLElement>();
  let fx = $state<HTMLElement>();
  let pressed = $state(false);
  let hovered = $state(false);

  const radiusCss = $derived(shape === "rect" ? `${radius}px` : "9999px");
  const lensOn = $derived(lens && themes.lensOn);

  // The lens follows the element; it is given up when lenses are turned off.
  $effect(() => {
    if (!lensOn || !el || !fx) return;
    const node = el;
    return lensEngine().attach(node, fx, {
      radius: () => (shape === "rect" ? radius : Math.min(node.offsetWidth, node.offsetHeight) / 2),
      strength: () => themes.glass.strength,
      angle: () => lightAngle,
    });
  });

  // ---- Pointer: specular highlight, rim light angle, liquid press ----
  let frame = 0;
  let px = 0.5;
  let py = 0.5;
  let downX = 0.5;
  let downY = 0.5;
  /** Where the light comes from, degrees on screen (225 = top left, as on the phone). */
  let lightAngle = 225;
  const motion = $derived(!themes.reduceMotion);

  function place(e: PointerEvent) {
    if (!el) return;
    const r = el.getBoundingClientRect();
    px = r.width ? (e.clientX - r.left) / r.width : 0.5;
    py = r.height ? (e.clientY - r.top) / r.height : 0.5;
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      if (!el) return;
      el.style.setProperty("--g-mx", `${(px * 100).toFixed(1)}%`);
      el.style.setProperty("--g-my", `${(py * 100).toFixed(1)}%`);
      // The rim catches the light from the pointer's side.
      const towards = (Math.atan2(py - 0.5, px - 0.5) * 180) / Math.PI;
      el.style.setProperty("--g-light", `${(towards + 90).toFixed(1)}deg`);
      lightAngle = Math.round(towards);
      if (lensOn) lensEngine().invalidate();
      if (pressed && motion) {
        // Liquid: stretched a little toward where the finger drags, squeezed across.
        const dx = Math.max(-1, Math.min(1, (px - downX) * 2));
        const dy = Math.max(-1, Math.min(1, (py - downY) * 2));
        el.style.setProperty("--g-tx", `${(dx * 3).toFixed(2)}px`);
        el.style.setProperty("--g-ty", `${(dy * 3).toFixed(2)}px`);
        el.style.setProperty("--g-sx", (1 + Math.abs(dx) * 0.035 - Math.abs(dy) * 0.02).toFixed(4));
        el.style.setProperty("--g-sy", (1 + Math.abs(dy) * 0.035 - Math.abs(dx) * 0.02).toFixed(4));
      }
    });
  }

  function release() {
    pressed = false;
    if (!el) return;
    for (const p of ["--g-tx", "--g-ty", "--g-sx", "--g-sy"]) el.style.removeProperty(p);
  }

  /** The pointer left: the light goes back to the top left. */
  function restLight() {
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    if (!el || lightAngle === 225) return;
    lightAngle = 225;
    el.style.removeProperty("--g-light");
    if (lensOn) lensEngine().invalidate();
  }

  type Handler = ((e: Event) => void) | undefined;
  const call = (name: string, e: Event) => (rest[name] as Handler)?.(e);

  function onpointerenter(e: PointerEvent) {
    if (interactive) {
      hovered = true;
      place(e);
    }
    call("onpointerenter", e);
  }
  function onpointermove(e: PointerEvent) {
    if (interactive) place(e);
    call("onpointermove", e);
  }
  function onpointerleave(e: PointerEvent) {
    if (interactive) {
      hovered = false;
      release();
      restLight();
    }
    call("onpointerleave", e);
  }
  function onpointerdown(e: PointerEvent) {
    if (interactive && e.button === 0 && !rest.disabled) {
      place(e);
      downX = px;
      downY = py;
      pressed = true;
    }
    call("onpointerdown", e);
  }
  function onpointerup(e: PointerEvent) {
    if (interactive) release();
    call("onpointerup", e);
  }
  function onpointercancel(e: PointerEvent) {
    if (interactive) release();
    call("onpointercancel", e);
  }
  function onkeydown(e: KeyboardEvent) {
    if (interactive && (e.key === " " || e.key === "Enter") && !e.repeat) pressed = true;
    call("onkeydown", e);
  }
  function onkeyup(e: KeyboardEvent) {
    if (interactive) release();
    call("onkeyup", e);
  }
  function onblur(e: FocusEvent) {
    if (interactive) release();
    call("onblur", e);
  }
</script>

<svelte:element
  this={as}
  bind:this={el}
  type={as === "button" ? ((rest.type as "button" | "submit" | "reset" | undefined) ?? "button") : undefined}
  {...rest}
  class="lfly-g {variant} {shape} {cls}"
  class:interactive
  class:pressed
  class:hovered
  class:lensed={lensOn}
  style="--g-radius:{radiusCss};{style}"
  {onpointerenter}
  {onpointermove}
  {onpointerleave}
  {onpointerdown}
  {onpointerup}
  {onpointercancel}
  {onkeydown}
  {onkeyup}
  {onblur}
>
  <span class="lfly-g-fx" bind:this={fx} aria-hidden="true"></span>
  <span class="lfly-g-rim" aria-hidden="true"></span>
  {@render children?.()}
</svelte:element>

<style>
  .lfly-g {
    --g-tint: var(--lfly-glass-tint);
    --g-light: 315deg;
    position: relative;
    isolation: isolate;
    border-radius: var(--g-radius);
    color: var(--lfly-text);
    background: transparent;
    -webkit-backdrop-filter: blur(var(--lfly-glass-blur)) saturate(var(--lfly-glass-sat));
    backdrop-filter: blur(var(--lfly-glass-blur)) saturate(var(--lfly-glass-sat));
    box-shadow: var(--lfly-shadow-1);
    /* Separate properties, so a transform set by the caller (centering, sliding in) still applies. */
    translate: var(--g-tx, 0) var(--g-ty, 0);
    scale: var(--g-sx, 1) var(--g-sy, 1);
    transition:
      scale 600ms var(--lfly-ease-jelly),
      translate 600ms var(--lfly-ease-jelly),
      box-shadow var(--lfly-dur-med) var(--lfly-ease);
  }
  /* A button's own look stays away: the glass is its look. */
  button.lfly-g {
    border: 0;
    padding: 0;
    font: inherit;
    cursor: pointer;
    text-align: inherit;
  }
  button.lfly-g:disabled {
    cursor: default;
    opacity: 0.45;
  }
  .lfly-g.clear {
    --g-tint: var(--lfly-glass-tint-clear);
  }
  .lfly-g.tinted {
    --g-tint: var(--lfly-glass-tint-accent);
    color: var(--lfly-on-accent);
  }

  /* Behind the content: the lens canvas, then the tint, then the moving highlight. */
  .lfly-g-fx {
    position: absolute;
    inset: 0;
    z-index: -1;
    border-radius: inherit;
    overflow: hidden;
    pointer-events: none;
  }
  .lfly-g-fx :global(canvas.lfly-lens) {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    z-index: 0;
  }
  .lfly-g-fx::before {
    content: "";
    position: absolute;
    inset: 0;
    z-index: 1;
    border-radius: inherit;
    background: var(--g-tint);
    /* Depth: a lit upper lip and a soft shade inside the lower edge. */
    box-shadow:
      inset 0 1px 0.5px rgba(255, 255, 255, var(--g-lip, 0.14)),
      inset 0 -1px 0.5px rgba(0, 0, 0, 0.06),
      inset 0 -14px 24px -18px var(--lfly-glass-shade);
    transition: background var(--lfly-dur-med) var(--lfly-ease);
  }
  .lfly-g-fx::after {
    content: "";
    position: absolute;
    inset: 0;
    z-index: 2;
    background: radial-gradient(
      circle at var(--g-mx, 50%) var(--g-my, 0%),
      rgba(255, 255, 255, 0.2),
      rgba(255, 255, 255, 0.06) 38%,
      transparent 70%
    );
    opacity: 0;
    transition: opacity var(--lfly-dur-med) var(--lfly-ease);
  }

  /* The rim: light caught along the edge, strongest facing the light (Kyant's Highlight.Default, 45°). */
  .lfly-g-rim {
    position: absolute;
    inset: 0;
    z-index: -1;
    border-radius: inherit;
    padding: 1px;
    pointer-events: none;
    background:
      conic-gradient(
        from var(--g-light),
        var(--lfly-glass-highlight),
        transparent 70deg,
        transparent 110deg,
        rgba(255, 255, 255, 0.28) 180deg,
        transparent 250deg,
        transparent 290deg,
        var(--lfly-glass-highlight)
      ),
      linear-gradient(var(--lfly-glass-border), var(--lfly-glass-border));
    -webkit-mask:
      linear-gradient(#000 0 0) content-box,
      linear-gradient(#000 0 0);
    -webkit-mask-composite: xor;
    mask-composite: exclude;
    mask:
      linear-gradient(#000 0 0) content-box exclude,
      linear-gradient(#000 0 0);
  }

  /* ---- Interaction ---- */
  .lfly-g.interactive.hovered .lfly-g-fx::after {
    opacity: 1;
  }
  .lfly-g.interactive.pressed {
    transition:
      scale 160ms var(--lfly-ease),
      translate 160ms var(--lfly-ease),
      box-shadow var(--lfly-dur-fast) var(--lfly-ease);
  }
  .lfly-g.interactive.pressed.rect {
    --g-sx: 0.985;
    --g-sy: 0.985;
  }
  .lfly-g.interactive.pressed.pill,
  .lfly-g.interactive.pressed.circle {
    --g-sx: 0.94;
    --g-sy: 0.94;
  }
  .lfly-g.interactive.pressed .lfly-g-fx::after {
    opacity: 1;
    background: radial-gradient(
      circle at var(--g-mx, 50%) var(--g-my, 50%),
      rgba(255, 255, 255, 0.34),
      rgba(255, 255, 255, 0.1) 45%,
      transparent 80%
    );
  }
  .lfly-g:focus-visible {
    outline: 2px solid var(--lfly-accent);
    outline-offset: 3px;
    border-radius: var(--g-radius);
  }

  /* ---- Light canvases: the lip is brighter, the rim reads as a fine dark line ---- */
  :global([data-scheme="light"]) .lfly-g {
    --g-lip: 0.7;
  }

  /* ---- Glass off (setting, or the system's reduced transparency): a solid surface ---- */
  :global([data-glass="off"]) .lfly-g {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
  :global([data-glass="off"]) .lfly-g .lfly-g-rim {
    background: linear-gradient(var(--lfly-glass-border), var(--lfly-glass-border));
  }
  @media (prefers-reduced-transparency: reduce) {
    .lfly-g {
      -webkit-backdrop-filter: none;
      backdrop-filter: none;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .lfly-g,
    .lfly-g.interactive.pressed {
      scale: none;
      translate: none;
      transition: none;
    }
  }
  @media (prefers-contrast: more) {
    .lfly-g .lfly-g-rim {
      background: linear-gradient(var(--lfly-text-2), var(--lfly-text-2));
    }
  }
</style>
