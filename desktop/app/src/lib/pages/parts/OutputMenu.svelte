<script lang="ts">
  // Where the music is heard: this computer's outputs, or another LFly device on the network.
  import Icon from "../../Icon.svelte";
  import Glass from "../../glass/Glass.svelte";
  import Glyph from "./Glyph.svelte";
  import { audio } from "../../audio.svelte";
  import { connect } from "../../connect.svelte";
  import { player } from "../../player.svelte";

  let { open = $bindable(false) }: { open?: boolean } = $props();

  $effect(() => {
    if (open && audio.available) audio.loadDevices();
  });

  const localName = $derived(
    audio.devices.find((d) => d.id === audio.device)?.name ?? audio.devices.find((d) => d.isDefault)?.name ?? "Questo computer",
  );
  const label = $derived(player.remote && player.where ? player.where : localName);
  const icon = (kind: string) => (["phone", "tablet", "desktop", "tv", "watch"].includes(kind) ? kind : "devices");
</script>

<div class="out">
  <button class="trigger" class:remote={player.remote} aria-haspopup="menu" aria-expanded={open} onclick={() => (open = !open)} title="Uscita audio">
    <Glyph name="output" size={17} />
    <span>{label}</span>
  </button>
  {#if open}
    <!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
    <div class="catch" onclick={() => (open = false)}></div>
    <Glass class="menu" radius={22} role="menu">
      <p class="sec">Questo computer</p>
      {#if player.remote}
        <button class="opt" role="menuitem" onclick={() => (player.playHere(), (open = false))}>
          <span class="ic"><Icon name="desktop" size={18} /></span><span class="nm">Ascolta qui</span>
        </button>
      {/if}
      {#if audio.available && audio.devices.length}
        {#each audio.devices as d (d.id)}
          {@const on = !player.remote && (audio.device ? audio.device === d.id : d.isDefault)}
          <button class="opt" class:on role="menuitemradio" aria-checked={on} onclick={() => (audio.setDevice(d.isDefault ? null : d.id), (open = false))}>
            <span class="ic"><Icon name="volume" size={18} /></span>
            <span class="nm">{d.name}</span>
            {#if on}<span class="ck"><Icon name="check" size={16} /></span>{/if}
          </button>
        {/each}
      {:else if !player.remote}
        <div class="opt on static"><span class="ic"><Icon name="desktop" size={18} /></span><span class="nm">Questo computer</span><span class="ck"><Icon name="check" size={16} /></span></div>
      {/if}
      {#if connect.devices.length}
        <p class="sec">LFly Connect</p>
        {#each connect.devices as d (d.id)}
          {@const on = connect.following?.id === d.id}
          <button class="opt" class:on role="menuitemradio" aria-checked={on} onclick={() => (on ? null : connect.follow(d), (open = false))}>
            <span class="ic"><Icon name={icon(d.kind)} size={18} /></span>
            <span class="nm">{d.name}</span>
            {#if connect.connecting === d.id}<span class="ck dim">Collego…</span>{:else if on}<span class="ck"><Icon name="check" size={16} /></span>{/if}
          </button>
        {/each}
        {#if connect.following}
          <button class="opt quiet" role="menuitem" onclick={() => (connect.disconnect(), (open = false))}>
            <span class="ic"><Icon name="close" size={16} /></span><span class="nm">Smetti di controllare {connect.following.name}</span>
          </button>
        {/if}
      {/if}
    </Glass>
  {/if}
</div>

<style>
  .out {
    position: relative;
    min-width: 0;
  }
  .trigger {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    height: 32px;
    max-width: 260px;
    padding: 0 12px;
    border-radius: 999px;
    font-size: 13px;
    font-weight: 600;
    color: rgba(255, 255, 255, 0.75);
    transition: background 160ms, color 160ms;
  }
  .trigger span {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .trigger:hover,
  .trigger[aria-expanded="true"] {
    color: #fff;
    background: rgba(255, 255, 255, 0.12);
  }
  .trigger.remote {
    color: var(--lfly-accent);
  }
  .catch {
    position: fixed;
    inset: 0;
    z-index: 1;
  }
  .out :global(.menu) {
    position: absolute;
    z-index: 2;
    bottom: calc(100% + 10px);
    left: 50%;
    transform: translateX(-50%);
    width: 300px;
    max-height: 60vh;
    overflow-y: auto;
    padding: 8px;
    color: #fff;
    background: rgba(28, 28, 32, 0.72);
    box-shadow: 0 24px 60px rgba(0, 0, 0, 0.5);
    animation: pop 240ms var(--lfly-ease, ease) both;
  }
  @keyframes pop {
    from {
      opacity: 0;
      transform: translateX(-50%) translateY(8px) scale(0.97);
    }
  }
  .sec {
    margin: 8px 10px 4px;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: rgba(255, 255, 255, 0.5);
  }
  .opt {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 9px 10px;
    border-radius: 12px;
    text-align: left;
    font-size: 14px;
    color: #fff;
  }
  .opt:hover:not(.static) {
    background: rgba(255, 255, 255, 0.1);
  }
  .opt.on .nm {
    font-weight: 650;
  }
  .opt.quiet {
    color: rgba(255, 255, 255, 0.65);
    font-size: 13px;
  }
  .ic {
    width: 32px;
    height: 32px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.1);
  }
  .on .ic {
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
  }
  .nm {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ck {
    color: var(--lfly-accent);
    display: grid;
  }
  .ck.dim {
    font-size: 12px;
    color: rgba(255, 255, 255, 0.6);
  }
</style>
