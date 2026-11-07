//! Opening a [`Source`], demuxing with symphonia and decoding to interleaved stereo `f32`.
//!
//! Opus (WebM itag 251, Ogg) goes through the pure-Rust `opus-decoder`; everything else through
//! symphonia's own decoders. Seeks are sample-accurate: whatever decodes before the target is
//! dropped.

use std::fs::File;
use std::sync::Arc;

use symphonia::core::codecs::audio::well_known::{CODEC_ID_AAC, CODEC_ID_OPUS};
use symphonia::core::codecs::audio::{AudioDecoder, AudioDecoderOptions};
use symphonia::core::errors::Error as SymError;
use symphonia::core::formats::probe::Hint;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo, TrackType};
use symphonia::core::io::{MediaSource, MediaSourceStream, MediaSourceStreamOptions};
use symphonia::core::meta::MetadataOptions;
use symphonia::core::packet::Packet;
use symphonia::core::units::{Time, TimeBase, Timestamp};

use crate::cache::DiskCache;
use crate::http::{HttpSource, ReaderCtl};
use crate::Source;

/// Why decoding stopped.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodeError {
    /// A newer command interrupted a blocked read; not a real failure.
    Aborted,
    Failed(String),
}

impl std::fmt::Display for DecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DecodeError::Aborted => f.write_str("aborted"),
            DecodeError::Failed(m) => f.write_str(m),
        }
    }
}

enum Codec {
    Sym(Box<dyn AudioDecoder>),
    Opus(Box<OpusCodec>),
}

pub struct TrackDecoder {
    format: Box<dyn FormatReader>,
    codec: Codec,
    track_id: u32,
    time_base: Option<TimeBase>,
    sample_rate: u32,
    duration_ms: Option<u64>,
    /// Audio before this time (seconds) is dropped: the seek target, or 0 for encoder delay.
    skip_until: Option<f64>,
    scratch: Vec<f32>,
    ctl: Arc<ReaderCtl>,
    source: Source,
    cache: Arc<DiskCache>,
    /// Encoder priming the container does not describe (AAC in MP4: 1024 frames), in seconds.
    priming: f64,
    /// End time (seconds) of the last packet decoded.
    last_end: f64,
    /// The demuxer reached (or failed at) the end: open the source again before seeking.
    needs_reopen: bool,
}

impl TrackDecoder {
    /// Opens and probes the source. For HTTP sources this blocks on the network (first chunk).
    pub fn open(source: &Source, cache: &Arc<DiskCache>, ctl: Arc<ReaderCtl>) -> Result<Self, DecodeError> {
        let format = open_format(source, cache, &ctl)?;
        Self::with_format(format, ctl, source.clone(), cache.clone())
    }

    fn with_format(
        format: Box<dyn FormatReader>,
        ctl: Arc<ReaderCtl>,
        source: Source,
        cache: Arc<DiskCache>,
    ) -> Result<Self, DecodeError> {
        let track = format
            .default_track(TrackType::Audio)
            .ok_or_else(|| DecodeError::Failed("no audio track".into()))?;
        let params = track
            .codec_params
            .as_ref()
            .and_then(|p| p.audio())
            .ok_or_else(|| DecodeError::Failed("no audio codec parameters".into()))?
            .clone();
        let track_id = track.id;
        let time_base = track.time_base;
        let duration_ms = match (track.duration, time_base) {
            (Some(d), Some(tb)) => tb.calc_duration(d).map(|t| t.as_millis().max(0) as u64),
            _ => None,
        }
        .or_else(|| {
            let rate = params.sample_rate? as u64;
            track.num_frames.map(|n| n * 1000 / rate.max(1))
        })
        .or_else(|| {
            let info = format.media_info();
            let (d, tb) = (info.duration?, info.time_base?);
            tb.calc_duration(d).map(|t| t.as_millis().max(0) as u64)
        });

        let codec = if params.codec == CODEC_ID_OPUS {
            Codec::Opus(Box::new(OpusCodec::new(params.extra_data.as_deref(), params.channels.as_ref().map(|c| c.count()))?))
        } else {
            let dec = symphonia::default::get_codecs()
                .make_audio_decoder(&params, &AudioDecoderOptions::default())
                .map_err(|e| DecodeError::Failed(format!("unsupported codec: {e}")))?;
            Codec::Sym(dec)
        };
        let sample_rate = match &codec {
            Codec::Opus(_) => 48_000,
            Codec::Sym(_) => params.sample_rate.unwrap_or(44_100),
        };
        // symphonia does not read MP4 edit lists yet: drop the AAC encoder's priming frame here.
        let priming = if format.format_info().short_name == "isomp4" && params.codec == CODEC_ID_AAC && track.delay.is_none()
        {
            1024.0 / sample_rate as f64
        } else {
            0.0
        };
        Ok(TrackDecoder {
            format,
            codec,
            track_id,
            time_base,
            sample_rate,
            duration_ms,
            skip_until: Some(0.0),
            scratch: Vec::new(),
            ctl,
            source,
            cache,
            priming,
            last_end: 0.0,
            needs_reopen: false,
        })
    }

    /// Rate of the decoded audio (it may change mid-stream; check after each packet).
    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn duration_ms(&self) -> Option<u64> {
        self.duration_ms
    }

    pub fn source(&self) -> &Source {
        &self.source
    }

    pub(crate) fn ctl(&self) -> &Arc<ReaderCtl> {
        &self.ctl
    }

    /// Decodes the next packet, appending interleaved stereo samples to `out`.
    /// Returns `Ok(false)` at the end of the track.
    pub fn decode_next(&mut self, out: &mut Vec<f32>) -> Result<bool, DecodeError> {
        loop {
            let packet = match self.format.next_packet() {
                Ok(Some(p)) => p,
                Ok(None) | Err(SymError::ResetRequired) => {
                    // Demuxers do not all seek well from the end (mkv, isomp4): reopen first.
                    self.needs_reopen = true;
                    return Ok(false);
                }
                Err(SymError::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof && !self.ctl.aborted() => {
                    self.needs_reopen = true;
                    return Ok(false);
                }
                Err(e) if !self.ctl.aborted() && self.near_end() => {
                    // Some demuxers (isomp4 after a seek) fail instead of ending cleanly.
                    log::debug!("demux error at the end, taken as the end: {e}");
                    self.needs_reopen = true;
                    return Ok(false);
                }
                Err(e) => return Err(sym_failure(&self.ctl, e)),
            };
            if packet.track_id != self.track_id {
                continue;
            }
            let (channels, rate) = match self.decode_packet(&packet) {
                Ok(Some(x)) => x,
                Ok(None) => continue,
                Err(e) => return Err(e),
            };
            self.sample_rate = rate;
            let frames = self.scratch.len() / channels.max(1);
            self.last_end = self.packet_time(&packet) + frames as f64 / rate as f64;
            let mut first = 0usize;
            if let Some(until) = self.skip_until {
                let start = self.packet_time(&packet);
                let end = start + frames as f64 / rate as f64;
                if end <= until {
                    continue;
                }
                if start < until {
                    first = (((until - start) * rate as f64).round() as usize).min(frames);
                }
                self.skip_until = None;
            }
            to_stereo(&self.scratch[first * channels..frames * channels], channels, out);
            return Ok(true);
        }
    }

    fn near_end(&self) -> bool {
        self.duration_ms.is_some_and(|d| self.last_end * 1000.0 >= d as f64 - 250.0)
    }

    fn packet_time(&self, packet: &Packet) -> f64 {
        let t = match self.time_base {
            Some(tb) => tb.calc_time_saturating(packet.pts).as_secs_f64(),
            None => packet.pts.get() as f64 / self.sample_rate as f64,
        };
        t - self.priming
    }

    /// Decodes into `self.scratch` (interleaved, native channel count). `None` skips the packet.
    fn decode_packet(&mut self, packet: &Packet) -> Result<Option<(usize, u32)>, DecodeError> {
        match &mut self.codec {
            Codec::Opus(op) => {
                let trim = |d: symphonia::core::units::Duration| -> usize {
                    // Ogg gives trims in 1/48000 ticks; convert through the time base anyway.
                    match self.time_base {
                        Some(tb) => (d.get() as f64 * tb.numer.get() as f64 / tb.denom.get() as f64 * 48_000.0).round()
                            as usize,
                        None => d.get() as usize,
                    }
                };
                let (ts, te) = (trim(packet.trim_start), trim(packet.trim_end));
                match op.decode(&packet.data, &mut self.scratch) {
                    Ok(frames) => {
                        let ch = op.channels;
                        let ts = ts.min(frames);
                        let te = te.min(frames - ts);
                        self.scratch.truncate((frames - te) * ch);
                        self.scratch.drain(..ts * ch);
                        Ok(Some((ch, 48_000)))
                    }
                    Err(e) => {
                        log::warn!("opus packet skipped: {e}");
                        Ok(None)
                    }
                }
            }
            Codec::Sym(dec) => match dec.decode(packet) {
                Ok(buf) => {
                    let ch = buf.spec().channels().count().max(1);
                    let rate = buf.spec().rate();
                    self.scratch.clear();
                    self.scratch.resize(buf.samples_interleaved(), 0.0);
                    buf.copy_to_slice_interleaved::<f32, _>(&mut self.scratch[..]);
                    Ok(Some((ch, rate)))
                }
                Err(SymError::DecodeError(e)) => {
                    log::warn!("packet skipped: {e}");
                    Ok(None)
                }
                Err(SymError::IoError(_)) => Ok(None),
                Err(e) => Err(DecodeError::Failed(format!("decoder: {e}"))),
            },
        }
    }

    /// Seeks so the next decoded sample is the one at `ms`. Returns where it landed (`ms`).
    pub fn seek(&mut self, ms: u64) -> Result<u64, DecodeError> {
        let ms = match self.duration_ms {
            Some(d) => ms.min(d.saturating_sub(1)),
            None => ms,
        };
        // Back to the start: a fresh reader is exactly the initial state (demuxers drop the first
        // packet's encoder delay differently after a seek). Cheap: the header is cached.
        if self.needs_reopen || ms == 0 {
            self.format = open_format(&self.source, &self.cache, &self.ctl)?;
            self.needs_reopen = false;
        }
        self.last_end = ms as f64 / 1000.0;
        if ms == 0 {
            // Already there.
        } else if let Err(e) = self.seek_format(ms) {
            if e == DecodeError::Aborted {
                return Err(e);
            }
            // Some demuxers cannot seek once they hit the end (mkv): open the source again.
            log::debug!("seek failed ({e}), reopening");
            self.format = open_format(&self.source, &self.cache, &self.ctl)?;
            if ms > 0 {
                self.seek_format(ms)?;
            }
        }
        match &mut self.codec {
            Codec::Sym(d) => d.reset(),
            Codec::Opus(o) => o.reset(),
        }
        self.skip_until = Some(ms as f64 / 1000.0);
        Ok(ms)
    }

    /// Positions the demuxer at or before `ms` (the decoder then drops what precedes `ms`).
    fn seek_format(&mut self, ms: u64) -> Result<(), DecodeError> {
        let mut req = ms;
        let mut step = 0u64;
        for _ in 0..8 {
            let landed = self.seek_once(req)?;
            // Within a packet of the target is fine: the decoder drops the rest.
            if landed <= ms as f64 / 1000.0 + 0.1 || req == 0 {
                return Ok(());
            }
            // Landed past the target. symphonia's mkv reader gives the cluster it finds through the
            // cues a timestamp of 0, so it skips that whole cluster: aim earlier, in steps that grow.
            let over = ((landed * 1000.0) as u64).saturating_sub(ms) + 1;
            step = if step == 0 { over } else { step * 2 };
            log::debug!("seek to {ms} ms landed at {landed:.3} s, retrying at {} ms", ms.saturating_sub(step));
            req = ms.saturating_sub(step);
            // A fresh reader: after that bug it does not seek backwards reliably.
            self.format = open_format(&self.source, &self.cache, &self.ctl)?;
        }
        self.seek_once(0).map(|_| ())
    }

    /// One demuxer seek; returns where it landed, in seconds of content.
    fn seek_once(&mut self, ms: u64) -> Result<f64, DecodeError> {
        let to = if ms == 0 && self.time_base.is_some() {
            SeekTo::Timestamp { ts: Timestamp::ZERO, track_id: self.track_id }
        } else {
            let time = Time::try_from_secs_f64(ms as f64 / 1000.0 + self.priming).unwrap_or(Time::ZERO);
            SeekTo::Time { time, track_id: Some(self.track_id) }
        };
        let seeked = self.format.seek(SeekMode::Accurate, to).map_err(|e| sym_failure(&self.ctl, e))?;
        let t = match self.time_base {
            Some(tb) => tb.calc_time_saturating(seeked.actual_ts).as_secs_f64(),
            None => seeked.actual_ts.get() as f64 / self.sample_rate as f64,
        };
        Ok(t - self.priming)
    }
}

fn open_format(source: &Source, cache: &Arc<DiskCache>, ctl: &Arc<ReaderCtl>) -> Result<Box<dyn FormatReader>, DecodeError> {
    let mut hint = Hint::new();
    let media: Box<dyn MediaSource> = match source {
        Source::File { path } => {
            if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
                hint.with_extension(&ext.to_ascii_lowercase());
            }
            Box::new(File::open(path).map_err(|e| DecodeError::Failed(format!("{}: {e}", path.display())))?)
        }
        Source::Http { url, cache_key, headers } => {
            if url.contains("audio%2Fwebm") || url.contains("audio/webm") {
                hint.with_extension("webm");
            } else if url.contains("audio%2Fmp4") || url.contains("audio/mp4") {
                hint.with_extension("m4a");
            }
            let src = HttpSource::open(url, cache_key, headers, cache.clone(), ctl.clone())
                .map_err(|e| io_failure(ctl, e))?;
            Box::new(src)
        }
    };
    let mss = MediaSourceStream::new(media, MediaSourceStreamOptions::default());
    symphonia::default::get_probe()
        .probe(&hint, mss, FormatOptions::default(), MetadataOptions::default())
        .map_err(|e| sym_failure(ctl, e))
}

fn to_stereo(src: &[f32], channels: usize, out: &mut Vec<f32>) {
    match channels {
        1 => {
            out.reserve(src.len() * 2);
            for &s in src {
                out.push(s);
                out.push(s);
            }
        }
        2 => out.extend_from_slice(src),
        n => {
            out.reserve(src.len() / n * 2);
            for f in src.chunks_exact(n) {
                out.push(f[0]);
                out.push(f[1]);
            }
        }
    }
}

fn io_failure(ctl: &ReaderCtl, e: std::io::Error) -> DecodeError {
    if ctl.aborted() {
        DecodeError::Aborted
    } else {
        DecodeError::Failed(format!("network: {e}"))
    }
}

fn sym_failure(ctl: &ReaderCtl, e: SymError) -> DecodeError {
    if ctl.aborted() {
        DecodeError::Aborted
    } else {
        match e {
            SymError::IoError(e) => DecodeError::Failed(format!("read: {e}")),
            e => DecodeError::Failed(format!("demux: {e}")),
        }
    }
}

/// Opus through the pure-Rust decoder, at 48 kHz.
struct OpusCodec {
    dec: opus_decoder::OpusDecoder,
    channels: usize,
    /// Output gain from the OpusHead, as a linear factor.
    gain: f32,
}

impl OpusCodec {
    fn new(head: Option<&[u8]>, channels: Option<usize>) -> Result<Self, DecodeError> {
        // OpusHead: magic(8) version(1) channels(1) pre_skip(2) rate(4) gain(2, Q7.8 dB) family(1)
        let (head_channels, gain_db) = match head {
            Some(h) if h.len() >= 19 && &h[..8] == b"OpusHead" => {
                (Some(h[9] as usize), i16::from_le_bytes([h[16], h[17]]) as f32 / 256.0)
            }
            _ => (None, 0.0),
        };
        let channels = head_channels.or(channels).unwrap_or(2);
        if !(1..=2).contains(&channels) {
            return Err(DecodeError::Failed(format!("Opus with {channels} channels is not supported")));
        }
        let dec = opus_decoder::OpusDecoder::new(48_000, channels)
            .map_err(|e| DecodeError::Failed(format!("opus: {e:?}")))?;
        Ok(OpusCodec { dec, channels, gain: 10f32.powf(gain_db / 20.0) })
    }

    /// Decodes one packet into `out` (replacing it); returns frames.
    fn decode(&mut self, packet: &[u8], out: &mut Vec<f32>) -> Result<usize, String> {
        out.clear();
        out.resize(opus_decoder::OpusDecoder::MAX_FRAME_SIZE_48K * self.channels, 0.0);
        let frames = self.dec.decode_float(packet, out, false).map_err(|e| format!("{e:?}"))?;
        out.truncate(frames * self.channels);
        if (self.gain - 1.0).abs() > 1e-6 {
            for s in out.iter_mut() {
                *s *= self.gain;
            }
        }
        Ok(frames)
    }

    fn reset(&mut self) {
        self.dec.reset();
    }
}

/// Sample-rate conversion of interleaved stereo to the output device's rate.
pub struct Resample {
    in_rate: u32,
    out_rate: u32,
    inner: Option<rubato::Fft<f32>>,
    pending: Vec<f32>,
    out_buf: Vec<f32>,
    /// Leading output frames that are the resampler's own delay, still to drop.
    delay_left: usize,
    fed: u64,
    emitted: u64,
}

const RS_CHUNK: usize = 1024;

impl Resample {
    pub fn new(in_rate: u32, out_rate: u32) -> Self {
        let mut r = Resample {
            in_rate,
            out_rate,
            inner: None,
            pending: Vec::new(),
            out_buf: Vec::new(),
            delay_left: 0,
            fed: 0,
            emitted: 0,
        };
        r.reset();
        r
    }

    pub fn rates(&self) -> (u32, u32) {
        (self.in_rate, self.out_rate)
    }

    pub fn reset(&mut self) {
        use rubato::Resampler;
        self.pending.clear();
        self.fed = 0;
        self.emitted = 0;
        if self.in_rate == self.out_rate {
            self.inner = None;
            self.delay_left = 0;
            return;
        }
        match rubato::Fft::<f32>::new(
            self.in_rate as usize,
            self.out_rate as usize,
            RS_CHUNK,
            2,
            rubato::FixedSync::Input,
        ) {
            Ok(fft) => {
                self.delay_left = fft.output_delay();
                self.out_buf = vec![0.0; fft.output_frames_max() * 2];
                self.inner = Some(fft);
            }
            Err(e) => {
                log::error!("resampler {} -> {}: {e}", self.in_rate, self.out_rate);
                self.inner = None;
            }
        }
    }

    /// Resamples `input` (interleaved stereo), appending to `out`.
    pub fn process(&mut self, input: &[f32], out: &mut Vec<f32>) {
        if self.inner.is_none() {
            out.extend_from_slice(input);
            return;
        }
        self.fed += (input.len() / 2) as u64;
        self.pending.extend_from_slice(input);
        while self.pending.len() >= RS_CHUNK * 2 {
            self.run_chunk(RS_CHUNK, out);
            self.pending.drain(..RS_CHUNK * 2);
        }
    }

    /// End of the stream: pushes out what is left, exactly as many frames as the input implies.
    pub fn flush(&mut self, out: &mut Vec<f32>) {
        if self.inner.is_none() {
            return;
        }
        let expected = (self.fed as f64 * self.out_rate as f64 / self.in_rate as f64).round() as u64;
        let mut rounds = 0;
        while self.emitted < expected && rounds < 64 {
            let take = (self.pending.len() / 2).min(RS_CHUNK);
            self.pending.resize(RS_CHUNK * 2, 0.0);
            self.run_chunk(take, out);
            self.pending.clear();
            rounds += 1;
        }
        // Trim what the zero padding produced past the true end.
        let extra = self.emitted.saturating_sub(expected) as usize;
        out.truncate(out.len().saturating_sub(extra * 2));
        self.emitted = expected;
    }

    fn run_chunk(&mut self, frames: usize, out: &mut Vec<f32>) {
        use rubato::audioadapter_buffers::direct::InterleavedSlice;
        use rubato::{Indexing, Resampler};
        let Some(fft) = self.inner.as_mut() else { return };
        let input = match InterleavedSlice::new(&self.pending[..RS_CHUNK * 2], 2, RS_CHUNK) {
            Ok(i) => i,
            Err(_) => return,
        };
        let cap = self.out_buf.len() / 2;
        let mut output = match InterleavedSlice::new_mut(&mut self.out_buf[..], 2, cap) {
            Ok(o) => o,
            Err(_) => return,
        };
        let mut indexing = Indexing::new();
        indexing.partial_len = Some(frames);
        let (_, produced) = match fft.process_into_buffer(&input, &mut output, Some(&indexing)) {
            Ok(r) => r,
            Err(e) => {
                log::error!("resample: {e}");
                return;
            }
        };
        let skip = self.delay_left.min(produced);
        self.delay_left -= skip;
        out.extend_from_slice(&self.out_buf[skip * 2..produced * 2]);
        self.emitted += (produced - skip) as u64;
    }
}
