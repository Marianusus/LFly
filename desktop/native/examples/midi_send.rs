//! A pretend console for test runs (`LFLY_MIDI_TEST`): plugs in a virtual MIDI output port, sends
//! messages from it, then unplugs (Linux).
//!
//!     midi_send <port name> <seconds>:<hex bytes>[ <seconds>:<hex bytes>…] [hold:<seconds>]
//!     e.g. midi_send lfly-test-console 3:900b7f 3.2:900b00 hold:5
//!
//! Each message goes out that many seconds after the port appears; `hold` keeps it plugged in
//! until then.

#[cfg(unix)]
fn main() {
    use midir::os::unix::VirtualOutput;
    let mut args = std::env::args().skip(1);
    let name = args.next().expect("a port name");
    let out = midir::MidiOutput::new("midi-send").expect("MIDI output");
    let mut conn = out.create_virtual(&name).expect("virtual port");
    println!("plugged in {name}");
    let start = std::time::Instant::now();
    let wait_until = |secs: f64| {
        if let Some(wait) = std::time::Duration::from_secs_f64(secs).checked_sub(start.elapsed()) {
            std::thread::sleep(wait);
        }
    };
    for arg in args {
        let (when, what) = arg.split_once(':').expect("<seconds>:<hex>");
        if when == "hold" {
            wait_until(what.parse().expect("seconds"));
            continue;
        }
        wait_until(when.parse().expect("seconds"));
        let bytes: Vec<u8> = (0..what.len()).step_by(2).map(|i| u8::from_str_radix(&what[i..i + 2], 16).expect("hex")).collect();
        conn.send(&bytes).expect("send");
        println!("sent {what} at {when} s");
    }
    println!("unplugged {name}");
}

#[cfg(not(unix))]
fn main() {
    eprintln!("virtual MIDI ports exist on Linux only");
}
