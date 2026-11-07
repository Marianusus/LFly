<script lang="ts">
  // The settings beyond the look: general, playback, lyrics, storage and privacy, the devices
  // and the about page's service status. Everything here changes something real: the core's
  // ones go through settings.rs, the window's through prefs.
  import { invoke } from "@tauri-apps/api/core";
  import { onMount } from "svelte";
  import Icon from "../../Icon.svelte";
  import { prefs } from "../../prefs.svelte";
  import { library } from "../../library.svelte";
  import { downloads } from "../../downloads.svelte";
  import { connect } from "../../connect.svelte";
  import { clearRecents } from "./recents";

  let { section }: { section: "general" | "playback" | "lyrics" | "storage" | "devices" | "about" } = $props();

  type Playback = { autoplay: boolean; format: "aac" | "opus" };
  type Everything = { playback: Playback; autostart: boolean; deviceName: string };
  type Disk = { audio: number; covers: number; downloads: number; downloadsDir: string };
  type Health = { name: string; kind: string; status: "ONLINE" | "DEGRADED" | "OFFLINE"; latencyMs?: number | null; lastError?: string | null };

  let core = $state<Everything | null>(null);
  let disk = $state<Disk | null>(null);
  let health = $state<Health[]>([]);
  let name = $state("");
  let busy = $state("");

  onMount(() => {
    invoke<Everything>("settings_get")
      .then((e) => {
        core = e;
        name = e.deviceName;
      })
      .catch(() => {});
    if (section === "storage") refreshDisk();
    if (section === "about") refreshHealth();
  });

  function refreshDisk() {
    invoke<Disk>("disk_usage").then((d) => (disk = d)).catch(() => {});
  }
  function refreshHealth() {
    invoke<Health[]>("yt_health").then((h) => (health = h)).catch(() => {});
  }

  function setPlayback(p: Partial<Playback>) {
    if (!core) return;
    core.playback = { ...core.playback, ...p };
    invoke("settings_set_playback", { playback: $state.snapshot(core.playback) }).catch((e) => connect.say(String(e)));
  }

  async function setAutostart(on: boolean) {
    try {
      const v = await invoke<boolean>("settings_set_autostart", { on });
      if (core) core.autostart = v;
    } catch (e) {
      connect.say(String(e));
    }
  }

  async function clear(kind: "audio" | "covers") {
    busy = kind;
    try {
      await invoke("disk_clear", { kind });
      connect.say(kind === "audio" ? "Cache audio svuotata" : "Copertine salvate eliminate");
      refreshDisk();
    } catch (e) {
      connect.say(String(e));
    } finally {
      busy = "";
    }
  }

  function removeAllDownloads() {
    if (!downloads.tracks.length) return;
    if (!confirm(`Eliminare ${downloads.tracks.length} brani scaricati da questo computer?`)) return;
    downloads.remove(downloads.tracks.map((t) => t.id));
    setTimeout(refreshDisk, 600);
  }

  async function rename() {
    try {
      name = await invoke<string>("connect_set_name", { name });
      connect.say(`Ora questo computer si chiama "${name}"`);
    } catch (e) {
      connect.say(String(e));
    }
  }

  function forgetPaired() {
    if (!confirm("I dispositivi di altri account abbinati con il codice dovranno chiederlo di nuovo. Continuare?")) return;
    invoke("connect_forget_paired").then(() => connect.say("Dispositivi abbinati dimenticati")).catch(() => {});
  }

  function resetOffsets() {
    invoke("store_set", { key: "lyrics-offsets", value: {} })
      .then(() => {
        window.dispatchEvent(new Event("lfly:lyrics-offsets-reset"));
        connect.say("Sincronie dei testi azzerate (vale dal prossimo brano)");
      })
      .catch(() => {});
  }

  function clearHistory() {
    if (!confirm("Cancellare la cronologia di ascolto di questo computer?")) return;
    library.clearHistory();
    connect.say("Cronologia cancellata");
  }

  const size = (b: number) =>
    b >= 1e9 ? `${(b / 1e9).toFixed(1).replace(".", ",")} GB` : b >= 1e6 ? `${Math.round(b / 1e6)} MB` : b > 0 ? `${Math.max(1, Math.round(b / 1e3))} kB` : "vuota";

  const KEYS: [string, string][] = [
    ["Spazio", "Riproduci o metti in pausa"],
    ["Ctrl + →  /  Ctrl + ←", "Brano successivo / precedente"],
    ["Maiusc + →  /  Maiusc + ←", "Avanti / indietro di 10 secondi"],
    ["Ctrl + K", "Cerca"],
    ["Alt + ←  /  Alt + →", "Pagina precedente / successiva"],
    ["Esc", "Chiudi il player a schermo intero"],
  ];

  const STATUS = { ONLINE: "Attivo", DEGRADED: "Lento", OFFLINE: "Non risponde" };
</script>

{#snippet seg<T extends string>(value: T, options: [T, string][], pick: (v: T) => void, label: string)}
  <div class="lfly-seg small" role="tablist" aria-label={label}>
    {#each options as [v, text]}
      <button role="tab" aria-selected={value === v} onclick={() => pick(v)}>{text}</button>
    {/each}
  </div>
{/snippet}

{#if section === "general"}
  <section class="group">
    <header class="group-head"><h2 class="lfly-title-m">Avvio</h2></header>
    <div class="lfly-list">
      <label class="lfly-list-row">
        <span class="grow">
          <strong>Apri LFly all'accesso</strong>
          <span class="lfly-caption">Si avvia quando entri nel computer, pronto con la tua musica.</span>
        </span>
        <input type="checkbox" class="lfly-switch" checked={core?.autostart ?? false} disabled={!core} onchange={(e) => setAutostart(e.currentTarget.checked)} />
      </label>
      <div class="lfly-list-row">
        <span class="grow"><strong>Pagina iniziale</strong><span class="lfly-caption">Dove si apre LFly.</span></span>
        {@render seg(
          prefs.v.startPage,
          [
            ["home", "Home"],
            ["library", "Libreria"],
            ["liked", "Preferiti"],
            ["search", "Cerca"],
          ],
          (v) => prefs.set("startPage", v),
          "Pagina iniziale",
        )}
      </div>
    </div>
  </section>
  <section class="group">
    <header class="group-head"><h2 class="lfly-title-m">Movimento</h2></header>
    <div class="lfly-list">
      <div class="lfly-list-row">
        <span class="grow">
          <strong>Animazioni</strong>
          <span class="lfly-caption">Ridotte: niente scorrimenti, copertina dinamica ferma, cambi istantanei.</span>
        </span>
        {@render seg(
          prefs.v.motion,
          [
            ["system", "Come il sistema"],
            ["full", "Complete"],
            ["reduce", "Ridotte"],
          ],
          (v) => prefs.set("motion", v),
          "Animazioni",
        )}
      </div>
    </div>
  </section>
  <section class="group">
    <header class="group-head"><h2 class="lfly-title-m">Scorciatoie da tastiera</h2></header>
    <div class="lfly-list">
      {#each KEYS as [k, what]}
        <div class="lfly-list-row"><span class="grow">{what}</span><kbd>{k}</kbd></div>
      {/each}
    </div>
  </section>
{:else if section === "playback"}
  <section class="group">
    <header class="group-head"><h2 class="lfly-title-m">Riproduzione</h2></header>
    <div class="lfly-list">
      <label class="lfly-list-row">
        <span class="grow">
          <strong>Continua con brani simili</strong>
          <span class="lfly-caption">Finita la coda, la musica va avanti con brani come l'ultimo, come la radio di YouTube Music.</span>
        </span>
        <input
          type="checkbox"
          class="lfly-switch"
          checked={core?.playback.autoplay ?? true}
          disabled={!core}
          onchange={(e) => setPlayback({ autoplay: e.currentTarget.checked })}
        />
      </label>
      <div class="lfly-list-row">
        <span class="grow">
          <strong>Formato audio</strong>
          <span class="lfly-caption">
            AAC (fino a 256 kbps) suona ovunque; Opus (fino a 160 kbps) occupa meno a parità di qualità. Vale dal prossimo brano.
          </span>
        </span>
        {#if core}
          {@render seg(
            core.playback.format,
            [
              ["aac", "AAC"],
              ["opus", "Opus"],
            ],
            (v) => setPlayback({ format: v }),
            "Formato audio",
          )}
        {/if}
      </div>
    </div>
  </section>
{:else if section === "lyrics"}
  <section class="group">
    <header class="group-head"><h2 class="lfly-title-m">Testi</h2></header>
    <div class="lfly-list">
      <label class="lfly-list-row">
        <span class="grow">
          <strong>Parola per parola</strong>
          <span class="lfly-caption">Ogni sillaba si accende mentre la canti, quando la fonte ha i tempi delle parole. Spento: righe intere.</span>
        </span>
        <input type="checkbox" class="lfly-switch" checked={prefs.v.lyricsWords} onchange={(e) => prefs.set("lyricsWords", e.currentTarget.checked)} />
      </label>
      <div class="lfly-list-row">
        <span class="grow"><strong>Dimensione</strong></span>
        {@render seg(
          prefs.v.lyricsSize,
          [
            ["s", "Piccola"],
            ["m", "Media"],
            ["l", "Grande"],
          ],
          (v) => prefs.set("lyricsSize", v),
          "Dimensione del testo",
        )}
      </div>
      <div class="lfly-list-row">
        <span class="grow"><strong>Allineamento</strong></span>
        {@render seg(
          prefs.v.lyricsAlign,
          [
            ["left", "A sinistra"],
            ["center", "Al centro"],
          ],
          (v) => prefs.set("lyricsAlign", v),
          "Allineamento del testo",
        )}
      </div>
      <label class="lfly-list-row">
        <span class="grow"><strong>Mostra la fonte</strong><span class="lfly-caption">"Testo da …" in fondo al testo.</span></span>
        <input type="checkbox" class="lfly-switch" checked={prefs.v.lyricsSource} onchange={(e) => prefs.set("lyricsSource", e.currentTarget.checked)} />
      </label>
      <div class="lfly-list-row">
        <span class="grow">
          <strong>Sincronie salvate</strong>
          <span class="lfly-caption">Le correzioni di tempo (− e +) che hai fatto canzone per canzone.</span>
        </span>
        <button class="lfly-btn lfly-btn-secondary" onclick={resetOffsets}>Azzera</button>
      </div>
    </div>
    <p class="foot below">
      I testi arrivano dalle stesse fonti del telefono: YouTube Music, BetterLyrics, lrc.red, BiniLyrics, LyricsPlus, Unison, KuGou e
      LRCLIB. Vince quella che corrisponde meglio a titolo, artista e durata, e i tempi parola per parola hanno la precedenza.
    </p>
  </section>
{:else if section === "storage"}
  <section class="group">
    <header class="group-head"><h2 class="lfly-title-m">Spazio</h2></header>
    <div class="lfly-list">
      <div class="lfly-list-row">
        <span class="grow">
          <strong>Brani scaricati</strong>
          <span class="lfly-caption">{downloads.tracks.length} brani · {disk ? size(disk.downloads) : "…"}</span>
        </span>
        {#if disk?.downloadsDir}
          <button class="lfly-btn lfly-btn-secondary" onclick={() => invoke("open_folder", { path: disk!.downloadsDir }).catch((e) => connect.say(String(e)))}>
            <Icon name="library" size={15} />Apri cartella
          </button>
        {/if}
        <button class="lfly-btn lfly-btn-secondary" disabled={!downloads.tracks.length} onclick={removeAllDownloads}>Elimina tutti</button>
      </div>
      <div class="lfly-list-row">
        <span class="grow">
          <strong>Cache audio</strong>
          <span class="lfly-caption">I brani ascoltati da poco, per ripartire subito e senza rete: {disk ? size(disk.audio) : "…"}</span>
        </span>
        <button class="lfly-btn lfly-btn-secondary" disabled={busy !== ""} onclick={() => clear("audio")}>Svuota</button>
      </div>
      <div class="lfly-list-row">
        <span class="grow">
          <strong>Copertine salvate</strong>
          <span class="lfly-caption">{disk ? size(disk.covers) : "…"} · si riscaricano quando servono</span>
        </span>
        <button class="lfly-btn lfly-btn-secondary" disabled={busy !== ""} onclick={() => clear("covers")}>Svuota</button>
      </div>
    </div>
  </section>
  <section class="group">
    <header class="group-head"><h2 class="lfly-title-m">Privacy</h2></header>
    <div class="lfly-list">
      <label class="lfly-list-row">
        <span class="grow">
          <strong>Metti in pausa la cronologia</strong>
          <span class="lfly-caption">I brani che ascolti non finiscono in "Ascoltati di recente" né nei suggerimenti.</span>
        </span>
        <input type="checkbox" class="lfly-switch" checked={prefs.v.pauseHistory} onchange={(e) => prefs.set("pauseHistory", e.currentTarget.checked)} />
      </label>
      <div class="lfly-list-row">
        <span class="grow"><strong>Cronologia di ascolto</strong><span class="lfly-caption">{library.history.length} brani</span></span>
        <button class="lfly-btn lfly-btn-secondary" disabled={!library.history.length} onclick={clearHistory}>Cancella</button>
      </div>
      <div class="lfly-list-row">
        <span class="grow"><strong>Ricerche recenti</strong></span>
        <button
          class="lfly-btn lfly-btn-secondary"
          onclick={() => {
            clearRecents();
            connect.say("Ricerche recenti cancellate");
          }}>Cancella</button
        >
      </div>
    </div>
  </section>
{:else if section === "devices"}
  <section class="group">
    <header class="group-head"><h2 class="lfly-title-m">Questo computer</h2></header>
    <div class="lfly-list">
      <div class="lfly-list-row">
        <span class="grow"><strong>Nome</strong><span class="lfly-caption">Come lo vedono il telefono e l'orologio.</span></span>
        <input
          class="lfly-input name"
          bind:value={name}
          maxlength="40"
          aria-label="Nome di questo computer"
          onkeydown={(e) => e.key === "Enter" && rename()}
        />
        <button class="lfly-btn lfly-btn-secondary" disabled={!core || name.trim() === core.deviceName} onclick={rename}>Salva</button>
      </div>
      <div class="lfly-list-row">
        <span class="grow">
          <strong>Dispositivi abbinati con il codice</strong>
          <span class="lfly-caption">Quelli di altri account che hai accettato una volta. I tuoi si collegano sempre senza codice.</span>
        </span>
        <button class="lfly-btn lfly-btn-secondary" onclick={forgetPaired}>Dimentica</button>
      </div>
    </div>
    <p class="foot below">
      Se il telefono non trova questo computer o non riesce a comandarlo, il firewall potrebbe bloccarlo: LFly Connect usa la porta TCP
      45780 e mDNS (UDP 5353) sulla rete locale.
    </p>
  </section>
{:else if section === "about"}
  <section class="group">
    <header class="group-head row">
      <h2 class="lfly-title-m">Stato dei servizi</h2>
      <button class="lfly-btn lfly-btn-secondary" onclick={refreshHealth}>Aggiorna</button>
    </header>
    <div class="lfly-list">
      {#each health as h (h.name)}
        <div class="lfly-list-row" title={h.lastError ?? ""}>
          <span class="dot {h.status.toLowerCase()}" aria-hidden="true"></span>
          <span class="grow">{h.name}</span>
          <span class="lfly-secondary">{STATUS[h.status]}{h.latencyMs != null && h.status !== "OFFLINE" ? ` · ${h.latencyMs} ms` : ""}</span>
        </div>
      {:else}
        <div class="lfly-list-row"><span class="grow lfly-secondary">Nessun servizio usato finora.</span></div>
      {/each}
    </div>
    <p class="foot below">
      Se una fonte non risponde, LFly passa da sola alla successiva e la riprova più tardi.
    </p>
  </section>
{/if}

<style>
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
  }
  .grow {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .foot {
    margin: 10px 4px 0;
    color: var(--lfly-text-2);
    line-height: 1.5;
    max-width: 640px;
  }
  .small {
    flex: none;
  }
  kbd {
    font: 600 12px/1 var(--lfly-font);
    padding: 6px 9px;
    border-radius: 8px;
    color: var(--lfly-text-2);
    background: color-mix(in srgb, var(--lfly-text) 8%, transparent);
    white-space: nowrap;
  }
  .name {
    width: min(240px, 40vw);
  }
  .dot {
    width: 9px;
    height: 9px;
    border-radius: 50%;
    flex: none;
    background: var(--lfly-text-3);
  }
  .dot.online {
    background: #30d158;
  }
  .dot.degraded {
    background: #ffd60a;
  }
  .dot.offline {
    background: #ff453a;
  }
</style>
