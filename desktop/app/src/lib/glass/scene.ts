// The app canvas behind everything, painted once into a small canvas: the Backdrop shows it
// scaled up, and every Liquid Glass lens refracts the same pixels (lens.ts).
//
// It is soft on purpose (gradients and a blurred cover), so a third of the window's size is plenty
// and it costs almost nothing to repaint.

export type SceneState = {
  /** Changing it crossfades from the old picture to the new one. */
  key: string;
  gradient: [string, string, string];
  glow: string | null;
  light: boolean;
  /** The blurred cover that flows behind the app (Copertina dinamica), or null. */
  cover: HTMLCanvasElement | null;
  /** A custom theme's picture, drawn over the colors at [imageOpacity]. */
  image: HTMLImageElement | null;
  imageOpacity: number;
  /** False under reduced motion: the cover holds still. */
  animate: boolean;
};

const SCALE = 1 / 3;
/** One loop of the flow: slow enough to feel like weather, not like a screensaver. */
const LOOP_MS = 36_000;
// 24 frames: the flow is slow and heavily blurred, and every glass surface above re-blurs on each.
const FLOW_FPS = 24;

import { isIdle, onIdle } from "../idle";

type Listener = () => void;

class Scene {
  readonly canvas: HTMLCanvasElement;
  /** Bumped on every repaint, so the lenses know when to sample again. */
  version = 0;
  width = 1;
  height = 1;
  /** One plain color, nothing a lens could bend. */
  flat = true;

  private ctx: CanvasRenderingContext2D;
  private prev: HTMLCanvasElement;
  private state: SceneState | null = null;
  private fadeFrom = 0;
  private fadeMs = 0;
  private raf = 0;
  private lastFlow = 0;
  private listeners = new Set<Listener>();
  private settle = 0;

  constructor() {
    this.canvas = document.createElement("canvas");
    this.ctx = this.canvas.getContext("2d", { alpha: false })!;
    this.prev = document.createElement("canvas");
    addEventListener("resize", () => this.resize());
    // Maximising can report the size before the window has it; the observer sees the final one.
    if (typeof ResizeObserver !== "undefined") new ResizeObserver(() => this.resize()).observe(document.documentElement);
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden) this.repaint();
      this.kick();
    });
    // Behind a game or another app the cover stops flowing: it picks up where it was.
    onIdle((idle) => {
      if (!idle) this.repaint();
      this.kick();
    });
    this.resize();
  }

  subscribe(fn: Listener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  /** Shows [next]; a different key crossfades over [fadeMs]. */
  set(next: SceneState, fadeMs: number) {
    const changed = !this.state || this.state.key !== next.key;
    // In the background nothing would run the fade: show the new picture straight away.
    if (changed && this.state && fadeMs > 0 && !isIdle() && !document.hidden) {
      // Keep what is on screen, and fade it out over the new picture.
      this.prev.width = this.canvas.width;
      this.prev.height = this.canvas.height;
      this.prev.getContext("2d")!.drawImage(this.canvas, 0, 0);
      this.fadeFrom = performance.now();
      this.fadeMs = fadeMs;
    }
    this.state = next;
    this.flat = !next.cover && !next.image && !next.glow && next.gradient.every((c) => c === next.gradient[0]);
    this.paint(performance.now());
    this.kick();
  }

  private resize() {
    this.measure();
    this.paint(performance.now());
    // WebKitGTK may not show a canvas painted in the same task it was resized in: with nothing
    // flowing (no song) the backdrop then stayed blank, and the accent colours were gone.
    // Paint again on the next frame, and once the window has settled.
    requestAnimationFrame(() => this.repaint());
    clearTimeout(this.settle);
    this.settle = window.setTimeout(() => {
      this.measure();
      this.repaint();
    }, 250);
  }

  private repaint() {
    this.paint(performance.now());
  }

  private measure() {
    const w = Math.max(1, innerWidth);
    const h = Math.max(1, innerHeight);
    this.width = w;
    this.height = h;
    const cw = Math.max(16, Math.ceil(w * SCALE));
    const ch = Math.max(16, Math.ceil(h * SCALE));
    if (this.canvas.width !== cw || this.canvas.height !== ch) {
      this.canvas.width = cw;
      this.canvas.height = ch;
    }
  }

  private get flowing() {
    return !!this.state?.cover && this.state.animate;
  }

  private get fading() {
    return this.fadeMs > 0 && performance.now() - this.fadeFrom < this.fadeMs;
  }

  private kick() {
    if (this.raf || document.hidden || isIdle()) return;
    if (!this.flowing && !this.fading) return;
    this.raf = requestAnimationFrame((now) => this.tick(now));
  }

  private tick(now: number) {
    this.raf = 0;
    if (document.hidden || isIdle()) return;
    const fading = this.fading;
    // The flow is slow: 30 frames a second are indistinguishable from 60 and cost half.
    if (fading || now - this.lastFlow >= 1000 / FLOW_FPS - 2) {
      this.lastFlow = now;
      this.paint(now);
    }
    if (!fading && this.fadeMs > 0) this.fadeMs = 0;
    this.kick();
  }

  private paint(now: number) {
    const s = this.state;
    if (!s) return;
    const ctx = this.ctx;
    const w = this.canvas.width;
    const h = this.canvas.height;

    const g = ctx.createLinearGradient(0, 0, 0, h);
    g.addColorStop(0, s.gradient[0]);
    g.addColorStop(0.5, s.gradient[1]);
    g.addColorStop(1, s.gradient[2]);
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = "source-over";
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h);

    if (s.glow) {
      // The phone's ambient glow, stretched for a wide window and lifted toward the top.
      ctx.save();
      const r = Math.max(w, h) * 0.62;
      ctx.translate(w * 0.5, h * 0.3);
      ctx.scale(1, (h / w) * 1.5);
      const rg = ctx.createRadialGradient(0, 0, 0, 0, 0, r);
      rg.addColorStop(0, withAlpha(s.glow, s.light ? 0.28 : 0.32));
      rg.addColorStop(0.5, withAlpha(s.glow, s.light ? 0.1 : 0.12));
      rg.addColorStop(1, withAlpha(s.glow, 0));
      ctx.fillStyle = rg;
      ctx.fillRect(-r, -r, r * 2, r * 2);
      ctx.restore();
    }

    if (s.cover) {
      const t = s.animate ? ((now % LOOP_MS) / LOOP_MS) * Math.PI * 2 : 0.18 * Math.PI * 2;
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = "high";
      layer(ctx, s.cover, w, h, t, 1, 0.16, 0, 1.9, 1);
      layer(ctx, s.cover, w, h, t, -1, 0.22, 2.1, 1.7, 0.8);
      layer(ctx, s.cover, w, h, t, 2, 0.26, 4.2, 1.6, 0.65);
      // The veil: darker at the bottom, where the player sits.
      const v = ctx.createLinearGradient(0, 0, 0, h);
      v.addColorStop(0, "rgba(0,0,0,0.2)");
      v.addColorStop(0.5, "rgba(0,0,0,0.3)");
      v.addColorStop(1, "rgba(0,0,0,0.56)");
      ctx.globalAlpha = 1;
      ctx.fillStyle = v;
      ctx.fillRect(0, 0, w, h);
    }

    if (s.image && s.image.complete && s.image.naturalWidth > 0) {
      const iw = s.image.naturalWidth;
      const ih = s.image.naturalHeight;
      const k = Math.max(w / iw, h / ih);
      ctx.globalAlpha = s.imageOpacity;
      ctx.drawImage(s.image, (w - iw * k) / 2, (h - ih * k) / 2, iw * k, ih * k);
      ctx.globalAlpha = 1;
    }

    if (this.fadeMs > 0) {
      const p = Math.min(1, (now - this.fadeFrom) / this.fadeMs);
      const eased = 1 - (1 - p) * (1 - p);
      if (p < 1) {
        ctx.globalAlpha = 1 - eased;
        ctx.drawImage(this.prev, 0, 0, w, h);
        ctx.globalAlpha = 1;
      }
    }

    this.version++;
    for (const fn of this.listeners) fn();
  }
}

/**
 * One copy of the cover, larger than the window, turning [turns] times per loop around a centre
 * that circles the middle at [orbit] of the window. Whole turns keep the loop seamless.
 */
function layer(
  ctx: CanvasRenderingContext2D,
  img: HTMLCanvasElement,
  w: number,
  h: number,
  t: number,
  turns: number,
  orbit: number,
  phase: number,
  scale: number,
  alpha: number,
) {
  // A turning square covers the disc inscribed in it, so its half side must reach the window's
  // farthest corner from the wandering centre: half the diagonal plus the whole orbit, with a
  // margin. Below that the copies' corners swept through the window (seen in wide windows).
  const reach = Math.hypot(w, h) / 2 + Math.hypot(w * orbit, h * orbit * 0.6);
  const side = Math.max(reach * 2 * 1.06, Math.hypot(w, h) * scale * 0.82);
  const cx = w / 2 + Math.cos(t + phase) * w * orbit;
  const cy = h / 2 + Math.sin(t * 2 + phase) * h * orbit * 0.6;
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.translate(cx, cy);
  ctx.rotate(t * turns);
  ctx.drawImage(img, -side / 2, -side / 2, side, side);
  ctx.restore();
}

function withAlpha(hex: string, a: number): string {
  const v = hex.replace("#", "");
  const n = parseInt(v.length === 3 ? v.replace(/./g, (c) => c + c) : v.slice(0, 6), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

let instance: Scene | null = null;

/** The one scene of the window (created on first use, in the browser only). */
export function scene(): Scene {
  return (instance ??= new Scene());
}

export type { Scene };
