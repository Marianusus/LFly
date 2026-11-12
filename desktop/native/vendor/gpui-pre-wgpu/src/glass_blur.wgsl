// LFly patch: the Liquid Glass backdrop blur (see glass.wgsl).

// ---- Backdrop blur: copy down to a smaller texture, then a separable Gaussian ----

struct BlurParams {
    // direction (texels), sigma (texels of this texture), radius (taps)
    p: vec4<f32>,
}

@group(0) @binding(0) var<uniform> blur: BlurParams;
@group(0) @binding(1) var t_src: texture_2d<f32>;
@group(0) @binding(2) var s_src: sampler;

struct FullVarying {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
}

@vertex
fn vs_full(@builtin(vertex_index) vertex_id: u32) -> FullVarying {
    let uv = vec2<f32>(f32((vertex_id << 1u) & 2u), f32(vertex_id & 2u));
    var out: FullVarying;
    out.position = vec4<f32>(uv * vec2<f32>(2.0, -2.0) + vec2<f32>(-1.0, 1.0), 0.0, 1.0);
    out.uv = uv;
    return out;
}

@fragment
fn fs_down(input: FullVarying) -> @location(0) vec4<f32> {
    // A 4-tap box at the corners of this texel's footprint in the source.
    let texel = 1.0 / vec2<f32>(textureDimensions(t_src, 0));
    let k = blur.p.xy * texel; // half the footprint, in source uv
    var c = textureSampleLevel(t_src, s_src, input.uv + vec2<f32>(-k.x, -k.y), 0.0);
    c += textureSampleLevel(t_src, s_src, input.uv + vec2<f32>(k.x, -k.y), 0.0);
    c += textureSampleLevel(t_src, s_src, input.uv + vec2<f32>(-k.x, k.y), 0.0);
    c += textureSampleLevel(t_src, s_src, input.uv + vec2<f32>(k.x, k.y), 0.0);
    return vec4<f32>((c * 0.25).rgb, 1.0);
}

@fragment
fn fs_blur(input: FullVarying) -> @location(0) vec4<f32> {
    let texel = 1.0 / vec2<f32>(textureDimensions(t_src, 0));
    let dir = blur.p.xy * texel;
    let sigma = max(blur.p.z, 0.01);
    let taps = i32(blur.p.w);
    var sum = textureSampleLevel(t_src, s_src, input.uv, 0.0).rgb;
    var wsum = 1.0;
    // Pairs of taps read between two texels with the linear sampler (half the reads).
    for (var i = 1; i <= taps; i += 2) {
        let x0 = f32(i);
        let x1 = f32(i + 1);
        let w0 = exp(-(x0 * x0) / (2.0 * sigma * sigma));
        let w1 = exp(-(x1 * x1) / (2.0 * sigma * sigma));
        let w = w0 + w1;
        let o = (x0 * w0 + x1 * w1) / max(w, 1e-6);
        sum += textureSampleLevel(t_src, s_src, input.uv + dir * o, 0.0).rgb * w;
        sum += textureSampleLevel(t_src, s_src, input.uv - dir * o, 0.0).rgb * w;
        wsum += 2.0 * w;
    }
    return vec4<f32>(sum / wsum, 1.0);
}
