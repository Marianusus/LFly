// LFly patch: Liquid Glass for DirectX 11, a line-by-line port of gpui-pre-wgpu's glass.wgsl
// (which is checked against Chromium rendering LFly's Tauri glass). Keep the two in step.

cbuffer GlassParams : register(b2) {
    float4 g_rect;       // origin.xy, size.xy
    float4 g_clip;       // origin.xy, size.xy
    float4 g_p0;         // corner radius, scale, saturation, refraction height
    float4 g_p1;         // refraction amount, dispersion, conic start (rad), pointer alpha
    float4 g_p2;         // pointer x, pointer y, premultiplied output, has backdrop
    float4 g_p3;         // viewport w, viewport h, edge bleed, blur sigma
    float4 g_tint;
    float4 g_border;
    float4 g_highlight;
    float4 g_shade;
    float4 g_shadow0;
    float4 g_shadow1;
    float4 g_shadow_geometry; // y offset 0, blur 0, y offset 1, blur 1
};

cbuffer BlurParams : register(b3) {
    float4 b_p; // direction or half footprint (texels), sigma, taps
};

Texture2D t_backdrop : register(t0);
Texture2D t_sharp : register(t2);
SamplerState s_linear : register(s0);

struct GlassVarying {
    float4 position : SV_Position;
    float4 clip_distances : TEXCOORD0;
};

GlassVarying glass_vertex(uint vertex_id : SV_VertexID) {
    float2 unit = float2((float)(vertex_id & 1u), 0.5 * (float)(vertex_id & 2u));
    float4 sg = g_shadow_geometry;
    float margin = max(abs(sg.x) + sg.y * 1.5, abs(sg.z) + sg.w * 1.5) + 1.0;
    float2 pos = g_rect.xy - float2(margin, margin) + unit * (g_rect.zw + float2(2.0 * margin, 2.0 * margin));
    GlassVarying o;
    float2 device = pos / g_p3.xy * float2(2.0, -2.0) + float2(-1.0, 1.0);
    o.position = float4(device, 0.0, 1.0);
    float2 tl = pos - g_clip.xy;
    float2 br = g_clip.xy + g_clip.zw - pos;
    o.clip_distances = float4(tl.x, br.x, tl.y, br.y);
    return o;
}

float sd_rounded_rect(float2 p, float2 h, float r) {
    float2 c = abs(p) - (h - float2(r, r));
    return length(max(c, float2(0.0, 0.0))) - r + min(max(c.x, c.y), 0.0);
}

float2 bend_direction(float2 p, float2 h, float gr) {
    float2 c = abs(p) - (h - float2(gr, gr));
    float2 grad;
    float2 sp = float2(sign(p.x), sign(p.y));
    if (c.x >= 0.0 || c.y >= 0.0) {
        float2 m = max(c, float2(0.0, 0.0));
        float l = max(length(m), 1e-6);
        grad = sp * (m / l);
    } else {
        float s = (c.y <= c.x) ? 1.0 : 0.0;
        grad = sp * float2(s, 1.0 - s);
    }
    float pl = max(length(p), 1e-6);
    float2 v = grad + p / pl;
    return v / max(length(v), 1e-6);
}

float3 backdrop_at(float2 at) {
    float2 uv = clamp(at / g_p3.xy, float2(0.0, 0.0), float2(1.0, 1.0));
    return t_backdrop.SampleLevel(s_linear, uv, 0).rgb;
}

float3 saturate_css(float3 c, float s) {
    float l = dot(c, float3(0.213, 0.715, 0.072));
    return clamp(lerp(float3(l, l, l), c, s), 0.0, 1.0);
}

float3 over(float3 dst, float4 src) {
    return lerp(dst, src.rgb, clamp(src.a, 0.0, 1.0));
}

float gauss_cdf(float x) {
    float t = x * 0.70710678;
    float a = abs(t);
    float k = 1.0 / (1.0 + 0.3275911 * a);
    float y = 1.0 - (((((1.061405429 * k - 1.453152027) * k) + 1.421413741) * k - 0.284496736) * k + 0.254829592) * k * exp(-a * a);
    return 0.5 * (1.0 + sign(t) * y);
}

float inset_shadow(float2 p, float2 h, float r, float oy, float b, float sp) {
    float2 hh = max(h - float2(sp, sp), float2(0.0, 0.0));
    float rr = max(r - sp, 0.0);
    float d = sd_rounded_rect(p - float2(0.0, oy), hh, min(rr, min(hh.x, hh.y)));
    float sigma = max(b * 0.5, 0.25);
    return gauss_cdf(d / sigma);
}

static const float TAU = 6.28318530718;

float4 conic(float turn) {
    float4 hi = g_highlight;
    float4 mid = float4(1.0, 1.0, 1.0, 0.28);
    float deg = turn * 360.0;
    float4 a;
    if (deg < 70.0) {
        a = float4(hi.rgb, hi.a * (1.0 - deg / 70.0));
    } else if (deg < 110.0) {
        a = float4(hi.rgb, 0.0);
    } else if (deg < 180.0) {
        a = float4(mid.rgb, mid.a * (deg - 110.0) / 70.0);
    } else if (deg < 250.0) {
        a = float4(mid.rgb, mid.a * (1.0 - (deg - 180.0) / 70.0));
    } else if (deg < 290.0) {
        a = float4(hi.rgb, 0.0);
    } else {
        a = float4(hi.rgb, hi.a * (deg - 290.0) / 70.0);
    }
    return a;
}

float4 glass_fragment(GlassVarying input) : SV_Target {
    if (any(input.clip_distances < float4(0.0, 0.0, 0.0, 0.0))) {
        return float4(0.0, 0.0, 0.0, 0.0);
    }
    float2 pos = input.position.xy;
    float2 size = g_rect.zw;
    float2 half_size = size * 0.5;
    float2 p = pos - (g_rect.xy + half_size);
    float radius = min(g_p0.x, min(half_size.x, half_size.y));
    float scale = g_p0.y;
    float sd = sd_rounded_rect(p, half_size, radius);
    float coverage = clamp(0.5 - sd, 0.0, 1.0);
    bool premultiplied = g_p2.z > 0.5;

    float4 shadow = float4(0.0, 0.0, 0.0, 0.0);
    if (coverage < 1.0) {
        float4 sg = g_shadow_geometry;
        float s0 = sd_rounded_rect(p - float2(0.0, sg.x), half_size, radius);
        float a0 = g_shadow0.a * (1.0 - gauss_cdf(s0 / max(sg.y * 0.5, 0.25)));
        float s1 = sd_rounded_rect(p - float2(0.0, sg.z), half_size, radius);
        float a1 = g_shadow1.a * (1.0 - gauss_cdf(s1 / max(sg.w * 0.5, 0.25)));
        float sa = a0 + a1 * (1.0 - a0);
        float3 srgb = (g_shadow0.rgb * a0 + g_shadow1.rgb * a1 * (1.0 - a0)) / max(sa, 1e-4);
        shadow = float4(srgb, sa * (1.0 - coverage));
    }
    if (coverage <= 0.0) {
        return float4(premultiplied ? shadow.rgb * shadow.a : shadow.rgb, shadow.a);
    }

    float3 color = float3(0.0, 0.0, 0.0);
    float alpha = 0.0;
    if (g_p2.w > 0.5) {
        float ref_h = g_p0.w;
        float ref_amt = g_p1.x;
        float disp = g_p1.y;
        float inside = -sd;
        float2 off = float2(0.0, 0.0);
        if (inside < ref_h && inside > -1.0 && ref_h > 0.0) {
            float t = 1.0 - max(0.0, inside) / ref_h;
            float d = (1.0 - sqrt(max(0.0, 1.0 - t * t))) * ref_amt;
            float gr = min(radius * 1.5, min(half_size.x, half_size.y));
            off = -d * bend_direction(p, half_size, gr);
        }
        float r = backdrop_at(pos + off * (1.0 + disp)).r;
        float gg = backdrop_at(pos + off).g;
        float b = backdrop_at(pos + off * (1.0 - disp)).b;
        color = saturate_css(float3(r, gg, b), g_p0.z);
        float bleed = g_p3.z;
        if (bleed > 0.0) {
            float sigma = max(g_p3.w, 0.25);
            float2 local = pos - g_rect.xy;
            float ax = gauss_cdf(local.x / sigma) + gauss_cdf((size.x - local.x) / sigma) - 1.0;
            float ay = gauss_cdf(local.y / sigma) + gauss_cdf((size.y - local.y) / sigma) - 1.0;
            float kept = clamp(ax * ay, 0.0, 1.0);
            float2 uv = clamp(pos / g_p3.xy, float2(0.0, 0.0), float2(1.0, 1.0));
            float3 raw = t_sharp.SampleLevel(s_linear, uv, 0).rgb;
            color = lerp(color, lerp(raw, color, kept), bleed);
        }
        alpha = 1.0;
    }

    color = over(color, g_tint);
    alpha = alpha + g_tint.a * (1.0 - alpha);
    float lip_top = inset_shadow(p, half_size, radius, 1.0 * scale, 0.5 * scale, 0.0);
    color = over(color, float4(1.0, 1.0, 1.0, 0.14 * lip_top));
    float lip_bottom = inset_shadow(p, half_size, radius, -1.0 * scale, 0.5 * scale, 0.0);
    color = over(color, float4(0.0, 0.0, 0.0, 0.06 * lip_bottom));
    float low = inset_shadow(p, half_size, radius, -14.0 * scale, 24.0 * scale, -18.0 * scale);
    color = over(color, float4(g_shade.rgb, g_shade.a * low));

    if (g_p1.w > 0.0) {
        float2 c = float2(g_p2.x, g_p2.y) * size;
        float2 local = pos - g_rect.xy;
        float far_c = max(max(length(c), length(c - float2(size.x, 0.0))), max(length(c - float2(0.0, size.y)), length(c - size)));
        float t = length(local - c) / max(far_c, 1.0);
        float a = 0.0;
        if (t < 0.38) {
            a = lerp(0.2, 0.06, t / 0.38);
        } else if (t < 0.7) {
            a = lerp(0.06, 0.0, (t - 0.38) / 0.32);
        }
        color = over(color, float4(1.0, 1.0, 1.0, a * g_p1.w));
        alpha = alpha + a * g_p1.w * (1.0 - alpha);
    }

    float inner = sd_rounded_rect(p, half_size - float2(scale, scale), max(radius - scale, 0.0));
    float band = clamp(inner + 0.5, 0.0, 1.0);
    if (band > 0.0) {
        float ang = atan2(p.x, -p.y);
        float turn = frac((ang - g_p1.z) / TAU + 1.0);
        float4 lit = conic(turn);
        float4 rim = g_border;
        float ra = lit.a + rim.a * (1.0 - lit.a);
        rim = float4(lerp(rim.rgb, lit.rgb, lit.a / max(ra, 1e-4)), ra);
        color = over(color, float4(rim.rgb, rim.a * band));
        alpha = alpha + rim.a * band * (1.0 - alpha);
    }

    float ga = alpha * coverage;
    float fa = ga + shadow.a * (1.0 - ga);
    float3 rgb = (color * ga + shadow.rgb * shadow.a * (1.0 - ga)) / max(fa, 1e-4);
    return float4(premultiplied ? rgb * fa : rgb, fa);
}

// ---- Backdrop blur ----

struct FullVarying {
    float4 position : SV_Position;
    float2 uv : TEXCOORD0;
};

FullVarying full_vertex(uint vertex_id : SV_VertexID) {
    float2 uv = float2((float)((vertex_id << 1) & 2u), (float)(vertex_id & 2u));
    FullVarying o;
    o.position = float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
    o.uv = uv;
    return o;
}

float4 down_fragment(FullVarying input) : SV_Target {
    float w, h;
    t_backdrop.GetDimensions(w, h);
    float2 texel = 1.0 / float2(w, h);
    float2 k = b_p.xy * texel;
    float4 c = t_backdrop.SampleLevel(s_linear, input.uv + float2(-k.x, -k.y), 0);
    c += t_backdrop.SampleLevel(s_linear, input.uv + float2(k.x, -k.y), 0);
    c += t_backdrop.SampleLevel(s_linear, input.uv + float2(-k.x, k.y), 0);
    c += t_backdrop.SampleLevel(s_linear, input.uv + float2(k.x, k.y), 0);
    return float4((c * 0.25).rgb, 1.0);
}

float4 blur_fragment(FullVarying input) : SV_Target {
    float w, h;
    t_backdrop.GetDimensions(w, h);
    float2 texel = 1.0 / float2(w, h);
    float2 dir = b_p.xy * texel;
    float sigma = max(b_p.z, 0.01);
    int taps = (int)b_p.w;
    float3 sum = t_backdrop.SampleLevel(s_linear, input.uv, 0).rgb;
    float wsum = 1.0;
    [loop]
    for (int i = 1; i <= taps; i += 2) {
        float x0 = (float)i;
        float x1 = (float)(i + 1);
        float w0 = exp(-(x0 * x0) / (2.0 * sigma * sigma));
        float w1 = exp(-(x1 * x1) / (2.0 * sigma * sigma));
        float ww = w0 + w1;
        float o = (x0 * w0 + x1 * w1) / max(ww, 1e-6);
        sum += t_backdrop.SampleLevel(s_linear, input.uv + dir * o, 0).rgb * ww;
        sum += t_backdrop.SampleLevel(s_linear, input.uv - dir * o, 0).rgb * ww;
        wsum += 2.0 * ww;
    }
    return float4(sum / wsum, 1.0);
}
