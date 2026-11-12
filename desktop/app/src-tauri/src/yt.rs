//! The window's YouTube Music commands: thin wrappers over lfly-core's pages.

use lfly_core::pages::{self, AlbumPage, ArtistPage, Item, Lyrics, PlaylistPage, SearchResults, Shelf};
use lfly_yt::{Track, YtMusic};
use tauri::State;

#[tauri::command]
pub async fn yt_home(yt: State<'_, YtMusic>) -> Result<Vec<Shelf>, String> {
    pages::home(&yt).await
}

#[tauri::command]
pub async fn yt_search(yt: State<'_, YtMusic>, query: String) -> Result<SearchResults, String> {
    pages::search(&yt, query).await
}

#[tauri::command]
pub async fn yt_suggestions(yt: State<'_, YtMusic>, query: String) -> Result<Vec<String>, String> {
    pages::suggestions(&yt, query).await
}

#[tauri::command]
pub async fn yt_artist(yt: State<'_, YtMusic>, id: String) -> Result<ArtistPage, String> {
    pages::artist(&yt, id).await
}

#[tauri::command]
pub async fn yt_album(yt: State<'_, YtMusic>, id: String) -> Result<AlbumPage, String> {
    pages::album(&yt, id).await
}

#[tauri::command]
pub async fn yt_playlist(yt: State<'_, YtMusic>, id: String) -> Result<PlaylistPage, String> {
    pages::playlist(&yt, id).await
}

#[tauri::command]
pub async fn yt_radio(yt: State<'_, YtMusic>, track: Track) -> Result<Vec<Track>, String> {
    pages::radio(&yt, track).await
}

#[tauri::command]
pub async fn yt_lyrics(yt: State<'_, YtMusic>, track: Track) -> Result<Option<Lyrics>, String> {
    pages::lyrics(&yt, track).await
}

#[tauri::command]
pub async fn yt_library(yt: State<'_, YtMusic>) -> Result<Vec<Item>, String> {
    pages::library(&yt).await
}

#[tauri::command]
pub async fn yt_rate(yt: State<'_, YtMusic>, id: String, liked: Option<bool>) -> Result<(), String> {
    pages::rate(&yt, id, liked).await
}

#[tauri::command]
pub fn yt_health(yt: State<'_, YtMusic>) -> Vec<lfly_yt::SourceHealth> {
    pages::health(&yt)
}
