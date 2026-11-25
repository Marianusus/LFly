//! LFly patch: draws `PrimitiveBatch::Glass` (Liquid Glass) with Metal, the same way
//! gpui-pre-windows' glass_dx.rs and gpui-pre-wgpu's glass.rs do: copy the frame drawn so far,
//! shrink and blur it, then draw each glass with glass.metal over it. The shaders are compiled
//! once, the first time a glass is drawn.

use anyhow::{Context as _, Result};
use gpui::{DevicePixels, Glass, Rgba, Size};
use objc2::{rc::Retained, runtime::ProtocolObject};
use objc2_foundation::NSString;
use objc2_metal::{
    MTLBlendFactor, MTLBlendOperation, MTLBlitCommandEncoder, MTLClearColor, MTLCommandBuffer,
    MTLCommandEncoder, MTLCompileOptions, MTLDevice, MTLLibrary, MTLLoadAction, MTLPixelFormat,
    MTLPrimitiveType, MTLRenderCommandEncoder, MTLRenderPassDescriptor,
    MTLRenderPipelineDescriptor, MTLRenderPipelineState, MTLStorageMode, MTLStoreAction,
    MTLTexture, MTLTextureDescriptor, MTLTextureUsage, MTLViewport,
};
use std::{mem, ptr};

const SOURCE: &str = include_str!("glass.metal");

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

type Texture = Retained<ProtocolObject<dyn MTLTexture>>;
type Pipeline = Retained<ProtocolObject<dyn MTLRenderPipelineState>>;

struct Targets {
    width: usize,
    height: usize,
    factor: usize,
    copy: Texture,
    a: Texture,
    b: Texture,
}

pub(crate) struct GlassMetal {
    glass: Pipeline,
    down: Pipeline,
    blur: Pipeline,
    targets: Option<Targets>,
}

fn rgba(c: gpui::Hsla) -> [f32; 4] {
    let c: Rgba = c.into();
    [c.r, c.g, c.b, c.a]
}

fn pipeline(
    device: &ProtocolObject<dyn MTLDevice>,
    library: &ProtocolObject<dyn MTLLibrary>,
    vertex: &str,
    fragment: &str,
    blend: bool,
) -> Result<Pipeline> {
    let vertex_fn = library
        .newFunctionWithName(&NSString::from_str(vertex))
        .with_context(|| format!("glass shader {vertex}"))?;
    let fragment_fn = library
        .newFunctionWithName(&NSString::from_str(fragment))
        .with_context(|| format!("glass shader {fragment}"))?;
    let descriptor = MTLRenderPipelineDescriptor::new();
    descriptor.setLabel(Some(&NSString::from_str(fragment)));
    descriptor.setVertexFunction(Some(vertex_fn.as_ref()));
    descriptor.setFragmentFunction(Some(fragment_fn.as_ref()));
    // SAFETY: Index zero is within the pipeline's color attachment array.
    let color = unsafe { descriptor.colorAttachments().objectAtIndexedSubscript(0) };
    color.setPixelFormat(MTLPixelFormat::BGRA8Unorm);
    if blend {
        // Straight alpha, as GPUI's quads.
        color.setBlendingEnabled(true);
        color.setRgbBlendOperation(MTLBlendOperation::Add);
        color.setAlphaBlendOperation(MTLBlendOperation::Add);
        color.setSourceRGBBlendFactor(MTLBlendFactor::SourceAlpha);
        color.setSourceAlphaBlendFactor(MTLBlendFactor::One);
        color.setDestinationRGBBlendFactor(MTLBlendFactor::OneMinusSourceAlpha);
        color.setDestinationAlphaBlendFactor(MTLBlendFactor::OneMinusSourceAlpha);
    }
    device
        .newRenderPipelineStateWithDescriptor_error(&descriptor)
        .with_context(|| format!("glass pipeline {fragment}"))
}

fn texture(device: &ProtocolObject<dyn MTLDevice>, width: usize, height: usize, target: bool) -> Result<Texture> {
    let descriptor = MTLTextureDescriptor::new();
    // SAFETY: The dimensions are positive and this descriptor creates a 2D texture.
    unsafe {
        descriptor.setWidth(width.max(1));
        descriptor.setHeight(height.max(1));
    }
    descriptor.setPixelFormat(MTLPixelFormat::BGRA8Unorm);
    descriptor.setUsage(if target {
        MTLTextureUsage::RenderTarget | MTLTextureUsage::ShaderRead
    } else {
        MTLTextureUsage::ShaderRead
    });
    descriptor.setStorageMode(MTLStorageMode::Private);
    device.newTextureWithDescriptor(&descriptor).context("glass texture")
}

fn pass(
    command_buffer: &ProtocolObject<dyn MTLCommandBuffer>,
    target: &ProtocolObject<dyn MTLTexture>,
    clear: bool,
) -> Result<Retained<ProtocolObject<dyn MTLRenderCommandEncoder>>> {
    let descriptor = MTLRenderPassDescriptor::new();
    // SAFETY: Index zero is within the render pass's color attachment array.
    let color = unsafe { descriptor.colorAttachments().objectAtIndexedSubscript(0) };
    color.setTexture(Some(target));
    color.setStoreAction(MTLStoreAction::Store);
    if clear {
        color.setLoadAction(MTLLoadAction::Clear);
        color.setClearColor(MTLClearColor { red: 0., green: 0., blue: 0., alpha: 1. });
    } else {
        color.setLoadAction(MTLLoadAction::Load);
    }
    command_buffer
        .renderCommandEncoderWithDescriptor(&descriptor)
        .context("glass render pass")
}

impl GlassMetal {
    pub(crate) fn new(device: &ProtocolObject<dyn MTLDevice>) -> Result<Self> {
        let library = device
            .newLibraryWithSource_options_error(&NSString::from_str(SOURCE), Some(&MTLCompileOptions::new()))
            .map_err(|e| anyhow::anyhow!("glass shaders: {}", e.localizedDescription()))?;
        Ok(Self {
            glass: pipeline(device, &library, "glass_vertex", "glass_fragment", true)?,
            down: pipeline(device, &library, "full_vertex", "down_fragment", false)?,
            blur: pipeline(device, &library, "full_vertex", "blur_fragment", false)?,
            targets: None,
        })
    }

    fn ensure_targets(&mut self, device: &ProtocolObject<dyn MTLDevice>, width: usize, height: usize, factor: usize) -> Result<()> {
        if self.targets.as_ref().is_some_and(|t| t.width == width && t.height == height && t.factor == factor) {
            return Ok(());
        }
        let (sw, sh) = (width.div_ceil(factor), height.div_ceil(factor));
        self.targets = Some(Targets {
            width,
            height,
            factor,
            copy: texture(device, width, height, false)?,
            a: texture(device, sw, sh, true)?,
            b: texture(device, sw, sh, true)?,
        });
        Ok(())
    }

    fn fullscreen(
        &self,
        command_buffer: &ProtocolObject<dyn MTLCommandBuffer>,
        target: &ProtocolObject<dyn MTLTexture>,
        source: &ProtocolObject<dyn MTLTexture>,
        pipeline: &ProtocolObject<dyn MTLRenderPipelineState>,
        params: [f32; 4],
    ) -> Result<()> {
        let encoder = pass(command_buffer, target, true)?;
        encoder.setRenderPipelineState(pipeline);
        let uniform = BlurUniform { p: params };
        // SAFETY: The bytes are copied by Metal; slot 0 and texture 0 match glass.metal.
        unsafe {
            encoder.setFragmentBytes_length_atIndex(ptr::NonNull::from(&uniform).cast(), mem::size_of::<BlurUniform>(), 0);
            encoder.setFragmentTexture_atIndex(Some(source), 0);
            encoder.drawPrimitives_vertexStart_vertexCount(MTLPrimitiveType::Triangle, 0, 3);
        }
        encoder.endEncoding();
        Ok(())
    }

    /// Captures and blurs what `target` holds so far, then draws the batch over it. The caller
    /// has ended its render pass and opens a new one (load) afterwards.
    pub(crate) fn draw(
        &mut self,
        device: &ProtocolObject<dyn MTLDevice>,
        command_buffer: &ProtocolObject<dyn MTLCommandBuffer>,
        target: &ProtocolObject<dyn MTLTexture>,
        viewport_size: Size<DevicePixels>,
        glass: &[Glass],
    ) -> Result<()> {
        // The copy must match the target exactly (a blit copies whole textures).
        let (width, height) = (target.width(), target.height());
        let sigma = glass.iter().map(|g| g.blur.0).fold(0.0f32, f32::max);
        let factor = if sigma >= 16.0 { 4 } else if sigma >= 6.0 { 2 } else { 1 };
        self.ensure_targets(device, width, height, factor)?;
        let t = self.targets.as_ref().unwrap();
        let small_sigma = sigma / factor as f32;
        let taps = (small_sigma * 3.0).ceil().clamp(0.0, 48.0);
        let half = factor as f32 / 4.0;

        let blit = command_buffer.blitCommandEncoder().context("glass blit")?;
        // SAFETY: Same size, format and sample count (both BGRA8Unorm, one sample).
        unsafe { blit.copyFromTexture_toTexture(target, &t.copy) };
        blit.endEncoding();

        self.fullscreen(command_buffer, &t.a, &t.copy, &self.down, [half, half, 0.0, 0.0])?;
        if taps > 0.0 {
            self.fullscreen(command_buffer, &t.b, &t.a, &self.blur, [1.0, 0.0, small_sigma, taps])?;
            self.fullscreen(command_buffer, &t.a, &t.b, &self.blur, [0.0, 1.0, small_sigma, taps])?;
        }

        let (vw, vh) = (i32::from(viewport_size.width) as f32, i32::from(viewport_size.height) as f32);
        let encoder = pass(command_buffer, target, false)?;
        encoder.setViewport(MTLViewport { originX: 0.0, originY: 0.0, width: vw as f64, height: vh as f64, znear: 0.0, zfar: 1.0 });
        encoder.setRenderPipelineState(&self.glass);
        // SAFETY: Texture slots 0 and 1 match glass.metal.
        unsafe {
            encoder.setFragmentTexture_atIndex(Some(&t.a), 0);
            encoder.setFragmentTexture_atIndex(Some(&t.copy), 1);
        }
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
                p3: [vw, vh, g.edge_bleed, g.blur.0],
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
            // SAFETY: The bytes are copied by Metal at each call; slot 0 matches glass.metal.
            unsafe {
                encoder.setVertexBytes_length_atIndex(ptr::NonNull::from(&u).cast(), mem::size_of::<GlassUniform>(), 0);
                encoder.setFragmentBytes_length_atIndex(ptr::NonNull::from(&u).cast(), mem::size_of::<GlassUniform>(), 0);
                encoder.drawPrimitives_vertexStart_vertexCount(MTLPrimitiveType::TriangleStrip, 0, 4);
            }
        }
        encoder.endEncoding();
        Ok(())
    }
}
