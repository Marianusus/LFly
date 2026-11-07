<script lang="ts">
  import { audio, BANDS, MAX_DB, PRESETS } from "../audio.svelte";

  $effect(() => {
    audio.loadDevices();
  });
</script>

<section class="eq" aria-label="Equalizzatore">
  <div class="card">
    <div class="head">
      <h2>Equalizzatore</h2>
      <label class="switch">
        <span class="state">{audio.eq.enabled ? "Attivo" : "Spento"}</span>
        <input type="checkbox" role="switch" checked={audio.eq.enabled} onchange={(e) => audio.setEnabled((e.target as HTMLInputElement).checked)} />
        <span class="knob" aria-hidden="true"></span>
      </label>
    </div>
    <p class="help">
      Lo stesso equalizzatore del telefono, dentro il player: suona uguale su casse, cuffie e DAC. Quando è piatto l'audio passa
      intatto, bit per bit.
    </p>

    <div class="presets" role="radiogroup" aria-label="Preset">
      {#each PRESETS as p}
        <button role="radio" aria-checked={audio.preset === p.name} class:on={audio.preset === p.name} onclick={() => audio.usePreset(p.name)}>
          {p.name}
        </button>
      {/each}
      {#if audio.preset === "Personalizzato"}<span class="custom">Personalizzato</span>{/if}
    </div>

    <div class="bands" class:off={!audio.eq.enabled}>
      <div class="zero" aria-hidden="true"></div>
      {#each BANDS as label, i}
        <label class="band">
          <span class="db" class:lift={audio.eq.bandsDb[i] !== 0}>{audio.eq.bandsDb[i] > 0 ? "+" : ""}{audio.eq.bandsDb[i].toFixed(1)} dB</span>
          <input
            type="range"
            min={-MAX_DB}
            max={MAX_DB}
            step="0.5"
            value={audio.eq.bandsDb[i]}
            style="--fill:{((audio.eq.bandsDb[i] + MAX_DB) / (2 * MAX_DB)) * 100}%"
            aria-label="Banda {label}"
            oninput={(e) => audio.setBand(i, Number((e.target as HTMLInputElement).value))}
            ondblclick={() => audio.setBand(i, 0)}
          />
          <span class="hz">{label}</span>
        </label>
      {/each}
    </div>

    <label class="bass">
      <span>Potenziamento bassi: <strong>{Math.round(audio.eq.bassBoost / 10)}%</strong></span>
      <input
        type="range"
        min="0"
        max="1000"
        step="10"
        value={audio.eq.bassBoost}
        style="--fill:{audio.eq.bassBoost / 10}%"
        oninput={(e) => audio.setBass(Number((e.target as HTMLInputElement).value))}
      />
    </label>
  </div>

  <div class="card">
    <h2>Automix</h2>
    <label class="bass">
      <span>Dissolvenza tra i brani: <strong>{audio.crossfade ? `${audio.crossfade} s` : "spenta"}</strong></span>
      <input
        type="range"
        min="0"
        max="12"
        step="1"
        value={audio.crossfade}
        style="--fill:{(audio.crossfade / 12) * 100}%"
        oninput={(e) => audio.setCrossfade(Number((e.target as HTMLInputElement).value))}
        aria-label="Secondi di dissolvenza tra i brani"
      />
    </label>
    <p class="help">Ogni brano sfuma nel successivo. Con la dissolvenza spenta i brani si susseguono senza pause, come negli album dal vivo.</p>
  </div>

  <div class="card">
    <h2>Uscita audio</h2>
    <div class="select">
      <select
        value={audio.device ?? ""}
        onchange={(e) => audio.setDevice((e.target as HTMLSelectElement).value || null)}
        aria-label="Uscita audio"
      >
        <option value="">Predefinita del sistema</option>
        {#each audio.devices as d (d.id)}
          <option value={d.id}>{d.name}{d.isDefault ? " (predefinita)" : ""}</option>
        {/each}
      </select>
      <span class="chev" aria-hidden="true"></span>
    </div>
  </div>
</section>

<style>
  .eq {
    max-width: 720px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .card {
    padding: 18px 20px 20px;
    border-radius: 24px;
    background: var(--lfly-glass-tint);
    box-shadow:
      inset 0 0 0 1px var(--lfly-glass-border),
      inset 0 1px 0 color-mix(in srgb, var(--lfly-glass-highlight) 70%, transparent);
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  h2 {
    margin: 0 0 8px;
    font: 600 17px/1.25 var(--lfly-font-display);
    color: var(--lfly-text);
  }
  .head h2 {
    margin: 0;
  }
  .help {
    margin: 8px 0 0;
    color: var(--lfly-text-2);
    line-height: 1.5;
  }

  /* An iOS switch on a real checkbox. */
  .switch {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    cursor: pointer;
    font-weight: 600;
    color: var(--lfly-text-2);
  }
  .switch input {
    appearance: none;
    -webkit-appearance: none;
    margin: 0;
    width: 46px;
    height: 28px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--lfly-text) 18%, transparent);
    cursor: pointer;
    transition: background var(--lfly-dur-fast) var(--lfly-ease);
  }
  .switch input:checked {
    background: var(--lfly-accent);
  }
  .switch input:focus-visible {
    outline: 2px solid var(--lfly-accent);
    outline-offset: 3px;
  }
  .knob {
    position: absolute;
    right: 20px;
    top: 50%;
    width: 24px;
    height: 24px;
    margin-top: -12px;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
    pointer-events: none;
    transition: transform var(--lfly-dur-med) var(--lfly-ease-spring);
  }
  .switch input:checked + .knob {
    transform: translateX(18px);
  }

  .presets {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin: 16px 0 18px;
  }
  .presets button,
  .custom {
    height: 30px;
    padding: 0 14px;
    border-radius: 999px;
    display: inline-flex;
    align-items: center;
    font-weight: 600;
    font-size: 12.5px;
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 8%, transparent);
    transition:
      background var(--lfly-dur-fast) var(--lfly-ease),
      scale var(--lfly-dur-fast) var(--lfly-ease-spring);
  }
  .presets button:hover {
    background: color-mix(in srgb, var(--lfly-text) 14%, transparent);
  }
  .presets button:active {
    scale: 0.95;
  }
  .presets button.on,
  .custom {
    background: var(--lfly-accent);
    color: var(--lfly-on-accent);
  }

  .bands {
    position: relative;
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    gap: 12px;
    border-radius: 20px;
    padding: 20px 12px 16px;
    background: color-mix(in srgb, var(--lfly-bg) 40%, transparent);
    box-shadow: inset 0 0 0 1px var(--lfly-separator);
    transition: opacity var(--lfly-dur-fast) var(--lfly-ease);
  }
  .bands.off {
    opacity: 0.5;
  }
  /* The 0 dB line across the bands. */
  .zero {
    position: absolute;
    left: 16px;
    right: 16px;
    top: calc(20px + 26px + 80px);
    height: 1px;
    background: var(--lfly-separator);
    pointer-events: none;
  }
  .band {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
  }
  .band input {
    writing-mode: vertical-lr;
    direction: rtl;
    width: 24px;
    height: 160px;
    -webkit-appearance: none;
    appearance: none;
    background: transparent;
    cursor: pointer;
  }
  .band input::-webkit-slider-runnable-track {
    width: 6px;
    height: auto;
    border-radius: 999px;
    background: linear-gradient(to top, var(--lfly-accent) var(--fill), color-mix(in srgb, var(--lfly-text) 16%, transparent) var(--fill));
  }
  .band input::-webkit-slider-thumb {
    -webkit-appearance: none;
    width: 20px;
    height: 20px;
    margin-left: -7px;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.35);
    opacity: 1;
  }
  .band input::-moz-range-thumb {
    width: 20px;
    height: 20px;
    border: 0;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.35);
  }
  .db {
    font-size: 11px;
    font-variant-numeric: tabular-nums;
    color: var(--lfly-text-3);
    height: 16px;
  }
  .db.lift {
    color: var(--lfly-accent);
    font-weight: 600;
  }
  .hz {
    font-size: 12px;
    font-weight: 600;
    color: var(--lfly-text-2);
  }
  .bass {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-top: 16px;
    color: var(--lfly-text-2);
  }
  .card > .bass:first-of-type {
    margin-top: 4px;
  }
  .bass strong {
    color: var(--lfly-text);
  }
  .bass input {
    -webkit-appearance: none;
    appearance: none;
    height: 20px;
    background: transparent;
    cursor: pointer;
  }
  .bass input::-webkit-slider-runnable-track {
    height: 6px;
    border-radius: 999px;
    background: linear-gradient(to right, var(--lfly-accent) var(--fill), color-mix(in srgb, var(--lfly-text) 16%, transparent) var(--fill));
  }
  .bass input::-webkit-slider-thumb {
    -webkit-appearance: none;
    width: 20px;
    height: 20px;
    margin-top: -7px;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.35);
    opacity: 1;
  }
  .bass input::-moz-range-thumb {
    width: 20px;
    height: 20px;
    border: 0;
    border-radius: 50%;
    background: #fff;
  }
  .bass input::-moz-range-progress {
    height: 6px;
    border-radius: 999px;
    background: var(--lfly-accent);
  }
  .select {
    position: relative;
    display: inline-block;
    max-width: 100%;
  }
  select {
    -webkit-appearance: none;
    appearance: none;
    height: 38px;
    min-width: 300px;
    max-width: 100%;
    padding: 0 40px 0 16px;
    border-radius: 999px;
    border: 0;
    color: var(--lfly-text);
    background: color-mix(in srgb, var(--lfly-text) 8%, transparent);
    box-shadow: inset 0 0 0 1px var(--lfly-glass-border);
    cursor: pointer;
  }
  select:focus-visible {
    outline: 2px solid var(--lfly-accent);
    outline-offset: 2px;
  }
  option {
    color: #111;
  }
  .chev {
    position: absolute;
    right: 16px;
    top: 50%;
    width: 7px;
    height: 7px;
    margin-top: -6px;
    border-right: 1.8px solid var(--lfly-text-2);
    border-bottom: 1.8px solid var(--lfly-text-2);
    rotate: 45deg;
    pointer-events: none;
  }
</style>
