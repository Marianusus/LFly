<script lang="ts">
  // A thin track that thickens under the pointer, as Apple's players do. Dragging reports every
  // step through `oninput` and the final value through `onchange`; the arrow keys move it too.
  let {
    value,
    max,
    step = 1,
    keyStep,
    label,
    valuetext,
    disabled = false,
    oninput,
    onchange,
    class: cls = "",
  }: {
    value: number;
    max: number;
    step?: number;
    keyStep?: number;
    label: string;
    valuetext?: string;
    disabled?: boolean;
    oninput?: (v: number) => void;
    onchange?: (v: number) => void;
    class?: string;
  } = $props();

  let track = $state<HTMLDivElement>();
  let dragging = $state(false);
  const pct = $derived(max > 0 ? Math.min(100, Math.max(0, (value / max) * 100)) : 0);

  function at(e: PointerEvent) {
    const r = track!.getBoundingClientRect();
    const f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
    return Math.round((f * max) / step) * step;
  }

  function down(e: PointerEvent) {
    if (disabled || e.button !== 0 || !track) return;
    dragging = true;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    oninput?.(at(e));
  }
  function move(e: PointerEvent) {
    if (dragging) oninput?.(at(e));
  }
  function up(e: PointerEvent) {
    if (!dragging) return;
    dragging = false;
    onchange?.(at(e));
  }
  function key(e: KeyboardEvent) {
    if (disabled) return;
    const k = keyStep ?? step;
    let next: number | null = null;
    if (e.key === "ArrowRight" || e.key === "ArrowUp") next = value + k;
    else if (e.key === "ArrowLeft" || e.key === "ArrowDown") next = value - k;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = max;
    if (next == null) return;
    e.preventDefault();
    e.stopPropagation();
    next = Math.min(max, Math.max(0, next));
    oninput?.(next);
    onchange?.(next);
  }
</script>

<div
  class="slider {cls}"
  class:dragging
  class:disabled
  role="slider"
  tabindex={disabled ? -1 : 0}
  aria-label={label}
  aria-valuemin={0}
  aria-valuemax={max}
  aria-valuenow={Math.round(value)}
  aria-valuetext={valuetext}
  aria-disabled={disabled}
  onpointerdown={down}
  onpointermove={move}
  onpointerup={up}
  onpointercancel={up}
  onkeydown={key}
>
  <div class="track" bind:this={track}>
    <div class="fill" style="width:{pct}%"></div>
  </div>
  <div class="knob" style="left:{pct}%"></div>
</div>

<style>
  .slider {
    position: relative;
    height: 20px;
    display: flex;
    align-items: center;
    cursor: pointer;
    touch-action: none;
    border-radius: 999px;
    --h: 4px;
  }
  .slider.disabled {
    cursor: default;
    opacity: 0.5;
  }
  .track {
    position: relative;
    width: 100%;
    height: var(--h);
    border-radius: 999px;
    background: color-mix(in srgb, var(--lfly-text) 18%, transparent);
    overflow: hidden;
    transition: height var(--lfly-dur-fast) var(--lfly-ease);
  }
  .fill {
    height: 100%;
    border-radius: inherit;
    background: color-mix(in srgb, var(--lfly-text) 82%, transparent);
    transition: background var(--lfly-dur-fast) var(--lfly-ease);
  }
  .slider:not(.disabled):hover,
  .slider.dragging,
  .slider:focus-visible {
    --h: 7px;
  }
  .slider:not(.disabled):hover .fill,
  .slider.dragging .fill,
  .slider:focus-visible .fill {
    background: var(--lfly-accent);
  }
  .knob {
    position: absolute;
    top: 50%;
    width: 14px;
    height: 14px;
    border-radius: 50%;
    background: #fff;
    box-shadow:
      0 1px 4px rgba(0, 0, 0, 0.35),
      0 0 0 0.5px rgba(0, 0, 0, 0.1);
    transform: translate(-50%, -50%) scale(0);
    transition: transform var(--lfly-dur-fast) var(--lfly-ease-spring);
    pointer-events: none;
  }
  .slider:not(.disabled):hover .knob,
  .slider.dragging .knob,
  .slider:focus-visible .knob {
    transform: translate(-50%, -50%) scale(1);
  }
  .slider.dragging .knob {
    transform: translate(-50%, -50%) scale(1.15);
  }
  .slider:focus-visible {
    outline: 2px solid var(--lfly-accent);
    outline-offset: 3px;
  }
</style>
