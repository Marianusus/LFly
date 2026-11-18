//! The iterative radix-2 FFT the phone uses, in doubles and in the same order of operations, so the
//! spectrogram fed to the beat model and the chromagram come out the same on both sides.

use std::f64::consts::PI;

pub(crate) struct Fft {
    size: usize,
    reversed: Vec<usize>,
    cos: Vec<f64>,
    sin: Vec<f64>,
}

impl Fft {
    /// `size` must be a power of two.
    pub fn new(size: usize) -> Self {
        assert!(size.is_power_of_two() && size >= 2, "FFT size must be a power of two");
        let bits = size.trailing_zeros();
        Fft {
            size,
            reversed: (0..size).map(|i| ((i as u32).reverse_bits() >> (32 - bits)) as usize).collect(),
            cos: (0..size / 2).map(|i| (-2.0 * PI * i as f64 / size as f64).cos()).collect(),
            sin: (0..size / 2).map(|i| (-2.0 * PI * i as f64 / size as f64).sin()).collect(),
        }
    }

    /// In place; both slices are `size` long.
    pub fn transform(&self, real: &mut [f64], imaginary: &mut [f64]) {
        for index in 0..self.size {
            let target = self.reversed[index];
            if index < target {
                real.swap(index, target);
                imaginary.swap(index, target);
            }
        }
        let mut length = 2;
        while length <= self.size {
            let half = length / 2;
            let step = self.size / length;
            let mut start = 0;
            while start < self.size {
                for offset in 0..half {
                    let wr = self.cos[offset * step];
                    let wi = self.sin[offset * step];
                    let odd = start + offset + half;
                    let even = start + offset;
                    let odd_real = real[odd] * wr - imaginary[odd] * wi;
                    let odd_imaginary = real[odd] * wi + imaginary[odd] * wr;
                    real[odd] = real[even] - odd_real;
                    imaginary[odd] = imaginary[even] - odd_imaginary;
                    real[even] += odd_real;
                    imaginary[even] += odd_imaginary;
                }
                start += length;
            }
            length *= 2;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_the_plain_transform() {
        let n = 64;
        let signal: Vec<f64> = (0..n).map(|i| ((i * 7 % 13) as f64 - 6.0) / 6.0).collect();
        let mut real = signal.clone();
        let mut imaginary = vec![0.0; n];
        Fft::new(n).transform(&mut real, &mut imaginary);
        for bin in 0..n {
            let (mut re, mut im) = (0.0, 0.0);
            for (i, x) in signal.iter().enumerate() {
                let angle = -2.0 * PI * (bin * i) as f64 / n as f64;
                re += x * angle.cos();
                im += x * angle.sin();
            }
            assert!((real[bin] - re).abs() < 1e-9 && (imaginary[bin] - im).abs() < 1e-9, "bin {bin}");
        }
    }
}
