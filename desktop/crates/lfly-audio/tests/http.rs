//! The HTTP Range reader against a local server: streaming, seeking, retries, cache, offline.

mod common;

use std::io::{Read, Seek, SeekFrom};
use std::sync::atomic::Ordering;
use std::sync::Arc;

use common::*;
use lfly_audio::decode::{DecodeError, TrackDecoder};
use lfly_audio::http::{prefetch, HttpSource, ReaderCtl};
use lfly_audio::{DiskCache, Source};

/// Small chunks so even the tiny fixtures span several Range requests.
const CHUNK: u64 = 8 * 1024;

fn cache(name: &str) -> Arc<DiskCache> {
    Arc::new(DiskCache::with_chunk_size(temp_dir(name), 64 << 20, CHUNK).unwrap())
}

#[test]
fn reads_and_seeks_byte_exact() {
    let data = std::fs::read(fixture("sine.webm")).unwrap();
    let server = TestServer::serve(data.clone());
    let cache = cache("http-bytes");
    let mut src = HttpSource::open(&server.url, "bytes", &[], cache.clone(), ReaderCtl::detached()).unwrap();
    assert_eq!(src.len(), Some(data.len() as u64));

    let mut all = Vec::new();
    src.read_to_end(&mut all).unwrap();
    assert_eq!(all, data);

    for &pos in &[20_000u64, 5, CHUNK - 3, data.len() as u64 - 10] {
        src.seek(SeekFrom::Start(pos)).unwrap();
        let mut buf = vec![0u8; 100];
        let n = src.read(&mut buf).unwrap();
        assert!(n > 0);
        assert_eq!(&buf[..n], &data[pos as usize..pos as usize + n], "at {pos}");
    }
    src.seek(SeekFrom::End(-4)).unwrap();
    let mut tail = Vec::new();
    src.read_to_end(&mut tail).unwrap();
    assert_eq!(tail, &data[data.len() - 4..]);
    assert!(cache.is_complete("bytes"));
}

#[test]
fn streams_decodes_caches_and_then_plays_offline() {
    for name in ["sine.webm", "sine_dash.m4a"] {
        let data = std::fs::read(fixture(name)).unwrap();
        let server = TestServer::serve(data.clone());
        let cache = cache(&format!("http-stream-{name}"));
        let key = format!("track-{name}");

        let d = decode_all(&Source::http(server.url.clone(), key.clone()), &cache).unwrap();
        assert!((d.seconds() - 3.0).abs() < 0.03, "{name}: {} s", d.seconds());
        assert_tone_440(&d, name);
        let chunks = data.len().div_ceil(CHUNK as usize);
        let requests = server.requests.load(Ordering::SeqCst);
        assert!(requests >= chunks, "{name}: {requests} requests for {chunks} chunks");
        assert!(cache.is_complete(&key), "{name}: fully cached");

        // The server is gone and the URL is dead: the cache alone plays it.
        drop(server);
        let offline = Source::http("http://127.0.0.1:9/expired", key.clone());
        let again = decode_all(&offline, &cache).unwrap();
        assert_eq!(again.samples.len(), d.samples.len(), "{name}: offline replay");
    }
}

#[test]
fn seeking_fetches_the_right_range() {
    // 20 s, cues at the front like YouTube's WebM: a seek jumps straight to the right chunk.
    let data = std::fs::read(fixture("long_cues_front.webm")).unwrap();
    let server = TestServer::serve(data.clone());
    let cache = Arc::new(DiskCache::with_chunk_size(temp_dir("http-seek"), 64 << 20, 4096).unwrap());
    let src = Source::http(server.url.clone(), "seek");
    let mut dec = TrackDecoder::open(&src, &cache, ReaderCtl::detached()).unwrap();
    assert_eq!(dec.seek(15_000).unwrap(), 15_000);
    let rest = drain(&mut dec).unwrap();
    assert!((rest.seconds() - 5.0).abs() < 0.03, "{} s after seeking to 15 s", rest.seconds());
    assert_tone_440(&rest, "after seek");
    let chunks = (data.len() as u64).div_ceil(4096);
    let fetched: Vec<u64> = (0..chunks).filter(|&i| cache.has_chunk("seek", i)).collect();
    println!("{chunks} chunks, fetched {fetched:?}");
    // The middle of the file (5..12 s) was skipped over, never downloaded.
    let middle = chunks * 8 / 20..chunks * 11 / 20;
    assert!(middle.clone().all(|i| !fetched.contains(&i)), "middle {middle:?} fetched: {fetched:?}");
    assert!(fetched.contains(&(chunks - 1)));
}

#[test]
fn dropped_connections_are_retried() {
    let data = std::fs::read(fixture("sine.webm")).unwrap();
    let server = TestServer::serve(data);
    server.cut_next.store(2, Ordering::SeqCst);
    let cache = cache("http-retry");
    let d = decode_all(&Source::http(server.url.clone(), "retry"), &cache).unwrap();
    assert!((d.seconds() - 3.0).abs() < 0.03);
    assert_tone_440(&d, "after retries");
    assert_eq!(server.cut_next.load(Ordering::SeqCst), 0);
}

#[test]
fn an_expired_url_fails_at_once() {
    let server = TestServer::serve(vec![0; 1000]);
    server.forbid.store(true, Ordering::SeqCst);
    let cache = cache("http-403");
    let started = std::time::Instant::now();
    match TrackDecoder::open(&Source::http(server.url.clone(), "gone"), &cache, ReaderCtl::detached()) {
        Err(DecodeError::Failed(m)) => assert!(m.contains("403"), "{m}"),
        Err(e) => panic!("unexpected {e}"),
        Ok(_) => panic!("opened a 403"),
    }
    assert_eq!(server.requests.load(Ordering::SeqCst), 1, "no retries on 403");
    assert!(started.elapsed().as_secs() < 2);
}

#[test]
fn a_cancelled_read_stops_quickly() {
    let data = std::fs::read(fixture("sine.webm")).unwrap();
    let server = TestServer::serve(data);
    server.cut_next.store(100, Ordering::SeqCst); // every request fails: it would retry with backoff
    let cache = cache("http-cancel");
    let ctl = ReaderCtl::detached();
    let c2 = ctl.clone();
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(100));
        c2.cancel();
    });
    let started = std::time::Instant::now();
    let r = TrackDecoder::open(&Source::http(server.url.clone(), "cancel"), &cache, ctl);
    assert!(matches!(r, Err(DecodeError::Aborted)), "{:?}", r.err());
    assert!(started.elapsed().as_millis() < 1_000);
}

#[test]
fn prefetch_fills_the_first_chunks() {
    let data = std::fs::read(fixture("sine.webm")).unwrap();
    let server = TestServer::serve(data);
    let cache = cache("http-prefetch");
    prefetch(&server.url, "pre", &[], cache.clone(), 2 * CHUNK, ReaderCtl::detached()).unwrap();
    assert!(cache.has_chunk("pre", 0) && cache.has_chunk("pre", 1));
    assert!(!cache.has_chunk("pre", 2));
}

#[test]
fn a_key_cached_from_another_file_is_not_mixed_up() {
    // Same cache key, other format of the song (as when the resolver falls back from WebM to MP4).
    let cache = cache("http-other-file");
    let webm = TestServer::serve(std::fs::read(fixture("sine.webm")).unwrap());
    decode_all(&Source::http(webm.url.clone(), "song"), &cache).unwrap();
    assert!(cache.is_complete("song"));

    let m4a_data = std::fs::read(fixture("sine_dash.m4a")).unwrap();
    let m4a = TestServer::serve(m4a_data.clone());
    // With `clen=` in the URL (googlevideo) the stale entry is dropped before reading anything.
    let url = format!("{}&clen={}", m4a.url, m4a_data.len());
    let d = decode_all(&Source::http(url, "song"), &cache).unwrap();
    assert_eq!(d.rate, 44_100);
    assert_tone_440(&d, "m4a after webm");
    assert_eq!(cache.total_len("song"), Some(m4a_data.len() as u64));
}
