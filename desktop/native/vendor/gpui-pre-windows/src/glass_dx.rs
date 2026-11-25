//! LFly patch: draws `PrimitiveBatch::Glass` (Liquid Glass) with Direct3D 11, the same way
//! gpui-pre-wgpu's glass.rs does: copy the frame drawn so far, shrink and blur it, then draw each
//! glass with glass.hlsl. The shaders are compiled once at start with D3DCompile
//! (d3dcompiler_47.dll ships with Windows 10 and 11).

use anyhow::{Context, Result};
use gpui::{Glass, Rgba};
use windows::{
    Win32::Graphics::{
        Direct3D::{Fxc::D3DCompile, ID3DBlob, *},
        Direct3D11::*,
        Dxgi::Common::*,
    },
    core::PCSTR,
};

const SOURCE: &str = include_str!("glass.hlsl");

#[repr(C)]
#[derive(Clone, Copy)]
struct GlassUniform {
    rect: [f32; 4],
    clip: [f32; 4],
    p0: [f32; 4],
    p1: [f32; 4],
    p2: [f32; 4],
    p3: [f32; 4],
    tint: [f32; 4],
    border: [f32; 4],
    highlight: [f32; 4],
    shade: [f32; 4],
    shadow0: [f32; 4],
    shadow1: [f32; 4],
    shadow_geometry: [f32; 4],
    p4: [f32; 4],
}

#[repr(C)]
#[derive(Clone, Copy)]
struct BlurUniform {
    p: [f32; 4],
}

struct Targets {
    width: u32,
    height: u32,
    factor: u32,
    copy: ID3D11Texture2D,
    copy_srv: Option<ID3D11ShaderResourceView>,
    a_rtv: Option<ID3D11RenderTargetView>,
    a_srv: Option<ID3D11ShaderResourceView>,
    b_rtv: Option<ID3D11RenderTargetView>,
    b_srv: Option<ID3D11ShaderResourceView>,
    small: D3D11_VIEWPORT,
}

pub(crate) struct GlassDx {
    glass_vs: ID3D11VertexShader,
    glass_ps: ID3D11PixelShader,
    full_vs: ID3D11VertexShader,
    down_ps: ID3D11PixelShader,
    blur_ps: ID3D11PixelShader,
    glass_cb: Option<ID3D11Buffer>,
    blur_cb: Option<ID3D11Buffer>,
    sampler: Option<ID3D11SamplerState>,
    blend: ID3D11BlendState,
    targets: Option<Targets>,
}

fn compile(entry: &str, target: &str) -> Result<ID3DBlob> {
    let entry = format!("{entry}\0");
    let target = format!("{target}\0");
    let mut code = None;
    let mut errors = None;
    let result = unsafe {
        D3DCompile(
            SOURCE.as_ptr() as *const _,
            SOURCE.len(),
            PCSTR::from_raw(c"glass.hlsl".as_ptr() as *const u8),
            None,
            None,
            PCSTR::from_raw(entry.as_ptr()),
            PCSTR::from_raw(target.as_ptr()),
            0,
            0,
            &mut code,
            Some(&mut errors),
        )
    };
    if let Err(e) = result {
        let message = errors
            .map(|b| unsafe {
                std::ffi::CStr::from_ptr(b.GetBufferPointer() as *const std::ffi::c_char)
                    .to_string_lossy()
                    .into_owned()
            })
            .unwrap_or_else(|| format!("{e:?}"));
        anyhow::bail!("glass shader {entry}: {message}");
    }
    code.context("no shader code")
}

fn bytes(blob: &ID3DBlob) -> &[u8] {
    unsafe { std::slice::from_raw_parts(blob.GetBufferPointer() as *const u8, blob.GetBufferSize()) }
}

fn vertex(device: &ID3D11Device, entry: &str) -> Result<ID3D11VertexShader> {
    let blob = compile(entry, "vs_4_1")?;
    let mut shader = None;
    unsafe { device.CreateVertexShader(bytes(&blob), None, Some(&mut shader)) }?;
    shader.context("vertex shader")
}

fn pixel(device: &ID3D11Device, entry: &str) -> Result<ID3D11PixelShader> {
    let blob = compile(entry, "ps_4_1")?;
    let mut shader = None;
    unsafe { device.CreatePixelShader(bytes(&blob), None, Some(&mut shader)) }?;
    shader.context("pixel shader")
}

fn constant_buffer<T>(device: &ID3D11Device) -> Result<Option<ID3D11Buffer>> {
    let desc = D3D11_BUFFER_DESC {
        ByteWidth: std::mem::size_of::<T>().next_multiple_of(16) as u32,
        Usage: D3D11_USAGE_DYNAMIC,
        BindFlags: D3D11_BIND_CONSTANT_BUFFER.0 as u32,
        CPUAccessFlags: D3D11_CPU_ACCESS_WRITE.0 as u32,
        MiscFlags: 0,
        StructureByteStride: 0,
    };
    let mut buffer = None;
    unsafe { device.CreateBuffer(&desc, None, Some(&mut buffer)) }?;
    Ok(buffer)
}

fn write<T: Copy>(context: &ID3D11DeviceContext, buffer: &ID3D11Buffer, value: &T) -> Result<()> {
    unsafe {
        let mut mapped = std::mem::zeroed();
        context.Map(buffer, 0, D3D11_MAP_WRITE_DISCARD, 0, Some(&mut mapped))?;
        std::ptr::copy_nonoverlapping(value as *const T as *const u8, mapped.pData as *mut u8, std::mem::size_of::<T>());
        context.Unmap(buffer, 0);
    }
    Ok(())
}

fn rgba(c: gpui::Hsla) -> [f32; 4] {
    let c: Rgba = c.into();
    [c.r, c.g, c.b, c.a]
}

impl GlassDx {
    pub(crate) fn new(device: &ID3D11Device) -> Result<Self> {
        let sampler = unsafe {
            let desc = D3D11_SAMPLER_DESC {
                Filter: D3D11_FILTER_MIN_MAG_MIP_LINEAR,
                AddressU: D3D11_TEXTURE_ADDRESS_CLAMP,
                AddressV: D3D11_TEXTURE_ADDRESS_CLAMP,
                AddressW: D3D11_TEXTURE_ADDRESS_CLAMP,
                MipLODBias: 0.0,
                MaxAnisotropy: 1,
                ComparisonFunc: D3D11_COMPARISON_ALWAYS,
                BorderColor: [0.0; 4],
                MinLOD: 0.0,
                MaxLOD: D3D11_FLOAT32_MAX,
            };
            let mut sampler = None;
            device.CreateSamplerState(&desc, Some(&mut sampler))?;
            sampler
        };
        // Straight alpha, as GPUI's quads.
        let blend = unsafe {
            let mut desc = D3D11_BLEND_DESC::default();
            desc.RenderTarget[0].BlendEnable = true.into();
            desc.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA;
            desc.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
            desc.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
            desc.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
            desc.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_INV_SRC_ALPHA;
            desc.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
            desc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL.0 as u8;
            let mut state = None;
            device.CreateBlendState(&desc, Some(&mut state))?;
            state.context("blend state")?
        };
        Ok(Self {
            glass_vs: vertex(device, "glass_vertex")?,
            glass_ps: pixel(device, "glass_fragment")?,
            full_vs: vertex(device, "full_vertex")?,
            down_ps: pixel(device, "down_fragment")?,
            blur_ps: pixel(device, "blur_fragment")?,
            glass_cb: constant_buffer::<GlassUniform>(device)?,
            blur_cb: constant_buffer::<BlurUniform>(device)?,
            sampler,
            blend,
            targets: None,
        })
    }

    fn ensure_targets(&mut self, device: &ID3D11Device, width: u32, height: u32, factor: u32) -> Result<()> {
        if self.targets.as_ref().is_some_and(|t| t.width == width && t.height == height && t.factor == factor) {
            return Ok(());
        }
        let texture = |w: u32, h: u32, bind: D3D11_BIND_FLAG| -> Result<ID3D11Texture2D> {
            let desc = D3D11_TEXTURE2D_DESC {
                Width: w.max(1),
                Height: h.max(1),
                MipLevels: 1,
                ArraySize: 1,
                Format: DXGI_FORMAT_B8G8R8A8_UNORM,
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                Usage: D3D11_USAGE_DEFAULT,
                BindFlags: bind.0 as u32,
                CPUAccessFlags: 0,
                MiscFlags: 0,
            };
            let mut tex = None;
            unsafe { device.CreateTexture2D(&desc, None, Some(&mut tex)) }?;
            tex.context("texture")
        };
        let srv = |t: &ID3D11Texture2D| -> Result<Option<ID3D11ShaderResourceView>> {
            let mut v = None;
            unsafe { device.CreateShaderResourceView(t, None, Some(&mut v)) }?;
            Ok(v)
        };
        let rtv = |t: &ID3D11Texture2D| -> Result<Option<ID3D11RenderTargetView>> {
            let mut v = None;
            unsafe { device.CreateRenderTargetView(t, None, Some(&mut v)) }?;
            Ok(v)
        };
        let copy = texture(width, height, D3D11_BIND_SHADER_RESOURCE)?;
        let (sw, sh) = (width.div_ceil(factor), height.div_ceil(factor));
        let both = D3D11_BIND_FLAG(D3D11_BIND_SHADER_RESOURCE.0 | D3D11_BIND_RENDER_TARGET.0);
        let a = texture(sw, sh, both)?;
        let b = texture(sw, sh, both)?;
        self.targets = Some(Targets {
            width,
            height,
            factor,
            copy_srv: srv(&copy)?,
            copy,
            a_rtv: rtv(&a)?,
            a_srv: srv(&a)?,
            b_rtv: rtv(&b)?,
            b_srv: srv(&b)?,
            small: D3D11_VIEWPORT {
                TopLeftX: 0.0,
                TopLeftY: 0.0,
                Width: sw as f32,
                Height: sh as f32,
                MinDepth: 0.0,
                MaxDepth: 1.0,
            },
        });
        Ok(())
    }

    fn fullscreen(
        &self,
        context: &ID3D11DeviceContext,
        target: &Option<ID3D11RenderTargetView>,
        source: &Option<ID3D11ShaderResourceView>,
        ps: &ID3D11PixelShader,
        params: [f32; 4],
    ) -> Result<()> {
        write(context, self.blur_cb.as_ref().context("blur buffer")?, &BlurUniform { p: params })?;
        unsafe {
            // The source must not stay bound while it is a target in the next pass.
            context.PSSetShaderResources(0, Some(&[None]));
            context.OMSetRenderTargets(Some(std::slice::from_ref(target)), None);
            context.PSSetShaderResources(0, Some(std::slice::from_ref(source)));
            context.PSSetConstantBuffers(3, Some(std::slice::from_ref(&self.blur_cb)));
            context.PSSetSamplers(0, Some(std::slice::from_ref(&self.sampler)));
            context.VSSetShader(&self.full_vs, None);
            context.PSSetShader(ps, None);
            context.IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
            context.Draw(3, 0);
        }
        Ok(())
    }

    /// Captures and blurs what is drawn so far, then draws the batch over it. Leaves the main
    /// render target and viewport bound again.
    pub(crate) fn draw(
        &mut self,
        device: &ID3D11Device,
        context: &ID3D11DeviceContext,
        render_target: &ID3D11Texture2D,
        render_target_view: &Option<ID3D11RenderTargetView>,
        viewport: &D3D11_VIEWPORT,
        glass: &[Glass],
    ) -> Result<()> {
        // The copy must match the back buffer exactly (CopyResource), so size it from there.
        let (width, height) = unsafe {
            let mut desc = D3D11_TEXTURE2D_DESC::default();
            render_target.GetDesc(&mut desc);
            (desc.Width, desc.Height)
        };
        let sigma = glass.iter().map(|g| g.blur.0).fold(0.0f32, f32::max);
        let factor = if sigma >= 16.0 { 4 } else if sigma >= 6.0 { 2 } else { 1 };
        self.ensure_targets(device, width, height, factor)?;
        let t = self.targets.as_ref().unwrap();
        let small_sigma = sigma / factor as f32;
        let taps = (small_sigma * 3.0).ceil().clamp(0.0, 48.0);
        let half = factor as f32 / 4.0;
        unsafe {
            context.CopyResource(&t.copy, render_target);
            context.RSSetViewports(Some(std::slice::from_ref(&t.small)));
        }
        self.fullscreen(context, &t.a_rtv, &t.copy_srv, &self.down_ps, [half, half, 0.0, 0.0])?;
        if taps > 0.0 {
            self.fullscreen(context, &t.b_rtv, &t.a_srv, &self.blur_ps, [1.0, 0.0, small_sigma, taps])?;
            self.fullscreen(context, &t.a_rtv, &t.b_srv, &self.blur_ps, [0.0, 1.0, small_sigma, taps])?;
        }
        unsafe {
            context.PSSetShaderResources(0, Some(&[None]));
            context.OMSetRenderTargets(Some(std::slice::from_ref(render_target_view)), None);
            context.RSSetViewports(Some(std::slice::from_ref(viewport)));
            context.VSSetShader(&self.glass_vs, None);
            context.PSSetShader(&self.glass_ps, None);
            context.VSSetConstantBuffers(2, Some(std::slice::from_ref(&self.glass_cb)));
            context.PSSetConstantBuffers(2, Some(std::slice::from_ref(&self.glass_cb)));
            context.PSSetShaderResources(0, Some(std::slice::from_ref(&t.a_srv)));
            context.PSSetShaderResources(2, Some(std::slice::from_ref(&t.copy_srv)));
            context.PSSetSamplers(0, Some(std::slice::from_ref(&self.sampler)));
            context.OMSetBlendState(&self.blend, None, 0xFFFFFFFF);
            context.IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);
        }
        let buffer = self.glass_cb.as_ref().context("glass buffer")?;
        for g in glass {
            let u = GlassUniform {
                rect: [g.bounds.origin.x.0, g.bounds.origin.y.0, g.bounds.size.width.0, g.bounds.size.height.0],
                clip: [
                    g.content_mask.bounds.origin.x.0,
                    g.content_mask.bounds.origin.y.0,
                    g.content_mask.bounds.size.width.0,
                    g.content_mask.bounds.size.height.0,
                ],
                p0: [g.corner_radius.0, g.scale.max(1.0), g.saturation, g.refraction_height.0],
                p1: [g.refraction_amount.0, g.dispersion, g.light_angle, g.pointer_alpha],
                p2: [g.pointer_x, g.pointer_y, 0.0, 1.0],
                p3: [viewport.Width, viewport.Height, g.edge_bleed, g.blur.0],
                tint: rgba(g.tint),
                border: rgba(g.border),
                highlight: rgba(g.highlight),
                shade: rgba(g.shade),
                shadow0: rgba(g.shadow_colors[0]),
                shadow1: rgba(g.shadow_colors[1]),
                shadow_geometry: g.shadow_geometry,
                // Glass.svelte: --g-lip is 0.14, 0.7 on light canvases.
                p4: [if g.light_scheme > 0.5 { 0.7 } else { 0.14 }, 0.0, 0.0, 0.0],
            };
            write(context, buffer, &u)?;
            unsafe { context.Draw(4, 0) };
        }
        unsafe {
            context.PSSetShaderResources(0, Some(&[None]));
            context.PSSetShaderResources(2, Some(&[None]));
        }
        Ok(())
    }
}
