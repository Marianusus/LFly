//! LFly Connect for the desktop app: the same wire protocol as the phone (`ConnectProtocol.kt`),
//! device discovery over DNS-SD and the remote-control side of a link.

pub mod controller;
pub mod discovery;
pub mod host;
pub mod protocol;

pub use controller::{Controller, ControllerEvent};
pub use discovery::{ConnectDevice, Discovery};
pub use host::{Host, HostEvent, HostPlayer};
pub use protocol::{Device, Message, State, TrackRef, Transfer};
