// LFly patch: Liquid Glass, as LFly's Tauri window draws it in Chromium (lens.ts `drawSvg`,
// Glass.svelte, app.css). Everything is in device pixels of the frame.
//
// Chromium runs `backdrop-filter: url(#lens) blur(B) saturate(S)`: the displacement map bends the
// backdrop near the rim (red, green and blue by slightly different amounts), then the result is
// blurred and saturated. Here the backdrop is blurred first and then sampled where the map points;
// the map is smooth, so the two orders look the same. Then, as the CSS layers do: the tint and its
// inset shadows (::before), the pointer's light (::after), and the 1px rim with its conic light.

struct GlassParams {
    rect: vec4<f32>,       // origin.xy, size.xy
    clip: vec4<f32>,       // origin.xy, size.xy
    p0: vec4<f32>,         // corner radius, scale (device px per CSS px), saturation, refraction height
    p1: vec4<f32>,         // refraction amount, dispersion, conic start angle (rad), pointer alpha
    p2: vec4<f32>,         // pointer x, pointer y (0..1 of the glass), premultiplied output, has backdrop
    p3: vec4<f32>,         // viewport w, viewport h, edge bleed (0..1), blur sigma
    tint: vec4<f32>,
    border: vec4<f32>,
    highlight: vec4<f32>,
    shade: vec4<f32>,
    shadow0: vec4<f32>,
    shadow1: vec4<f32>,
    shadow_geometry: vec4<f32>, // y offset 0, blur 0, y offset 1, blur 1
    p4: vec4<f32>,         // lip alpha (--g-lip), unused...
}

@group(0) @binding(0) var<uniform> g: GlassParams;
@group(0) @binding(1) var t_backdrop: texture_2d<f32>;
@group(0) @binding(2) var s_backdrop: sampler;
@group(0) @binding(3) var t_sharp: texture_2d<f32>;

struct GlassVarying {
    @builtin(position) position: vec4<f32>,
    @location(0) clip_distances: vec4<f32>,
}

@vertex
fn vs_glass(@builtin(vertex_index) vertex_id: u32) -> GlassVarying {
    let unit = vec2<f32>(f32(vertex_id & 1u), 0.5 * f32(vertex_id & 2u));
    // Room around the glass for its outer shadows.
    let sg = g.shadow_geometry;
    let margin = max(abs(sg.x) + sg.y * 1.5, abs(sg.z) + sg.w * 1.5) + 1.0;
    let pos = g.rect.xy - vec2<f32>(margin) + unit * (g.rect.zw + vec2<f32>(2.0 * margin));
    var out: GlassVarying;
    let device = pos / g.p3.xy * vec2<f32>(2.0, -2.0) + vec2<f32>(-1.0, 1.0);
    out.position = vec4<f32>(device, 0.0, 1.0);
    let tl = pos - g.clip.xy;
    let br = g.clip.xy + g.clip.zw - pos;
    out.clip_distances = vec4<f32>(tl.x, br.x, tl.y, br.y);
    return out;
}

fn sd_rounded_rect(p: vec2<f32>, h: vec2<f32>, r: f32) -> f32 {
    let c = abs(p) - (h - vec2<f32>(r));
    return length(max(c, vec2<f32>(0.0))) - r + min(max(c.x, c.y), 0.0);
}

// The map's direction (displacementMap in lens.ts): the SDF gradient of a rounder rect, plus the
// direction from the centre (depth 1), normalised.
fn bend_direction(p: vec2<f32>, h: vec2<f32>, gr: f32) -> vec2<f32> {
    let c = abs(p) - (h - vec2<f32>(gr));
    var grad: vec2<f32>;
    if (c.x >= 0.0 || c.y >= 0.0) {
        let m = max(c, vec2<f32>(0.0));
        let l = max(length(m), 1e-6);
        grad = sign(p) * (m / l);
    } else {
        let s = select(0.0, 1.0, c.y <= c.x);
        grad = sign(p) * vec2<f32>(s, 1.0 - s);
    }
    let pl = max(length(p), 1e-6);
    let v = grad + p / pl;
    return v / max(length(v), 1e-6);
}

fn backdrop(at: vec2<f32>) -> vec3<f32> {
    let uv = clamp(at / g.p3.xy, vec2<f32>(0.0), vec2<f32>(1.0));
    return textureSampleLevel(t_backdrop, s_backdrop, uv, 0.0).rgb;
}

// CSS `saturate()`: the filter-effects matrix, i.e. a mix with Rec.709-ish luminance.
fn saturate_css(c: vec3<f32>, s: f32) -> vec3<f32> {
    let l = dot(c, vec3<f32>(0.213, 0.715, 0.072));
    return clamp(mix(vec3<f32>(l), c, s), vec3<f32>(0.0), vec3<f32>(1.0));
}

fn over(dst: vec3<f32>, src: vec4<f32>) -> vec3<f32> {
    return mix(dst, src.rgb, clamp(src.a, 0.0, 1.0));
}

// Coverage of an edge blurred by a Gaussian (CSS box-shadow blur radius = 2 sigma).
fn gauss_cdf(x: f32) -> f32 {
    // Abramowitz-Stegun style erf approximation, good to ~1e-3.
    let t = x * 0.70710678;
    let a = abs(t);
    let k = 1.0 / (1.0 + 0.3275911 * a);
    let y = 1.0 - (((((1.061405429 * k - 1.453152027) * k) + 1.421413741) * k - 0.284496736) * k + 0.254829592) * k * exp(-a * a);
    return 0.5 * (1.0 + sign(t) * y);
}

// An inset box-shadow (offset oy, blur b, spread sp, all device px) of a rounded box: how much of
// the shadow colour shows at p. The hole is the box moved by oy and shrunk by sp; blurred by b.
fn inset_shadow(p: vec2<f32>, h: vec2<f32>, r: f32, oy: f32, b: f32, sp: f32) -> f32 {
    let hh = max(h - vec2<f32>(sp), vec2<f32>(0.0));
    let rr = max(r - sp, 0.0);
    let d = sd_rounded_rect(p - vec2<f32>(0.0, oy), hh, min(rr, min(hh.x, hh.y)));
    let sigma = max(b * 0.5, 0.25);
    return gauss_cdf(d / sigma);
}

const TAU: f32 = 6.28318530718;

// The rim's conic-gradient (Glass.svelte .lfly-g-rim), angle in turns from the start, 0..1.
fn conic(turn: f32) -> vec4<f32> {
    let hi = g.highlight;
    let mid = vec4<f32>(1.0, 1.0, 1.0, 0.28);
    let deg = turn * 360.0;
    // Stops: hi 0, clear 70, clear 110, mid 180, clear 250, clear 290, hi 360 (premultiplied mix).
    var a = vec4<f32>(0.0);
    if (deg < 70.0) {
        a = vec4<f32>(hi.rgb, hi.a * (1.0 - deg / 70.0));
    } else if (deg < 110.0) {
        a = vec4<f32>(hi.rgb, 0.0);
    } else if (deg < 180.0) {
        a = vec4<f32>(mid.rgb, mid.a * (deg - 110.0) / 70.0);
    } else if (deg < 250.0) {
        a = vec4<f32>(mid.rgb, mid.a * (1.0 - (deg - 180.0) / 70.0));
    } else if (deg < 290.0) {
        a = vec4<f32>(hi.rgb, 0.0);
    } else {
        a = vec4<f32>(hi.rgb, hi.a * (deg - 290.0) / 70.0);
    }
    return a;
}

@fragment
fn fs_glass(input: GlassVarying) -> @location(0) vec4<f32> {
    if (any(input.clip_distances < vec4<f32>(0.0))) {
        return vec4<f32>(0.0);
    }
    let pos = input.position.xy;
    let size = g.rect.zw;
    let half = size * 0.5;
    let p = pos - (g.rect.xy + half);
    let radius = min(g.p0.x, min(half.x, half.y));
    let scale = g.p0.y;
    let sd = sd_rounded_rect(p, half, radius);
    let coverage = clamp(0.5 - sd, 0.0, 1.0);

    // ---- Outer shadows (box-shadow, clipped to outside the edge) ----
    var shadow = vec4<f32>(0.0);
    if (coverage < 1.0) {
        let sg = g.shadow_geometry;
        let s0 = sd_rounded_rect(p - vec2<f32>(0.0, sg.x), half, radius);
        let a0 = g.shadow0.a * (1.0 - gauss_cdf(s0 / max(sg.y * 0.5, 0.25)));
        let s1 = sd_rounded_rect(p - vec2<f32>(0.0, sg.z), half, radius);
        let a1 = g.shadow1.a * (1.0 - gauss_cdf(s1 / max(sg.w * 0.5, 0.25)));
        // The second shadow under the first, both over the page.
        let a = a0 + a1 * (1.0 - a0);
        let rgb = (g.shadow0.rgb * a0 + g.shadow1.rgb * a1 * (1.0 - a0)) / max(a, 1e-4);
        shadow = vec4<f32>(rgb, a * (1.0 - coverage));
    }
    if (coverage <= 0.0) {
        let premul = g.p2.z > 0.5;
        return vec4<f32>(select(shadow.rgb, shadow.rgb * shadow.a, premul), shadow.a);
    }

    // ---- The bent, blurred, saturated backdrop ----
    var color = vec3<f32>(0.0);
    var alpha = 0.0;
    if (g.p2.w > 0.5) {
        let ref_h = g.p0.w;
        let ref_amt = g.p1.x;
        let disp = g.p1.y;
        let inside = -sd;
        var off = vec2<f32>(0.0);
        if (inside < ref_h && inside > -1.0 && ref_h > 0.0) {
            let t = 1.0 - max(0.0, inside) / ref_h;
            let d = (1.0 - sqrt(max(0.0, 1.0 - t * t))) * ref_amt;
            let gr = min(radius * 1.5, min(half.x, half.y));
            off = -d * bend_direction(p, half, gr);
        }
        let r = backdrop(pos + off * (1.0 + disp)).r;
        let gg = backdrop(pos + off).g;
        let b = backdrop(pos + off * (1.0 - disp)).b;
        color = saturate_css(vec3<f32>(r, gg, b), g.p0.z);
        // Chromium: the lens filter's output stops at the element box, so the blur after it
        // fades to transparent toward the edges and the plain backdrop shows through there.
        let bleed = g.p3.z;
        if (bleed > 0.0) {
            let sigma = max(g.p3.w, 0.25);
            let local = pos - g.rect.xy;
            let ax = gauss_cdf(local.x / sigma) + gauss_cdf((size.x - local.x) / sigma) - 1.0;
            let ay = gauss_cdf(local.y / sigma) + gauss_cdf((size.y - local.y) / sigma) - 1.0;
            let kept = clamp(ax * ay, 0.0, 1.0);
            let uv = clamp(pos / g.p3.xy, vec2<f32>(0.0), vec2<f32>(1.0));
            let raw = textureSampleLevel(t_sharp, s_backdrop, uv, 0.0).rgb;
            color = mix(color, mix(raw, color, kept), bleed);
        }
        alpha = 1.0;
    }

    // ---- ::before: the tint, a lit upper lip, a soft shade inside the lower edge ----
    color = over(color, g.tint);
    alpha = alpha + g.tint.a * (1.0 - alpha);
    let lip_top = inset_shadow(p, half, radius, 1.0 * scale, 0.5 * scale, 0.0);
    color = over(color, vec4<f32>(1.0, 1.0, 1.0, g.p4.x * lip_top));
    let lip_bottom = inset_shadow(p, half, radius, -1.0 * scale, 0.5 * scale, 0.0);
    color = over(color, vec4<f32>(0.0, 0.0, 0.0, 0.06 * lip_bottom));
    let low = inset_shadow(p, half, radius, -14.0 * scale, 24.0 * scale, -18.0 * scale);
    color = over(color, vec4<f32>(g.shade.rgb, g.shade.a * low));

    // ---- ::after: light where the pointer is (radial-gradient, farthest corner) ----
    if (g.p1.w > 0.0) {
        let c = vec2<f32>(g.p2.x, g.p2.y) * size;
        let local = pos - g.rect.xy;
        let far = max(max(length(c), length(c - vec2<f32>(size.x, 0.0))), max(length(c - vec2<f32>(0.0, size.y)), length(c - size)));
        let t = length(local - c) / max(far, 1.0);
        var a = 0.0;
        if (t < 0.38) {
            a = mix(0.2, 0.06, t / 0.38);
        } else if (t < 0.7) {
            a = mix(0.06, 0.0, (t - 0.38) / 0.32);
        }
        color = over(color, vec4<f32>(1.0, 1.0, 1.0, a * g.p1.w));
        alpha = alpha + a * g.p1.w * (1.0 - alpha);
    }

    // ---- The rim: a 1px band inside the edge, conic light over the border colour ----
    let inner = sd_rounded_rect(p, half - vec2<f32>(scale), max(radius - scale, 0.0));
    let band = clamp(inner + 0.5, 0.0, 1.0);
    if (band > 0.0) {
        // CSS conic angles run clockwise from the top.
        let ang = atan2(p.x, -p.y);
        let turn = fract((ang - g.p1.z) / TAU + 1.0);
        let lit = conic(turn);
        var rim = g.border;
        rim = vec4<f32>(mix(rim.rgb, lit.rgb, lit.a / max(lit.a + rim.a * (1.0 - lit.a), 1e-4)), lit.a + rim.a * (1.0 - lit.a));
        color = over(color, vec4<f32>(rim.rgb, rim.a * band));
        alpha = alpha + rim.a * band * (1.0 - alpha);
    }

    // The antialiased edge: glass over its own shadow.
    let ga = alpha * coverage;
    let a = ga + shadow.a * (1.0 - ga);
    let rgb = (color * ga + shadow.rgb * shadow.a * (1.0 - ga)) / max(a, 1e-4);
    let premultiplied = g.p2.z > 0.5;
    return vec4<f32>(select(rgb, rgb * a, premultiplied), a);
}
