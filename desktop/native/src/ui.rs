//! Window layout choices that last between sessions (Tauri: ui.svelte.ts): the right panel and
//! the sidebar's width. Kept under the same store key as the Tauri window's.

use gpui_kit::*;
use serde_json::{json, Value};

const KEY: &str = "ui";

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum SidePanel {
    Queue,
    Lyrics,
    Devices,
}

impl SidePanel {
    fn name(self) -> &'static str {
        match self {
            SidePanel::Queue => "queue",
            SidePanel::Lyrics => "lyrics",
            SidePanel::Devices => "devices",
        }
    }

    fn parse(v: &str) -> Option<Self> {
        match v {
            "queue" => Some(SidePanel::Queue),
            "lyrics" => Some(SidePanel::Lyrics),
            "devices" => Some(SidePanel::Devices),
            _ => None,
        }
    }
}

pub struct Ui {
    pub panel: Option<SidePanel>,
    pub sidebar_wide: bool,
}

impl Ui {
    pub fn new() -> Self {
        let saved = lfly_core::store::get(KEY).ok().flatten().unwrap_or(Value::Null);
        // LFLY_PANEL (queue, lyrics, devices) opens one at start, for screenshots.
        let panel = std::env::var("LFLY_PANEL").ok().and_then(|p| SidePanel::parse(&p));
        Self {
            panel: panel.or_else(|| saved.get("panel").and_then(Value::as_str).and_then(SidePanel::parse)),
            sidebar_wide: saved.get("sidebarWide").and_then(Value::as_bool).unwrap_or(true),
        }
    }

    fn save(&self) {
        let v = json!({ "panel": self.panel.map(SidePanel::name), "sidebarWide": self.sidebar_wide });
        if let Err(e) = lfly_core::store::set(KEY, &v) {
            log::warn!("{KEY}: {e}");
        }
    }

    pub fn set_panel(&mut self, panel: Option<SidePanel>, cx: &mut Context<Self>) {
        self.panel = panel;
        self.save();
        cx.notify();
    }

    pub fn toggle_panel(&mut self, panel: SidePanel, cx: &mut Context<Self>) {
        let next = if self.panel == Some(panel) { None } else { Some(panel) };
        self.set_panel(next, cx);
    }

    pub fn toggle_sidebar(&mut self, cx: &mut Context<Self>) {
        self.sidebar_wide = !self.sidebar_wide;
        self.save();
        cx.notify();
    }

    /// `ui.sideWide`: as chosen, unless the window is narrow or the panel needs the room.
    pub fn side_wide(&self, width: f32) -> bool {
        self.sidebar_wide && width > 900. && !(self.panel.is_some() && width < 1180.)
    }
}
