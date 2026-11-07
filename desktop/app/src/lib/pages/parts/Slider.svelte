<script lang="ts">
  // A thin bar that thickens under the pointer, like the system's media sliders. A native range
  // input sits on top, invisible, so keyboard and screen readers work as usual.
  let {
    value,
    max,
    step = 1,
    label,
    valuetext,
    disabled = false,
    oninput,
    onchange,
  }: {
    value: number;
    max: number;
    step?: number;
    label: string;
    valuetext?: string;
    disabled?: boolean;
    oninput?: (v: number) => void;
    onchange?: (v: number) => void;
  } = $props();

  const pct = $derived(max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0);
</script>

<div class="slider" class:disabled style="--p:{pct}%">
  <div class="bar"><span class="fill"></span></div>
  <input
    type="range"
    min="0"
    {max}
    {step}
    {value}
    {disabled}
    aria-label={label}
    aria-valuetext={valuetext}
    oninput={(e) => oninput?.(Number((e.target as HTMLInputElement).value))}
    onchange={(e) => onchange?.(Number((e.target as HTMLInputElement).value))}
  />
</div>

<style>
  .slider {
    position: relative;
    height: 22px;
    display: flex;
    align-items: center;
    --ink: var(--slider-ink, #fff);
  }
  .bar {
    position: relative;
    width: 100%;
    height: 6px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--ink) 22%, transparent);
    overflow: hidden;
    transition: height 220ms var(--lfly-ease-spring, ease);
  }
  .fill {
    position: absolute;
    inset: 0 auto 0 0;
    width: var(--p);
    background: color-mix(in srgb, var(--ink) 82%, transparent);
    border-radius: inherit;
    transition: background 160ms;
  }
  .slider:hover:not(.disabled) .bar,
  .slider:focus-within .bar {
    height: 10px;
  }
  .slider:hover:not(.disabled) .fill,
  .slider:focus-within .fill {
    background: var(--ink);
  }
  input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    margin: 0;
    opacity: 0;
    cursor: pointer;
  }
  input:disabled {
    cursor: default;
  }
  .slider:has(input:focus-visible) .bar {
    outline: 2px solid var(--ink);
    outline-offset: 3px;
  }
  .disabled {
    opacity: 0.5;
  }
</style>
