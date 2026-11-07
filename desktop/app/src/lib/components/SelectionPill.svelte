<script lang="ts">
  // The selection pill of the phone's tab bar, standing up: it travels to the chosen item on a
  // spring, lifts into glass for the journey, stretches with its speed and settles back into a
  // flat fill where it lands. The constants are the phone's (GlassSelectionPill.kt).
  import { onMount, untrack } from "svelte";
  import Glass from "../glass/Glass.svelte";
  import { themes } from "../theme/theme.svelte";

  let { active, tone = "accent" }: { active: HTMLElement | null | undefined; tone?: "accent" | "neutral" } = $props();

  let el = $state<HTMLDivElement>();
  let fill = $state<HTMLDivElement>();
  let glass = $state<HTMLDivElement>();
  let gone = $state(true);

  type Spring = { x: number; v: number };
  const TRAVEL_K = 280, TRAVEL_C = 31.4;
  const LIFT_K = 250, LIFT_CX = 19, LIFT_CY = 22.1;
  const MAT_K = 1000, MAT_C = 63.3;
  const HANDOVER_START = 0.92;

  const y: Spring = { x: 0, v: 0 }, h: Spring = { x: 0, v: 0 }, l: Spring = { x: 0, v: 0 }, w: Spring = { x: 0, v: 0 };
  const lx: Spring = { x: 0, v: 0 }, ly: Spring = { x: 0, v: 0 }, mat: Spring = { x: 0, v: 0 };
  let goal = { top: 0, left: 0, width: 0, height: 0 };
  let fromY = 0;
  let fromX = 0;
  let lifted = false;
  let handover = 1;
  let stretch = 0;
  let stretchX = 0;
  let frame = 0;
  let last = -1;
  let current: HTMLElement | null = null;

  function step(s: Spring, target: number, dt: number, k: number, c: number) {
    let t = dt;
    while (t > 0) {
      const d = Math.min(t, 1 / 240);
      s.v += (-k * (s.x - target) - c * s.v) * d;
      s.x += s.v * d;
      t -= d;
    }
    if (Math.abs(s.x - target) < 0.02 && Math.abs(s.v) < 0.05) {
      s.x = target;
      s.v = 0;
    }
  }

  function measure(a: HTMLElement) {
    return { top: a.offsetTop, left: a.offsetLeft, width: a.offsetWidth, height: a.offsetHeight };
  }

  function snap(g: typeof goal) {
    goal = g;
    y.x = g.top; h.x = g.height; l.x = g.left; w.x = g.width;
    y.v = h.v = l.v = w.v = 0;
    fromY = g.top;
    fromX = g.left;
  }

  function render() {
    if (!el) return;
    const lift = themes.reduceMotion ? 0 : 1;
    let hh = h.x + 6 * ly.x * lift;
    let ww = w.x + 8 * lx.x * lift;
    hh *= 1 + stretch - stretchX * 0.35;
    ww *= 1 + stretchX - stretch * 0.35;
    const cy = y.x + h.x / 2;
    const cx = l.x + w.x / 2;
    el.style.transform = `translate3d(${cx - ww / 2}px, ${cy - hh / 2}px, 0)`;
    el.style.width = `${Math.max(0, ww)}px`;
    el.style.height = `${Math.max(0, hh)}px`;
    const m = Math.min(1, Math.max(0, mat.x));
    const presence = 1 - handover;
    const rim = Math.min(1, Math.max(0, (presence - 0.45) / 0.55));
    if (fill) fill.style.opacity = String(1 - 0.8 * m);
    if (glass) glass.style.opacity = String(m * Math.max(rim, presence * 0.5));
  }

  function tick(now: number) {
    const dt = last < 0 ? 1 / 60 : Math.min(0.05, (now - last) / 1000);
    last = now;
    step(y, goal.top, dt, TRAVEL_K, TRAVEL_C);
    step(h, goal.height, dt, TRAVEL_K, TRAVEL_C);
    step(l, goal.left, dt, TRAVEL_K, TRAVEL_C);
    step(w, goal.width, dt, TRAVEL_K, TRAVEL_C);
    const spanY = Math.abs(goal.top - fromY);
    const spanX = Math.abs(goal.left - fromX);
    const progress =
      Math.max(spanX, spanY) < 0.5 ? 1 : spanY >= spanX ? 1 - Math.abs(goal.top - y.x) / spanY : 1 - Math.abs(goal.left - l.x) / spanX;
    if (progress >= HANDOVER_START) lifted = false;
    const lt = lifted ? 1 : 0;
    step(lx, lt, dt, LIFT_K, LIFT_CX);
    step(ly, lt, dt, LIFT_K, LIFT_CY);
    step(mat, lt, dt, MAT_K, MAT_C);
    const ht = lifted ? 0 : 1;
    handover += (ht - handover) * (1 - Math.exp(-dt / (ht > handover ? 0.09 : 0.05)));
    if (Math.abs(ht - handover) < 0.002) handover = ht;
    const target = Math.min(0.28, Math.abs(y.v) * 0.00022);
    stretch += (target - stretch) * (1 - Math.exp(-dt / 0.06));
    if (stretch < 0.0005 && target === 0) stretch = 0;
    const targetX = Math.min(0.22, Math.abs(l.v) * 0.00018);
    stretchX += (targetX - stretchX) * (1 - Math.exp(-dt / 0.06));
    if (stretchX < 0.0005 && targetX === 0) stretchX = 0;
    render();
    const settled =
      !lifted && y.v === 0 && h.v === 0 && l.v === 0 && w.v === 0 && lx.x === 0 && ly.x === 0 && mat.x === 0 && handover === 1 && stretch === 0 && stretchX === 0;
    frame = settled ? 0 : requestAnimationFrame(tick);
    if (settled) last = -1;
  }

  function run() {
    if (!frame) {
      last = -1;
      frame = requestAnimationFrame(tick);
    }
  }

  function moveTo(a: HTMLElement | null | undefined) {
    if (!a) {
      current = null;
      gone = true;
      return;
    }
    if (a === current && !gone) return;
    const g = measure(a);
    const fresh = gone || current == null;
    current = a;
    gone = false;
    if (themes.reduceMotion) {
      snap(g);
      render();
      return;
    }
    if (fresh) {
      // Appears where it is needed, with a short lift and nothing to travel.
      snap(g);
      lifted = true;
      setTimeout(() => (lifted = false), 140);
    } else {
      fromY = y.x;
      fromX = l.x;
      goal = g;
      lifted = true;
    }
    run();
  }

  $effect(() => {
    const a = active;
    untrack(() => moveTo(a));
  });

  onMount(() => {
    // Items move when the list changes or the window resizes: follow them without a journey.
    const ro = new ResizeObserver(() => {
      if (current && current.isConnected && !frame) {
        snap(measure(current));
        render();
      } else if (current && current.isConnected) {
        goal = measure(current);
      }
    });
    if (el?.parentElement) ro.observe(el.parentElement);
    return () => {
      ro.disconnect();
      cancelAnimationFrame(frame);
    };
  });
</script>

<div class="pill {tone}" class:gone bind:this={el} aria-hidden="true">
  <div class="inner">
    <div class="fill" bind:this={fill}></div>
    <div class="glass-wrap" bind:this={glass}>
      <Glass variant="clear" shape="pill" lens style="position:absolute;inset:0;pointer-events:none" />
    </div>
  </div>
</div>

<style>
  .pill {
    position: absolute;
    top: 0;
    left: 0;
    z-index: 0;
    pointer-events: none;
    will-change: transform, width, height;
  }
  .inner {
    position: absolute;
    inset: 0;
    transition:
      opacity var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-med) var(--lfly-ease-spring);
  }
  .gone .inner {
    opacity: 0;
    scale: 0.92;
  }
  .fill {
    position: absolute;
    inset: 0;
    border-radius: 999px;
    background: color-mix(in srgb, var(--lfly-accent) 20%, var(--lfly-glass-tint));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--lfly-accent) 22%, transparent);
  }
  .neutral .fill {
    background: color-mix(in srgb, var(--lfly-text) 16%, transparent);
    box-shadow:
      inset 0 1px 0 color-mix(in srgb, var(--lfly-text) 18%, transparent),
      0 1px 4px rgba(0, 0, 0, 0.18);
  }
  .glass-wrap {
    position: absolute;
    inset: 0;
    opacity: 0;
    border-radius: 999px;
    box-shadow:
      0 2px 12px rgba(0, 0, 0, 0.3),
      inset 0 1px 0 var(--lfly-glass-highlight);
  }
</style>
