<script lang="ts" module>
  /** macOS keeps its own traffic lights over our backdrop; elsewhere the window has no frame. */
  export const nativeControls = typeof navigator !== "undefined" && /Mac/i.test(navigator.platform || navigator.userAgent);
  if (typeof document !== "undefined") document.documentElement.classList.toggle("native-frame", nativeControls);
</script>

<script lang="ts">
  // Minimise, maximise and close, drawn by LFly so the window's backdrop (the Dynamic cover)
  // reaches the very top. The window has no frame on Linux and Windows.
  import { getCurrentWindow } from "@tauri-apps/api/window";
  import { onMount } from "svelte";

  const win = (() => {
    try {
      return getCurrentWindow();
    } catch {
      return null;
    }
  })();
  let maximized = $state(false);

  onMount(() => {
    if (!win) return;
    const read = async () => {
      maximized = await win.isMaximized().catch(() => maximized);
      // Square corners when the window fills the screen.
      document.documentElement.classList.toggle("maximized", maximized);
    };
    read();
    let off: (() => void) | undefined;
    win
      .onResized(read)
      .then((u) => (off = u))
      .catch(() => {});
    return () => off?.();
  });
</script>

{#if !nativeControls}
  <div class="wc" role="group" aria-label="Finestra">
    <button aria-label="Riduci a icona" title="Riduci a icona" onclick={() => win?.minimize()}>
      <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M2.5 6h7" /></svg>
    </button>
    <button aria-label={maximized ? "Ripristina" : "Ingrandisci"} title={maximized ? "Ripristina" : "Ingrandisci"} onclick={() => win?.toggleMaximize()}>
      {#if maximized}
        <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M4 2.5h5.5V8M2.5 4h5.5v5.5H2.5z" /></svg>
      {:else}
        <svg viewBox="0 0 12 12" aria-hidden="true"><rect x="2.5" y="2.5" width="7" height="7" rx="1.2" /></svg>
      {/if}
    </button>
    <button class="close" aria-label="Chiudi" title="Chiudi" onclick={() => win?.close()}>
      <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M3 3l6 6M9 3l-6 6" /></svg>
    </button>
  </div>
{/if}

<style>
  .wc {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: none;
  }
  button {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: var(--lfly-text-2);
    background: color-mix(in srgb, var(--lfly-text) 8%, transparent);
    transition:
      background var(--lfly-dur-fast, 160ms) var(--lfly-ease, ease),
      color var(--lfly-dur-fast, 160ms) var(--lfly-ease, ease),
      scale var(--lfly-dur-fast, 160ms) var(--lfly-ease-spring, ease);
  }
  button:hover {
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 16%, transparent);
  }
  button:active {
    scale: 0.9;
  }
  .close:hover {
    color: #fff;
    background: #e5484d;
  }
  svg {
    width: 12px;
    height: 12px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.4;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
</style>
