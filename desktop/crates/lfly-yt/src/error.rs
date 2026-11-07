//! The crate's error type.

/// Everything that can go wrong while talking to YouTube Music, the
/// fallback stream sources or LRCLIB.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// The request never got an answer (DNS, TLS, connection reset, ...).
    #[error("network error: {0}")]
    Http(#[from] reqwest::Error),
    /// The server answered with a non-success status code.
    #[error("HTTP {status} from {endpoint}")]
    Status { status: u16, endpoint: String },
    /// The body was not the JSON we expected.
    #[error("invalid JSON: {0}")]
    Json(#[from] serde_json::Error),
    /// The caller passed something unusable (blank id, bad video id, ...).
    #[error("invalid input: {0}")]
    InvalidInput(String),
    /// The response arrived but did not contain the page we asked for.
    #[error("could not read the {0} page")]
    Parse(&'static str),
    /// Every source was tried and none returned a full, playable stream.
    #[error("no playable stream for {0}")]
    NoStream(String),
    /// A source took longer than its time budget.
    #[error("timed out: {0}")]
    Timeout(String),
}

/// Short alias used across the crate.
pub type Result<T> = std::result::Result<T, Error>;
