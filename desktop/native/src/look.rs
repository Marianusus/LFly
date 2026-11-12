//! The look in use (Tauri: theme/theme.svelte.ts): mode, the scheme built from the accent or the
//! playing cover, the glass setting, and the painted scene. Views read it; it notifies on change.

use std::sync::Arc;

use gpui_kit::*;

use crate::glass::GlassLook;
use crate::scene::{self, SceneSpec};
use crate::theme::{accent_color, build_scheme, Mode, Rgb, Scheme};

/// The store key Tauri keeps the look under (theme.svelte.ts SavedLook).
const KEY: &str = "appearance";

pub struct Look {
    pub mode: Mode,
    /// palette.ts ACCENTS id.
    pub accent: String,
    pub scheme: Scheme,
    /// The glass setting (theme.svelte.ts DEFAULT_GLASS).
    pub glass_enabled: bool,
    pub lens: bool,
    pub strength: f32,
    cover: Option<(Rgb, Rgb)>,
    /// The playing cover, tiny and blurred, for Copertina dinamica.
    pub cover_art: Option<Arc<tiny_skia::Pixmap>>,
    scene: Option<Arc<Image>>,
    /// What the scene in `scene` was painted for.
    painted: Option<(SceneSpec, (f32, f32))>,
    painting: bool,
}

impl Look {
    /// The look the person chose (in either app): mode, accent, glass.
    pub fn new() -> Self {
        let saved = lfly_core::store::get(KEY).ok().flatten().unwrap_or(serde_json::Value::Null);
        // LFLY_MODE (AMOLED_BLACK, PURE_WHITE, ...): a mode for screenshots; nothing is saved then.
        let forced = std::env::var("LFLY_MODE").ok().and_then(|m| Mode::parse(&m));
        let mode = forced.or_else(|| saved.get("mode").and_then(|v| v.as_str()).and_then(Mode::parse)).unwrap_or(Mode::DynamicDark);
        let accent = saved
            .get("accent")
            .and_then(|v| v.as_str())
            .filter(|a| crate::theme::ACCENTS.iter().any(|x| x.0 == *a))
            .unwrap_or("emerald")
            .to_string();
        let g = saved.get("glass");
        let flag = |k: &str, d: bool| g.and_then(|g| g.get(k)).and_then(|v| v.as_bool()).unwrap_or(d);
        let a = accent_color(&accent);
        Self {
            mode,
            accent,
            scheme: build_scheme(mode, Some(a), Some(a)),
            glass_enabled: flag("enabled", true),
            lens: flag("lens", true),
            strength: g.and_then(|g| g.get("strength")).and_then(|v| v.as_f64()).map_or(0.75, |v| v.clamp(0., 1.) as f32),
            cover: None,
            cover_art: None,
            scene: None,
            painted: None,
            painting: false,
        }
    }

    /// The playing cover read: its colours drive the dynamic modes, its blurred copy the flow.
    pub fn set_cover_art(&mut self, art: Option<((Rgb, Rgb), Arc<tiny_skia::Pixmap>)>, cx: &mut Context<Self>) {
        let (colors, image) = art.map_or((None, None), |(c, i)| (Some(c), Some(i)));
        self.cover_art = image;
        if self.cover == colors {
            cx.notify();
            return;
        }
        self.cover = colors;
        self.rebuild(cx);
    }

    fn rebuild(&mut self, cx: &mut Context<Self>) {
        // theme.svelte.ts scheme: the cover's colours in the dynamic modes, else the accent.
        let a = accent_color(&self.accent);
        let c = if self.mode.dynamic() { self.cover } else { None };
        self.scheme = build_scheme(self.mode, Some(c.map_or(a, |c| c.0)), Some(c.map_or(a, |c| c.1)));
        cx.notify();
    }

    /// The playing cover's two colours, when known.
    pub fn cover_colors(&self) -> Option<(Rgb, Rgb)> {
        self.cover
    }

    pub fn set_mode(&mut self, mode: Mode, cx: &mut Context<Self>) {
        self.mode = mode;
        self.rebuild(cx);
        self.save();
    }

    pub fn set_accent(&mut self, id: &str, cx: &mut Context<Self>) {
        self.accent = id.to_string();
        self.rebuild(cx);
        self.save();
    }

    pub fn set_glass(&mut self, enabled: Option<bool>, lens: Option<bool>, strength: Option<f32>, cx: &mut Context<Self>) {
        self.glass_enabled = enabled.unwrap_or(self.glass_enabled);
        self.lens = lens.unwrap_or(self.lens);
        self.strength = strength.map_or(self.strength, |s| s.clamp(0., 1.));
        cx.notify();
        self.save();
    }

    /// Written over what is saved, so fields only Tauri knows (its custom theme) stay.
    fn save(&self) {
        if std::env::var_os("LFLY_MODE").is_some() {
            return;
        }
        let mut v = lfly_core::store::get(KEY).ok().flatten().filter(|v| v.is_object()).unwrap_or_else(|| serde_json::json!({}));
        v["mode"] = self.mode.id().into();
        v["accent"] = self.accent.clone().into();
        v["glass"] = serde_json::json!({ "enabled": self.glass_enabled, "lens": self.lens, "strength": self.strength });
        if let Err(e) = lfly_core::store::set(KEY, &v) {
            log::warn!("{KEY}: {e}");
        }
    }

    pub fn glass(&self) -> GlassLook {
        let mut g = GlassLook::dark(self.strength);
        let k = self.strength;
        g.light = self.scheme.light;
        g.border = self.scheme.glass_border;
        g.lens = self.lens && self.glass_enabled;
        g.on = self.glass_enabled;
        // apply.ts --lfly-glass-tint: #fafafa or #121214; nearly opaque without glass.
        let alpha = if !self.glass_enabled { 0.97 } else if g.light { 0.5 + (1. - k) * 0.35 } else { 0.4 + (1. - k) * 0.4 };
        g.tint = if g.light { Rgb::hex(0xfafafa).hsla(alpha) } else { Rgb::hex(0x121214).hsla(alpha) };
        // apply.ts --lfly-glass-highlight / --lfly-glass-shade for the light schemes.
        if g.light {
            g.highlight = white().opacity(0.9);
            g.shade = black().opacity(0.08);
        }
        g
    }

    /// The scene for this window size: the last one painted, and a new one is painted in the
    /// background when the look or the size (by more than a few percent) changed.
    pub fn scene(&mut self, size: Size<Pixels>, cx: &mut Context<Self>) -> Option<Arc<Image>> {
        let spec = SceneSpec::from_scheme(&self.scheme);
        let (w, h) = (f32::from(size.width), f32::from(size.height));
        let fresh = self.painted.as_ref().is_some_and(|(s, (pw, ph))| {
            *s == spec && (w / pw - 1.).abs() < 0.04 && (h / ph - 1.).abs() < 0.04
        });
        if !fresh && !self.painting && w > 0. && h > 0. {
            self.painting = true;
            cx.spawn(async move |this, cx| {
                let s = spec.clone();
                let img = cx.background_executor().spawn(async move { scene::paint(&s, w, h) }).await;
                let _ = this.update(cx, |this, cx| {
                    this.painting = false;
                    this.scene = img;
                    this.painted = Some((spec, (w, h)));
                    cx.notify();
                });
            })
            .detach();
        }
        self.scene.clone()
    }
}
