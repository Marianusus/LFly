# Changes from opus-decoder 0.1.1 (crates.io)

Upstream: https://github.com/TadeuszWolfGang/Rusopus, license MIT OR Apache-2.0
(LICENSE-MIT and LICENSE-APACHE copied from the upstream repository).

1. `src/celt/vq.rs`, `extract_collapse_mask`: the mask was a `u8` built with `mask |= 1 << i`,
   which overflows (panics in debug, sets the wrong bit in release) when a band is split in more
   than 8 blocks, as happens on real YouTube Opus streams (itag 251). libopus computes it as an
   `unsigned` and truncates to `unsigned char` only where the per-band masks are stored; the
   decoder already does the same (`cm & 0xFF`) in `bands.rs`. The mask is now a `u32`.

2. `src/celt/kiss_fft.rs`, `flat_fft_forward` (the FFT inside the CELT inverse MDCT): was a
   direct O(n²) DFT computing `sin_cos` for every term, decoding only ~4x faster than real time
   even optimized. It now runs the same forward transform in f64 through `rustfft` (already in
   LFly's dependency tree via rubato), with the planner and buffers cached per thread.

The upstream tests, benches and test vectors are not included.
