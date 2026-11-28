//! Stems for the Console's decks: a song split into vocals, drums, bass (and the rest) by
//! HT-Demucs (`lfly_beat::stems`), kept on the disk so a song is split once.
//!
//! The model (166 MB, MIT) is downloaded on first use into the data folder and checked against its
//! published SHA-256. Kept stems are 16-bit stereo at the song's own rate, three parts (the fourth
//! is the song less them), about 95 MB for three minutes; the folder keeps [`MAX_BYTES`] at most,
//! the least recently used going first.

use std::io::{Read, Write};
use std::path::PathBuf;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use lfly_audio::decks::{Stems, TrackBuf};

pub const MODEL_FILE: &str = "htdemucs_fp16weights.onnx";
const MODEL_URL: &str = "https://huggingface.co/adowu/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx";
const MODEL_SHA256: &str = "d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a";
const MODEL_BYTES: u64 = 165_612_636;
/// The kept stems' folder at most (then the least recently used go).
pub const MAX_BYTES: u64 = 6 * 1024 * 1024 * 1024;
const MAGIC: &[u8; 6] = b"LFST1\n";

pub fn model_path() -> Option<PathBuf> {
    // Test runs point at a model elsewhere (their data folder is a copy).
    if let Some(p) = std::env::var_os("LFLY_STEMS_MODEL") {
        return Some(PathBuf::from(p));
    }
    Some(dirs::data_dir()?.join("lfly").join("models").join(MODEL_FILE))
}

/// The model is on the disk (complete).
pub fn model_ready() -> bool {
    model_path().and_then(|p| std::fs::metadata(p).ok()).is_some_and(|m| m.len() == MODEL_BYTES)
}

/// Downloads the model once, checked against its hash; [progress] hears (bytes, total).
pub async fn download_model(progress: impl Fn(u64, u64)) -> Result<PathBuf, String> {
    use sha2::Digest;
    let path = model_path().ok_or("no data folder")?;
    if model_ready() {
        return Ok(path);
    }
    std::fs::create_dir_all(path.parent().ok_or("no folder")?).map_err(|e| e.to_string())?;
    let part = path.with_extension("onnx.part");
    let mut resp = reqwest::Client::new().get(MODEL_URL).send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }
    let mut file = std::fs::File::create(&part).map_err(|e| e.to_string())?;
    let mut hash = sha2::Sha256::new();
    let mut got = 0u64;
    while let Some(chunk) = resp.chunk().await.map_err(|e| e.to_string())? {
        file.write_all(&chunk).map_err(|e| e.to_string())?;
        hash.update(&chunk);
        got += chunk.len() as u64;
        progress(got, MODEL_BYTES);
    }
    file.flush().map_err(|e| e.to_string())?;
    let sum: String = hash.finalize().iter().map(|b| format!("{b:02x}")).collect();
    if sum != MODEL_SHA256 {
        let _ = std::fs::remove_file(&part);
        return Err("the stems model downloaded is not the expected one".into());
    }
    std::fs::rename(&part, &path).map_err(|e| e.to_string())?;
    log::info!("stems model downloaded ({got} bytes)");
    Ok(path)
}

fn dir() -> Option<PathBuf> {
    Some(dirs::cache_dir()?.join("lfly").join("stems"))
}

fn file_of(id: &str) -> Option<PathBuf> {
    let name: String = id.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '.' }).collect();
    // Distinct ids that read the same once escaped (local paths) get their hash too.
    use sha2::Digest;
    let h: String = sha2::Sha256::digest(id.as_bytes()).iter().take(6).map(|b| format!("{b:02x}")).collect();
    let short: String = name.chars().rev().take(80).collect::<Vec<_>>().into_iter().rev().collect();
    Some(dir()?.join(format!("{short}-{h}.stems")))
}

/// The song's stems are kept.
pub fn cached(id: &str) -> bool {
    file_of(id).is_some_and(|p| p.is_file())
}

/// The kept stems of the song, read back (and marked as used now).
pub fn load(id: &str) -> Option<Arc<Stems>> {
    let path = file_of(id)?;
    let mut f = std::fs::File::open(&path).ok()?;
    let mut head = [0u8; 6 + 4 + 8];
    f.read_exact(&mut head).ok()?;
    if &head[..6] != MAGIC {
        return None;
    }
    let rate = u32::from_le_bytes(head[6..10].try_into().ok()?);
    let frames = u64::from_le_bytes(head[10..18].try_into().ok()?) as usize;
    let mut part = || -> Option<Arc<TrackBuf>> {
        let mut bytes = vec![0u8; frames * 4];
        f.read_exact(&mut bytes).ok()?;
        let samples: Vec<i16> = bytes.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect();
        Some(TrackBuf::from_i16(rate, samples))
    };
    let (vocals, drums, bass) = (part()?, part()?, part()?);
    let _ = std::fs::File::options().append(true).open(&path).and_then(|f| f.set_modified(std::time::SystemTime::now()));
    Some(Arc::new(Stems { vocals, drums, bass }))
}

fn save(id: &str, rate: u32, st: &Stems) -> Result<(), String> {
    let path = file_of(id).ok_or("no cache folder")?;
    std::fs::create_dir_all(path.parent().ok_or("no folder")?).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("part");
    let mut w = std::io::BufWriter::new(std::fs::File::create(&tmp).map_err(|e| e.to_string())?);
    let parts = [st.vocals.raw(), st.drums.raw(), st.bass.raw()];
    let frames = parts.iter().map(|p| p.len() / 2).min().unwrap_or(0);
    w.write_all(MAGIC).map_err(|e| e.to_string())?;
    w.write_all(&rate.to_le_bytes()).map_err(|e| e.to_string())?;
    w.write_all(&(frames as u64).to_le_bytes()).map_err(|e| e.to_string())?;
    for p in &parts {
        for s in &p[..frames * 2] {
            w.write_all(&s.to_le_bytes()).map_err(|e| e.to_string())?;
        }
    }
    w.flush().map_err(|e| e.to_string())?;
    drop(w);
    std::fs::rename(&tmp, &path).map_err(|e| e.to_string())?;
    evict();
    Ok(())
}

/// Splits the song in [buf] (decoded whole) and keeps its stems under [id]. [progress] hears the
/// share done; [cancel] stops it.
pub fn split(id: &str, buf: &TrackBuf, progress: impl Fn(f32) + Send, cancel: &AtomicBool) -> Result<Arc<Stems>, String> {
    let model = model_path().filter(|_| model_ready()).ok_or("the stems model is not downloaded")?;
    let started = std::time::Instant::now();
    let rate = buf.rate();
    let frames = buf.ready();
    let input = buf.stereo(lfly_beat::stems::SAMPLE_RATE);
    // At the lowest priority, so the decks never crackle while a song is being split.
    let parts = lfly_beat::in_background(|| lfly_beat::stems::separate(&model, &input, progress, cancel))?;
    drop(input);
    // Back at the song's own rate, frame for frame with it.
    let back = |v: &[f32]| -> Arc<TrackBuf> {
        let mut out = Vec::with_capacity(frames * 2 + 8192);
        let mut rs = lfly_audio::decode::Resample::new(lfly_beat::stems::SAMPLE_RATE, rate);
        rs.process(v, &mut out);
        rs.flush(&mut out);
        out.resize(frames * 2, 0.);
        TrackBuf::from_samples(rate, &out)
    };
    // The model's order: drums, bass, other, vocals.
    let st = Stems { vocals: back(&parts[3]), drums: back(&parts[0]), bass: back(&parts[1]) };
    if let Err(e) = save(id, rate, &st) {
        log::warn!("stems not kept: {e}");
    }
    log::info!("stems of {id}: {} s of song in {} s", frames / rate.max(1) as usize, started.elapsed().as_secs());
    Ok(Arc::new(st))
}

/// The kept stems' size in bytes.
pub fn size() -> u64 {
    let Some(d) = dir() else { return 0 };
    std::fs::read_dir(d).map(|r| r.flatten().filter_map(|e| e.metadata().ok()).map(|m| m.len()).sum()).unwrap_or(0)
}

/// Over [`MAX_BYTES`]: the least recently used go.
fn evict() {
    let Some(d) = dir() else { return };
    let Ok(read) = std::fs::read_dir(d) else { return };
    let mut files: Vec<(std::time::SystemTime, u64, PathBuf)> = read
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|x| x == "stems"))
        .filter_map(|e| {
            let m = e.metadata().ok()?;
            Some((m.modified().ok()?, m.len(), e.path()))
        })
        .collect();
    let mut total: u64 = files.iter().map(|f| f.1).sum();
    files.sort();
    for (_, len, path) in files {
        if total <= MAX_BYTES {
            break;
        }
        if std::fs::remove_file(&path).is_ok() {
            total -= len;
        }
    }
}

/// Every kept stem removed (Settings).
pub fn clear() {
    if let Some(d) = dir() {
        let _ = std::fs::remove_dir_all(d);
    }
}

/// The model's memory back once nothing waits to be split.
pub fn unload_model() {
    lfly_beat::stems::unload();
}
