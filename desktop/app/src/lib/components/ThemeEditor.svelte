<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import Icon from "../Icon.svelte";
  import { themes } from "../theme/theme.svelte";
  import { COLOR_KEYS, COLOR_LABELS, contrast, exportTheme, normalizeHex, type ColorKey, type Theme } from "../theme/themes";
  import { connect } from "../connect.svelte";

  // Edits themes.draft in place: every change shows at once, nothing is kept until "Salva".
  const d = $derived(themes.draft!);

  const GROUPS: { title: string; keys: ColorKey[] }[] = [
    { title: "Testo", keys: ["text", "subtext"] },
    { title: "Sfondi", keys: ["main", "main-elevated", "sidebar", "player", "card", "highlight", "highlight-elevated"] },
    { title: "Accento", keys: ["button", "button-active", "button-disabled", "tab-active"] },
    { title: "Altro", keys: ["selected-row", "shadow", "notification", "notification-error", "misc"] },
  ];

  const FONTS = [
    { label: "Sistema", value: "" },
    { label: "Inter", value: '"Inter", system-ui, sans-serif' },
    { label: "Noto Sans", value: '"Noto Sans", system-ui, sans-serif' },
    { label: "Ubuntu", value: '"Ubuntu", system-ui, sans-serif' },
    { label: "Fira Sans", value: '"Fira Sans", system-ui, sans-serif' },
    { label: "JetBrains Mono", value: '"JetBrains Mono", ui-monospace, monospace' },
    { label: "Georgia (serif)", value: 'Georgia, "Times New Roman", serif' },
  ];

  const CLASSES = [
    ".lfly-sidebar",
    ".lfly-main",
    ".lfly-panel",
    ".lfly-player",
    ".lfly-card",
    ".lfly-row",
    ".lfly-cover",
    ".lfly-shelf",
    ".lfly-page-title",
    ".lfly-progress",
    ".lfly-button-primary",
    ".lfly-lyrics",
  ];

  function setColor(key: ColorKey, value: string) {
    const hex = normalizeHex(value);
    if (hex) d.colors[key] = hex;
  }

  const textContrast = $derived(contrast(d.colors.text, d.colors.main));
  const subContrast = $derived(contrast(d.colors.subtext, d.colors.main));

  function pickBackground(e: Event) {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    if (file.size > 4 * 1024 * 1024) {
      connect.say("Immagine troppo grande: massimo 4 MB.");
      return;
    }
    const reader = new FileReader();
    reader.onload = () => (d.background = String(reader.result));
    reader.readAsDataURL(file);
  }

  async function exportIt() {
    const theme = $state.snapshot(d) as Theme;
    const name = `${theme.name.replace(/[^\w\- ]+/g, "").trim() || "tema"}.lflytheme.json`;
    try {
      if (await invoke<boolean>("save_text_file", { name, extension: "json", contents: exportTheme(theme) })) {
        connect.say("Tema esportato");
      }
    } catch (e) {
      connect.say(String(e));
    }
  }
</script>

<section class="editor" aria-label="Modifica tema">
  <header class="bar">
    <input class="name" bind:value={d.name} aria-label="Nome del tema" maxlength="40" />
    <div class="actions">
      <button class="button-secondary" onclick={exportIt}><Icon name="download" size={15} />Esporta</button>
      <button class="button-secondary" onclick={() => themes.cancelDraft()}>Annulla</button>
      <button class="lfly-button-primary" onclick={() => themes.saveDraft()}>Salva tema</button>
    </div>
  </header>

  <div class="cols">
    <div class="col">
      {#each GROUPS as g}
        <h3>{g.title}</h3>
        <div class="colors">
          {#each g.keys as key}
            <label class="color">
              <input type="color" value={d.colors[key]} oninput={(e) => setColor(key, (e.target as HTMLInputElement).value)} />
              <span class="c-text">
                <span>{COLOR_LABELS[key]}</span>
                <input
                  class="hex"
                  value={d.colors[key]}
                  spellcheck="false"
                  aria-label="{COLOR_LABELS[key]} in esadecimale"
                  onchange={(e) => setColor(key, (e.target as HTMLInputElement).value)}
                />
              </span>
            </label>
          {/each}
        </div>
      {/each}
      <p class="check" class:bad={textContrast < 4.5 || subContrast < 4.5}>
        Contrasto sul fondo: testo {textContrast.toFixed(1)}:1, secondario {subContrast.toFixed(1)}:1
        {textContrast < 4.5 || subContrast < 4.5 ? "— sotto 4.5:1 si legge male" : "— leggibile"}
      </p>
    </div>

    <div class="col">
      <h3>Colori dalla copertina</h3>
      <p class="help">Come i temi dinamici di LFly: l'accento, o tutta la finestra, prendono il colore della copertina in riproduzione.</p>
      <div class="lfly-seg" role="radiogroup" aria-label="Colori dalla copertina">
        {#each [["off", "No"], ["accent", "Solo accento"], ["full", "Tutta la finestra"]] as [v, label]}
          <button role="radio" aria-checked={d.dynamic === v} class:on={d.dynamic === v} onclick={() => (d.dynamic = v as Theme["dynamic"])}>
            {label}
          </button>
        {/each}
      </div>

      <h3>Caratteri</h3>
      <label class="field">
        <span>Interfaccia</span>
        <select class="lfly-input" bind:value={d.fontUi}>
          {#each FONTS as f}<option value={f.value}>{f.label}</option>{/each}
          {#if !FONTS.some((f) => f.value === d.fontUi)}<option value={d.fontUi}>{d.fontUi}</option>{/if}
        </select>
      </label>
      <label class="field">
        <span>Titoli</span>
        <select class="lfly-input" bind:value={d.fontDisplay}>
          <option value="">Come l'interfaccia</option>
          {#each FONTS.slice(1) as f}<option value={f.value}>{f.label}</option>{/each}
          {#if d.fontDisplay && !FONTS.some((f) => f.value === d.fontDisplay)}<option value={d.fontDisplay}>{d.fontDisplay}</option>{/if}
        </select>
      </label>
      <label class="field">
        <span>Altro carattere installato</span>
        <input class="lfly-input" placeholder="es. Comic Neue" onchange={(e) => {
          const v = (e.target as HTMLInputElement).value.trim();
          if (v) d.fontUi = `"${v.replace(/"/g, "")}", system-ui, sans-serif`;
        }} />
      </label>

      <h3>Forma</h3>
      <label class="field">
        <span>Angoli arrotondati: {d.radius}px</span>
        <input type="range" min="0" max="24" step="1" bind:value={d.radius} style="--fill:{(d.radius / 24) * 100}%" />
      </label>
      <div class="lfly-seg" role="radiogroup" aria-label="Densità">
        {#each [["compact", "Compatta"], ["comfortable", "Normale"], ["spacious", "Ariosa"]] as [v, label]}
          <button role="radio" aria-checked={d.density === v} class:on={d.density === v} onclick={() => (d.density = v as Theme["density"])}>
            {label}
          </button>
        {/each}
      </div>
      <label class="toggle">
        <input type="checkbox" class="lfly-switch" bind:checked={d.glass} />
        <span>Liquid Glass su barre e pannelli</span>
      </label>

      <h3>Sfondo</h3>
      <div class="bg">
        <label class="button-secondary file">
          <Icon name="upload" size={15} />Scegli immagine
          <input type="file" accept="image/*" onchange={pickBackground} />
        </label>
        {#if d.background}
          <button class="button-secondary" onclick={() => (d.background = undefined)}>Togli</button>
        {/if}
      </div>
      {#if d.background}
        <label class="field">
          <span>Visibilità: {Math.round(d.backgroundOpacity * 100)}%</span>
          <input type="range" min="0.05" max="1" step="0.05" bind:value={d.backgroundOpacity} style="--fill:{d.backgroundOpacity * 100}%" />
        </label>
      {/if}

      <h3>CSS personalizzato</h3>
      <p class="help">
        Come lo user.css di Spicetify: le variabili <code>--spice-*</code> e <code>--spice-rgb-*</code> funzionano uguali. Classi
        stabili: {#each CLASSES as c, i}<code>{c}</code>{i < CLASSES.length - 1 ? " " : ""}{/each}
      </p>
      <textarea
        class="css"
        bind:value={d.css}
        spellcheck="false"
        rows="10"
        placeholder={".lfly-player {\n  border-top: 1px solid var(--spice-button);\n}"}
        aria-label="CSS personalizzato"
      ></textarea>
    </div>
  </div>
</section>

<style>
  .editor {
    background: var(--lfly-surface);
    border-radius: var(--lfly-radius-l);
    box-shadow: inset 0 0 0 0.5px var(--lfly-glass-border), var(--lfly-shadow-1);
    padding: 20px 22px 24px;
  }
  .bar {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 14px;
    border-bottom: 0.5px solid var(--lfly-separator);
  }
  .name {
    font: 700 22px/1.2 var(--lfly-font-display);
    letter-spacing: -0.02em;
    background: transparent;
    border: 0;
    border-radius: 6px;
    padding: 4px 6px;
    margin-left: -6px;
    min-width: 0;
    flex: 1 1 200px;
    color: var(--lfly-text);
    user-select: text;
  }
  .name:hover {
    background: var(--lfly-fill);
  }
  .name:focus {
    outline: none;
    background: var(--lfly-fill);
    box-shadow: 0 0 0 3px rgba(var(--lfly-rgb-accent), 0.4);
  }
  .actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .cols {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: 28px;
    margin-top: 4px;
  }
  h3 {
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    margin: 22px 0 10px;
    color: var(--lfly-text-3);
  }
  .colors {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
    gap: 4px;
  }
  .color {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px;
    border-radius: var(--lfly-radius-s);
    cursor: pointer;
    transition: background-color var(--lfly-dur-fast) var(--lfly-ease);
  }
  .color:hover {
    background: var(--lfly-fill);
  }
  input[type="color"] {
    width: 30px;
    height: 30px;
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: none;
    cursor: pointer;
    flex: none;
    box-shadow: inset 0 0 0 0.5px var(--lfly-glass-border), 0 1px 3px rgba(0, 0, 0, 0.2);
  }
  input[type="color"]::-webkit-color-swatch-wrapper {
    padding: 0;
  }
  input[type="color"]::-webkit-color-swatch {
    border: 0;
    border-radius: 50%;
  }
  input[type="color"]::-moz-color-swatch {
    border: 0;
    border-radius: 50%;
  }
  .c-text {
    display: flex;
    flex-direction: column;
    min-width: 0;
    gap: 1px;
  }
  .hex {
    font-family: ui-monospace, "SF Mono", "JetBrains Mono", monospace;
    font-size: 11px;
    color: var(--lfly-text-2);
    background: transparent;
    border: 0;
    padding: 0;
    width: 80px;
    user-select: text;
  }
  .check {
    font-size: 12px;
    color: var(--lfly-text-2);
    margin-top: 12px;
  }
  .check.bad {
    color: var(--lfly-danger);
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 10px 0;
  }
  .field > span {
    color: var(--lfly-text-2);
    font-size: 12px;
  }
  select.lfly-input {
    padding-right: 8px;
  }
  .toggle {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 14px;
    cursor: pointer;
  }
  .bg {
    display: flex;
    gap: 8px;
  }
  .file {
    cursor: pointer;
  }
  .file input {
    display: none;
  }
  .help {
    font-size: 12px;
    color: var(--lfly-text-2);
    line-height: 1.6;
    margin: 0 0 10px;
  }
  code {
    font-family: ui-monospace, "SF Mono", "JetBrains Mono", monospace;
    font-size: 11px;
    background: var(--lfly-fill);
    padding: 1px 5px;
    border-radius: 4px;
  }
  .css {
    width: 100%;
    padding: 10px 12px;
    border: 0;
    border-radius: var(--lfly-radius-s);
    background: var(--lfly-fill);
    box-shadow: inset 0 0 0 0.5px var(--lfly-glass-border);
    font-family: ui-monospace, "SF Mono", "JetBrains Mono", monospace;
    font-size: 12px;
    line-height: 1.5;
    resize: vertical;
    user-select: text;
  }
  .css:focus-visible {
    outline: none;
    box-shadow: inset 0 0 0 0.5px var(--lfly-glass-border), 0 0 0 3px rgba(var(--lfly-rgb-accent), 0.4);
  }
</style>
