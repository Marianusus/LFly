//! Prints the first artist, album and playlist ids for a query, for LFLY_ROUTE:
//! `cargo run --release --example ids -- "daft punk"`.
#[path = "../src/core.rs"]
mod core;

fn main() {
    let q = std::env::args().nth(1).unwrap_or_else(|| "daft punk".into());
    let (core, _rx) = core::Core::new().expect("core");
    let r = core::runtime().block_on(async move { lfly_core::pages::search(&core.yt, q).await }).expect("search");
    for (k, items) in [("artist", &r.artists), ("album", &r.albums), ("playlist", &r.playlists)] {
        if let Some(i) = items.first() {
            println!("{k}:{} ({})", i.id, i.title);
        }
    }
}
