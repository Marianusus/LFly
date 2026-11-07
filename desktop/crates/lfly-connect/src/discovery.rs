//! Finding the other LFly devices on the network (DNS-SD, as Android's NSD announces them) and,
//! once this computer can play, announcing it.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};

use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};

use crate::protocol::{self, Device};

/// Another LFly on the network, as the device picker shows it.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectDevice {
    pub id: String,
    pub name: String,
    pub kind: String,
    /// Signed into the same account (by the public tag; proven on connect).
    pub same_account: bool,
    pub host: String,
    pub port: u16,
}

type OnChange = Arc<dyn Fn(Vec<ConnectDevice>) + Send + Sync>;

pub struct Discovery {
    daemon: ServiceDaemon,
    devices: Arc<Mutex<HashMap<String, ConnectDevice>>>,
    /// Each device's public account tag, to tell again who shares the account when ours changes.
    tags: Arc<Mutex<HashMap<String, Option<String>>>>,
    owner: Arc<Mutex<Option<String>>>,
    on_change: Arc<Mutex<Option<OnChange>>>,
    announced: Mutex<Option<String>>,
}

impl Discovery {
    pub fn new() -> Result<Self, String> {
        let daemon = ServiceDaemon::new().map_err(|e| e.to_string())?;
        Ok(Self {
            daemon,
            devices: Arc::default(),
            tags: Arc::default(),
            owner: Arc::default(),
            on_change: Arc::default(),
            announced: Mutex::new(None),
        })
    }

    /// The devices seen so far, by name.
    pub fn devices(&self) -> Vec<ConnectDevice> {
        sorted(&self.devices.lock().unwrap())
    }

    /// Keeps looking for devices; [on_change] gets the whole list after every change.
    pub fn browse(
        &self,
        me: &Device,
        on_change: impl Fn(Vec<ConnectDevice>) + Send + Sync + 'static,
    ) -> Result<(), String> {
        let receiver = self.daemon.browse(protocol::SERVICE_TYPE).map_err(|e| e.to_string())?;
        let devices = self.devices.clone();
        let tags = self.tags.clone();
        let owner = self.owner.clone();
        *owner.lock().unwrap() = me.owner.clone();
        let on_change: OnChange = Arc::new(on_change);
        *self.on_change.lock().unwrap() = Some(on_change.clone());
        let my_id = me.id.clone();
        std::thread::Builder::new()
            .name("lfly-connect-browse".into())
            .spawn(move || {
                // Full service name -> device id, to forget a device when its service goes away.
                let mut names: HashMap<String, String> = HashMap::new();
                while let Ok(event) = receiver.recv() {
                    match event {
                        ServiceEvent::ServiceResolved(info) => {
                            let my_owner = owner.lock().unwrap().clone();
                            let Some((device, tag)) = device_of(&info, &my_id, my_owner.as_deref()) else { continue };
                            tags.lock().unwrap().insert(device.id.clone(), tag);
                            names.insert(info.get_fullname().to_string(), device.id.clone());
                            let mut map = devices.lock().unwrap();
                            if map.get(&device.id) == Some(&device) {
                                continue;
                            }
                            log::info!("Found '{}' at {}:{}", device.name, device.host, device.port);
                            map.insert(device.id.clone(), device);
                            on_change(sorted(&map));
                        }
                        ServiceEvent::ServiceRemoved(_, fullname) => {
                            let Some(id) = names.remove(&fullname) else { continue };
                            let mut map = devices.lock().unwrap();
                            if map.remove(&id).is_some() {
                                on_change(sorted(&map));
                            }
                        }
                        ServiceEvent::SearchStopped(_) => break,
                        _ => {}
                    }
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    /// Our account changed: devices on the new one are marked as such, the rest no longer.
    pub fn set_owner(&self, owner: Option<String>) {
        *self.owner.lock().unwrap() = owner.clone();
        let list = {
            let tags = self.tags.lock().unwrap();
            let mut map = self.devices.lock().unwrap();
            for d in map.values_mut() {
                let tag = tags.get(&d.id).cloned().flatten();
                d.same_account = owner.is_some() && tag == owner;
            }
            sorted(&map)
        };
        if let Some(notify) = self.on_change.lock().unwrap().clone() {
            notify(list);
        }
    }

    /// Announces this computer as a device others can see and control, serving on [port]. Called
    /// again (say, with a new account tag), it replaces the previous announcement.
    pub fn announce(&self, me: &Device, port: u16) -> Result<(), String> {
        if let Some(old) = self.announced.lock().unwrap().take() {
            let _ = self.daemon.unregister(&old);
        }
        let host = format!("{}.local.", sanitize(&hostname()));
        let instance = format!("LFly {}", me.name);
        let mut properties = vec![
            (protocol::TXT_ID, me.id.clone()),
            (protocol::TXT_NAME, me.name.clone()),
            (protocol::TXT_KIND, me.kind.clone()),
        ];
        if let Some(owner) = &me.owner {
            properties.push((protocol::TXT_OWNER, owner.clone()));
        }
        let info = ServiceInfo::new(protocol::SERVICE_TYPE, &instance, &host, "", port, &properties[..])
            .map_err(|e| e.to_string())?
            .enable_addr_auto();
        let fullname = info.get_fullname().to_string();
        self.daemon.register(info).map_err(|e| e.to_string())?;
        *self.announced.lock().unwrap() = Some(fullname);
        Ok(())
    }
}

impl Drop for Discovery {
    fn drop(&mut self) {
        if let Some(name) = self.announced.lock().unwrap().take() {
            let _ = self.daemon.unregister(&name);
        }
        let _ = self.daemon.shutdown();
    }
}

fn device_of(info: &mdns_sd::ResolvedService, my_id: &str, my_owner: Option<&str>) -> Option<(ConnectDevice, Option<String>)> {
    let txt = |key: &str| info.get_property_val_str(key).map(str::to_string);
    let id = txt(protocol::TXT_ID)?;
    if id == my_id {
        return None;
    }
    // IPv4 first: phones answer on it reliably; a link-local IPv6 needs a scope to be reachable.
    let mut addresses: Vec<IpAddr> = info.get_addresses().iter().map(|a| a.to_ip_addr()).collect();
    addresses.sort_by_key(|a| !a.is_ipv4());
    let host = addresses.first()?.to_string();
    let instance = info.get_fullname().split("._lfly-connect").next().unwrap_or_default();
    let tag = txt(protocol::TXT_OWNER);
    Some((ConnectDevice {
        name: txt(protocol::TXT_NAME).unwrap_or_else(|| instance.trim_start_matches("LFly ").to_string()),
        kind: txt(protocol::TXT_KIND).unwrap_or_else(|| protocol::KIND_PHONE.to_string()),
        same_account: my_owner.is_some() && tag.as_deref() == my_owner,
        id,
        host,
        port: info.get_port(),
    }, tag))
}

fn sorted(map: &HashMap<String, ConnectDevice>) -> Vec<ConnectDevice> {
    let mut list: Vec<_> = map.values().cloned().collect();
    list.sort_by_key(|d| d.name.to_lowercase());
    list
}

/// This computer's name, as the other devices list it.
pub fn hostname() -> String {
    Some(gethostname::gethostname().to_string_lossy().trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "LFly Desktop".to_string())
}

fn sanitize(name: &str) -> String {
    let s: String = name.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '-' }).collect();
    if s.is_empty() { "lfly".into() } else { s }
}
