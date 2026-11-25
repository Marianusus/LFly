//! Small helpers that keep the port numerically in step with the Kotlin it comes from.

/// Kotlin's `Math.round` / `roundToInt`: halves go up (towards positive infinity), where Rust's
/// `f64::round` sends them away from zero. They differ on negative halves only, but a bar line
/// that lands on the other side of one is a different plan.
pub(crate) fn jround(x: f64) -> f64 {
    (x + 0.5).floor()
}

pub(crate) fn sorted(values: &[f64]) -> Vec<f64> {
    let mut out = values.to_vec();
    out.sort_by(f64::total_cmp);
    out
}

/// The upper median, as the Kotlin takes it: `sorted[size / 2]`, 0 for nothing.
pub(crate) fn median(values: &[f64]) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    sorted(values)[values.len() / 2]
}

/// The first item with the smallest key, as Kotlin's `minBy` (ties keep the earlier one).
pub(crate) fn first_min_by<T: Copy>(items: impl IntoIterator<Item = T>, key: impl Fn(T) -> f64) -> Option<T> {
    let mut best: Option<(T, f64)> = None;
    for item in items {
        let k = key(item);
        if best.is_none_or(|(_, b)| k < b) {
            best = Some((item, k));
        }
    }
    best.map(|(item, _)| item)
}

#[cfg(test)]
pub(crate) mod testing {
    /// A tiny deterministic generator for test signals (xorshift64*), uniform in 0..1.
    pub struct Noise(pub u64);

    impl Noise {
        pub fn next(&mut self) -> f64 {
            self.0 ^= self.0 >> 12;
            self.0 ^= self.0 << 25;
            self.0 ^= self.0 >> 27;
            (self.0.wrapping_mul(0x2545_F491_4F6C_DD1D) >> 11) as f64 / (1u64 << 53) as f64
        }
    }

    pub fn sine(freq: f64, sample_rate: u32, seconds: f64, amplitude: f64) -> Vec<f32> {
        (0..(seconds * sample_rate as f64) as usize)
            .map(|i| (amplitude * (2.0 * std::f64::consts::PI * freq * i as f64 / sample_rate as f64).sin()) as f32)
            .collect()
    }
}
