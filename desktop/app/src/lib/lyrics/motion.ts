// How the lyrics move, in the manner of Liner and Apple Music: every syllable lights up with a
// soft edge sweeping across it, long notes glow while they are held, a line without word
// timings fades in word after word, a finished line fades back, and when the song moves on the
// lines glide up one after the other instead of as one block. Pure functions and small classes,
// no DOM: LyricsLines.svelte paints what these say.

import type { LyricLine } from "../api";

/** One piece of a line that lights up on its own: a syllable or word, or the space between. */
export type Token = { text: string; start: number; dur: number; space: boolean };
/** A run of tokens that must not wrap apart (the syllables of one word). */
export type Group = { tokens: Token[]; space: boolean };

export type LineModel = {
  start: number;
  end: number;
  /** When the line lets go of its light: the next line or its own end, whichever is later. */
  exit: number;
  text: string;
  groups: Group[];
  bg?: string;
  /** Lines without words but with a pause in the song. */
  instrumental: boolean;
  timed: boolean;
};

const STAGGER_MS = 50;
const SPACE = /^\s+$/u;

export const cubicBezier = (x1: number, y1: number, x2: number, y2: number) => {
  // Newton steps, then bisection when the slope is too flat to trust.
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  const x = (t: number) => ((ax * t + bx) * t + cx) * t;
  const y = (t: number) => ((ay * t + by) * t + cy) * t;
  const dx = (t: number) => (3 * ax * t + 2 * bx) * t + cx;
  return (p: number) => {
    if (p <= 0) return 0;
    if (p >= 1) return 1;
    let t = p;
    for (let i = 0; i < 6; i++) {
      const e = x(t) - p, d = dx(t);
      if (Math.abs(e) < 1e-5) return y(t);
      if (Math.abs(d) < 1e-6) break;
      t -= e / d;
    }
    let lo = 0, hi = 1;
    t = p;
    for (let i = 0; i < 24; i++) {
      if (x(t) < p) lo = t;
      else hi = t;
      t = (lo + hi) / 2;
    }
    return y(t);
  };
};

const clamp01 = (v: number) => (v < 0 ? 0 : v > 1 ? 1 : v);
const ease = cubicBezier(0.25, 0.1, 0.25, 1);
/** Slow out, quick through the middle, soft landing: the glide of a line. */
export const glideEase = cubicBezier(0.86, 0, 0.2, 1);

/** A timed word cut at its spaces, its time shared by letters. */
function split(text: string, start: number, dur: number): Token[] {
  const chunks = text.match(/\s+|\S+/gu) ?? [];
  const letters = chunks.reduce((n, c) => (SPACE.test(c) ? n : n + c.length), 0) || 1;
  let seen = 0;
  return chunks.map((c) => {
    if (SPACE.test(c)) return { text: c, start: 0, dur: 0, space: true };
    const from = start + Math.round((dur * seen) / letters);
    seen += c.length;
    return { text: c, start: from, dur: start + Math.round((dur * seen) / letters) - from, space: false };
  });
}

function group(tokens: Token[]): Group[] {
  const out: Group[] = [];
  let word: Group | null = null;
  for (const t of tokens) {
    if (t.space) {
      word = null;
      out.push({ tokens: [t], space: true });
    } else {
      if (!word) out.push((word = { tokens: [], space: false }));
      word.tokens.push(t);
    }
  }
  return out;
}

export function buildLines(lines: LyricLine[], durationMs: number): LineModel[] {
  return lines.map((l, i) => {
    const next = lines[i + 1]?.ms ?? (durationMs > l.ms ? durationMs : l.ms + 6000);
    const end = l.endMs && l.endMs > l.ms ? l.endMs : next;
    let tokens: Token[];
    if (l.words?.length) {
      tokens = l.words.flatMap((w) => split(w.text, w.ms, Math.max(0, w.endMs - w.ms)));
    } else {
      // No word timings: the words show one after the other as the line starts.
      let n = 0;
      tokens = (l.text.match(/\s+|\S+/gu) ?? []).map((c) =>
        SPACE.test(c) ? { text: c, start: 0, dur: 0, space: true } : { text: c, start: l.ms + n++ * STAGGER_MS, dur: 0, space: false },
      );
    }
    return {
      start: l.ms,
      end,
      exit: Math.max(next, end + 50),
      text: l.text,
      groups: group(tokens),
      bg: l.bg,
      instrumental: !l.text.trim(),
      timed: !!l.words?.length,
    };
  });
}

// --- one token's light ---------------------------------------------------------------------

const LEAD = 0.1;
const SWEEP = 1.6;
const FROM = -0.2;
const TO = 1.4;
const EDGE = 0.1;
const FADE_IN_MS = 330;
const FADE_OUT_MS = 500;
const LONG_MS = 1500;
const GLOW_MIN_MS = 1200;

/** Lit share of a token: fully lit left of `solid`, unlit right of `solid + EDGE`; `glow` 0..1. */
export type Light = { alpha: number; solid: number; glow: number };
export const DARK: Light = { alpha: 0, solid: FROM, glow: 0 };

export function lightOf(t: Token, line: LineModel, at: number): Light {
  if (at < t.start || t.start > line.exit) return DARK;
  if (at >= line.exit) {
    const fade = 1 - ease(clamp01((at - line.exit) / FADE_OUT_MS));
    return fade <= 0 ? DARK : { alpha: fade, solid: TO, glow: 0 };
  }
  const since = at - t.start;
  if (t.dur <= 0) return { alpha: ease(clamp01(since / FADE_IN_MS)), solid: TO, glow: 0 };
  const sweepMs = t.dur * SWEEP;
  const solid = FROM + ((TO - FROM) * Math.min(since + t.dur * LEAD, sweepMs)) / sweepMs;
  let glow = 0;
  if (t.dur > LONG_MS) {
    const glowMs = Math.max(t.dur * 1.2, GLOW_MIN_MS);
    if (since < glowMs) glow = 1 - ease(since / glowMs);
  }
  return { alpha: 1, solid, glow };
}

export const EDGE_WIDTH = EDGE;

/** Whether a line needs painting every frame at `at`. */
export function isLive(line: LineModel, at: number): boolean {
  return at + 2000 >= line.start && at < line.exit + FADE_OUT_MS + 50;
}

// --- following the song --------------------------------------------------------------------

export const TARGET = 0.37;
const LOOK_AHEAD_MS = 500;
const EARLY_MS = 540;
const ENDING_MS = 500;

export type Box = { top: number; height: number };

/** Where the list should stand for the lines being sung at `at` (scroll top), and its anchor. */
export function target(lines: LineModel[], boxes: Box[], at: number, view: number) {
  const t = at + LOOK_AHEAD_MS;
  let active: number[] = [];
  lines.forEach((l, i) => {
    if (t >= l.start - EARLY_MS && (t < l.exit - 50 || t < l.end)) active.push(i);
  });
  if (!active.length) {
    // Between lines: the last one that started, or the first.
    let last = 0;
    lines.forEach((l, i) => l.start <= t && (last = i));
    active = [last];
  }
  const sung = active.filter((i, k) => t < lines[i].end - ENDING_MS || k === active.length - 1);
  const running = sung.filter((i) => lines[i].start <= t && t < lines[i].end);
  const started = sung.filter((i) => lines[i].start <= t);
  const anchor = running.at(-1) ?? started.at(-1) ?? sung[0];
  const first = boxes[sung[0]], last = boxes[sung[sung.length - 1]], a = boxes[anchor];
  const centre = sung.reduce((n, i) => n + boxes[i].top + boxes[i].height / 2, 0) / sung.length;
  let top = centre - view * TARGET;
  top = Math.max(top, last.top + last.height - view);
  top = Math.min(top, last.top, first.top);
  top = Math.max(top, a.top + a.height - view);
  top = Math.min(top, a.top);
  return { top, anchor: active[active.length - 1], active, overflows: last.top + last.height - first.top > view };
}

/** How far the list can scroll: enough for the last line to reach the target height. */
export function maxScroll(boxes: Box[], view: number): number {
  const last = boxes[boxes.length - 1];
  if (!last) return 0;
  const content = last.top + last.height;
  const tail = 1 - TARGET;
  const extra = Math.max(last.top + last.height / 2 + view * tail - content, view - content, 0);
  return Math.max(0, content + Math.ceil(extra) - view);
}

type Glide = { delta: number; at: number; anchor: number };

/** The list's scroll: glides to where the song is, each line on its own delay. */
export class Scroll {
  pos = 0;
  max = 0;
  private glides: Glide[] = [];
  private wheelTo: number | null = null;

  limit(max: number) {
    this.max = Math.max(0, max);
    this.pos = this.clamp(this.pos);
    if (this.wheelTo != null) this.wheelTo = this.clamp(this.wheelTo);
  }

  jump(to: number) {
    this.pos = this.clamp(to);
    this.glides = [];
    this.wheelTo = null;
  }

  glide(to: number, anchor: number, now: number) {
    to = this.clamp(to);
    this.wheelTo = null;
    const delta = to - this.pos;
    if (Math.abs(delta) <= 2) {
      this.pos = to;
      return;
    }
    this.glides.push({ delta, at: now, anchor });
    this.pos = to;
  }

  wheel(dy: number) {
    this.wheelTo = this.clamp((this.wheelTo ?? this.pos) + dy);
  }

  step(now: number, dt: number) {
    this.glides = this.glides.filter((g) => now - g.at < 1900);
    if (this.wheelTo == null) return;
    const left = this.wheelTo - this.pos;
    if (Math.abs(left) < 0.5) {
      this.pos = this.wheelTo;
      this.wheelTo = null;
      return;
    }
    this.pos += left * (1 - Math.exp(-dt / 70));
  }

  /** How far line `i` still lags behind the scroll (it catches up on its own delay). */
  lag(i: number, now: number): number {
    let off = 0;
    for (const g of this.glides) {
      const d = Math.abs(i - g.anchor);
      const above = (i < g.anchor && g.delta >= 0) || (i > g.anchor && g.delta < 0);
      const ms = above ? 750 + Math.min(d, 6) * 20 : 750 + Math.log(d + 2) * 80 + (d + 1) * 20;
      off += g.delta * (1 - glideEase(clamp01((now - g.at) / ms)));
    }
    return off;
  }

  get moving() {
    return this.glides.length > 0 || this.wheelTo != null;
  }

  private clamp(v: number) {
    return Math.min(this.max, Math.max(0, v));
  }
}

/** Decides when the list moves: when a new line comes in, the song jumps, or a held line ends. */
export class Follower {
  private committed: number[] = [];
  private pinned: number | null = null;
  private last = NaN;
  private forced = true;

  reset() {
    this.committed = [];
    this.pinned = null;
    this.forced = true;
  }

  decide(lines: LineModel[], boxes: Box[], at: number, view: number) {
    if (!lines.length || boxes.length !== lines.length) return null;
    const t = at + LOOK_AHEAD_MS;
    const jumped = Number.isNaN(this.last) || Math.abs(at - this.last) > 500;
    this.last = at;
    const goal = target(lines, boxes, at, view);
    const entered = goal.active.some((i) => !this.committed.includes(i) && t >= lines[i].start);
    const pinnedEnded =
      this.pinned != null && this.pinned !== goal.anchor && (!goal.active.includes(this.pinned) || t >= lines[this.pinned].end);
    if (!this.forced && !jumped && !entered && !pinnedEnded) return null;
    const forced = this.forced;
    this.forced = false;
    this.committed = goal.active;
    this.pinned = goal.overflows ? goal.anchor : null;
    return { top: goal.top, anchor: goal.anchor, jump: forced };
  }
}
