<script lang="ts" generics="T extends string">
  // A segmented control on glass: the selected segment is a lens that slides to the one picked.
  import Glass from "../../glass/Glass.svelte";

  let {
    options,
    value,
    onchange,
    label,
  }: { options: { id: T; label: string }[]; value: T; onchange: (id: T) => void; label: string } = $props();

  let box = $state<HTMLDivElement>();
  let pill = $state({ x: 0, w: 0 });

  $effect(() => {
    value;
    options.length;
    const el = box?.querySelector<HTMLElement>(`[data-id="${CSS.escape(value)}"]`);
    if (el) pill = { x: el.offsetLeft, w: el.offsetWidth };
  });

  function key(e: KeyboardEvent) {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
    const i = options.findIndex((o) => o.id === value);
    const n = options[(i + (e.key === "ArrowRight" ? 1 : options.length - 1)) % options.length];
    onchange(n.id);
    box?.querySelector<HTMLElement>(`[data-id="${CSS.escape(n.id)}"]`)?.focus();
    e.preventDefault();
  }
</script>

<Glass shape="pill" class="lfly-chips">
  <div class="track" bind:this={box} role="tablist" aria-label={label} tabindex="-1" onkeydown={key}>
    <span class="lens" style="transform:translateX({pill.x}px);width:{pill.w}px" class:ready={pill.w > 0}></span>
    {#each options as o (o.id)}
      <button
        role="tab"
        data-id={o.id}
        aria-selected={o.id === value}
        tabindex={o.id === value ? 0 : -1}
        class:on={o.id === value}
        onclick={() => onchange(o.id)}>{o.label}</button
      >
    {/each}
  </div>
</Glass>

<style>
  :global(.lfly-chips) {
    display: inline-flex;
    padding: 4px;
    max-width: 100%;
    overflow-x: auto;
    scrollbar-width: none;
  }
  .track {
    position: relative;
    display: flex;
    gap: 2px;
  }
  .lens {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    border-radius: 999px;
    background: var(--lfly-text);
    box-shadow:
      0 4px 14px rgba(0, 0, 0, 0.25),
      inset 0 1px 0 rgba(255, 255, 255, 0.5);
    opacity: 0;
    transition:
      transform 420ms var(--lfly-ease-spring, cubic-bezier(0.34, 1.56, 0.64, 1)),
      width 420ms var(--lfly-ease-spring, cubic-bezier(0.34, 1.56, 0.64, 1));
  }
  .lens.ready {
    opacity: 1;
  }
  button {
    position: relative;
    height: 32px;
    padding: 0 16px;
    border-radius: 999px;
    font-size: 13px;
    font-weight: 600;
    white-space: nowrap;
    color: var(--lfly-text-2);
    transition: color 240ms var(--lfly-ease, ease);
  }
  button:hover:not(.on) {
    color: var(--lfly-text);
  }
  button.on {
    color: var(--lfly-bg, #000);
  }
  @media (prefers-reduced-motion: reduce) {
    .lens {
      transition: none;
    }
  }
</style>
