<script lang="ts">
  import Icon from "../Icon.svelte";
  import ThemeCard from "../components/ThemeCard.svelte";
  import ThemeEditor from "../components/ThemeEditor.svelte";
  import Glass from "../glass/Glass.svelte";
  import { themes } from "../theme/theme.svelte";
  import { ACCENTS, MODES, modeInfo } from "../theme/palette";
  import { importThemeJson, parseColorIni, themeFromSpicetify, themeFromScheme, newId, type Theme } from "../theme/themes";
  import { lensEngine } from "../glass/lens";
  import { connect } from "../connect.svelte";
  import { nav } from "../nav.svelte";
  import { library } from "../library.svelte";
  import EqualizerPanel from "../components/EqualizerPanel.svelte";
  import Cover from "../components/Cover.svelte";
  import { account } from "../account.svelte";
  import { drive } from "../drive.svelte";
  import SettingsExtra from "./parts/SettingsExtra.svelte";
  import SpicetifyMarket from "./parts/SpicetifyMarket.svelte";
  import { player } from "../player.svelte";
  import { cover } from "../connect.svelte";

  const TABS = [
    ["general", "Generale"],
    ["appearance", "Aspetto"],
    ["sound", "Riproduzione"],
    ["lyrics", "Testi"],
    ["devices", "Dispositivi"],
    ["account", "Account"],
    ["storage", "Spazio e privacy"],
    ["about", "Informazioni"],
  ] as const;

  const section = $derived(nav.route.page === "settings" ? (nav.route.section ?? "appearance") : "appearance");

  /** The blurred playing cover, for the Copertina dinamica picture. */
  const coverSrc = $derived(themes.art ? themes.art.blurred.toDataURL() : null);
  const accentName = $derived(ACCENTS.find((a) => a.id === themes.accent)?.name ?? "");
  const followsCover = $derived(!themes.active && modeInfo(themes.mode).dynamic && !!themes.cover);
  const lensBackend = $derived(themes.lensOn ? lensEngine().backend : "none");

  /** The base for a new or imported theme: the custom theme in use, or the LFly look in use. */
  function baseTheme(): Theme {
    return themes.active
      ? ($state.snapshot(themes.active) as Theme)
      : themeFromScheme($state.snapshot(themes.scheme) as typeof themes.scheme, "LFly");
  }

  /** Spicetify color.ini with several schemes: the person picks which ones to add. */
  let iniSchemes = $state<{ name: string; colors: Record<string, string> }[] | null>(null);
  let iniFile = $state("");
  let showExtra = $state(false);
  let showMarket = $state(false);

  async function importFile(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = "";
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      connect.say("File troppo grande per essere un tema.");
      return;
    }
    const text = await file.text();
    const name = file.name.toLowerCase();
    try {
      if (name.endsWith(".ini")) {
        const schemes = parseColorIni(text);
        if (schemes.length === 0) throw new Error("Nessuno schema di colori nel file");
        if (schemes.length === 1) addSpicetify(schemes[0], file.name);
        else {
          iniSchemes = schemes as typeof iniSchemes;
          iniFile = file.name.replace(/\.ini$/i, "");
        }
      } else if (name.endsWith(".css")) {
        // A user.css: its rules on top of the look in use, as a new theme.
        const base = baseTheme();
        const theme: Theme = { ...base, builtin: false, id: newId(file.name), name: `${base.name} + ${file.name}`, css: text };
        themes.add(theme);
        themes.edit(theme);
        connect.say("CSS importato: controlla l'anteprima e salva");
      } else {
        const theme = importThemeJson(text, baseTheme());
        themes.add(theme);
        connect.say(`Tema "${theme.name}" importato`);
      }
    } catch (err) {
      connect.say(`Non riesco a leggere il tema: ${err instanceof Error ? err.message : err}`);
    }
  }

  function addSpicetify(s: { name: string; colors: Record<string, string> }, file = iniFile) {
    const label = file ? `${file.replace(/\.ini$/i, "")} · ${s.name}` : s.name;
    themes.add(themeFromSpicetify(label, s.colors, baseTheme()));
    connect.say(`Schema "${s.name}" aggiunto`);
  }

  function pickAccent(e: KeyboardEvent, i: number) {
    const keys: Record<string, number> = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
    if (!(e.key in keys)) return;
    e.preventDefault();
    const next = ACCENTS[(i + keys[e.key] + ACCENTS.length) % ACCENTS.length];
    themes.setAccent(next.id);
    (e.currentTarget as HTMLElement).parentElement?.querySelector<HTMLElement>(`[data-accent="${next.id}"]`)?.focus();
  }

  const playground = $derived(cover(player.track?.coverUrl, 240));
</script>

<div class="settings">
  <h1 class="lfly-page-title lfly-title-xl">Impostazioni</h1>

  <div class="lfly-seg tabs" role="tablist" aria-label="Sezioni delle impostazioni">
    {#each TABS as [id, label]}
      <button
        role="tab"
        aria-selected={section === id}
        onclick={() => nav.replace({ page: "settings", section: id as "appearance" })}
      >
        {label}
      </button>
    {/each}
  </div>

  {#if section === "appearance"}
    {#if themes.draft}
      <ThemeEditor />
    {:else}
      <!-- ---- Aspetto: the phone's modes ---- -->
      <section class="group" aria-labelledby="h-mode">
        <header class="group-head">
          <h2 id="h-mode" class="lfly-title-m">Aspetto</h2>
          <p class="foot">Canvas Liquid Glass e animazioni, come sul telefono.</p>
        </header>
        <div class="modes">
          {#each MODES as m (m.id)}
            <ThemeCard mode={m.id} {coverSrc} active={!themes.active && themes.mode === m.id} onpick={() => themes.setMode(m.id)} />
          {/each}
        </div>
      </section>

      <!-- ---- Colore accento ---- -->
      <section class="group" aria-labelledby="h-accent">
        <header class="group-head">
          <h2 id="h-accent" class="lfly-title-m">Colore accento</h2>
          <p class="foot">
            Si applica a tutti i temi; nei temi dinamici segue la copertina quando disponibile.
            {#if followsCover}<strong>Ora segue la copertina.</strong>{/if}
          </p>
        </header>
        <div class="lfly-list">
          <div class="accents" role="radiogroup" aria-label="Colore accento">
            {#each ACCENTS as a, i (a.id)}
              <button
                class="swatch"
                class:premium={a.premium}
                role="radio"
                aria-checked={themes.accent === a.id}
                aria-label={a.name}
                title={a.name}
                tabindex={themes.accent === a.id ? 0 : -1}
                data-accent={a.id}
                style="--sw:{a.color}"
                onclick={() => themes.setAccent(a.id)}
                onkeydown={(e) => pickAccent(e, i)}
              >
                {#if themes.accent === a.id}<Icon name="check" size={14} />{/if}
              </button>
            {/each}
          </div>
          <div class="lfly-list-row accent-row">
            <span class="dot" style="background:{themes.accentColor}"></span>
            <span class="grow">{accentName}</span>
            <span class="lfly-caption">{ACCENTS.find((a) => a.id === themes.accent)?.premium ? "Premium" : "Classico"}</span>
          </div>
        </div>
      </section>

      <!-- ---- Liquid Glass ---- -->
      <section class="group" aria-labelledby="h-glass">
        <header class="group-head">
          <h2 id="h-glass" class="lfly-title-m">Liquid Glass</h2>
          <p class="foot">Vetro con rifrazione reale su barre, pannelli e pulsanti. Passa il puntatore sui pulsanti qui sotto.</p>
        </header>

        <div class="stage" aria-label="Anteprima del vetro">
          {#if playground}
            <img class="stage-art" src={playground} alt="" />
          {/if}
          <div class="stage-stripes" aria-hidden="true"></div>
          <Glass class="stage-bar" shape="pill" interactive>
            <span class="stage-title">
              <strong class="ellipsis">{player.track?.title ?? "Get Lucky"}</strong>
              <span class="ellipsis">{player.track?.artist ?? "Daft Punk"}</span>
            </span>
            <Glass as="button" shape="circle" variant="clear" interactive class="stage-round" aria-label="Brano precedente">
              <Icon name="previous" size={16} fill />
            </Glass>
            <Glass as="button" shape="circle" variant="tinted" interactive class="stage-round big" aria-label="Riproduci">
              <Icon name="play" size={18} fill />
            </Glass>
            <Glass as="button" shape="circle" variant="clear" interactive class="stage-round" aria-label="Brano successivo">
              <Icon name="next" size={16} fill />
            </Glass>
          </Glass>
          <Glass class="stage-card" radius={22} interactive>
            <span class="lfly-eyebrow">Vetro</span>
            <strong>{themes.lensOn ? "Lente attiva" : themes.glassOn ? "Solo sfocatura" : "Superficie piena"}</strong>
          </Glass>
        </div>

        <div class="lfly-list">
          <label class="lfly-list-row">
            <span class="tile blue"><Icon name="expand" size={15} /></span>
            <span class="grow">
              <strong>Liquid Glass</strong>
              <span class="lfly-caption">Vetro traslucido sulle superfici che galleggiano sopra i contenuti.</span>
            </span>
            <input
              type="checkbox"
              class="lfly-switch"
              checked={themes.glass.enabled}
              onchange={(e) => themes.setGlass({ enabled: e.currentTarget.checked })}
            />
          </label>
          <label class="lfly-list-row">
            <span class="tile purple"><Icon name="lyrics" size={15} /></span>
            <span class="grow">
              <strong>Effetto lente</strong>
              <span class="lfly-caption">
                Rifrazione ai bordi, dispersione cromatica e riflessi che seguono il puntatore. Usa più GPU.
              </span>
            </span>
            <input
              type="checkbox"
              class="lfly-switch"
              checked={themes.glass.lens}
              disabled={!themes.glassOn}
              onchange={(e) => themes.setGlass({ lens: e.currentTarget.checked })}
            />
          </label>
          <div class="lfly-list-row slider-row">
            <span class="tile graphite"><Icon name="volume" size={15} /></span>
            <label class="grow" for="glass-strength">
              <strong>Intensità</strong>
              <span class="lfly-caption">Da più solido a più trasparente, con una lente più marcata.</span>
            </label>
            <span class="lfly-caption end">Solido</span>
            <input
              id="glass-strength"
              type="range"
              min="0"
              max="1"
              step="0.05"
              disabled={!themes.glassOn}
              value={themes.glass.strength}
              style="--fill:{themes.glass.strength * 100}%"
              oninput={(e) => themes.setGlass({ strength: +e.currentTarget.value })}
            />
            <span class="lfly-caption">Chiaro</span>
          </div>
        </div>
        {#if themes.reduceTransparency}
          <p class="note">Il sistema chiede meno trasparenza: il vetro resta una superficie piena.</p>
        {/if}
        {#if themes.reduceMotion}
          <p class="note">Il sistema chiede meno movimento: la copertina dinamica resta ferma e i pulsanti non rimbalzano.</p>
        {/if}
        {#if themes.glass.lens && themes.glassOn && lensBackend === "none"}
          <p class="note">Questa finestra non offre WebGL: il vetro usa solo la sfocatura.</p>
        {/if}
      </section>

      <!-- ---- Avanzate: custom themes, Spicetify ---- -->
      <section class="group" aria-labelledby="h-market">
        <header class="group-head row">
          <div>
            <h2 id="h-market" class="lfly-title-m">Marketplace di Spicetify</h2>
            <p class="foot">I temi della comunità di Spicetify, installati con un clic: i loro colori diventano temi di LFly.</p>
          </div>
          <button class="lfly-btn lfly-btn-secondary" onclick={() => (showMarket = !showMarket)}>
            {showMarket ? "Chiudi" : "Sfoglia i temi"}
          </button>
        </header>
        {#if showMarket}<SpicetifyMarket />{/if}
      </section>

      <section class="group" aria-labelledby="h-adv">
        <header class="group-head row">
          <div>
            <h2 id="h-adv" class="lfly-title-m">Temi personalizzati</h2>
            <p class="foot">
              Per chi vuole di più: colori, caratteri, sfondo e CSS. Importa i <strong>color.ini</strong> e gli
              <strong>user.css</strong> di Spicetify o i temi LFly esportati da un altro computer.
            </p>
          </div>
          <div class="row-actions">
            <label class="lfly-btn lfly-btn-secondary file" title="Tema LFly (.json), color.ini o user.css di Spicetify">
              <Icon name="upload" size={15} />Importa
              <input type="file" accept=".json,.ini,.css" onchange={importFile} />
            </label>
            <button class="lfly-btn lfly-btn-primary" onclick={() => themes.edit()}>
              <Icon name="edit" size={15} />Personalizza
            </button>
          </div>
        </header>

        {#if themes.active}
          <div class="in-use lfly-list">
            <div class="lfly-list-row">
              <span class="grow">In uso: <strong>{themes.active.name}</strong>, al posto di {modeInfo(themes.mode).title}.</span>
              <button class="lfly-btn lfly-btn-secondary" onclick={() => themes.useLFly()}>Torna a LFly</button>
            </div>
          </div>
        {/if}

        {#if iniSchemes}
          <div class="ini lfly-list">
            <div class="lfly-list-row"><strong class="grow">{iniFile}: {iniSchemes.length} schemi</strong>
              <button class="lfly-btn lfly-btn-secondary" onclick={() => (iniSchemes = null)}>Fatto</button>
            </div>
            <div class="ini-list">
              {#each iniSchemes as s}
                <button class="chip" onclick={() => addSpicetify(s)}>
                  <span class="dots">
                    {#each ["main", "sidebar", "text", "button"] as k}
                      <span style="background:{s.colors[k] ?? 'transparent'}"></span>
                    {/each}
                  </span>
                  {s.name}
                </button>
              {/each}
            </div>
          </div>
        {/if}

        {#if themes.custom.length}
          <div class="grid">
            {#each themes.custom as t (t.id)}
              <div class="cell">
                <ThemeCard theme={t} active={themes.active?.id === t.id} onpick={() => themes.use(t.id)} />
                <div class="cell-actions lfly-glass">
                  <button class="lfly-icon-btn" aria-label="Modifica {t.name}" title="Modifica" onclick={() => themes.edit(t)}>
                    <Icon name="edit" size={14} />
                  </button>
                  <button class="lfly-icon-btn" aria-label="Elimina {t.name}" title="Elimina" onclick={() => themes.remove(t.id)}>
                    <Icon name="trash" size={14} />
                  </button>
                </div>
              </div>
            {/each}
          </div>
        {:else}
          <p class="empty">Nessun tema tuo per ora. <strong>Personalizza</strong> parte dall'aspetto in uso.</p>
        {/if}

        <button class="disclose" aria-expanded={showExtra} onclick={() => (showExtra = !showExtra)}>
          <span class="chev" class:open={showExtra}><Icon name="forward" size={12} /></span>
          Altri temi
          <span class="lfly-caption">Palette open source di altri autori</span>
        </button>
        {#if showExtra}
          <div class="grid">
            {#each themes.extra as t (t.id)}
              <ThemeCard theme={t} active={themes.active?.id === t.id} onpick={() => themes.use(t.id)} />
            {/each}
          </div>
        {/if}
      </section>
    {/if}
  {:else if section === "general"}
    <SettingsExtra section="general" />
  {:else if section === "sound"}
    <SettingsExtra section="playback" />
    <EqualizerPanel />
  {:else if section === "lyrics"}
    <SettingsExtra section="lyrics" />
  {:else if section === "storage"}
    <SettingsExtra section="storage" />
  {:else if section === "devices"}
    <section class="group">
      <header class="group-head"><h2 class="lfly-title-m">LFly Connect</h2></header>
      <div class="lfly-list">
        <div class="lfly-list-row"><span class="grow">Questo computer</span><strong>{connect.me?.name ?? "—"}</strong></div>
        <div class="lfly-list-row"><span class="grow">Dispositivi in rete</span><strong>{connect.devices.length}</strong></div>
        <div class="lfly-list-row"><span class="grow">Controlli ora</span><strong>{connect.following?.name ?? "Nessuno"}</strong></div>
      </div>
      <p class="foot below">
        I telefoni, gli orologi e gli altri computer con LFly sulla stessa rete Wi-Fi si vedono a vicenda. La prima volta un
        dispositivo con un altro account chiede di confermare un codice di quattro cifre.
      </p>
    </section>
    <section class="group">
      <header class="group-head"><h2 class="lfly-title-m">Sincronizza con il telefono</h2></header>
      <div class="lfly-list">
        <label class="lfly-list-row">
          <span class="grow">
            <strong>Sulla stessa rete Wi-Fi</strong>
            <span class="lfly-caption">
              {#if !account.info}
                Accedi a YouTube Music qui sotto con lo stesso account del telefono: la sincronizzazione parte da sola.
              {:else if drive.local.lastSync}
                Ultima volta alle {new Date(drive.local.lastSync * 1000).toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" })}
              {:else}
                Preferiti, playlist e cronologia si allineano con i telefoni del tuo account aperti sulla stessa rete.
              {/if}
            </span>
          </span>
          <input
            type="checkbox"
            class="lfly-switch"
            checked={drive.local.enabled}
            disabled={drive.localBusy}
            onchange={(e) => drive.setLocal(e.currentTarget.checked)}
          />
        </label>
        {#if drive.local.enabled && account.info}
          <div class="lfly-list-row">
            <span class="grow lfly-secondary">Ogni minuto e mezzo e subito dopo ogni modifica, senza configurare niente.</span>
            <button class="lfly-btn lfly-btn-secondary" disabled={drive.localBusy} onclick={() => drive.syncLocalNow()}>
              {drive.localBusy ? "Sincronizzo…" : "Sincronizza ora"}
            </button>
          </div>
        {/if}
      </div>
    </section>

    <section class="group">
      <header class="group-head"><h2 class="lfly-title-m">Anche da lontano: Google Drive</h2></header>
      {#if drive.status.signedIn}
        <div class="lfly-list">
          <label class="lfly-list-row">
            <span class="grow">
              <strong>Sincronizza tramite Google Drive</strong>
              <span class="lfly-caption">
                {drive.status.email ?? "Account Google"} ·
                {drive.status.lastSync ? `ultima volta alle ${new Date(drive.status.lastSync * 1000).toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" })}` : "mai sincronizzato"}
              </span>
            </span>
            <input
              type="checkbox"
              class="lfly-switch"
              checked={drive.status.enabled}
              disabled={drive.busy}
              onchange={(e) => drive.setEnabled(e.currentTarget.checked)}
            />
          </label>
          <div class="lfly-list-row">
            <span class="grow lfly-secondary">Preferiti, playlist e cronologia si allineano da soli ogni pochi minuti e dopo ogni modifica.</span>
            <button class="lfly-btn lfly-btn-secondary" disabled={drive.busy || !drive.status.enabled} onclick={() => drive.syncNow()}>
              {drive.busy ? "Sincronizzo…" : "Sincronizza ora"}
            </button>
            <button class="lfly-btn lfly-btn-secondary" disabled={drive.busy} onclick={() => drive.signOut()}>Scollega</button>
          </div>
        </div>
      {:else}
        <div class="lfly-list">
          <div class="lfly-list-row">
            <span class="grow lfly-secondary">
              Per allinearli anche quando non sono sulla stessa rete: la libreria passa da una cartella nascosta del tuo Google
              Drive, visibile solo a LFly. Va configurato una volta; attivalo anche sul telefono.
            </span>
            <button class="lfly-btn lfly-btn-primary" disabled={drive.busy || !drive.status.configured} onclick={() => drive.signIn()}>
              {drive.busy ? "Aspetto Google…" : "Collega Google Drive"}
            </button>
          </div>
        </div>
        {#if !drive.status.configured}
          <p class="foot below">
            Manca il client OAuth di LFly: salva il file JSON del client "App desktop" del progetto Google Cloud di LFly come
            <code>~/.config/lfly/google-oauth.json</code>.
          </p>
        {/if}
      {/if}
      {#if drive.error}<p class="error" role="alert">{drive.error}</p>{/if}
    </section>
    <section class="group">
      <header class="group-head"><h2 class="lfly-title-m">Libreria</h2></header>
      <div class="lfly-list">
        <div class="lfly-list-row">
          <span class="grow">
            {library.playlists.length} playlist, {library.favorites.length} preferiti, {library.history.length} brani ascoltati.
          </span>
          <button class="lfly-btn lfly-btn-secondary" onclick={() => library.importBackup()}>
            <Icon name="upload" size={15} />Importa backup del telefono
          </button>
        </div>
      </div>
    </section>
    <SettingsExtra section="devices" />
  {:else if section === "account"}
    <section class="group">
      <header class="group-head"><h2 class="lfly-title-m">YouTube Music</h2></header>
      {#if account.info}
        <div class="lfly-list">
          <div class="lfly-list-row who">
            <Cover url={account.info.avatarUrl} size={48} round />
            <span class="grow who-text">
              <strong>{account.info.name}</strong>
              <span class="lfly-caption">{account.info.email || account.info.handle}</span>
            </span>
            <button class="lfly-btn lfly-btn-secondary" onclick={() => account.signOut()}>Esci</button>
          </div>
        </div>
        <p class="foot below">
          La Home e le playlist sono quelle del tuo account, e i "mi piace" arrivano anche su YouTube Music. I telefoni e gli
          orologi con lo stesso account si collegano a questo computer senza codice.
        </p>
      {:else if account.step === "idle"}
        <div class="lfly-list">
          <div class="lfly-list-row">
            <span class="grow lfly-secondary">
              Accedi con l'account Google che usi sul telefono: vedrai la tua Home e le tue playlist, e i tuoi dispositivi si
              collegheranno senza codice. La password la scrivi solo nella pagina di Google.
            </span>
            <button class="lfly-btn lfly-btn-primary" onclick={() => account.signIn()}>Accedi con Google</button>
          </div>
        </div>
      {:else}
        <div class="lfly-list steps">
          <p>
            Si è aperta la finestra di Google. Accedi e, quando vedi YouTube Music con il tuo account (e il canale giusto, se ne
            hai più di uno), torna qui e premi <strong>Fatto</strong>.
          </p>
          <div class="row-actions">
            <button class="lfly-btn lfly-btn-primary" disabled={account.step === "checking"} onclick={() => account.done()}>
              {account.step === "checking" ? "Controllo…" : "Fatto"}
            </button>
            <button class="lfly-btn lfly-btn-secondary" onclick={() => account.cancel()}>Annulla</button>
          </div>
        </div>
      {/if}
      {#if account.error}<p class="error" role="alert">{account.error}</p>{/if}
    </section>

    <section class="group">
      <header class="group-head"><h2 class="lfly-title-m">Discord</h2></header>
      <div class="lfly-list">
        <label class="lfly-list-row">
          <span class="grow">
            <strong>Mostra cosa ascolti</strong>
            <span class="lfly-caption">
              Con l'app Discord aperta su questo computer, il tuo stato diventa "Sta ascoltando" con il brano. Si toglie quando
              metti in pausa.
            </span>
          </span>
          <input
            type="checkbox"
            class="lfly-switch"
            checked={account.discord}
            onchange={(e) => account.setDiscord(e.currentTarget.checked)}
          />
        </label>
      </div>
    </section>
  {:else}
    <SettingsExtra section="about" />
    <section class="group">
      <header class="group-head"><h2 class="lfly-title-m">LFly per computer</h2></header>
      <div class="lfly-list">
        <div class="lfly-list-row"><span class="grow">Versione</span><strong>0.1.0</strong></div>
        <div class="lfly-list-row"><span class="grow">Sistemi</span><span class="lfly-secondary">Linux, Windows e macOS</span></div>
        <div class="lfly-list-row"><span class="grow">Licenza</span><span class="lfly-secondary">GPL-3.0, software libero</span></div>
      </div>
      <p class="foot below">
        Il Liquid Glass porta sul computer la lente di Kyant0/backdrop (Apache 2.0), la stessa dell'app per telefono. Il carattere
        Inter è di Rasmus Andersson (SIL Open Font License). Gli altri temi Catppuccin, Nord, Dracula, Gruvbox, Rosé Pine e Tokyo
        Night sono palette open source dei rispettivi autori (licenza MIT).
      </p>
    </section>
  {/if}
</div>

<style>
  .settings {
    padding: 28px calc(28px * var(--density)) 56px;
    max-width: 1040px;
  }
  .lfly-page-title {
    margin: 4px 0 18px;
  }
  .tabs {
    margin-bottom: 8px;
    flex-wrap: wrap;
  }
  .group {
    margin-top: 28px;
  }
  .group-head {
    margin: 0 4px 12px;
  }
  .group-head.row {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
  }
  .foot {
    margin: 4px 0 0;
    color: var(--lfly-text-2);
    line-height: 1.5;
    max-width: 640px;
  }
  .foot.below {
    margin: 10px 4px 0;
  }
  .row-actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .grow {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  /* Modes */
  .modes {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(176px, 1fr));
    gap: 20px 16px;
  }

  /* Accents */
  .accents {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    padding: 16px;
  }
  .swatch {
    width: 30px;
    height: 30px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: var(--sw);
    color: #fff;
    box-shadow:
      inset 0 0 0 0.5px rgba(0, 0, 0, 0.2),
      inset 0 1px 0 rgba(255, 255, 255, 0.3),
      0 1px 3px rgba(0, 0, 0, 0.25);
    transition: transform 500ms var(--lfly-ease-spring), box-shadow var(--lfly-dur-fast) var(--lfly-ease);
  }
  .swatch :global(svg) {
    filter: drop-shadow(0 1px 1px rgba(0, 0, 0, 0.45));
  }
  .swatch.premium {
    background:
      linear-gradient(145deg, rgba(255, 255, 255, 0.35), transparent 45%, rgba(0, 0, 0, 0.18)),
      var(--sw);
  }
  .swatch:hover {
    transform: scale(1.1);
  }
  .swatch:active {
    transform: scale(0.92);
    transition-duration: 120ms;
  }
  .swatch[aria-checked="true"] {
    box-shadow:
      0 0 0 2px var(--lfly-surface),
      0 0 0 4px var(--sw),
      0 2px 6px rgba(0, 0, 0, 0.3);
  }
  .swatch:focus-visible {
    outline: 2px solid var(--lfly-text);
    outline-offset: 4px;
    border-radius: 50%;
  }
  .accent-row {
    border-top: 0.5px solid var(--lfly-separator);
  }
  .accent-row .grow {
    flex-direction: row;
    font-weight: 600;
  }
  .dot {
    width: 12px;
    height: 12px;
    border-radius: 50%;
  }

  /* Glass stage: something colorful behind real glass. */
  .stage {
    position: relative;
    height: 168px;
    margin-bottom: 14px;
    border-radius: var(--lfly-radius-l);
    overflow: hidden;
    isolation: isolate;
    box-shadow: inset 0 0 0 0.5px var(--lfly-glass-border);
  }
  .stage-art {
    position: absolute;
    left: 6%;
    top: -30%;
    width: 46%;
    aspect-ratio: 1;
    object-fit: cover;
    border-radius: 24px;
    transform: rotate(-8deg);
    z-index: -1;
  }
  .stage-stripes {
    position: absolute;
    inset: 0;
    z-index: -2;
    background:
      radial-gradient(circle at 78% 30%, rgba(var(--lfly-rgb-accent), 0.85) 0 13%, transparent 13.5%),
      repeating-linear-gradient(110deg, rgba(var(--lfly-rgb-text), 0.1) 0 14px, transparent 14px 28px);
  }
  .stage :global(.stage-bar) {
    position: absolute;
    left: 0;
    right: 0;
    margin: 0 auto;
    bottom: 20px;
    display: flex;
    align-items: center;
    gap: 8px;
    width: min(440px, calc(100% - 40px));
    height: 60px;
    padding: 0 8px 0 22px;
  }
  .stage-title {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .stage-title span {
    color: var(--lfly-text-2);
    font-size: 12px;
  }
  .stage :global(.stage-round) {
    width: 40px;
    height: 40px;
    display: grid;
    place-items: center;
    flex: none;
  }
  .stage :global(.stage-round.big) {
    width: 46px;
    height: 46px;
  }
  .stage :global(.stage-card) {
    position: absolute;
    right: 22px;
    top: 18px;
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 12px 16px;
    min-width: 150px;
  }
  .tile {
    width: 28px;
    height: 28px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 7px;
    color: #fff;
  }
  .tile.blue {
    background: linear-gradient(180deg, #4d8dff, #2f6bf0);
  }
  .tile.purple {
    background: linear-gradient(180deg, #a173ff, #7c4dff);
  }
  .tile.graphite {
    background: linear-gradient(180deg, #8e8e93, #636366);
  }
  .slider-row {
    flex-wrap: wrap;
  }
  .slider-row input[type="range"] {
    width: min(220px, 40%);
  }
  .end {
    margin-left: auto;
  }
  .note {
    margin: 10px 4px 0;
    font-size: 12px;
    color: var(--lfly-text-2);
  }

  /* Custom themes */
  .file {
    cursor: pointer;
  }
  .file input {
    display: none;
  }
  .in-use,
  .ini {
    margin-bottom: 14px;
  }
  .ini-list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    padding: 0 14px 14px;
  }
  .chip {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    border-radius: 999px;
    background: var(--lfly-fill);
  }
  .chip:hover {
    background: var(--lfly-fill-2);
  }
  .dots {
    display: inline-flex;
  }
  .dots span {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    margin-left: -3px;
    box-shadow: 0 0 0 1px var(--lfly-separator);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(176px, 1fr));
    gap: 20px 16px;
    margin-top: 6px;
  }
  .cell {
    position: relative;
  }
  .cell-actions {
    position: absolute;
    top: 8px;
    right: 8px;
    display: flex;
    gap: 2px;
    padding: 2px;
    border-radius: 999px;
    opacity: 0;
    transition: opacity var(--lfly-dur-fast) var(--lfly-ease);
  }
  .cell:hover .cell-actions,
  .cell:focus-within .cell-actions {
    opacity: 1;
  }
  .cell-actions .lfly-icon-btn {
    width: 28px;
    height: 28px;
    color: var(--lfly-text);
  }
  .empty {
    margin: 0 4px;
    color: var(--lfly-text-2);
  }
  .disclose {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 22px 0 12px;
    padding: 6px 4px;
    font-weight: 600;
    border-radius: var(--lfly-radius-s);
  }
  .disclose .lfly-caption {
    font-weight: 400;
  }
  .chev {
    display: inline-grid;
    transition: transform var(--lfly-dur-med) var(--lfly-ease);
    color: var(--lfly-text-2);
  }
  .chev.open {
    transform: rotate(90deg);
  }

  /* Other tabs */
  .who-text {
    gap: 2px;
  }
  .steps {
    padding: 16px;
  }
  .steps p {
    margin: 0 0 12px;
    line-height: 1.5;
  }
  .error {
    color: var(--lfly-danger);
    margin: 10px 4px 0;
  }
  label.lfly-list-row {
    cursor: pointer;
  }
</style>
