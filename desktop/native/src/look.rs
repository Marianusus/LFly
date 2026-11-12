//! The look in use (Tauri: theme/theme.svelte.ts): mode, the scheme built from the accent or the
//! playing cover, the glass setting, and the painted scene. Views read it; it notifies on change.

use std::sync::Arc;

use gpui_kit::*;

use crate::glass::GlassLook;
use crate::scene::{self, SceneSpec};
use crate::theme::{build_scheme, Mode, Rgb, Scheme};

pub struct Look {
    pub mode: Mode,
    pub scheme: Scheme,
    /// The glass setting (theme.svelte.ts DEFAULT_GLASS).
    pub glass_enabled: bool,
    pub lens: bool,
    pub strength: f32,
    cover: Option<(Rgb, Rgb)>,
    scene: Option<Arc<Image>>,
    /// What the scene in `scene` was painted for.
    painted: Option<(SceneSpec, (f32, f32))>,
    painting: bool,
}

impl Look {
    pub fn new() -> Self {
        let mode = Mode::DynamicDark;
        Self {
            mode,
            scheme: build_scheme(mode, None, None),
            glass_enabled: true,
            lens: true,
            strength: 0.75,
            cover: None,
            scene: None,
            painted: None,
            painting: false,
        }
    }

    /// The colours of the playing cover drive the dynamic modes.
    pub fn set_cover_colors(&mut self, colors: Option<(Rgb, Rgb)>, cx: &mut Context<Self>) {
        if self.cover == colors {
            return;
        }
        self.cover = colors;
        self.rebuild(cx);
    }

    fn rebuild(&mut self, cx: &mut Context<Self>) {
        let (p, s) = if self.mode.dynamic() { (self.cover.map(|c| c.0), self.cover.map(|c| c.1)) } else { (None, None) };
        self.scheme = build_scheme(self.mode, p, s);
        cx.notify();
    }

    pub fn glass(&self) -> GlassLook {
        let mut g = GlassLook::dark(self.strength);
        g.light = self.scheme.light;
        g.border = self.scheme.glass_border;
        g.lens = self.lens && self.glass_enabled;
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
