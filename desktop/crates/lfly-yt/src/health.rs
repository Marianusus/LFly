//! Circuit breaker for stream and lyrics sources (port of the Kotlin `ApiHealthManager`).
//!
//! Each source (YouTube Music, every Invidious and Piped instance, LRCLIB) keeps
//! its status, last latency and failure count. After [`FAILURE_THRESHOLD`]
//! failures in a row the circuit opens for [`COOLDOWN_MS`]; the source is
//! skipped until then and gets one trial call afterwards.

use crate::model::{HealthStatus, SourceHealth, SourceKind};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

/// Failures in a row that open the circuit.
pub const FAILURE_THRESHOLD: u32 = 3;
/// How long an open circuit keeps a source out.
pub const COOLDOWN_MS: u64 = 60_000;
/// Calls slower than this mark the source DEGRADED.
pub const SLOW_MS: u64 = 1_500;

pub(crate) fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// Shared, thread-safe registry of source health.
#[derive(Debug, Clone, Default)]
pub struct HealthRegistry {
    inner: Arc<Mutex<Vec<SourceHealth>>>,
}

fn normalize(url: &str) -> String {
    url.trim().trim_end_matches('/').to_string()
}

impl HealthRegistry {
    /// Adds a source if it is not known yet. New sources start ONLINE.
    pub fn register(&self, name: &str, url: &str, kind: SourceKind) {
        let url = normalize(url);
        let mut list = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if !list.iter().any(|h| h.url == url) {
            list.push(SourceHealth {
                name: name.to_string(),
                url,
                kind,
                status: HealthStatus::Online,
                latency_ms: None,
                consecutive_failures: 0,
                open_until_ms: None,
                last_checked_ms: None,
                last_error: None,
            });
        }
    }

    /// Snapshot of every source.
    pub fn snapshot(&self) -> Vec<SourceHealth> {
        let now = now_ms();
        let mut list = self.inner.lock().unwrap_or_else(|e| e.into_inner()).clone();
        for h in &mut list {
            if h.open_until_ms.is_some_and(|t| t <= now) {
                h.open_until_ms = None;
            }
        }
        list
    }

    /// False while the source's circuit is open.
    pub fn is_available(&self, url: &str) -> bool {
        let url = normalize(url);
        let now = now_ms();
        let list = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        list.iter().find(|h| h.url == url).is_none_or(|h| h.open_until_ms.is_none_or(|t| t <= now))
    }

    /// Records a successful call: closes the circuit and stores the latency.
    pub fn record_success(&self, url: &str, latency_ms: u64) {
        self.update(url, |h, now| {
            h.status = if latency_ms > SLOW_MS { HealthStatus::Degraded } else { HealthStatus::Online };
            h.latency_ms = Some(latency_ms);
            h.consecutive_failures = 0;
            h.open_until_ms = None;
            h.last_checked_ms = Some(now);
            h.last_error = None;
        });
    }

    /// Records a failed call (error, bad status, timeout, unplayable url).
    pub fn record_failure(&self, url: &str, error: &str) {
        self.update(url, |h, now| {
            h.consecutive_failures += 1;
            h.last_checked_ms = Some(now);
            h.last_error = Some(error.to_string());
            if h.consecutive_failures >= FAILURE_THRESHOLD {
                h.status = HealthStatus::Offline;
                h.open_until_ms = Some(now + COOLDOWN_MS);
                log::warn!("circuit open for {} after {} failures: {error}", h.name, h.consecutive_failures);
            } else {
                h.status = HealthStatus::Degraded;
            }
        });
    }

    fn update(&self, url: &str, f: impl FnOnce(&mut SourceHealth, u64)) {
        let url = normalize(url);
        let now = now_ms();
        let mut list = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(h) = list.iter_mut().find(|h| h.url == url) {
            f(h, now);
        }
    }

    /// Urls of one kind, best first: closed circuits before open ones, then
    /// ONLINE, DEGRADED, OFFLINE, then lower latency. Registration order breaks ties.
    pub fn ordered(&self, kind: SourceKind) -> Vec<String> {
        let now = now_ms();
        let list = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let mut items: Vec<(usize, &SourceHealth)> = list.iter().filter(|h| h.kind == kind).enumerate().collect();
        items.sort_by_key(|(i, h)| {
            let open = h.open_until_ms.is_some_and(|t| t > now);
            let status = match h.status {
                HealthStatus::Online => 0,
                HealthStatus::Degraded => 1,
                HealthStatus::Offline => 2,
            };
            (open, status, h.latency_ms.unwrap_or(u64::MAX / 2), *i)
        });
        items.into_iter().map(|(_, h)| h.url.clone()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opens_after_three_failures_and_closes_on_success() {
        let reg = HealthRegistry::default();
        reg.register("A", "https://a.example/", SourceKind::Invidious);
        reg.register("B", "https://b.example", SourceKind::Invidious);
        for _ in 0..2 {
            reg.record_failure("https://a.example", "HTTP 503");
        }
        assert!(reg.is_available("https://a.example"));
        assert_eq!(reg.snapshot()[0].status, HealthStatus::Degraded);
        reg.record_failure("https://a.example", "HTTP 503");
        assert!(!reg.is_available("https://a.example"));
        assert_eq!(reg.snapshot()[0].status, HealthStatus::Offline);
        assert_eq!(reg.ordered(SourceKind::Invidious), ["https://b.example", "https://a.example"]);
        reg.record_success("https://a.example", 2_000);
        assert!(reg.is_available("https://a.example"));
        let a = &reg.snapshot()[0];
        assert_eq!((a.status, a.latency_ms, a.consecutive_failures), (HealthStatus::Degraded, Some(2_000), 0));
    }
}
