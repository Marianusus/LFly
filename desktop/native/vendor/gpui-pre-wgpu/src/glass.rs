//! LFly patch: draws `PrimitiveBatch::Glass` (Liquid Glass).
//!
//! For each batch: the frame drawn so far is copied, shrunk and blurred (separable Gaussian),
//! then every glass of the batch is drawn by `glass.wgsl`, which bends, saturates and tints the
//! blurred copy. Frames without glass do none of this.

use gpui::{Glass, Rgba};

/// One glass draw's parameters, laid out as `GlassParams` in glass.wgsl (vec4s only).
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
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
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct BlurUniform {
    p: [f32; 4],
}

/// Uniform slots per frame (each glass and each blur pass takes one).
const SLOTS: u64 = 256;

struct Targets {
    width: u32,
    height: u32,
    factor: u32,
    copy: wgpu::Texture,
    copy_view: wgpu::TextureView,
    a_view: wgpu::TextureView,
    b_view: wgpu::TextureView,
}

pub(crate) struct GlassRenderer {
    format: wgpu::TextureFormat,
    glass_pipeline: wgpu::RenderPipeline,
    down_pipeline: wgpu::RenderPipeline,
    blur_pipeline: wgpu::RenderPipeline,
    layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    uniforms: wgpu::Buffer,
    stride: u64,
    next_slot: u64,
    targets: Option<Targets>,
    empty_view: wgpu::TextureView,
}

fn rgba(c: gpui::Hsla) -> [f32; 4] {
    let c: Rgba = c.into();
    [c.r, c.g, c.b, c.a]
}

impl GlassRenderer {
    pub(crate) fn new(
        device: &wgpu::Device,
        format: wgpu::TextureFormat,
        blend: wgpu::BlendState,
    ) -> Self {
        let glass_module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("glass"),
            source: wgpu::ShaderSource::Wgsl(include_str!("glass.wgsl").into()),
        });
        let blur_module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("glass_blur"),
            source: wgpu::ShaderSource::Wgsl(include_str!("glass_blur.wgsl").into()),
        });
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("glass_layout"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: true,
                        min_binding_size: None,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
                // The sharp copy (glass only; the blur passes bind the same texture again).
                wgpu::BindGroupLayoutEntry {
                    binding: 3,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
            ],
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("glass_pipeline_layout"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = |name: &str,
                        module: &wgpu::ShaderModule,
                        vs: &str,
                        fs: &str,
                        topology: wgpu::PrimitiveTopology,
                        blend: Option<wgpu::BlendState>| {
            device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(name),
                layout: Some(&pipeline_layout),
                vertex: wgpu::VertexState {
                    module,
                    entry_point: Some(vs),
                    buffers: &[],
                    compilation_options: wgpu::PipelineCompilationOptions::default(),
                },
                fragment: Some(wgpu::FragmentState {
                    module,
                    entry_point: Some(fs),
                    targets: &[Some(wgpu::ColorTargetState {
                        format,
                        blend,
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                    compilation_options: wgpu::PipelineCompilationOptions::default(),
                }),
                primitive: wgpu::PrimitiveState {
                    topology,
                    strip_index_format: None,
                    front_face: wgpu::FrontFace::Ccw,
                    cull_mode: None,
                    polygon_mode: wgpu::PolygonMode::Fill,
                    unclipped_depth: false,
                    conservative: false,
                },
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                multiview_mask: None,
                cache: None,
            })
        };
        let glass_pipeline = pipeline(
            "glass",
            &glass_module,
            "vs_glass",
            "fs_glass",
            wgpu::PrimitiveTopology::TriangleStrip,
            Some(blend),
        );
        let down_pipeline = pipeline(
            "glass_down",
            &blur_module,
            "vs_full",
            "fs_down",
            wgpu::PrimitiveTopology::TriangleList,
            None,
        );
        let blur_pipeline = pipeline(
            "glass_blur",
            &blur_module,
            "vs_full",
            "fs_blur",
            wgpu::PrimitiveTopology::TriangleList,
            None,
        );
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("glass_sampler"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            ..Default::default()
        });
        let align = device.limits().min_uniform_buffer_offset_alignment as u64;
        let stride = (std::mem::size_of::<GlassUniform>() as u64).next_multiple_of(align);
        let uniforms = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glass_uniforms"),
            size: stride * SLOTS,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let empty = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("glass_empty"),
            size: wgpu::Extent3d { width: 1, height: 1, depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format,
            usage: wgpu::TextureUsages::TEXTURE_BINDING,
            view_formats: &[],
        });
        let empty_view = empty.create_view(&wgpu::TextureViewDescriptor::default());
        Self {
            format,
            glass_pipeline,
            down_pipeline,
            blur_pipeline,
            layout,
            sampler,
            uniforms,
            stride,
            next_slot: 0,
            targets: None,
            empty_view,
        }
    }

    pub(crate) fn begin_frame(&mut self) {
        self.next_slot = 0;
    }

    fn slot(&mut self, queue: &wgpu::Queue, bytes: &[u8]) -> Option<u32> {
        if self.next_slot >= SLOTS {
            return None;
        }
        let offset = self.next_slot * self.stride;
        self.next_slot += 1;
        queue.write_buffer(&self.uniforms, offset, bytes);
        Some(offset as u32)
    }

    fn ensure_targets(&mut self, device: &wgpu::Device, width: u32, height: u32, factor: u32) {
        if self
            .targets
            .as_ref()
            .is_some_and(|t| t.width == width && t.height == height && t.factor == factor)
        {
            return;
        }
        let texture = |label: &str, w: u32, h: u32, usage: wgpu::TextureUsages| {
            device.create_texture(&wgpu::TextureDescriptor {
                label: Some(label),
                size: wgpu::Extent3d { width: w.max(1), height: h.max(1), depth_or_array_layers: 1 },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: self.format,
                usage,
                view_formats: &[],
            })
        };
        let copy = texture(
            "glass_copy",
            width,
            height,
            wgpu::TextureUsages::COPY_DST | wgpu::TextureUsages::TEXTURE_BINDING,
        );
        let small = wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING;
        let (sw, sh) = (width.div_ceil(factor), height.div_ceil(factor));
        let a = texture("glass_blur_a", sw, sh, small);
        let b = texture("glass_blur_b", sw, sh, small);
        self.targets = Some(Targets {
            width,
            height,
            factor,
            copy_view: copy.create_view(&Default::default()),
            copy,
            a_view: a.create_view(&Default::default()),
            b_view: b.create_view(&Default::default()),
        });
    }

    fn bind(&self, device: &wgpu::Device, view: &wgpu::TextureView) -> wgpu::BindGroup {
        self.bind2(device, view, view)
    }

    fn bind2(&self, device: &wgpu::Device, view: &wgpu::TextureView, sharp: &wgpu::TextureView) -> wgpu::BindGroup {
        device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("glass_bind_group"),
            layout: &self.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                        buffer: &self.uniforms,
                        offset: 0,
                        size: wgpu::BufferSize::new(std::mem::size_of::<GlassUniform>() as u64),
                    }),
                },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(view) },
                wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::Sampler(&self.sampler) },
                wgpu::BindGroupEntry { binding: 3, resource: wgpu::BindingResource::TextureView(sharp) },
            ],
        })
    }

    fn pass<'a>(encoder: &'a mut wgpu::CommandEncoder, view: &'a wgpu::TextureView) -> wgpu::RenderPass<'a> {
        encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("glass_blur_pass"),
            color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                view,
                resolve_target: None,
                ops: wgpu::Operations { load: wgpu::LoadOp::Clear(wgpu::Color::BLACK), store: wgpu::StoreOp::Store },
                depth_slice: None,
            })],
            depth_stencil_attachment: None,
            ..Default::default()
        })
    }

    /// Copies and blurs what is drawn so far. Must run outside a render pass. Returns false when
    /// the glass has to go without a backdrop (no frame texture to copy).
    pub(crate) fn capture(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        encoder: &mut wgpu::CommandEncoder,
        frame: Option<&wgpu::Texture>,
        glass: &[Glass],
    ) -> bool {
        let Some(frame) = frame else { return false };
        let sigma = glass.iter().map(|g| g.blur.0).fold(0.0f32, f32::max);
        let (width, height) = (frame.width(), frame.height());
        // Shrink so the Gaussian stays within ~8 texels of the small texture.
        let factor = if sigma >= 16.0 { 4 } else if sigma >= 6.0 { 2 } else { 1 };
        self.ensure_targets(device, width, height, factor);
        let size = wgpu::Extent3d { width, height, depth_or_array_layers: 1 };
        encoder.copy_texture_to_texture(
            frame.as_image_copy(),
            self.targets.as_ref().unwrap().copy.as_image_copy(),
            size,
        );
        let small_sigma = sigma / factor as f32;
        let taps = (small_sigma * 3.0).ceil().clamp(0.0, 48.0);
        let half = factor as f32 / 4.0;
        let (Some(down), Some(h), Some(v)) = (
            self.slot(queue, bytemuck::bytes_of(&BlurUniform { p: [half, half, 0.0, 0.0] })),
            self.slot(queue, bytemuck::bytes_of(&BlurUniform { p: [1.0, 0.0, small_sigma, taps] })),
            self.slot(queue, bytemuck::bytes_of(&BlurUniform { p: [0.0, 1.0, small_sigma, taps] })),
        ) else {
            return false;
        };
        let t = self.targets.as_ref().unwrap();
        let from_copy = self.bind(device, &t.copy_view);
        let from_a = self.bind(device, &t.a_view);
        let from_b = self.bind(device, &t.b_view);
        {
            let mut pass = Self::pass(encoder, &t.a_view);
            pass.set_pipeline(&self.down_pipeline);
            pass.set_bind_group(0, &from_copy, &[down]);
            pass.draw(0..3, 0..1);
        }
        if taps > 0.0 {
            {
                let mut pass = Self::pass(encoder, &t.b_view);
                pass.set_pipeline(&self.blur_pipeline);
                pass.set_bind_group(0, &from_a, &[h]);
                pass.draw(0..3, 0..1);
            }
            let mut pass = Self::pass(encoder, &t.a_view);
            pass.set_pipeline(&self.blur_pipeline);
            pass.set_bind_group(0, &from_b, &[v]);
            pass.draw(0..3, 0..1);
        }
        true
    }

    /// Draws the batch into the main pass, over the backdrop captured just before.
    pub(crate) fn draw(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        pass: &mut wgpu::RenderPass<'_>,
        glass: &[Glass],
        viewport: [f32; 2],
        premultiplied: bool,
        has_backdrop: bool,
    ) {
        let (view, sharp) = match (&self.targets, has_backdrop) {
            (Some(t), true) => (t.a_view.clone(), t.copy_view.clone()),
            _ => (self.empty_view.clone(), self.empty_view.clone()),
        };
        let group = self.bind2(device, &view, &sharp);
        pass.set_pipeline(&self.glass_pipeline);
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
                p2: [
                    g.pointer_x,
                    g.pointer_y,
                    if premultiplied { 1.0 } else { 0.0 },
                    if has_backdrop { 1.0 } else { 0.0 },
                ],
                p3: [viewport[0], viewport[1], g.edge_bleed, g.blur.0],
                tint: rgba(g.tint),
                border: rgba(g.border),
                highlight: rgba(g.highlight),
                shade: rgba(g.shade),
                shadow0: rgba(g.shadow_colors[0]),
                shadow1: rgba(g.shadow_colors[1]),
                shadow_geometry: g.shadow_geometry,
            };
            let Some(offset) = self.slot(queue, bytemuck::bytes_of(&u)) else { return };
            pass.set_bind_group(0, &group, &[offset]);
            pass.draw(0..4, 0..1);
        }
    }
}
