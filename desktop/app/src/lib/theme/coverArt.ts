// Reads the playing cover once: its two accent colors (CoverPalette.kt) and a tiny blurred,
// saturated copy that the Backdrop draws huge (DynamicCoverBackdrop.kt).

import { cover } from "../connect.svelte";
import { coverAccent, type CoverAccent } from "./palette";

export type CoverArt = { url: string; accent: CoverAccent; blurred: HTMLCanvasElement };

const BLUR_SIZE = 48;
const cache = new Map<string, CoverArt>();

export function loadCoverArt(url: string): Promise<CoverArt> {
  const hit = cache.get(url);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.decoding = "async";
    img.onload = () => {
      try {
        const canvas = document.createElement("canvas");
        canvas.width = canvas.height = BLUR_SIZE;
        const ctx = canvas.getContext("2d", { willReadFrequently: true })!;
        ctx.drawImage(img, 0, 0, BLUR_SIZE, BLUR_SIZE);
        const image = ctx.getImageData(0, 0, BLUR_SIZE, BLUR_SIZE);
        const accent = coverAccent(image.data);
        // A light blur on a tiny copy: drawn huge, the artwork keeps its color regions as soft shapes.
        boxBlur(image.data, BLUR_SIZE, BLUR_SIZE, 2);
        boxBlur(image.data, BLUR_SIZE, BLUR_SIZE, 2);
        saturate(image.data, 1.6);
        ctx.putImageData(image, 0, 0);
        const art = { url, accent, blurred: canvas };
        if (cache.size > 12) cache.delete(cache.keys().next().value!);
        cache.set(url, art);
        resolve(art);
      } catch (e) {
        reject(e);
      }
    };
    img.onerror = reject;
    img.src = cover(url, 96) ?? url;
  });
}

function boxBlur(px: Uint8ClampedArray, w: number, h: number, r: number) {
  const tmp = new Uint8ClampedArray(px.length);
  const pass = (src: Uint8ClampedArray, dst: Uint8ClampedArray, horizontal: boolean) => {
    const lines = horizontal ? h : w;
    const len = horizontal ? w : h;
    const win = 2 * r + 1;
    for (let line = 0; line < lines; line++) {
      const idx = (i: number) => {
        const k = Math.min(len - 1, Math.max(0, i));
        return (horizontal ? line * w + k : k * w + line) * 4;
      };
      const sum = [0, 0, 0, 0];
      for (let i = -r; i <= r; i++) {
        const p = idx(i);
        for (let c = 0; c < 4; c++) sum[c] += src[p + c];
      }
      for (let i = 0; i < len; i++) {
        const o = idx(i);
        for (let c = 0; c < 4; c++) dst[o + c] = sum[c] / win;
        const out = idx(i - r);
        const inn = idx(i + r + 1);
        for (let c = 0; c < 4; c++) sum[c] += src[inn + c] - src[out + c];
      }
    }
  };
  pass(px, tmp, true);
  pass(tmp, px, false);
}

/** ColorMatrix.setToSaturation, in place. */
function saturate(px: Uint8ClampedArray, s: number) {
  const lr = 0.213, lg = 0.715, lb = 0.072;
  for (let i = 0; i < px.length; i += 4) {
    const r = px[i], g = px[i + 1], b = px[i + 2];
    const l = lr * r + lg * g + lb * b;
    px[i] = l + (r - l) * s;
    px[i + 1] = l + (g - l) * s;
    px[i + 2] = l + (b - l) * s;
  }
}
