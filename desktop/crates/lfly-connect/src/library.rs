//! Library sync between devices of the same account over LFly Connect: a short link of its
//! own (not the remote-control one), opened by the device that wants to sync. It proves the
//! account like any link, offers its library and gets the merged one back, then closes.
//! Devices of another account are never asked: they would show the owner a pairing code.

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::tungstenite;

use crate::discovery::ConnectDevice;
use crate::protocol::{self, Device, Message};

/// Offers `library` to `device` and returns what it merged, or why not.
pub async fn exchange(me: Device, account_secret: &str, device: &ConnectDevice, library: serde_json::Value) -> Result<serde_json::Value, String> {
    let host = if device.host.contains(':') { format!("[{}]", device.host) } else { device.host.clone() };
    let url = format!("ws://{host}:{}", device.port);
    let key = protocol::account_key(account_secret);
    let run = async {
        let (mut socket, _) = tokio_tungstenite::connect_async(&url).await.map_err(|e| e.to_string())?;
        let send = |m: Message| tungstenite::Message::text(m.encode());
        socket.send(send(Message::Hello { device: me, protocol: protocol::VERSION })).await.map_err(|e| e.to_string())?;
        let mut offer = Some(library);
        while let Some(frame) = socket.next().await {
            let tungstenite::Message::Text(text) = frame.map_err(|e| e.to_string())? else { continue };
            match Message::decode(text.as_str()) {
                Some(Message::Challenge { nonce, .. }) => {
                    let hmac = Some(protocol::proof(&key, &nonce));
                    socket.send(send(Message::Proof { hmac })).await.map_err(|e| e.to_string())?;
                }
                Some(Message::PairPending { .. }) => {
                    let _ = socket.close(None).await;
                    return Err("non è dello stesso account".to_string());
                }
                Some(Message::Welcome { accepted: false }) => return Err("rifiutato".to_string()),
                Some(Message::Welcome { accepted: true }) => {
                    if let Some(library) = offer.take() {
                        socket.send(send(Message::Library { library, reply: false })).await.map_err(|e| e.to_string())?;
                    }
                }
                Some(Message::Library { library, reply: true }) => {
                    let _ = socket.close(None).await;
                    return Ok(library);
                }
                _ => {}
            }
        }
        Err("collegamento chiuso".to_string())
    };
    tokio::time::timeout(Duration::from_secs(20), run).await.map_err(|_| "nessuna risposta".to_string())?
}
