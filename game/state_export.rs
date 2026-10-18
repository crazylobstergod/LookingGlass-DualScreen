//! Live state export for a companion HUD on a second display.
//!
//! The game loop publishes a cheap snapshot each frame ([`publish`]); a single
//! background thread serves the latest snapshot as newline-delimited JSON to
//! any local client (e.g. a dash-screen companion app) on `127.0.0.1`.
//!
//! The transport is intentionally dumb and dependency-free: loopback TCP, one
//! JSON object per line, latest-wins. The companion app always renders the
//! most recent line it has received, so a dropped or slow frame is harmless.
//!
//! Enabled on Android by default, or anywhere via `LOOKINGGLASS_EXPORT=1`.
//! The port can be overridden with `LOOKINGGLASS_EXPORT_PORT` (default 4781).

use serde::Serialize;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const DEFAULT_PORT: u16 = 4781;
const TICK_MS: u64 = 20; // ~50 Hz is plenty for a HUD

/// One frame of player state, shaped for the companion HUD.
#[derive(Clone, Serialize)]
pub struct Snapshot {
    /// Monotonic frame counter (for the client to detect fresh data).
    pub t: u64,
    /// Current map name — drives the companion's minimap.
    pub map: String,
    /// Player eye position in world space.
    pub pos: [f32; 3],
    /// Facing angle (radians), for orienting the minimap.
    pub yaw: f32,
    /// Health (0..=max) and will (0..=100).
    pub health: f32,
    pub will: f32,
    pub alive: bool,
    /// Equipped weapon index (None when unarmed) and the selected slot.
    pub weapon: Option<usize>,
    pub selected: usize,
    /// Human-readable name of the selected weapon ("" when unarmed).
    pub weapon_name: String,
    /// Copies owned per weapon slot (slot 6 = Demon Dice, up to 3).
    pub owned: [u8; 10],
    /// Power-up timers: [rage, tea, stopped, recharge].
    pub powers: [f32; 4],
    /// Inventory item names currently held.
    pub inventory: Vec<String>,
}

static CURRENT: Mutex<Option<Snapshot>> = Mutex::new(None);
static EQUIP: Mutex<Option<(usize, String, Instant)>> = Mutex::new(None);

/// Consumed once per game frame; never retain a tap through pause/map transitions.
pub fn take_equip(map: &str) -> Option<usize> {
    EQUIP.lock().unwrap().take().and_then(|(slot, requested_map, at)| {
        (requested_map == map && at.elapsed() < Duration::from_millis(500)).then_some(slot)
    })
}

fn parse_equip(line: &[u8], state: &Snapshot) -> Option<usize> {
    let command: serde_json::Value = serde_json::from_slice(line).ok()?;
    let slot = usize::try_from(command.get("equip")?.as_u64()?).ok()?;
    (command.get("map")?.as_str()? == state.map && state.alive
        && state.owned.get(slot).copied().unwrap_or(0) > 0).then_some(slot)
}

struct Client { stream: TcpStream, input: Vec<u8> }
impl Client {
    fn receive(&mut self) -> bool {
        let mut bytes = [0u8; 512];
        // Bound work and message size, including for a broken local client.
        for _ in 0..8 {
            match self.stream.read(&mut bytes) {
                Ok(0) => return false,
                Ok(n) => {
                    self.input.extend_from_slice(&bytes[..n]);
                    while let Some(end) = self.input.iter().position(|&b| b == b'\n') {
                        if end > 512 { return false; }
                        let line: Vec<_> = self.input.drain(..=end).collect();
                        if let Some(state) = CURRENT.lock().unwrap().as_ref() {
                            if let Some(slot) = parse_equip(&line, state) {
                                *EQUIP.lock().unwrap() = Some((slot, state.map.clone(), Instant::now()));
                            }
                        }
                    }
                    if self.input.len() > 512 { return false; }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(_) => return false,
            }
        }
        true
    }
}

static STARTED: AtomicBool = AtomicBool::new(false);

/// Publish a snapshot from the game loop. Cheap: one mutex write, no I/O.
pub fn publish(snapshot: Snapshot) {
    *CURRENT.lock().unwrap() = Some(snapshot);
}

/// Start the background server. Idempotent; safe to call more than once.
/// Returns `true` if the server thread was (or already was) started.
pub fn start() -> bool {
    if STARTED.swap(true, Ordering::SeqCst) {
        return true;
    }
    let port = std::env::var("LOOKINGGLASS_EXPORT_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(DEFAULT_PORT);
    std::thread::Builder::new()
        .name("state-export".into())
        .spawn(move || server_loop(port))
        .ok();
    true
}

/// Should the export be active in this build/runtime?
pub fn enabled() -> bool {
    cfg!(target_os = "android")
        || std::env::var("LOOKINGGLASS_EXPORT").map(|v| v == "1" || v == "true").unwrap_or(false)
}

fn server_loop(port: u16) {
    let listener = match TcpListener::bind(("127.0.0.1", port)) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("state_export: could not bind 127.0.0.1:{port}: {e}");
            return;
        }
    };
    let _ = listener.set_nonblocking(true);
    eprintln!("state_export: serving latest state on 127.0.0.1:{port} (ndjson)");

    let clients: Arc<Mutex<Vec<Client>>> = Arc::new(Mutex::new(Vec::new()));
    loop {
        // Accept any new companion connection.
        while let Ok((stream, _addr)) = listener.accept() {
            let _ = stream.set_nonblocking(true);
            clients.lock().unwrap().push(Client { stream, input: Vec::new() });
        }

        clients.lock().unwrap().retain_mut(|c| c.receive());

        // Push the latest snapshot to every client.
        if let Some(snapshot) = CURRENT.lock().unwrap().clone() {
            if let Ok(json) = serde_json::to_string(&snapshot) {
                let line = format!("{json}\n");
                let line = line.as_bytes();
                clients.lock().unwrap().retain_mut(|c| send_line(&mut c.stream, line));
            }
        }

        std::thread::sleep(Duration::from_millis(TICK_MS));
    }
}

/// Write one line to a non-blocking stream. Returns `false` if the client is
/// gone or stuck (so it can be dropped). Partial writes are left for the next
/// tick; the companion only ever renders the last complete line it parsed.
fn send_line(stream: &mut TcpStream, line: &[u8]) -> bool {
    let mut written = 0usize;
    while written < line.len() {
        match stream.write(&line[written..]) {
            Ok(0) => return false,
            Ok(n) => written += n,
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => return written == 0,
            Err(_) => return false,
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    #[test]
    fn roundtrip_serves_latest_snapshot() {
        // Use a dedicated port so this never collides with a running game.
        let port: u16 = 4799;
        std::env::set_var("LOOKINGGLASS_EXPORT_PORT", port.to_string());
        start();
        // Give the server thread a moment to bind.
        std::thread::sleep(Duration::from_millis(150));

        publish(Snapshot {
            t: 42,
            map: "gvillage".into(),
            pos: [1.0, 2.0, 3.0],
            yaw: 0.5,
            health: 87.0,
            will: 50.0,
            alive: true,
            weapon: Some(0),
            selected: 0,
            weapon_name: "Vorpal Blade".into(),
            owned: [1, 0, 0, 0, 0, 0, 2, 0, 0, 0],
            powers: [0.0, 0.0, 0.0, 1.5],
            inventory: vec!["bronze key".into()],
        });

        let mut stream = TcpStream::connect(("127.0.0.1", port)).expect("connect");
        let mut buf = [0u8; 4096];
        let n = stream.read(&mut buf).expect("read");
        let text = String::from_utf8_lossy(&buf[..n]).to_string();
        assert!(text.contains("\"t\":42"), "got: {text}");
        assert!(text.contains("\"map\":\"gvillage\""), "got: {text}");
        assert!(text.contains("\"health\":87.0"), "got: {text}");
        assert!(text.contains("\"weapon_name\":\"Vorpal Blade\""), "got: {text}");
        assert!(text.contains("\"bronze key\""), "got: {text}");
    }
}
