#![allow(dead_code)]

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

use lfly_audio::decode::{DecodeError, TrackDecoder};
use lfly_audio::http::ReaderCtl;
use lfly_audio::{DiskCache, Source};

pub fn fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)
}

/// A fresh, empty directory under the system temp dir.
pub fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("lfly-audio-test-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

pub struct Decoded {
    pub samples: Vec<f32>,
    pub rate: u32,
    pub duration_ms: Option<u64>,
}

impl Decoded {
    pub fn seconds(&self) -> f64 {
        self.samples.len() as f64 / 2.0 / self.rate as f64
    }

    pub fn left(&self) -> Vec<f32> {
        self.samples.iter().step_by(2).copied().collect()
    }
}

pub fn decode_all(source: &Source, cache: &Arc<DiskCache>) -> Result<Decoded, DecodeError> {
    let mut dec = TrackDecoder::open(source, cache, ReaderCtl::detached())?;
    drain(&mut dec)
}

pub fn drain(dec: &mut TrackDecoder) -> Result<Decoded, DecodeError> {
    let mut samples = Vec::new();
    while dec.decode_next(&mut samples)? {}
    Ok(Decoded { samples, rate: dec.sample_rate(), duration_ms: dec.duration_ms() })
}

/// Goertzel power of `freq` in `x`, normalized by length.
pub fn goertzel(x: &[f32], rate: u32, freq: f64) -> f64 {
    let w = 2.0 * std::f64::consts::PI * freq / rate as f64;
    let coeff = 2.0 * w.cos();
    let (mut s1, mut s2) = (0.0f64, 0.0f64);
    for &v in x {
        let s = v as f64 + coeff * s1 - s2;
        s2 = s1;
        s1 = s;
    }
    (s1 * s1 + s2 * s2 - coeff * s1 * s2) / (x.len() as f64).powi(2)
}

/// Frequency from the zero crossings.
pub fn zero_crossing_hz(x: &[f32], rate: u32) -> f64 {
    let crossings = x.windows(2).filter(|w| (w[0] < 0.0) != (w[1] < 0.0)).count();
    crossings as f64 / 2.0 / (x.len() as f64 / rate as f64)
}

/// Asserts the audio is a clean 440 Hz tone.
pub fn assert_tone_440(d: &Decoded, what: &str) {
    let left = d.left();
    // Skip the edges (encoder ramps, priming).
    let mid = &left[left.len() / 10..left.len() * 9 / 10];
    let hz = zero_crossing_hz(mid, d.rate);
    assert!((hz - 440.0).abs() < 5.0, "{what}: zero crossings say {hz} Hz");
    let on = goertzel(mid, d.rate, 440.0);
    let off = goertzel(mid, d.rate, 1000.0).max(goertzel(mid, d.rate, 220.0));
    assert!(on > off * 1000.0, "{what}: 440 Hz power {on} vs {off}");
    let peak = mid.iter().fold(0f32, |m, v| m.max(v.abs()));
    assert!(peak > 0.05 && peak < 1.0, "{what}: peak {peak}");
}

/// A tiny HTTP/1.1 server with Range support, serving one file.
pub struct TestServer {
    pub url: String,
    pub requests: Arc<AtomicUsize>,
    /// Requests that will be cut off halfway through the body.
    pub cut_next: Arc<AtomicUsize>,
    /// Answer every request with 403.
    pub forbid: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
}

impl TestServer {
    pub fn serve(data: Vec<u8>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/videoplayback?id=test", listener.local_addr().unwrap());
        let requests = Arc::new(AtomicUsize::new(0));
        let cut_next = Arc::new(AtomicUsize::new(0));
        let forbid = Arc::new(AtomicBool::new(false));
        let stop = Arc::new(AtomicBool::new(false));
        let data = Arc::new(data);
        let (r, c, f, s) = (requests.clone(), cut_next.clone(), forbid.clone(), stop.clone());
        listener.set_nonblocking(true).unwrap();
        std::thread::spawn(move || {
            while !s.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let (data, r, c, f) = (data.clone(), r.clone(), c.clone(), f.clone());
                        std::thread::spawn(move || {
                            let _ = handle(stream, &data, &r, &c, &f);
                        });
                    }
                    Err(_) => std::thread::sleep(std::time::Duration::from_millis(5)),
                }
            }
        });
        TestServer { url, requests, cut_next, forbid, stop }
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
    }
}

fn handle(
    stream: TcpStream,
    data: &[u8],
    requests: &AtomicUsize,
    cut: &AtomicUsize,
    forbid: &AtomicBool,
) -> std::io::Result<()> {
    stream.set_nonblocking(false)?;
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut out = stream;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 {
            return Ok(());
        }
        let mut range = None;
        loop {
            let mut h = String::new();
            reader.read_line(&mut h)?;
            let h = h.trim_end();
            if h.is_empty() {
                break;
            }
            if let Some((k, v)) = h.split_once(':') {
                if k.eq_ignore_ascii_case("range") {
                    range = Some(v.trim().to_string());
                }
            }
        }
        requests.fetch_add(1, Ordering::SeqCst);
        if forbid.load(Ordering::SeqCst) {
            out.write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")?;
            continue;
        }
        let len = data.len() as u64;
        let (start, end) = range
            .as_deref()
            .and_then(|r| r.strip_prefix("bytes="))
            .and_then(|r| {
                let (a, b) = r.split_once('-')?;
                let a: u64 = a.parse().ok()?;
                let b: u64 = if b.is_empty() { len - 1 } else { b.parse().ok()? };
                Some((a, b.min(len - 1)))
            })
            .unwrap_or((0, len - 1));
        if start >= len {
            out.write_all(format!("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{len}\r\nContent-Length: 0\r\n\r\n").as_bytes())?;
            continue;
        }
        let body = &data[start as usize..=end as usize];
        let head = format!(
            "HTTP/1.1 206 Partial Content\r\nContent-Type: audio/webm\r\nContent-Range: bytes {start}-{end}/{len}\r\nContent-Length: {}\r\n\r\n",
            body.len()
        );
        out.write_all(head.as_bytes())?;
        let cut_now = cut.load(Ordering::SeqCst) > 0 && body.len() > 2;
        if cut_now {
            cut.fetch_sub(1, Ordering::SeqCst);
            out.write_all(&body[..body.len() / 2])?;
            out.flush()?;
            return Ok(()); // connection dropped mid-body
        }
        out.write_all(body)?;
        out.flush()?;
        // Keep-alive: loop for the next request on this connection.
    }
}
