//! The output device: a cpal stream fed from a lock-free ring of interleaved stereo `f32`.
//!
//! The audio callback never blocks or allocates: it reads the ring, runs the equalizer, applies
//! the volume with ~10 ms linear ramps (pause, play, seek and volume changes never click) and maps
//! stereo onto the device's channels. Everything it shares with the engine is atomics, plus the
//! EQ curve behind a mutex it only ever `try_lock`s.

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SizedSample};
use serde::{Deserialize, Serialize};

use crate::eq::{EqCurve, Equalizer};

/// An output device as the UI lists it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutputDevice {
    /// Stable id (`"<host>:<device>"`), for [`crate::Player::set_device`].
    pub id: String,
    pub name: String,
    pub is_default: bool,
}

/// Lists the output devices of the default host.
pub fn devices() -> Vec<OutputDevice> {
    let host = cpal::default_host();
    let default_id = host.default_output_device().and_then(|d| d.id().ok()).map(|id| id.to_string());
    let Ok(list) = host.output_devices() else { return Vec::new() };
    list.filter_map(|d| {
        let id = d.id().ok()?.to_string();
        let name = d.description().map(|desc| desc.name().to_string()).unwrap_or_else(|_| id.clone());
        Some(OutputDevice { is_default: Some(&id) == default_id.as_ref(), id, name })
    })
    .collect()
}

/// State shared between the engine, the player handle and the audio callback.
pub(crate) struct OutShared {
    /// Frames taken out of the ring (played or discarded by a flush).
    pub consumed: AtomicU64,
    pub playing: AtomicBool,
    volume_bits: AtomicU32,
    pub flush_req: AtomicU64,
    pub flush_ack: AtomicU64,
    pub eq_gen: AtomicU64,
    pub eq: Mutex<EqCurve>,
    /// The callback ran out of audio while it should have been playing.
    pub starved: AtomicBool,
    /// The stream reported a fatal error (device gone): rebuild it.
    pub failed: AtomicBool,
}

impl OutShared {
    pub fn new() -> Self {
        OutShared {
            consumed: AtomicU64::new(0),
            playing: AtomicBool::new(false),
            volume_bits: AtomicU32::new(1f32.to_bits()),
            flush_req: AtomicU64::new(0),
            flush_ack: AtomicU64::new(0),
            eq_gen: AtomicU64::new(0),
            eq: Mutex::new(EqCurve::FLAT),
            starved: AtomicBool::new(false),
            failed: AtomicBool::new(false),
        }
    }

    pub fn volume(&self) -> f32 {
        f32::from_bits(self.volume_bits.load(Ordering::Relaxed))
    }

    pub fn set_volume(&self, v: f32) {
        self.volume_bits.store(v.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
    }

    pub fn set_eq(&self, curve: EqCurve) {
        *self.eq.lock().unwrap_or_else(|e| e.into_inner()) = curve;
        self.eq_gen.fetch_add(1, Ordering::SeqCst);
    }

    fn eq_curve(&self) -> EqCurve {
        *self.eq.lock().unwrap_or_else(|e| e.into_inner())
    }
}

pub(crate) struct Output {
    _stream: cpal::Stream,
    pub producer: rtrb::Producer<f32>,
    pub rate: u32,
    pub device_id: Option<String>,
    pub device_name: String,
}

/// Seconds of audio the ring holds.
const RING_SECONDS: f32 = 2.0;
/// Fade length for pause, play, seek and volume changes.
const RAMP_SECONDS: f32 = 0.010;
/// Frames processed per pass inside the callback (the scratch buffer is this big).
const BLOCK: usize = 1024;

/// Opens the device with `id`, or the default one when `id` is `None` or no longer exists.
pub(crate) fn open(id: Option<&str>, shared: Arc<OutShared>) -> Result<Output, String> {
    let host = cpal::default_host();
    let wanted = id.and_then(|id| {
        let parsed = id.parse::<cpal::DeviceId>().ok()?;
        host.device_by_id(&parsed).or_else(|| {
            host.output_devices().ok()?.find(|d| d.id().map(|d| d.to_string() == id).unwrap_or(false))
        })
    });
    if id.is_some() && wanted.is_none() {
        log::warn!("output device {id:?} not found, using the default one");
    }
    let device = match wanted {
        Some(d) => d,
        None => host.default_output_device().ok_or_else(|| "no audio output device".to_string())?,
    };
    let device_id = device.id().ok().map(|d| d.to_string());
    let device_name = device.description().map(|d| d.name().to_string()).unwrap_or_default();
    let supported = device.default_output_config().map_err(|e| format!("output config: {e}"))?;
    let config = supported.config();
    let rate = config.sample_rate;
    let capacity = (rate as f32 * RING_SECONDS) as usize * 2;
    let (producer, consumer) = rtrb::RingBuffer::<f32>::new(capacity);
    let stream = match supported.sample_format() {
        cpal::SampleFormat::F32 => build::<f32>(&device, &config, consumer, shared.clone()),
        cpal::SampleFormat::I16 => build::<i16>(&device, &config, consumer, shared.clone()),
        cpal::SampleFormat::I32 => build::<i32>(&device, &config, consumer, shared.clone()),
        cpal::SampleFormat::U16 => build::<u16>(&device, &config, consumer, shared.clone()),
        cpal::SampleFormat::F64 => build::<f64>(&device, &config, consumer, shared.clone()),
        other => return Err(format!("unsupported sample format {other}")),
    }?;
    stream.play().map_err(|e| format!("output start: {e}"))?;
    shared.failed.store(false, Ordering::SeqCst);
    log::info!("audio output: {device_name} ({device_id:?}) {rate} Hz, {} ch", config.channels);
    Ok(Output { _stream: stream, producer, rate, device_id, device_name })
}

fn build<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    consumer: rtrb::Consumer<f32>,
    shared: Arc<OutShared>,
) -> Result<cpal::Stream, String>
where
    T: SizedSample + FromSample<f32>,
{
    let mut render = Render::new(consumer, shared.clone(), config.sample_rate, config.channels as usize);
    let err_shared = shared;
    device
        .build_output_stream::<T, _, _>(
            *config,
            move |data: &mut [T], _| render.fill(data),
            move |err: cpal::Error| {
                use cpal::ErrorKind::*;
                match err.kind() {
                    Xrun | DeviceChanged | RealtimeDenied => log::debug!("output: {err}"),
                    _ => {
                        log::warn!("output stream error: {err}");
                        err_shared.failed.store(true, Ordering::SeqCst);
                    }
                }
            },
            None,
        )
        .map_err(|e| format!("output stream: {e}"))
}

/// Everything the callback owns. Allocated once, before the stream starts.
pub(crate) struct Render {
    consumer: rtrb::Consumer<f32>,
    shared: Arc<OutShared>,
    eq: Equalizer,
    eq_gen: u64,
    gain: f32,
    vol: f32,
    step: f32,
    channels: usize,
    block: Vec<f32>,
}

impl Render {
    pub(crate) fn new(consumer: rtrb::Consumer<f32>, shared: Arc<OutShared>, rate: u32, channels: usize) -> Self {
        let curve = shared.eq_curve();
        let eq_gen = shared.eq_gen.load(Ordering::SeqCst);
        let vol = shared.volume();
        Render {
            consumer,
            shared,
            eq: Equalizer::new(rate, 2, curve),
            eq_gen,
            gain: 0.0,
            vol,
            step: 1.0 / (RAMP_SECONDS * rate as f32).max(1.0),
            channels: channels.max(1),
            block: vec![0.0; BLOCK * 2],
        }
    }

    pub(crate) fn fill<T: SizedSample + FromSample<f32>>(&mut self, data: &mut [T]) {
        let total = data.len() / self.channels;
        let mut done = 0;
        while done < total {
            let frames = (total - done).min(BLOCK);
            self.render_block(frames);
            let ch = self.channels;
            for (i, frame) in data[done * ch..(done + frames) * ch].chunks_exact_mut(ch).enumerate() {
                let (l, r) = (self.block[i * 2], self.block[i * 2 + 1]);
                if ch == 1 {
                    frame[0] = T::from_sample((l + r) * 0.5);
                } else {
                    frame[0] = T::from_sample(l);
                    frame[1] = T::from_sample(r);
                    for s in &mut frame[2..] {
                        *s = T::from_sample(0.0f32);
                    }
                }
            }
            done += frames;
        }
        for s in &mut data[total * self.channels..] {
            *s = T::from_sample(0.0f32);
        }
    }

    /// Renders `frames` stereo frames into `self.block`.
    fn render_block(&mut self, frames: usize) {
        let sh = &self.shared;
        let flush_req = sh.flush_req.load(Ordering::Acquire);
        let flushing = flush_req != sh.flush_ack.load(Ordering::Acquire);
        let playing = sh.playing.load(Ordering::Relaxed);
        // Two ramps: `gain` fades the stream in and out (pause, play, flush), `vol` follows the
        // volume. Only the first decides whether the ring is read: at volume 0 time still runs.
        let target = if flushing || !playing { 0.0 } else { 1.0 };
        let volume = sh.volume();
        let out = &mut self.block[..frames * 2];

        if self.gain <= 0.0 && target <= 0.0 {
            // Silent and staying silent: nothing is taken from the ring (paused keeps its place).
            self.gain = 0.0;
            if flushing {
                let n = self.consumer.slots();
                if let Ok(chunk) = self.consumer.read_chunk(n) {
                    chunk.commit_all();
                }
                sh.consumed.fetch_add((n / 2) as u64, Ordering::AcqRel);
                self.eq.flush();
                sh.flush_ack.store(flush_req, Ordering::Release);
            }
            out.fill(0.0);
            return;
        }

        let available = self.consumer.slots() / 2;
        let n = available.min(frames);
        if let Ok(chunk) = self.consumer.read_chunk(n * 2) {
            let (a, b) = chunk.as_slices();
            out[..a.len()].copy_from_slice(a);
            out[a.len()..a.len() + b.len()].copy_from_slice(b);
            chunk.commit_all();
        }
        sh.consumed.fetch_add(n as u64, Ordering::AcqRel);
        out[n * 2..].fill(0.0);
        if n < frames && playing && !flushing {
            sh.starved.store(true, Ordering::Relaxed);
        } else if n == frames {
            sh.starved.store(false, Ordering::Relaxed);
        }

        let eq_gen = sh.eq_gen.load(Ordering::Acquire);
        if eq_gen != self.eq_gen {
            if let Ok(curve) = sh.eq.try_lock() {
                self.eq.set_curve(*curve);
                self.eq_gen = eq_gen;
            }
        }
        self.eq.process(out);

        let (mut gain, mut vol) = (self.gain, self.vol);
        for frame in out.as_chunks_mut::<2>().0 {
            gain = ramp(gain, target, self.step);
            vol = ramp(vol, volume, self.step);
            let g = gain * vol;
            frame[0] *= g;
            frame[1] *= g;
        }
        self.gain = gain;
        self.vol = vol;
    }
}

/// One step of a linear ramp from `from` toward `to`.
#[inline]
fn ramp(from: f32, to: f32, step: f32) -> f32 {
    if from < to {
        (from + step).min(to)
    } else {
        (from - step).max(to)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn render_ramps_and_flushes_without_blocking() {
        let shared = Arc::new(OutShared::new());
        let (mut prod, cons) = rtrb::RingBuffer::<f32>::new(48_000 * 2);
        let mut render = Render::new(cons, shared.clone(), 48_000, 2);
        for _ in 0..4800 {
            prod.push(0.5).unwrap();
            prod.push(-0.5).unwrap();
        }
        // Paused: silence, nothing consumed.
        let mut out = vec![0f32; 960];
        render.fill(&mut out);
        assert!(out.iter().all(|&s| s == 0.0));
        assert_eq!(shared.consumed.load(Ordering::SeqCst), 0);

        // Playing: ramps up over 10 ms (480 frames), then full level.
        shared.playing.store(true, Ordering::SeqCst);
        let mut out = vec![0f32; 2 * 960];
        render.fill(&mut out);
        assert!(out[0].abs() < 0.01);
        assert!((out[2 * 600] - 0.5).abs() < 1e-6);
        let steps: Vec<f32> = out.chunks(2).map(|f| f[0]).collect();
        assert!(steps.windows(2).all(|w| (w[1] - w[0]).abs() < 0.01), "no jumps");
        assert_eq!(shared.consumed.load(Ordering::SeqCst), 960);

        // Volume 0 is silent but time still runs (the ring is read).
        shared.set_volume(0.0);
        let mut out = vec![0f32; 2 * 960];
        render.fill(&mut out);
        render.fill(&mut out);
        assert!(out.iter().all(|&s| s == 0.0));
        assert_eq!(shared.consumed.load(Ordering::SeqCst), 2880);
        shared.set_volume(1.0);

        // Flush: fades out, drops the ring, acknowledges.
        shared.flush_req.fetch_add(1, Ordering::SeqCst);
        let mut out = vec![0f32; 2 * 960];
        render.fill(&mut out);
        render.fill(&mut out);
        assert_eq!(shared.flush_ack.load(Ordering::SeqCst), 1);
        assert_eq!(shared.consumed.load(Ordering::SeqCst), 4800);
    }
}
