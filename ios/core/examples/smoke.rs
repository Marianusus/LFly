//! `cargo run --example smoke`: search, then the first song's stream, as the app does.
fn main() {
    let core = lfly_ios::Core::new().expect("core");
    let rt = tokio::runtime::Runtime::new().unwrap();
    rt.block_on(async {
        let r = core.search("Daft Punk Get Lucky".into()).await.expect("search");
        let s = r.songs.first().expect("a song").clone();
        println!("song: {} – {} ({} s)", s.title, s.artist, s.duration_ms / 1000);
        let st = core.stream(s.clone()).await.expect("stream");
        println!("stream: {} via {} at {} kbit/s", st.mime, st.provider, st.bitrate / 1000);
        let l = core.lyrics(s).await.expect("lyrics");
        println!("lyrics: {} lines from {:?}", l.as_ref().map_or(0, |l| l.lines.len()), l.map(|l| l.source));
        println!("home shelves: {}", core.home().await.map(|h| h.len()).unwrap_or(0));
    });
}
