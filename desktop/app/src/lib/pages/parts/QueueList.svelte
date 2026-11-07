<script lang="ts">
  // What plays now and next, for the full-screen player.
  import Icon from "../../Icon.svelte";
  import Cover from "../../components/Cover.svelte";
  import { player } from "../../player.svelte";
  import { menu } from "../../menu.svelte";
  import { time } from "../../connect.svelte";

  const upcoming = $derived(player.queue.map((t, i) => ({ t, i })).filter(({ i }) => i > player.index));
</script>

<div class="queue">
  {#if player.track}
    <p class="sec">In riproduzione</p>
    <div class="row now">
      <Cover url={player.track.coverUrl} size={48} />
      <span class="text"><span class="t">{player.track.title}</span><span class="s">{player.track.artist}</span></span>
      <span class="eq" class:paused={!player.playing} aria-hidden="true"><i></i><i></i><i></i></span>
    </div>
  {/if}
  <p class="sec">Successivi{upcoming.length ? ` · ${upcoming.length}` : ""}</p>
  {#if upcoming.length}
    {#each upcoming as { t, i } (i + ":" + t.id)}
      <div class="row" role="listitem" oncontextmenu={(e) => (e.preventDefault(), menu.openTrack(e, t, () => player.removeAt(i)))}>
        <button class="open" onclick={() => player.skipTo(i)} aria-label="Riproduci {t.title}">
          <span class="art"><Cover url={t.coverUrl} size={48} /><span class="hov"><Icon name="play" size={16} /></span></span>
          <span class="text"><span class="t">{t.title}</span><span class="s">{t.artist}</span></span>
        </button>
        <span class="dur">{t.durationMs ? time(t.durationMs) : ""}</span>
        <button class="rm" aria-label="Togli {t.title} dalla coda" onclick={() => player.removeAt(i)}><Icon name="close" size={14} /></button>
      </div>
    {/each}
  {:else}
    <p class="empty">Nient'altro in coda.</p>
  {/if}
</div>

<style>
  .queue {
    color: #fff;
  }
  .sec {
    margin: 18px 8px 8px;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: rgba(255, 255, 255, 0.55);
  }
  .sec:first-child {
    margin-top: 0;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 6px 8px;
    border-radius: 12px;
    transition: background 160ms;
  }
  .row:hover:not(.now) {
    background: rgba(255, 255, 255, 0.08);
  }
  .now {
    background: rgba(255, 255, 255, 0.1);
  }
  .open {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 12px;
    text-align: left;
  }
  .art {
    position: relative;
    flex: none;
  }
  .row :global(.lfly-cover) {
    border-radius: 8px;
  }
  .hov {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    border-radius: 8px;
    background: rgba(0, 0, 0, 0.45);
    opacity: 0;
    transition: opacity 160ms;
  }
  .row:hover .hov {
    opacity: 1;
  }
  .text {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .t {
    font-size: 14px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .s,
  .dur {
    font-size: 12px;
    color: rgba(255, 255, 255, 0.6);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .dur {
    font-variant-numeric: tabular-nums;
  }
  .rm {
    width: 30px;
    height: 30px;
    flex: none;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: rgba(255, 255, 255, 0.6);
    opacity: 0;
  }
  .row:hover .rm,
  .rm:focus-visible {
    opacity: 1;
  }
  .rm:hover {
    background: rgba(255, 255, 255, 0.12);
    color: #fff;
  }
  .eq {
    display: flex;
    align-items: flex-end;
    gap: 2px;
    height: 14px;
    margin-right: 6px;
    color: var(--lfly-accent);
  }
  .eq i {
    width: 3px;
    height: 100%;
    border-radius: 2px;
    background: currentColor;
    animation: bars 0.9s ease-in-out infinite alternate;
    transform-origin: bottom;
  }
  .eq i:nth-child(2) {
    animation-delay: -0.3s;
  }
  .eq i:nth-child(3) {
    animation-delay: -0.6s;
  }
  .eq.paused i {
    animation: none;
    transform: scaleY(0.35);
  }
  @keyframes bars {
    from {
      transform: scaleY(0.25);
    }
  }
  .empty {
    margin: 4px 8px;
    color: rgba(255, 255, 255, 0.6);
  }
</style>
