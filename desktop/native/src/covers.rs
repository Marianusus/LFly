//! Cover images, loaded once per URL and shared by every view.

use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::*;

use crate::art;
use crate::core::Core;

pub struct Covers {
    core: Arc<Core>,
    images: HashMap<String, Arc<Image>>,
    loading: HashSet<String>,
}

impl Covers {
    pub fn new(core: Arc<Core>) -> Self {
        Self { core, images: HashMap::new(), loading: HashSet::new() }
    }

    /// The image if it is here; otherwise starts loading it and returns None (observers are
    /// notified when it arrives).
    pub fn get(&mut self, url: &str, cx: &mut Context<Self>) -> Option<Arc<Image>> {
        if let Some(img) = self.images.get(url) {
            return Some(img.clone());
        }
        if self.loading.insert(url.to_string()) {
            let core = self.core.clone();
            let url = url.to_string();
            cx.spawn(async move |this, cx| {
                let bytes = core.fetch(url.clone()).await;
                let _ = this.update(cx, |this, cx| {
                    if let Some(img) = bytes.and_then(art::image) {
                        ARRIVED.with(|a| a.borrow_mut().insert(img.id(), Instant::now()));
                        this.images.insert(url, img);
                        cx.notify();
                    }
                });
            })
            .detach();
        }
        None
    }
}

thread_local! {
    /// When each image arrived from the network (image id → instant): the ones that just did
    /// fade in, the ones already here (a page drawn again) are simply there.
    static ARRIVED: RefCell<HashMap<u64, Instant>> = RefCell::new(HashMap::new());
}

/// How long a cover takes to fade in (Cover.svelte: 320 ms).
const FADE: Duration = Duration::from_millis(320);

/// [el], a cover image showing the image [id]: fades in over 320 ms from when it arrived, if it
/// has just arrived (drawn first a little later, it starts part way in, not from nothing).
pub fn fade(id: u64, el: Img) -> AnyElement {
    let arrived = ARRIVED.with(|a| {
        let mut a = a.borrow_mut();
        let at = a.get(&id).copied().filter(|t| t.elapsed() < FADE + Duration::from_millis(80));
        if at.is_none() {
            a.remove(&id);
        }
        at
    });
    let Some(arrived) = arrived else {
        return el.into_any_element();
    };
    div()
        .size_full()
        .child(el)
        // Runs a full FADE from its first frame, so it never stops short of opaque.
        .with_animation(("cover-in", id), Animation::new(FADE), move |d, _| {
            let t = (arrived.elapsed().as_secs_f32() / FADE.as_secs_f32()).min(1.);
            d.opacity(1. - (1. - t).powi(3))
        })
        .into_any_element()
}
