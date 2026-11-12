//! Cover images, loaded once per URL and shared by every view.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

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
