//! The icon set of the Tauri window (Icon.svelte), generated from its paths: 24x24, stroked
//! at 1.8 with round caps, except the solid transport icons. Served as SVG assets under
//! `lfly/icons/<name>.svg` and tinted with the text colour, as `currentColor` there.

use std::borrow::Cow;

use gpui_kit::*;

const PATHS: &[(&str, &str)] = &[
    ("play", "M7 4.5v15a1 1 0 0 0 1.5.86l12.4-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5Z"),
    ("pause", "M6.5 4h3.5v16H6.5zM14 4h3.5v16H14z"),
    ("next", "M4 5.2v13.6a.8.8 0 0 0 1.2.7L15 13v5.5h2.5v-13H15V11L5.2 4.5a.8.8 0 0 0-1.2.7Z"),
    ("previous", "M20 5.2v13.6a.8.8 0 0 1-1.2.7L9 13v5.5H6.5v-13H9V11l9.8-6.5a.8.8 0 0 1 1.2.7Z"),
    ("shuffle", "M16 4l4 4-4 4M20 8h-4.5c-1.6 0-3 .8-3.9 2.1L9.4 13.9A4.6 4.6 0 0 1 5.5 16H3M16 20l4-4-4-4M20 16h-4.5c-1.6 0-3-.8-3.9-2.1M3 8h2.5c1.2 0 2.3.5 3.1 1.3"),
    ("repeat", "M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3"),
    ("volume", "M4 9.5v5h3.5L12 19V5L7.5 9.5H4ZM15.5 8.5a5 5 0 0 1 0 7M18 6a8.5 8.5 0 0 1 0 12"),
    ("volume-mute", "M4 9.5v5h3.5L12 19V5L7.5 9.5H4ZM16 9.5l5 5M21 9.5l-5 5"),
    ("phone", "M8 2.5h8a1.5 1.5 0 0 1 1.5 1.5v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 20V4A1.5 1.5 0 0 1 8 2.5ZM11 18.5h2"),
    ("tablet", "M5.5 2.5h13A1.5 1.5 0 0 1 20 4v16a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 20V4a1.5 1.5 0 0 1 1.5-1.5ZM11 18.5h2"),
    ("desktop", "M3.5 4h17a1 1 0 0 1 1 1v10.5a1 1 0 0 1-1 1h-17a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1ZM9 20.5h6M12 16.5v4"),
    ("tv", "M3.5 5h17a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-17a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1ZM8 20.5h8"),
    ("watch", "M9 2.5h6l.8 3.5H8.2L9 2.5ZM9 21.5h6l.8-3.5H8.2L9 21.5ZM8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z"),
    ("devices", "M3 5.5h12v9H3zM6.5 18h5M9 14.5V18M17 8.5h4v11h-4z"),
    ("close", "M6 6l12 12M18 6L6 18"),
    ("wifi", "M2.5 9a14 14 0 0 1 19 0M5.5 12.3a9.5 9.5 0 0 1 13 0M8.6 15.5a5 5 0 0 1 6.8 0M12 19h.01"),
    ("music", "M9 18V5l11-2v13M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"),
    ("home", "M3.5 10.5 12 3.5l8.5 7V20a.5.5 0 0 1-.5.5h-5v-6h-6v6H4a.5.5 0 0 1-.5-.5Z"),
    ("search", "M10.5 17.5a7 7 0 1 0 0-14 7 7 0 0 0 0 14ZM15.5 15.5l5 5"),
    ("library", "M4.5 3.5v17M9.5 3.5v17M14 4.2l5.6 15.9"),
    ("heart", "M12 20.5s-7.5-4.4-9-9.3C1.9 7.6 4.3 4.5 7.6 4.5c1.9 0 3.4 1 4.4 2.5 1-1.5 2.5-2.5 4.4-2.5 3.3 0 5.7 3.1 4.6 6.7-1.5 4.9-9 9.3-9 9.3Z"),
    ("plus", "M12 5v14M5 12h14"),
    ("queue", "M3.5 5.5h13M3.5 10.5h13M3.5 15.5h7M15.5 14v6.5l5-3.25-5-3.25Z"),
    ("lyrics", "M4 5.5h16v10H9l-5 4v-14ZM8 9.5h8M8 12.5h5"),
    ("back", "M15 4.5 7.5 12l7.5 7.5"),
    ("forward", "M9 4.5l7.5 7.5L9 19.5"),
    ("settings", "M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4ZM19.4 13.4l1.6 1.2-1.8 3.1-1.9-.7a7.4 7.4 0 0 1-2 1.2l-.3 2H11l-.3-2a7.4 7.4 0 0 1-2-1.2l-1.9.7L5 14.6l1.6-1.2a7.3 7.3 0 0 1 0-2.8L5 9.4l1.8-3.1 1.9.7a7.4 7.4 0 0 1 2-1.2l.3-2h3.6l.3 2a7.4 7.4 0 0 1 2 1.2l1.9-.7 1.8 3.1-1.6 1.2a7.3 7.3 0 0 1 0 2.8Z"),
    ("palette", "M12 3a9 9 0 0 0 0 18c1.1 0 1.6-.8 1.6-1.6 0-.9-.7-1.3-.7-2.2 0-.9.7-1.6 1.6-1.6h2.2A4.3 4.3 0 0 0 21 11.3C21 6.7 17 3 12 3ZM7.5 12.5h.01M9.5 8h.01M14.5 8h.01"),
    ("more", "M5 12h.01M12 12h.01M19 12h.01"),
    ("check", "M5 12.5l4.5 4.5L19 7.5"),
    ("upload", "M12 15V4M7.5 8.5 12 4l4.5 4.5M4.5 15v4.5h15V15"),
    ("download", "M12 4v11M7.5 10.5 12 15l4.5-4.5M4.5 15v4.5h15V15"),
    ("trash", "M4.5 6.5h15M9.5 6.5V4h5v2.5M6.5 6.5l1 13.5h9l1-13.5"),
    ("edit", "M15.5 4.5l4 4L8.5 19.5h-4v-4ZM13 7l4 4"),
    ("clock", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 7v5l3 2"),
    ("expand", "M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7"),
    ("sidebar", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM9.5 4v16M5.5 8h1.5M5.5 11h1.5"),
    ("chevron-down", "M6 9.5l6 6 6-6"),
    ("chevron-up", "M6 14.5l6-6 6 6"),
    ("shuffle2", "M3 7h3.5c2 0 3.2 1 4.2 2.6l2.6 4.8c1 1.6 2.2 2.6 4.2 2.6H21M18 14l3 3-3 3M3 17h3.5c1.4 0 2.4-.5 3.2-1.4M13.3 8.4c.8-.9 1.8-1.4 3.2-1.4H21M18 4l3 3-3 3"),
    ("speaker-wave", "M4 9.5v5h3.5L12 19V5L7.5 9.5H4ZM15.5 9a4.5 4.5 0 0 1 0 6"),
    ("plane", "M21 4.5 3.5 11.2l6.3 2.3 2.3 6.3L21 4.5ZM9.8 13.5 21 4.5"),
    ("sparkles", "M12 3.5l1.6 4.4 4.4 1.6-4.4 1.6L12 15.5l-1.6-4.4L6 9.5l4.4-1.6L12 3.5ZM18.5 15l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8.8-2.2Z"),
];

const SOLID: &[&str] = &["play", "pause", "next", "previous"];

fn svg_source(name: &str) -> Option<String> {
    let d = PATHS.iter().find(|(n, _)| *n == name)?.1;
    let body = if SOLID.contains(&name) {
        format!(r#"<path d="{d}" fill="black"/>"#)
    } else {
        format!(
            r#"<path d="{d}" fill="none" stroke="black" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>"#
        )
    };
    Some(format!(r#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">{body}</svg>"#))
}

/// LFly's icons, then gpui-kit's own assets.
pub struct Assets;

impl AssetSource for Assets {
    fn load(&self, path: &str) -> anyhow::Result<Option<Cow<'static, [u8]>>> {
        if let Some(name) = path.strip_prefix("lfly/icons/").and_then(|p| p.strip_suffix(".svg")) {
            return Ok(svg_source(name).map(|s| Cow::Owned(s.into_bytes())));
        }
        gpui_kit::assets::Assets.load(path)
    }

    fn list(&self, path: &str) -> anyhow::Result<Vec<SharedString>> {
        gpui_kit::assets::Assets.list(path)
    }
}

/// An icon at [size] px in [color] (Icon.svelte, default 18). GPUI's svg does not inherit the
/// text colour as `currentColor` does, so it is passed here.
pub fn icon(name: &'static str, size: f32, color: Hsla) -> Svg {
    svg().path(format!("lfly/icons/{name}.svg")).size(px(size)).flex_none().text_color(color)
}
