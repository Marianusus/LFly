// Liquid Glass lenses: the refraction of Kyant0's backdrop (the phone's components/backdrop),
// ported to the web.
//
// WebKit (Linux, macOS) and Firefox cannot run an SVG filter in backdrop-filter, so there the lens
// is drawn: one shared WebGL context samples the app canvas (scene.ts) at every lens's place on
// screen, bends it with the same rounded-rect SDF the AGSL shader uses (refraction near the edges,
// depth, chromatic dispersion), and copies the result into a small canvas inside the glass. Only the
// rim band is drawn; the middle stays clear so the CSS backdrop blur shows what is behind.
//
// Chromium (Windows WebView2) can, so there each lens gets an SVG displacement map built from the
// same SDF, and the real content behind the glass is refracted, dispersed and blurred by the browser.
//
// Work is done only when something moved: a lens is drawn again when its place, size or the scene
// changes, never while the window is hidden, and not at all when it is scrolled out of sight.

import { scene } from "./scene";

export type LensOptions = {
  /** Corner radius in CSS px (a pill passes half its height; it is clamped anyway). */
  radius: () => number;
  /** 0..1, from the person's glass setting. */
  strength: () => number;
  /** Direction the light comes from, degrees (screen: 0 = right, 90 = down). */
  angle?: () => number;
};

export type LensBackend = "webgl" | "svg" | "none";

const VERT = `
attribute vec2 aPos;
uniform vec2 uSize;
varying vec2 vLocal;
void main() {
  vLocal = (aPos * 0.5 + 0.5) * uSize;
  vLocal.y = uSize.y - vLocal.y;
  gl_Position = vec4(aPos, 0.0, 1.0);
}`;

// The AGSL of components/backdrop/internal/Shaders.kt (RoundedRectRefractionWithDispersion), in GLSL ES 1.0.
const FRAG = `
precision highp float;
uniform sampler2D uScene;
uniform vec2 uView;
uniform vec4 uRect;
uniform vec2 uSize;
uniform float uRadius;
uniform float uRefH;
uniform float uRefAmt;
uniform float uDepth;
uniform float uDisp;
uniform float uSat;
uniform float uLip;
uniform float uLight;
uniform float uRefAlpha;
varying vec2 vLocal;

float sdRoundedRect(vec2 p, vec2 h, float r) {
  vec2 c = abs(p) - (h - vec2(r));
  return length(max(c, 0.0)) - r + min(max(c.x, c.y), 0.0);
}

vec2 gradSdRoundedRect(vec2 p, vec2 h, float r) {
  vec2 c = abs(p) - (h - vec2(r));
  if (c.x >= 0.0 || c.y >= 0.0) {
    return sign(p) * normalize(max(c, 0.0) + vec2(1e-5));
  }
  float gx = step(c.y, c.x);
  return sign(p) * vec2(gx, 1.0 - gx);
}

vec3 sat(vec3 c) {
  float l = dot(c, vec3(0.213, 0.715, 0.072));
  return clamp(mix(vec3(l), c, uSat), 0.0, 1.0);
}

vec3 sampleAt(vec2 local) {
  vec2 uv = (uRect.xy + local * (uRect.zw / uSize)) / uView;
  return texture2D(uScene, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
}

void main() {
  vec2 halfSize = uSize * 0.5;
  vec2 p = vLocal - halfSize;
  float radius = min(uRadius, min(halfSize.x, halfSize.y));
  float sd = sdRoundedRect(p, halfSize, radius);
  float inside = -sd;
  if (inside >= uRefH || sd > 0.5) {
    gl_FragColor = vec4(0.0);
    return;
  }
  inside = max(inside, 0.0);
  float x = 1.0 - inside / uRefH;
  float d = (1.0 - sqrt(1.0 - x * x)) * uRefAmt;
  float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
  vec2 grad = normalize(gradSdRoundedRect(p, halfSize, gradRadius) + uDepth * normalize(p + vec2(1e-4)));
  vec2 refracted = vLocal - d * grad;
  float di = uDisp * ((p.x * p.y) / (halfSize.x * halfSize.y));
  vec2 dc = -d * grad * di;

  vec3 c = vec3(0.0);
  vec3 red = sampleAt(refracted + dc);
  c.r += red.r / 3.5;
  vec3 orange = sampleAt(refracted + dc * (2.0 / 3.0));
  c.r += orange.r / 3.5; c.g += orange.g / 7.0;
  vec3 yellow = sampleAt(refracted + dc * (1.0 / 3.0));
  c.r += yellow.r / 3.5; c.g += yellow.g / 3.5;
  vec3 green = sampleAt(refracted);
  c.g += green.g / 3.5;
  vec3 cyan = sampleAt(refracted - dc * (1.0 / 3.0));
  c.g += cyan.g / 3.5; c.b += cyan.b / 3.0;
  vec3 blue = sampleAt(refracted - dc * (2.0 / 3.0));
  c.b += blue.b / 3.0;
  vec3 purple = sampleAt(refracted - dc);
  c.r += purple.r / 7.0; c.b += purple.b / 3.0;

  c = sat(c);
  // Strongest at the rim, fading to nothing where the bend ends, so it meets the CSS blur seamlessly.
  float a = smoothstep(0.0, 1.0, x) * uRefAlpha;
  // The thick glass lip catches light: brightest where the edge faces the light (and its opposite,
  // as Kyant's Highlight.Default at 45°), a little everywhere along the rim.
  vec2 n = gradSdRoundedRect(p, halfSize, gradRadius);
  vec2 l = vec2(cos(uLight), sin(uLight));
  float facing = pow(abs(dot(n, l)), 1.5);
  float lip = pow(x, 4.0) * (0.3 + 0.7 * facing) * uLip;
  // Refracted canvas, then the lip over it (premultiplied).
  vec3 col = c * a * (1.0 - lip) + vec3(lip);
  float alpha = a * (1.0 - lip) + lip;
  // Antialiased outer edge.
  float edge = clamp(0.5 - sd, 0.0, 1.0);
  gl_FragColor = vec4(col, alpha) * edge;
}`;

type Entry = {
  el: HTMLElement;
  host: HTMLElement;
  opts: LensOptions;
  canvas: HTMLCanvasElement | null;
  ctx: CanvasRenderingContext2D | null;
  visible: boolean;
  key: string;
  svg?: SvgLens;
};

type SvgLens = { svg: SVGSVGElement; id: string; key: string };

const isChromium = typeof navigator !== "undefined" && /Chrome\/|Chromium\/|Edg\//.test(navigator.userAgent);

class LensEngine {
  backend: LensBackend = "none";
  private gl: WebGLRenderingContext | null = null;
  private glCanvas: HTMLCanvasElement | null = null;
  private prog: WebGLProgram | null = null;
  private tex: WebGLTexture | null = null;
  private u: Record<string, WebGLUniformLocation | null> = {};
  private uploaded = -1;
  private entries = new Set<Entry>();
  private raf = 0;
  private io: IntersectionObserver | null = null;
  private ro: ResizeObserver | null = null;
  private poll = 0;
  private seq = 0;

  constructor() {
    if (isChromium && typeof CSS !== "undefined" && CSS.supports("backdrop-filter", "url(#a)")) {
      this.backend = "svg";
    } else if (this.initGl()) {
      this.backend = "webgl";
    }
    if (this.backend === "none") return;
    this.io = new IntersectionObserver((list) => {
      for (const e of list) {
        const entry = [...this.entries].find((x) => x.el === e.target);
        if (entry) entry.visible = e.isIntersecting;
      }
      this.invalidate();
    });
    this.ro = new ResizeObserver(() => this.invalidate());
    const wake = () => this.invalidate();
    addEventListener("scroll", wake, { capture: true, passive: true });
    addEventListener("resize", wake);
    document.addEventListener("visibilitychange", wake);
    if (this.backend === "webgl") scene().subscribe(wake);
  }

  private initGl(): boolean {
    try {
      const canvas = document.createElement("canvas");
      canvas.width = canvas.height = 256;
      const gl = canvas.getContext("webgl", {
        alpha: true,
        premultipliedAlpha: true,
        preserveDrawingBuffer: true,
        antialias: false,
        depth: false,
        stencil: false,
        powerPreference: "low-power",
      }) as WebGLRenderingContext | null;
      if (!gl) return false;
      const compile = (type: number, src: string) => {
        const s = gl.createShader(type)!;
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? "shader");
        return s;
      };
      const prog = gl.createProgram()!;
      gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT));
      gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG));
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog) ?? "link");
      gl.useProgram(prog);
      const buf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      const loc = gl.getAttribLocation(prog, "aPos");
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      for (const name of ["uScene", "uView", "uRect", "uSize", "uRadius", "uRefH", "uRefAmt", "uDepth", "uDisp", "uSat", "uLip", "uLight", "uRefAlpha"]) {
        this.u[name] = gl.getUniformLocation(prog, name);
      }
      gl.uniform1i(this.u.uScene, 0);
      canvas.addEventListener("webglcontextlost", (e) => {
        e.preventDefault();
        this.backend = "none";
        for (const entry of this.entries) entry.canvas?.remove();
      });
      this.gl = gl;
      this.glCanvas = canvas;
      this.prog = prog;
      this.tex = tex;
      return true;
    } catch (e) {
      console.warn("Liquid Glass: lente WebGL non disponibile", e);
      return false;
    }
  }

  /** Starts drawing the lens of [el] into [host] (an absolutely placed layer inside it). */
  attach(el: HTMLElement, host: HTMLElement, opts: LensOptions): () => void {
    if (this.backend === "none") return () => {};
    const entry: Entry = { el, host, opts, canvas: null, ctx: null, visible: true, key: "" };
    if (this.backend === "webgl") {
      const canvas = document.createElement("canvas");
      canvas.className = "lfly-lens";
      canvas.setAttribute("aria-hidden", "true");
      host.prepend(canvas);
      entry.canvas = canvas;
      entry.ctx = canvas.getContext("2d");
    }
    this.entries.add(entry);
    this.io?.observe(el);
    this.ro?.observe(el);
    if (!this.poll) {
      // Catches layout shifts nothing announces (images loading above, panels opening).
      this.poll = window.setInterval(() => this.invalidate(), 400);
    }
    this.invalidate();
    return () => {
      this.entries.delete(entry);
      this.io?.unobserve(el);
      this.ro?.unobserve(el);
      entry.canvas?.remove();
      if (entry.svg) {
        entry.svg.svg.remove();
        el.style.removeProperty("backdrop-filter");
        el.style.removeProperty("-webkit-backdrop-filter");
      }
      if (this.entries.size === 0 && this.poll) {
        clearInterval(this.poll);
        this.poll = 0;
      }
    };
  }

  invalidate() {
    if (this.raf || this.entries.size === 0) return;
    this.raf = requestAnimationFrame(() => {
      this.raf = 0;
      if (document.hidden) return;
      if (this.backend === "webgl") this.drawGl();
      else if (this.backend === "svg") this.drawSvg();
    });
  }

  // ---- WebGL: WebKit, Firefox ----

  private drawGl() {
    const gl = this.gl!;
    const sc = scene();
    let sceneChanged = false;
    if (this.uploaded !== sc.version) {
      gl.bindTexture(gl.TEXTURE_2D, this.tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, sc.canvas);
      this.uploaded = sc.version;
      sceneChanged = true;
    }
    const dpr = Math.min(2, devicePixelRatio || 1);
    const q = dpr * 0.75;
    const vw = sc.width;
    const vh = sc.height;
    const light = document.documentElement.dataset.scheme === "light";
    for (const e of this.entries) {
      if (!e.visible || !e.ctx || !e.canvas) continue;
      const r = e.el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) continue;
      // Scaled ancestors (a pressed button) report a scaled rect: draw at layout size, sample at screen place.
      const w = e.el.offsetWidth || r.width;
      const h = e.el.offsetHeight || r.height;
      const k = e.opts.strength();
      const radius = e.opts.radius();
      const key = `${light},${r.left.toFixed(1)},${r.top.toFixed(1)},${r.width.toFixed(1)},${r.height.toFixed(1)},${w},${h},${radius},${k},${e.opts.angle?.() ?? 225}`;
      if (!sceneChanged && key === e.key) continue;
      e.key = key;
      const cw = Math.max(1, Math.round(w * q));
      const ch = Math.max(1, Math.round(h * q));
      if (e.canvas.width !== cw || e.canvas.height !== ch) {
        e.canvas.width = cw;
        e.canvas.height = ch;
      }
      this.ensureGlSize(cw, ch);
      const minHalf = Math.min(w, h) / 2;
      const refH = Math.min(minHalf, 10 + 18 * k);
      const refAmt = 8 + 26 * k;
      gl.viewport(0, 0, cw, ch);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.uniform2f(this.u.uView, vw, vh);
      gl.uniform4f(this.u.uRect, r.left, r.top, r.width, r.height);
      gl.uniform2f(this.u.uSize, w, h);
      gl.uniform1f(this.u.uRadius, Math.min(radius, minHalf));
      gl.uniform1f(this.u.uRefH, refH);
      gl.uniform1f(this.u.uRefAmt, refAmt);
      gl.uniform1f(this.u.uDepth, 1);
      gl.uniform1f(this.u.uDisp, 0.6 + 0.6 * k);
      gl.uniform1f(this.u.uSat, 1 + 0.5 * k);
      gl.uniform1f(this.u.uLip, light ? 0.6 : 0.28);
      // A flat canvas (Nero AMOLED, Bianco) has nothing to bend: only the lip shows there, so the rim
      // never darkens what scrolls under the glass.
      gl.uniform1f(this.u.uRefAlpha, sc.flat ? 0 : 0.8);
      gl.uniform1f(this.u.uLight, (e.opts.angle?.() ?? 225) * (Math.PI / 180));
      // The quad's vLocal is in CSS px of the lens; the viewport maps it onto cw × ch pixels.
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const H = this.glCanvas!.height;
      e.ctx.clearRect(0, 0, cw, ch);
      e.ctx.drawImage(this.glCanvas!, 0, H - ch, cw, ch, 0, 0, cw, ch);
    }
  }

  private ensureGlSize(w: number, h: number) {
    const c = this.glCanvas!;
    if (c.width >= w && c.height >= h) return;
    c.width = Math.max(c.width, Math.ceil(w / 64) * 64);
    c.height = Math.max(c.height, Math.ceil(h / 64) * 64);
  }

  // ---- SVG displacement: Chromium ----

  private drawSvg() {
    for (const e of this.entries) {
      if (!e.visible) continue;
      const w = Math.round(e.el.offsetWidth);
      const h = Math.round(e.el.offsetHeight);
      if (w < 2 || h < 2) continue;
      const k = e.opts.strength();
      const radius = Math.min(e.opts.radius(), Math.min(w, h) / 2);
      const key = `${w},${h},${radius},${k}`;
      // Re-applied if the element's style attribute was rewritten since.
      if (e.svg?.key === key && e.el.style.getPropertyValue("backdrop-filter").includes(e.svg.id)) continue;
      if (e.svg?.key !== key) {
        e.svg?.svg.remove();
        e.svg = this.buildSvg(w, h, radius, k, key);
        document.body.appendChild(e.svg.svg);
      }
      const svg = e.svg!;
      const blur = getComputedStyle(document.documentElement).getPropertyValue("--lfly-glass-blur").trim() || "20px";
      const sat = getComputedStyle(document.documentElement).getPropertyValue("--lfly-glass-sat").trim() || "1.5";
      const value = `url(#${svg.id}) blur(${blur}) saturate(${sat})`;
      e.el.style.setProperty("backdrop-filter", value);
      e.el.style.setProperty("-webkit-backdrop-filter", value);
    }
  }

  private buildSvg(w: number, h: number, radius: number, k: number, key: string): SvgLens {
    const refH = Math.min(Math.min(w, h) / 2, 10 + 18 * k);
    const refAmt = 8 + 26 * k;
    const map = displacementMap(w, h, radius, refH, refAmt);
    const id = `lfly-lens-${++this.seq}`;
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("width", "0");
    svg.setAttribute("height", "0");
    svg.setAttribute("aria-hidden", "true");
    svg.style.position = "absolute";
    const scale = refAmt * 2;
    const disp = 0.06 + 0.06 * k;
    svg.innerHTML = `<filter id="${id}" x="0" y="0" width="${w}" height="${h}" filterUnits="userSpaceOnUse" primitiveUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
  <feImage href="${map}" x="0" y="0" width="${w}" height="${h}" preserveAspectRatio="none" result="map"/>
  <feDisplacementMap in="SourceGraphic" in2="map" scale="${(scale * (1 + disp)).toFixed(2)}" xChannelSelector="R" yChannelSelector="G" result="dr"/>
  <feColorMatrix in="dr" type="matrix" values="1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0" result="r"/>
  <feDisplacementMap in="SourceGraphic" in2="map" scale="${scale.toFixed(2)}" xChannelSelector="R" yChannelSelector="G" result="dg"/>
  <feColorMatrix in="dg" type="matrix" values="0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0 1 0" result="g"/>
  <feDisplacementMap in="SourceGraphic" in2="map" scale="${(scale * (1 - disp)).toFixed(2)}" xChannelSelector="R" yChannelSelector="G" result="db"/>
  <feColorMatrix in="db" type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 1 0" result="b"/>
  <feBlend in="r" in2="g" mode="screen" result="rg"/>
  <feBlend in="rg" in2="b" mode="screen"/>
</filter>`;
    return { svg, id, key };
  }
}

/**
 * The lens as a displacement map: red and green carry how far, and which way, each pixel bends
 * (128 = not at all). Same SDF and circle profile as the shader.
 */
function displacementMap(w: number, h: number, radius: number, refH: number, refAmt: number): string {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  const ctx = c.getContext("2d")!;
  const img = ctx.createImageData(w, h);
  const hx = w / 2, hy = h / 2;
  const gr = Math.min(radius * 1.5, Math.min(hx, hy));
  const sd = (px: number, py: number, r: number) => {
    const cx = Math.abs(px) - (hx - r), cy = Math.abs(py) - (hy - r);
    return Math.hypot(Math.max(cx, 0), Math.max(cy, 0)) - r + Math.min(Math.max(cx, cy), 0);
  };
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      const px = x + 0.5 - hx, py = y + 0.5 - hy;
      const inside = -sd(px, py, radius);
      let dx = 0, dy = 0;
      if (inside < refH && inside > -1) {
        const t = 1 - Math.max(0, inside) / refH;
        const d = (1 - Math.sqrt(Math.max(0, 1 - t * t))) * refAmt;
        const cx = Math.abs(px) - (hx - gr), cy = Math.abs(py) - (hy - gr);
        let gx: number, gy: number;
        if (cx >= 0 || cy >= 0) {
          const mx = Math.max(cx, 0), my = Math.max(cy, 0);
          const l = Math.hypot(mx, my) || 1;
          gx = Math.sign(px) * (mx / l);
          gy = Math.sign(py) * (my / l);
        } else {
          const s = cy <= cx ? 1 : 0;
          gx = Math.sign(px) * s;
          gy = Math.sign(py) * (1 - s);
        }
        const pl = Math.hypot(px, py) || 1;
        gx += px / pl;
        gy += py / pl;
        const gl = Math.hypot(gx, gy) || 1;
        dx = (-d * gx) / gl;
        dy = (-d * gy) / gl;
      }
      img.data[i] = 128 + (dx / refAmt) * 127;
      img.data[i + 1] = 128 + (dy / refAmt) * 127;
      img.data[i + 2] = 128;
      img.data[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return c.toDataURL();
}

let engine: LensEngine | null = null;

export function lensEngine(): LensEngine {
  return (engine ??= new LensEngine());
}
