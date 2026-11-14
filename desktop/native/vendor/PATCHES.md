# GPUI, patched for LFly

`gpui-pre`, `gpui-pre-wgpu` and `gpui-pre-windows` 0.3.12 from crates.io (snapshots of Zed's GPUI,
Apache-2.0, see each crate's `LICENSE-APACHE`), wired in with `[patch.crates-io]` in
`../Cargo.toml`. The versions stay 0.3.12 because gpui-kit pins `=0.3.12`. Every change is marked
`LFly patch`.

## Liquid Glass primitive

LFly's glass (Tauri: `app/src/lib/glass/Glass.svelte` + `lens.ts`) needs a backdrop: what is already
drawn under an element, bent near the rim, blurred and saturated. GPUI has no backdrop filter, so:

- `gpui-pre/src/scene.rs`: `Glass` (device pixels) and `GlassStyle` (logical), `Primitive::Glass`,
  `PrimitiveKind::Glass`, `Scene::glass`, `PrimitiveBatch::Glass`. Glass sharing a draw order is
  one batch, so the backdrop is captured once for them.
- `gpui-pre/src/window.rs`: `Window::paint_glass(bounds, GlassStyle)`.
- `gpui-pre-wgpu` (Linux):
  - `src/glass.rs`, `src/glass.wgsl`, `src/glass_blur.wgsl`: for each glass batch the frame drawn
    so far is copied, shrunk (1/2 or 1/4) and blurred with a separable Gaussian. Then each glass
    is drawn: displacement-map refraction with red/blue dispersion (`displacementMap` and
    `buildSvg` in lens.ts), CSS `saturate()`, tint, the three inset shadows, the pointer's
    radial light, the 1px rim with its conic light, and the two outer shadows (clipped to outside
    the edge, as CSS does, and never part of the backdrop).
  - `src/wgpu_renderer.rs`: the surface is configured with `COPY_SRC` where the adapter allows
    it (otherwise the glass is drawn without a backdrop); `render_frame`/`record_frame` take the
    frame texture; the `Glass` batch arm ends the main pass, captures, and resumes it.
- `gpui-pre-windows` (DirectX 11): `src/glass_dx.rs` + `src/glass.hlsl`, a line-by-line port of
  the wgpu glass (keep the two in step). The HLSL is compiled once at start with `D3DCompile`;
  the frame is copied with `CopyResource` from the back buffer; constant buffers `b2`/`b3` and
  textures `t0`/`t2` leave GPUI's `b0`/`b1` alone, and the main target and viewport are bound
  again afterwards. If the shaders fail to build, glass is skipped and the error is logged once.
- `gpui-pre/build.rs`: `LFLY_CHECK_NO_RC=1` skips the Windows manifest resource, only so that
  `cargo check --target x86_64-pc-windows-gnu` runs from Linux (there is no `windres` there).

`Glass::edge_bleed` reproduces Chromium/WebView2: the lens filter's output stops at the element
box, so `blur()` after it fades toward the edges and the sharp backdrop shows through (1 = as the
Windows Tauri build looks, 0 = a clean blur). Checked against `chrome-headless-shell` 157
rendering the Tauri CSS over the same backdrop: mean difference 0.7/255.

macOS: the Metal renderer lives in `gpui-pre-apple` (vendored). Its batch loop gets an empty
`PrimitiveBatch::Glass` arm, so it builds; there is no Metal glass pass yet, and LFly paints solid
surfaces on macOS (`src/look.rs` `GLASS_RENDERER`). Porting `glass.wgsl` to Metal is the next step.

## Images that fill their box

`gpui-pre/src/elements/img.rs`: the image's own aspect ratio is applied only when one side is left
to it (width or height `Auto`). Upstream applied it even with both sides given (`size_full()`),
so a portrait cover in a square box grew past the box, over the text below it; with both sides
given the box wins, as in CSS, and `object_fit` fills it.

## Hover fade

`gpui-pre/src/elements/div.rs` (`HoverFade`, `mix_fill`, `compute_style_internal`): a hover
background fades in over 120 ms and out over 220 ms, easing out, instead of switching (the Tauri
app's CSS `transition: background`). Only the background blends (straight RGBA weighted by alpha);
the rest of the hover style switches at once. Only stateful elements (with an id) fade, the state
is written in the paint pass only, and nothing fades under `App::reduce_motion`.

## Blurred and scaled glyphs

The lyrics (Tauri: LyricsLines.svelte; phone: LyricsView.kt) blur the lines away from the one
sung, glow under what is sung and grow the word being sung. GPUI has no text blur or text shadow,
so:

- `gpui-pre/src/text_system.rs`: `RenderGlyphParams::blur` (a Gaussian's sigma in half device
  pixels, part of the atlas key). `TextSystem::raster_bounds` grows the sharp glyph's bounds by
  three sigmas; `rasterize_glyph` rasterises the sharp glyph with the platform's text system and
  blurs its one-byte mask (`blur_mask`, separable), so Linux and Windows both get it.
- `gpui-pre/src/window.rs`: `Window::paint_glyph_fx(origin, font, glyph, size, color, blur, scale,
  pivot)`, like `paint_glyph` but always a grayscale `MonochromeSprite` (sharp, blurred or scaled
  glyphs look alike), blurred glyphs on whole pixels, the scale a sprite transform about `pivot`.
