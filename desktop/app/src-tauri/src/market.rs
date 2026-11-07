//! The Spicetify Marketplace's themes, for LFly: the same catalogue the Marketplace shows (GitHub
//! repositories tagged `spicetify-themes`, each describing its themes in a `manifest.json`), read
//! here because the window may not talk to GitHub. Only the colours (`color.ini`) come into LFly:
//! a theme's `user.css` and the Marketplace's extensions are written for Spotify's own page.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use futures_util::stream::{self, StreamExt};
use serde::Serialize;
use serde_json::Value;

const UA: &str = "LFly-desktop";
const KEEP: Duration = Duration::from_secs(3600);

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MarketTheme {
    name: String,
    description: String,
    author: String,
    repo: String,
    stars: u64,
    preview: Option<String>,
    /// The color.ini to install.
    schemes: String,
    outdated: bool,
}

static CACHE: Mutex<Option<(Instant, Vec<MarketTheme>)>> = Mutex::new(None);

fn client() -> reqwest::Client {
    reqwest::Client::builder().user_agent(UA).timeout(Duration::from_secs(15)).build().unwrap_or_default()
}

/// A repository path inside it, as a raw file url (paths may start with "./" or be urls already).
fn raw(owner_repo: &str, branch: &str, path: &str) -> String {
    if path.starts_with("http") {
        return path.to_string();
    }
    let path = path.trim_start_matches("./").trim_start_matches('/');
    format!("https://raw.githubusercontent.com/{owner_repo}/{branch}/{path}")
}

async fn themes_of(http: &reqwest::Client, repo: &Value) -> Vec<MarketTheme> {
    let (Some(full), Some(branch)) = (repo["full_name"].as_str(), repo["default_branch"].as_str()) else { return vec![] };
    let stars = repo["stargazers_count"].as_u64().unwrap_or(0);
    let Ok(resp) = http.get(raw(full, branch, "manifest.json")).send().await else { return vec![] };
    let Ok(manifest) = resp.json::<Value>().await else { return vec![] };
    let items = match manifest {
        Value::Array(a) => a,
        v @ Value::Object(_) => vec![v],
        _ => return vec![],
    };
    items
        .iter()
        .filter_map(|t| {
            let schemes = t["schemes"].as_str().filter(|s| !s.is_empty())?;
            let author = t["authors"][0]["name"].as_str().or_else(|| full.split('/').next()).unwrap_or("").to_string();
            let tags: Vec<&str> = t["tags"].as_array().map(|a| a.iter().filter_map(Value::as_str).collect()).unwrap_or_default();
            Some(MarketTheme {
                name: t["name"].as_str().unwrap_or(full).to_string(),
                description: t["description"].as_str().unwrap_or("").to_string(),
                author,
                repo: full.to_string(),
                stars,
                preview: t["preview"].as_str().map(|p| raw(full, branch, p)),
                schemes: raw(full, branch, schemes),
                outdated: tags.contains(&"outdated") || tags.contains(&"archived"),
            })
        })
        .collect()
}

/// The catalogue, most starred first; kept for an hour.
#[tauri::command]
pub async fn market_themes(refresh: Option<bool>) -> Result<Vec<MarketTheme>, String> {
    if refresh != Some(true) {
        if let Some((at, list)) = CACHE.lock().unwrap().as_ref() {
            if at.elapsed() < KEEP {
                return Ok(list.clone());
            }
        }
    }
    let http = client();
    let found: Value = http
        .get("https://api.github.com/search/repositories")
        .query(&[("q", "topic:spicetify-themes"), ("sort", "stars"), ("order", "desc"), ("per_page", "60")])
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|e| format!("GitHub non risponde: {e}"))?
        .error_for_status()
        .map_err(|e| format!("GitHub: {e}"))?
        .json()
        .await
        .map_err(|e| e.to_string())?;
    let repos = found["items"].as_array().cloned().unwrap_or_default();
    let mut lists: Vec<(usize, Vec<MarketTheme>)> = stream::iter(repos.into_iter().enumerate())
        .map(|(i, r)| {
            let http = http.clone();
            async move { (i, themes_of(&http, &r).await) }
        })
        .buffer_unordered(8)
        .collect()
        .await;
    lists.sort_by_key(|(i, _)| *i);
    let list: Vec<MarketTheme> = lists.into_iter().flat_map(|(_, l)| l).collect();
    *CACHE.lock().unwrap() = Some((Instant::now(), list.clone()));
    Ok(list)
}

/// One color.ini of the catalogue (GitHub raw files only, small ones only).
#[tauri::command]
pub async fn market_file(url: String) -> Result<String, String> {
    let parsed = reqwest::Url::parse(&url).map_err(|e| e.to_string())?;
    if parsed.scheme() != "https" || parsed.host_str() != Some("raw.githubusercontent.com") {
        return Err("Solo file del catalogo".into());
    }
    let resp = client().get(parsed).send().await.map_err(|e| e.to_string())?.error_for_status().map_err(|e| e.to_string())?;
    if resp.content_length().is_some_and(|n| n > 512 * 1024) {
        return Err("File troppo grande".into());
    }
    let text = resp.text().await.map_err(|e| e.to_string())?;
    if text.len() > 512 * 1024 {
        return Err("File troppo grande".into());
    }
    Ok(text)
}

#[cfg(test)]
mod live {
    //! Against GitHub: `cargo test -p lfly-desktop market -- --ignored --nocapture`.
    use super::*;

    #[tokio::test]
    #[ignore]
    async fn the_catalogue_has_themes_with_colours() {
        let list = market_themes(Some(true)).await.unwrap();
        println!("{} themes", list.len());
        for t in list.iter().take(8) {
            println!("{} by {} ({}★) {}", t.name, t.author, t.stars, t.schemes);
        }
        assert!(list.len() >= 20);
        let ini = market_file(list[0].schemes.clone()).await.unwrap();
        assert!(ini.contains('['));
    }
}
