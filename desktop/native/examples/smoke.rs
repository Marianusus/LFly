//! Search → full stream → engine, without a window, at volume 0: `cargo run --example smoke`.
#[path = "../src/core.rs"]
mod core;

fn main() {
    let core = core::Core::new().expect("core");
    core.audio.set_volume(0.0);
    let mut events = core.audio.subscribe();
    let rt = core::runtime();
    let tracks = rt.block_on(core.clone().search("Daft Punk Get Lucky".into())).expect("search");
    println!("search: {} songs, first = {} — {}", tracks.len(), tracks[0].title, tracks[0].artist);
    rt.block_on(core.clone().play(tracks[0].clone())).expect("play");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(12);
    let mut positions = 0;
    rt.block_on(async {
        while std::time::Instant::now() < deadline {
            match tokio::time::timeout(std::time::Duration::from_secs(1), events.recv()).await {
                Ok(Ok(lfly_audio::PlayerEvent::Position { ms })) => {
                    positions += 1;
                    if positions % 8 == 1 {
                        println!("position {ms} ms");
                    }
                }
                Ok(Ok(ev)) => println!("event {ev:?}"),
                _ => {}
            }
        }
    });
    println!("positions received: {positions}");
    let cover = tracks[0].cover_url.clone().unwrap_or_default();
    let bytes = rt.block_on(core.clone().fetch(lfly_yt::upscale_thumbnail(&cover)));
    println!("cover bytes: {:?}", bytes.as_ref().map(|b| b.len()));
}
