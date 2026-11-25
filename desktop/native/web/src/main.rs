//! `lfly-web`: the native app's web helper (it has no webview of its own). Each mode opens one
//! page, prints one line of JSON on stdout and exits; the app runs it only when needed.
//!
//! - `spotify-login`: Spotify's sign-in page in a window; once signed in, prints
//!   `{"sp_dc": "…"}` (the web session cookie the phone also keeps). Closing the window: exit 1.
//! - `spotify-token`: reads that cookie from stdin, loads the web player in a hidden window and
//!   prints the signed-in token the page asks for itself (`/api/token`), as the phone does
//!   (auth/SpotifyAuth.kt `harvest`). Nothing in 35 s: exit 2.
//!
//! - `ytmusic-login`: Google's sign-in page for YouTube Music in a window (as the Tauri app's
//!   account.rs); once YouTube Music shows a signed-in account, prints its cookies and the page's
//!   ids (`{"cookie": "…", "probe": {…}}`). Closing the window: exit 1.
//!
//! All use a fresh, private web profile: nothing stays on disk.

use std::io::{BufRead, Write};
use std::time::{Duration, Instant};

use tao::dpi::LogicalSize;
use tao::event::{Event, StartCause, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::{Window, WindowBuilder};
use wry::{WebView, WebViewBuilder};

const LOGIN_URL: &str = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F";
const PLAYER_URL: &str = "https://open.spotify.com/";
const YT_LOGIN_URL: &str = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F";
const YT_MUSIC: &str = "https://music.youtube.com/";
/// Google refuses sign-in from browsers it doesn't know; the window presents itself as Firefox
/// (the Tauri app does the same).
const YT_LOGIN_UA: &str = "Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0";

/// Who the YouTube Music page says it is (the Tauri app's YTCFG_PROBE).
const YTCFG_PROBE: &str = r#"(function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA')
    };
  } catch (e) {
    return null;
  }
})()"#;

/// Reports every `/api/token` answer the page receives (fetch or XHR), with the browser's
/// user agent, which the app then sends along with the token.
const HOOK: &str = r#"
(function () {
  if (window.__lflyTokenHook) return;
  window.__lflyTokenHook = true;
  var report = function (body) {
    try { window.ipc.postMessage(JSON.stringify({ ua: navigator.userAgent, body: body })); } catch (e) {}
  };
  var isToken = function (u) { try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; } };
  var originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function (input, init) {
      var url = (input && input.url) ? input.url : input;
      var result = originalFetch.apply(this, arguments);
      if (isToken(url)) {
        try { result.then(function (r) { r.clone().text().then(report).catch(function () {}); }).catch(function () {}); } catch (e) {}
      }
      return result;
    };
  }
  var originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) { this.__lflyUrl = url; return originalOpen.apply(this, arguments); };
  var originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    var xhr = this;
    try { xhr.addEventListener('load', function () { if (isToken(xhr.__lflyUrl)) report(xhr.responseText); }); } catch (e) {}
    return originalSend.apply(this, arguments);
  };
})();
"#;

enum Found {
    Token(String),
    Probe(String),
}

fn main() {
    let mode = std::env::args().nth(1).unwrap_or_default();
    match mode.as_str() {
        "spotify-login" => login(),
        "spotify-token" => token(),
        "ytmusic-login" => ytmusic_login(),
        _ => {
            eprintln!("usage: lfly-web spotify-login | spotify-token | ytmusic-login");
            std::process::exit(64);
        }
    }
}

fn say(line: &str) -> ! {
    let mut out = std::io::stdout();
    let _ = writeln!(out, "{line}");
    let _ = out.flush();
    std::process::exit(0);
}

fn build(builder: WebViewBuilder, window: &Window) -> wry::Result<WebView> {
    #[cfg(any(target_os = "linux", target_os = "dragonfly", target_os = "freebsd", target_os = "netbsd", target_os = "openbsd"))]
    {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        builder.build_gtk(window.default_vbox().expect("gtk box"))
    }
    #[cfg(not(any(target_os = "linux", target_os = "dragonfly", target_os = "freebsd", target_os = "netbsd", target_os = "openbsd")))]
    {
        builder.build(window)
    }
}

fn login() {
    let event_loop = EventLoopBuilder::<Found>::with_user_event().build();
    let window = WindowBuilder::new()
        .with_title("Spotify · LFly")
        .with_inner_size(LogicalSize::new(480.0, 720.0))
        .build(&event_loop)
        .expect("window");
    let webview = build(WebViewBuilder::new().with_incognito(true).with_url(LOGIN_URL), &window).expect("webview");
    let started = Instant::now();
    event_loop.run(move |event, _, flow| {
        *flow = ControlFlow::WaitUntil(Instant::now() + Duration::from_millis(700));
        match event {
            Event::NewEvents(StartCause::ResumeTimeReached { .. }) => {
                let cookie = webview
                    .cookies_for_url(PLAYER_URL)
                    .ok()
                    .and_then(|all| all.into_iter().find(|c| c.name() == "sp_dc" && !c.value().is_empty()));
                if let Some(c) = cookie {
                    say(&serde_json::json!({ "sp_dc": c.value() }).to_string());
                }
                if started.elapsed() > Duration::from_secs(600) {
                    std::process::exit(1);
                }
            }
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => std::process::exit(1),
            _ => {}
        }
    });
}

fn ytmusic_login() {
    let event_loop = EventLoopBuilder::<Found>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let window = WindowBuilder::new()
        .with_title("YouTube Music · LFly")
        .with_inner_size(LogicalSize::new(520.0, 720.0))
        .build(&event_loop)
        .expect("window");
    let webview = build(WebViewBuilder::new().with_incognito(true).with_user_agent(YT_LOGIN_UA).with_url(YT_LOGIN_URL), &window).expect("webview");
    let started = Instant::now();
    let mut asking = false;
    event_loop.run(move |event, _, flow| {
        *flow = ControlFlow::WaitUntil(Instant::now() + Duration::from_millis(1000));
        match event {
            Event::NewEvents(StartCause::ResumeTimeReached { .. }) => {
                // Signed in to Google and on YouTube Music: ask the page who it shows.
                let on_music = webview.url().is_ok_and(|u| u.starts_with(YT_MUSIC));
                let signed = webview
                    .cookies_for_url(YT_MUSIC)
                    .is_ok_and(|all| all.iter().any(|c| (c.name() == "SAPISID" || c.name() == "__Secure-3PAPISID") && !c.value().is_empty()));
                if on_music && signed && !asking {
                    asking = true;
                    let proxy = proxy.clone();
                    let _ = webview.evaluate_script_with_callback(YTCFG_PROBE, move |raw| {
                        let _ = proxy.send_event(Found::Probe(raw));
                    });
                }
                if started.elapsed() > Duration::from_secs(900) {
                    std::process::exit(1);
                }
            }
            Event::UserEvent(Found::Probe(raw)) => {
                asking = false;
                let probe = serde_json::from_str::<serde_json::Value>(&raw).unwrap_or_default();
                if probe.get("loggedIn").and_then(|v| v.as_str()) != Some("true") {
                    return;
                }
                let cookie = webview
                    .cookies_for_url(YT_MUSIC)
                    .map(|all| all.iter().map(|c| format!("{}={}", c.name(), c.value())).collect::<Vec<_>>().join("; "))
                    .unwrap_or_default();
                say(&serde_json::json!({ "cookie": cookie, "probe": probe }).to_string());
            }
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => std::process::exit(1),
            _ => {}
        }
    });
}

fn token() {
    let mut cookie = String::new();
    let _ = std::io::stdin().lock().read_line(&mut cookie);
    let cookie = cookie.trim().to_string();
    if cookie.is_empty() {
        std::process::exit(64);
    }
    let event_loop = EventLoopBuilder::<Found>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let window = WindowBuilder::new()
        .with_title("Spotify · LFly")
        .with_visible(false)
        .with_inner_size(LogicalSize::new(800.0, 600.0))
        .build(&event_loop)
        .expect("window");
    let webview = build(
        WebViewBuilder::new()
            .with_incognito(true)
            .with_initialization_script(HOOK)
            .with_ipc_handler(move |request| {
                let _ = proxy.send_event(Found::Token(request.body().clone()));
            }),
        &window,
    )
    .expect("webview");
    let c = wry::cookie::Cookie::build(("sp_dc", cookie)).domain(".spotify.com").path("/").secure(true).http_only(true).build();
    if let Err(e) = webview.set_cookie(&c) {
        eprintln!("cookie: {e}");
    }
    let started = Instant::now();
    let mut loaded = false;
    event_loop.run(move |event, _, flow| {
        *flow = ControlFlow::WaitUntil(Instant::now() + Duration::from_millis(300));
        match event {
            Event::NewEvents(StartCause::ResumeTimeReached { .. }) => {
                // The cookie goes in first; the player is loaded on the next tick.
                if !loaded {
                    loaded = true;
                    let _ = webview.load_url(PLAYER_URL);
                }
                if started.elapsed() > Duration::from_secs(35) {
                    std::process::exit(2);
                }
            }
            Event::UserEvent(Found::Token(message)) => {
                let Ok(outer) = serde_json::from_str::<serde_json::Value>(&message) else { return };
                let ua = outer.get("ua").and_then(|v| v.as_str()).unwrap_or_default().to_string();
                let Some(body) = outer.get("body").and_then(|v| v.as_str()).and_then(|b| serde_json::from_str::<serde_json::Value>(b).ok()) else {
                    return;
                };
                let token = body.get("accessToken").and_then(|v| v.as_str()).unwrap_or_default();
                // LFLY_WEB_DEBUG=1: what the page answered (never the token itself).
                if std::env::var_os("LFLY_WEB_DEBUG").is_some() {
                    eprintln!("token answer: has token {}, anonymous {:?}", !token.is_empty(), body.get("isAnonymous"));
                }
                // An anonymous token comes first, before the cookie counts: wait for the signed-in one.
                if token.is_empty() || body.get("isAnonymous").and_then(|v| v.as_bool()).unwrap_or(false) {
                    return;
                }
                say(&serde_json::json!({
                    "accessToken": token,
                    "expiresAtMs": body.get("accessTokenExpirationTimestampMs"),
                    "clientId": body.get("clientId"),
                    "userAgent": ua,
                })
                .to_string());
            }
            _ => {}
        }
    });
}
