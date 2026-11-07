//! LFly for the desktop. The core is Rust (player, YouTube Music, LFly Connect); the window shows it.

mod account;
mod covers;
mod discord;
mod drive;
mod libsync;
mod market;
mod settings;
mod spotify;
mod downloads;
mod identity;
mod library;
mod media;
mod player;
mod queue;
mod yt;
mod store;

use lfly_connect::protocol::{Device, Message, TrackRef};
use std::sync::Arc;

use lfly_connect::{ConnectDevice, Controller, ControllerEvent, Discovery, Host, HostEvent};
use player::LocalPlayer;
use tauri::{Emitter, Manager, State};
use tauri_plugin_dialog::DialogExt;

struct Core {
    /// This computer; its account tag changes with the YouTube Music account.
    me: std::sync::RwLock<Device>,
    discovery: Option<Discovery>,
    controller: Controller,
    local: Option<Arc<LocalPlayer>>,
}

/// Shows (or stops showing) what plays on Discord.
#[tauri::command]
fn discord_set_enabled(discord: State<'_, discord::Discord>, enabled: bool) -> Result<(), String> {
    discord.set_enabled(enabled);
    store::set("discord", &serde_json::json!({ "enabled": enabled }))
}

/// Warnings from the window, into the app's log.
#[tauri::command]
fn ui_log(message: String) {
    log::warn!("ui: {message}");
}

#[tauri::command]
fn connect_me(core: State<'_, Core>) -> Device {
    core.me.read().unwrap().clone()
}

/// Signs every part in to `account` (or out, with None) at once: YouTube Music requests, LFly
/// Connect's proof and trust, and the account tag this computer announces on the network.
pub(crate) fn apply_account(app: &tauri::AppHandle, account: Option<lfly_yt::Account>) {
    let secret = account.as_ref().and_then(|a| a.connect_secret());
    if let Some(yt) = app.try_state::<lfly_yt::YtMusic>() {
        yt.set_account(account);
    }
    let core = app.state::<Core>();
    let me = {
        let mut me = core.me.write().unwrap();
        me.owner = secret.as_deref().map(lfly_connect::protocol::owner_tag);
        me.clone()
    };
    core.controller.set_identity(me.clone(), secret.as_deref());
    if let Some(host) = core.local.as_ref().and_then(|l| l.host()) {
        host.set_identity(me.clone(), secret.as_deref());
        if let Some(d) = &core.discovery {
            if let Err(e) = d.announce(&me, host.port()) {
                log::warn!("Cannot announce this computer again: {e}");
            }
        }
    }
    if let Some(d) = &core.discovery {
        d.set_owner(me.owner.clone());
    }
    let _ = app.emit("connect:me", me);
}

#[tauri::command]
fn connect_devices(core: State<'_, Core>) -> Vec<ConnectDevice> {
    core.discovery.as_ref().map(Discovery::devices).unwrap_or_default()
}

#[tauri::command]
fn connect_following(core: State<'_, Core>) -> Option<ConnectDevice> {
    core.controller.following()
}

/// Async so the link runs on Tauri's Tokio runtime.
#[tauri::command]
async fn connect_follow(core: State<'_, Core>, id: String) -> Result<(), String> {
    let device = connect_devices(core.clone())
        .into_iter()
        .find(|d| d.id == id)
        .ok_or("Dispositivo non più in rete")?;
    core.controller.follow(device);
    Ok(())
}

#[tauri::command]
async fn connect_disconnect(core: State<'_, Core>) -> Result<(), String> {
    core.controller.disconnect();
    Ok(())
}

#[tauri::command]
async fn connect_command(core: State<'_, Core>, action: String, value: i64) -> Result<(), String> {
    core.controller.command(&action, value);
    Ok(())
}

#[tauri::command]
async fn connect_edit(
    core: State<'_, Core>,
    op: String,
    tracks: Option<Vec<TrackRef>>,
    index: Option<i32>,
    to: Option<i32>,
) -> Result<(), String> {
    core.controller.send(Message::QueueEdit {
        op,
        tracks: tracks.unwrap_or_default(),
        index: index.unwrap_or(-1),
        to: to.unwrap_or(-1),
    });
    Ok(())
}

#[tauri::command]
fn store_get(key: String) -> Result<Option<serde_json::Value>, String> {
    store::get(&key)
}

#[tauri::command]
fn store_set(key: String, value: serde_json::Value) -> Result<(), String> {
    store::set(&key, &value)
}

/// Asks where to save [contents] and writes it there; false when the person cancelled.
#[tauri::command]
async fn save_text_file(app: tauri::AppHandle, name: String, extension: String, contents: String) -> Result<bool, String> {
    let picked = app
        .dialog()
        .file()
        .set_file_name(&name)
        .add_filter(extension.to_uppercase(), &[extension.as_str()])
        .blocking_save_file();
    let Some(path) = picked else { return Ok(false) };
    let path = path.into_path().map_err(|e| e.to_string())?;
    std::fs::write(path, contents).map_err(|e| e.to_string())?;
    Ok(true)
}

#[tauri::command]
fn library_get() -> Result<library::Library, String> {
    Ok(library::load(&library::dir()?))
}

#[tauri::command]
fn library_save(app: tauri::AppHandle, name: String, value: serde_json::Value) -> Result<(), String> {
    library::save(&library::dir()?, &name, &value)?;
    drive::changed(&app);
    libsync::changed(&app);
    Ok(())
}

/// Asks for a phone backup (.lfly) and takes its library; None when the person cancelled.
#[tauri::command]
async fn library_import(app: tauri::AppHandle) -> Result<Option<library::ImportSummary>, String> {
    let picked = app
        .dialog()
        .file()
        .set_title("Scegli il backup di LFly")
        .add_filter("Backup LFly", &["lfly", "zip"])
        .blocking_pick_file();
    let Some(path) = picked else { return Ok(None) };
    let path = path.into_path().map_err(|e| e.to_string())?;
    library::import_backup(&path, &library::dir()?).map(Some)
}

/// Plays [tracks] from [index] on the device this computer controls.
#[tauri::command]
async fn connect_play(core: State<'_, Core>, tracks: Vec<TrackRef>, index: i32) -> Result<(), String> {
    if core.controller.following().is_none() {
        return Err("Nessun dispositivo collegato".into());
    }
    core.controller.send(Message::Transfer(lfly_connect::Transfer {
        queue: tracks,
        index,
        position_ms: 0,
        playing: true,
        from_device_id: core.me.read().unwrap().id.clone(),
    }));
    Ok(())
}

#[tauri::command]
fn player_ready(core: State<'_, Core>) -> bool {
    core.local.is_some()
}

fn local(core: &Core) -> Result<&Arc<LocalPlayer>, String> {
    core.local.as_ref().ok_or_else(|| "Nessuna uscita audio su questo computer".to_string())
}

#[tauri::command]
async fn player_play(core: State<'_, Core>, tracks: Vec<TrackRef>, index: i32, position_ms: i64) -> Result<(), String> {
    local(&core)?.play_queue(tracks, index, position_ms, true);
    Ok(())
}

#[tauri::command]
async fn player_command(core: State<'_, Core>, action: String, value: i64) -> Result<(), String> {
    local(&core)?.run(&action, value);
    Ok(())
}

#[tauri::command]
async fn player_edit(
    core: State<'_, Core>,
    op: String,
    tracks: Option<Vec<TrackRef>>,
    index: Option<i32>,
    to: Option<i32>,
) -> Result<(), String> {
    local(&core)?.edit_queue(&op, tracks.unwrap_or_default(), index.unwrap_or(-1), to.unwrap_or(-1));
    Ok(())
}

#[tauri::command]
fn player_state(core: State<'_, Core>) -> Option<lfly_connect::State> {
    core.local.as_ref().map(|p| p.state())
}

#[tauri::command]
fn player_set_crossfade(core: State<'_, Core>, seconds: u32) -> Result<(), String> {
    local(&core)?.set_crossfade(seconds);
    Ok(())
}

#[tauri::command]
fn player_set_equalizer(core: State<'_, Core>, curve: lfly_audio::EqCurve) -> Result<(), String> {
    local(&core)?.set_equalizer(curve);
    Ok(())
}

#[tauri::command]
fn player_devices(core: State<'_, Core>) -> Result<Vec<lfly_audio::OutputDevice>, String> {
    Ok(local(&core)?.devices())
}

#[tauri::command]
fn player_set_device(core: State<'_, Core>, id: Option<String>) -> Result<(), String> {
    local(&core)?.set_device(id);
    Ok(())
}

/// The music comes back here from the device this computer controls, from where it was.
#[tauri::command]
async fn connect_play_here(core: State<'_, Core>) -> Result<(), String> {
    if core.controller.following().is_none() {
        return Ok(());
    }
    local(&core)?;
    // The device answers with everything needed to play on (see the HandOver event).
    core.controller.command(lfly_connect::protocol::CMD_RELEASE, 0);
    Ok(())
}

/// The owner's answer to a device asking to control this computer.
#[tauri::command]
fn host_answer(core: State<'_, Core>, request: u64, accept: bool) {
    if let Some(host) = core.local.as_ref().and_then(|p| p.host()) {
        host.answer(request, accept);
    }
}

/// What the remote side reports, and what it means for the local player.
fn on_controller(app: &tauri::AppHandle, event: ControllerEvent) {
    let _ = app.emit("connect:event", event.clone());
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let core = app.state::<Core>();
        let Some(local) = core.local.clone() else { return };
        match event {
            // Picking another device moves the music playing here to it, like Spotify.
            ControllerEvent::Following { device: Some(device) } => {
                if local.is_playing() {
                    if let Some(transfer) = lfly_connect::HostPlayer::release(local.as_ref()) {
                        core.controller.send(Message::Transfer(transfer));
                        if let Some(host) = local.host() {
                            host.handed_to(&device.id);
                        }
                    }
                }
            }
            ControllerEvent::HandOver { transfer } => {
                core.controller.disconnect();
                local.play_queue(transfer.queue, transfer.index, transfer.position_ms, transfer.playing);
            }
            _ => {}
        }
    });
}

pub fn run() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info,mdns_sd=warn")).init();
    let covers = covers::Covers::new();
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .register_asynchronous_uri_scheme_protocol("cover", move |_ctx, request, responder| {
            covers.clone().serve(request, responder)
        })
        .setup(|app| {
            let mut me = identity::me();
            // Signed in to YouTube Music: the same secret as the phone, so the devices of one
            // account trust each other without a code.
            let signed_in = account::load().account;
            let secret = signed_in.as_ref().and_then(|a| a.connect_secret());
            me.owner = secret.as_deref().map(lfly_connect::protocol::owner_tag);
            log::info!("LFly Connect as '{}' ({}), account: {}", me.name, me.id, secret.is_some());
            let handle = app.handle().clone();
            let controller = Controller::new(me.clone(), secret.as_deref(), move |event| on_controller(&handle, event));
            // AAC first: symphonia's AAC decoder is mature, while the young pure-Rust Opus decoder
            // still trips on some real streams (YouTube's AAC 128k sounds the same).
            let yt = lfly_yt::YtMusic::with_config(lfly_yt::YtMusicConfig {
                preference: lfly_yt::AudioPreference::AacFirst,
                ..Default::default()
            })
            .map_err(|e| e.to_string())?;
            yt.set_account(signed_in);
            match downloads::start(app.handle().clone(), yt.clone()) {
                Some(d) => {
                    app.manage(d);
                }
                None => return Err("Non riesco a creare la cartella dei download".into()),
            }
            let local = match LocalPlayer::new(app.handle().clone(), yt.clone(), me.id.clone()) {
                Ok(p) => Some(p),
                Err(e) => {
                    log::warn!("No local player: {e}");
                    None
                }
            };
            let discovery = match Discovery::new() {
                Ok(d) => {
                    let handle = app.handle().clone();
                    if let Err(e) = d.browse(&me, move |list| {
                        let _ = handle.emit("connect:devices", list);
                    }) {
                        log::warn!("Cannot look for devices: {e}");
                    }
                    Some(d)
                }
                Err(e) => {
                    log::warn!("No network discovery: {e}");
                    None
                }
            };
            // This computer as a speaker the phones and the watch can pick.
            if let Some(player) = local.clone() {
                let handle = app.handle().clone();
                let announce_me = me.clone();
                let started = tauri::async_runtime::block_on(Host::start(
                    me.clone(),
                    secret.as_deref(),
                    identity::trusted(),
                    player.clone(),
                    move |event| {
                        if let HostEvent::Trusted { device_id, .. } = &event {
                            identity::trust(device_id);
                        }
                        let _ = handle.emit("host:event", event);
                    },
                ));
                match started {
                    Ok(host) => {
                        if let Some(d) = &discovery {
                            if let Err(e) = d.announce(&announce_me, host.port()) {
                                log::warn!("Cannot announce this computer: {e}");
                            }
                        }
                        player.attach_host(host);
                    }
                    Err(e) => log::warn!("Connect host did not start: {e}"),
                }
            }
            app.manage(Core { me: std::sync::RwLock::new(me), discovery, controller, local });
            app.manage(media::start(app.handle()));
            let discord_on = store::get("discord").ok().flatten().and_then(|v| v.get("enabled").and_then(|e| e.as_bool())).unwrap_or(true);
            app.manage(discord::Discord::start(discord_on));
            settings::load(&yt);
            app.manage(yt);
            app.manage(drive::start(app.handle().clone()));
            libsync::start(app.handle().clone());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            market::market_themes,
            market::market_file,
            settings::settings_get,
            settings::settings_set_playback,
            settings::settings_set_autostart,
            settings::disk_usage,
            settings::disk_clear,
            settings::open_folder,
            settings::connect_set_name,
            settings::connect_forget_paired,
            libsync::libsync_status,
            libsync::libsync_set_enabled,
            libsync::libsync_now,
            drive::drive_status,
            drive::drive_sign_in,
            drive::drive_sync,
            drive::drive_set_enabled,
            drive::drive_sign_out,
            ui_log,
            account::account_get,
            account::account_login,
            account::account_login_done,
            account::account_login_cancel,
            account::account_logout,
            yt::yt_library,
            downloads::downloads_list,
            downloads::downloads_add,
            downloads::downloads_remove,
            yt::yt_rate,
            connect_me,
            connect_devices,
            connect_following,
            connect_follow,
            connect_disconnect,
            connect_command,
            connect_edit,
            store_get,
            store_set,
            save_text_file,
            library_get,
            library_save,
            library_import,
            connect_play,
            player_ready,
            player_play,
            player_command,
            player_edit,
            player_state,
            connect_play_here,
            host_answer,
            player_set_equalizer,
            player_set_crossfade,
            player_devices,
            player_set_device,
            media::media_update,
            discord_set_enabled,
            spotify::spotify_import,
            yt::yt_home,
            yt::yt_search,
            yt::yt_suggestions,
            yt::yt_artist,
            yt::yt_album,
            yt::yt_playlist,
            yt::yt_radio,
            yt::yt_lyrics,
            yt::yt_health
        ])
        .run(tauri::generate_context!())
        .expect("LFly could not start");
}
