//! C ABI over an in-process Arti Tor client. Mirrored by `include/torkmp.h`.
//!
//! Conventions:
//! - Every call is blocking; the Kotlin side runs them off the main thread.
//! - Failure returns NULL or a negative number; `torkmp_last_error` explains it
//!   and must be read on the same thread, right after the failing call.
//! - Handles are freed exactly once. The Kotlin wrapper guarantees no call is in
//!   flight when it frees one.

#[cfg(feature = "jni")]
mod java;
mod socks;

use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{c_char, CStr, CString};
use std::sync::{Arc, Mutex};

use std::sync::atomic::{AtomicU64, Ordering};

use arti_client::config::pt::TransportConfigBuilder;
use arti_client::config::{BridgeConfigBuilder, CfgPath, TorClientConfigBuilder};
use arti_client::{
    BootstrapBehavior, DataStream, ErrorKind, HasKind, IsolationToken, StreamPrefs, TorClient,
};
use futures::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use futures_rustls::rustls::pki_types::ServerName;
use futures_rustls::rustls::{ClientConfig, RootCertStore};
use futures_rustls::TlsConnector;
use serde::Deserialize;
use tokio::runtime::Runtime;
use tokio::sync::watch;
use tor_rtcompat::PreferredRuntime;

type Client = TorClient<PreferredRuntime>;

/// A running Tor client plus the runtime that drives it.
pub struct TorKmp {
    runtime: Arc<Runtime>,
    circuits: Arc<Circuits>,
    socks: Mutex<Option<socks::SocksServer>>,
    /// Present while `torkmp_bootstrap` runs; `torkmp_cancel_bootstrap` fires it.
    running_bootstrap: Mutex<Option<watch::Sender<bool>>>,
}

/// Which client and isolation group a new stream goes through. Shared with the
/// SOCKS proxy so both paths honour `torkmp_new_identity`.
pub(crate) struct Circuits {
    /// Swapped by `torkmp_new_identity`; streams opened afterwards never share
    /// circuits with streams opened before.
    current: Mutex<Arc<Client>>,
    isolation_groups: Mutex<HashMap<String, IsolationToken>>,
    /// SOCKS CONNECTs that carried an IP address instead of a hostname: the
    /// client resolved DNS itself, outside Tor.
    pub(crate) socks_ip_connects: AtomicU64,
}

type Reader = Box<dyn AsyncRead + Send + Unpin>;
type Writer = Box<dyn AsyncWrite + Send + Unpin>;

/// One Tor stream (plain or TLS), split so a blocked read never stalls a write.
pub struct TorKmpStream {
    runtime: Arc<Runtime>,
    reader: tokio::sync::Mutex<Reader>,
    writer: tokio::sync::Mutex<Writer>,
    shutdown: watch::Sender<bool>,
}

impl TorKmpStream {
    fn new(runtime: Arc<Runtime>, reader: Reader, writer: Writer) -> *mut Self {
        Box::into_raw(Box::new(Self {
            runtime,
            reader: tokio::sync::Mutex::new(reader),
            writer: tokio::sync::Mutex::new(writer),
            shutdown: watch::channel(false).0,
        }))
    }
}

/// TLS client settings shared by every `torkmp_connect_tls`: Mozilla's root
/// store, rustls defaults (TLS 1.2 and 1.3), HTTP/1.1 offered via ALPN.
fn tls_connector() -> TlsConnector {
    static CONFIG: std::sync::OnceLock<Arc<ClientConfig>> = std::sync::OnceLock::new();
    let config = CONFIG.get_or_init(|| {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let mut config = ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        config.alpn_protocols = vec![b"http/1.1".to_vec()];
        Arc::new(config)
    });
    TlsConnector::from(config.clone())
}

thread_local! {
    static LAST_ERROR: RefCell<Option<CString>> = const { RefCell::new(None) };
}

fn set_error(message: impl std::fmt::Display) {
    let text = CString::new(message.to_string().replace('\0', " ")).unwrap_or_default();
    LAST_ERROR.with(|slot| *slot.borrow_mut() = Some(text));
}

/// The error and every cause beneath it, joined with ": ", so a failure deep in
/// Arti (a locked state file, a refused socket, an unreadable directory) names
/// its root cause instead of only the layer that wrapped it. A cause whose text
/// its parent already ends with (the common `"context: {source}"` style) is not
/// repeated.
fn chain(error: &dyn std::error::Error) -> String {
    let mut text = error.to_string();
    let mut cause = error.source();
    while let Some(next) = cause {
        let part = next.to_string();
        if !text.ends_with(&part) {
            text.push_str(": ");
            text.push_str(&part);
        }
        cause = next.source();
    }
    text
}

/// Reads a required C string argument, recording an error when it is unusable.
unsafe fn arg_str<'a>(ptr: *const c_char, name: &str) -> Option<&'a str> {
    if ptr.is_null() {
        set_error(format!("{name} is null"));
        return None;
    }
    match CStr::from_ptr(ptr).to_str() {
        Ok(text) => Some(text),
        Err(_) => {
            set_error(format!("{name} is not valid UTF-8"));
            None
        }
    }
}

impl Circuits {
    fn client(&self) -> Arc<Client> {
        self.current.lock().unwrap().clone()
    }

    fn prefs_for(&self, isolation: Option<&str>) -> StreamPrefs {
        let mut prefs = StreamPrefs::new();
        if let Some(key) = isolation {
            let token = *self
                .isolation_groups
                .lock()
                .unwrap()
                .entry(key.to_owned())
                .or_insert_with(IsolationToken::new);
            prefs.set_isolation(token);
        }
        prefs
    }

    /// Opens a stream, retrying on a fresh circuit when the exit does not answer
    /// in time. Arti makes one attempt per call and keeps the slow circuit, so
    /// without this a congested bridge or exit fails every connect for a while;
    /// C Tor moves such streams to another circuit, and so does this. A retried
    /// stream gets a circuit of its own rather than its isolation group's: more
    /// isolated than asked for, never less.
    pub(crate) async fn open(
        &self,
        host: &str,
        port: u16,
        isolation: Option<&str>,
    ) -> arti_client::Result<DataStream> {
        let client = self.client();
        let mut prefs = self.prefs_for(isolation);
        let mut attempt = 0;
        loop {
            match client.connect_with_prefs((host, port), &prefs).await {
                Err(error)
                    if attempt < EXIT_TIMEOUT_RETRIES
                        && error.kind() == ErrorKind::RemoteNetworkTimeout =>
                {
                    attempt += 1;
                    // A token nothing else holds cannot land on the circuit that just stalled.
                    prefs.set_isolation(IsolationToken::new());
                }
                result => return result,
            }
        }
    }
}

/// Extra attempts after a stream times out at the exit, each on a new circuit.
const EXIT_TIMEOUT_RETRIES: u32 = 2;

/// Installs a stderr logger for Arti's tracing output when `TORKMP_LOG` names a
/// level (`error`, `warn`, `info`, `debug`, `trace`). Off by default: the output
/// is for diagnosing a stuck bootstrap, not for production, and it can name
/// relays and timings a release build should keep to itself. Runs once.
fn init_logging() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let Ok(level) = std::env::var("TORKMP_LOG") else {
            return;
        };
        let Ok(level) = level.parse::<tracing::level_filters::LevelFilter>() else {
            eprintln!(
                "torkmp: TORKMP_LOG={level:?} is not a level (error, warn, info, debug, trace)"
            );
            return;
        };
        let _ = tracing_subscriber::fmt()
            .with_max_level(level)
            .with_writer(std::io::stderr)
            .with_ansi(false)
            .try_init();
    });
}

/// Optional settings for `torkmp_create`, as the Kotlin side serialises them.
#[derive(Deserialize, Default)]
struct Settings {
    /// Bridge lines, with or without the leading `Bridge` keyword.
    #[serde(default)]
    bridges: Vec<String>,
    /// Pluggable-transport clients Arti may launch for the bridges above.
    #[serde(default)]
    transports: Vec<TransportSettings>,
}

#[derive(Deserialize)]
struct TransportSettings {
    /// Transport names the executable serves, e.g. `obfs4`.
    protocols: Vec<String>,
    /// Absolute path of the executable.
    path: String,
    #[serde(default)]
    arguments: Vec<String>,
}

/// Applies a `Settings` JSON document to `builder`.
fn apply_settings(builder: &mut TorClientConfigBuilder, json: &str) -> Result<(), String> {
    let settings: Settings =
        serde_json::from_str(json).map_err(|error| format!("invalid settings: {error}"))?;
    for line in &settings.bridges {
        let bridge = parse_bridge(line)?;
        builder.bridges().bridges().push(bridge);
    }
    for transport in &settings.transports {
        let protocols = transport
            .protocols
            .iter()
            .map(|name| {
                name.parse()
                    .map_err(|error| format!("invalid transport name {name:?}: {error}"))
            })
            .collect::<Result<Vec<_>, String>>()?;
        let mut config = TransportConfigBuilder::default();
        config
            .protocols(protocols)
            .path(CfgPath::new(transport.path.clone()))
            .arguments(transport.arguments.clone())
            .run_on_startup(false);
        builder.bridges().transports().push(config);
    }
    Ok(())
}

/// Parses and validates one bridge line.
fn parse_bridge(line: &str) -> Result<BridgeConfigBuilder, String> {
    let bridge: BridgeConfigBuilder = line
        .parse()
        .map_err(|error| format!("invalid bridge line: {error}"))?;
    bridge
        .build()
        .map_err(|error| format!("invalid bridge line: {error}"))?;
    Ok(bridge)
}

/// Checks a bridge line without creating a client. Returns NULL when it is
/// valid, otherwise the reason; free it with `torkmp_string_free`.
#[no_mangle]
pub unsafe extern "C" fn torkmp_bridge_check(line: *const c_char) -> *mut c_char {
    let Some(line) = arg_str(line, "bridge") else {
        return torkmp_last_error();
    };
    match parse_bridge(line) {
        Ok(_) => std::ptr::null_mut(),
        Err(message) => CString::new(message.replace('\0', " "))
            .map(CString::into_raw)
            .unwrap_or(std::ptr::null_mut()),
    }
}

/// Fills `buffer` from the operating system's random source. Returns 0, or -1
/// if the OS refused.
#[no_mangle]
pub unsafe extern "C" fn torkmp_random_bytes(buffer: *mut u8, len: usize) -> i32 {
    let buffer = std::slice::from_raw_parts_mut(buffer, len);
    match getrandom::fill(buffer) {
        Ok(()) => 0,
        Err(error) => {
            set_error(format!("OS randomness unavailable: {error}"));
            -1
        }
    }
}

/// Creates a client that keeps its consensus in `cache_dir` and its guards and
/// keys in `state_dir`. `settings` is an optional JSON document (see `Settings`)
/// with bridges and pluggable transports; NULL means the public network.
/// Does not touch the network until `torkmp_bootstrap`.
#[no_mangle]
pub unsafe extern "C" fn torkmp_create(
    state_dir: *const c_char,
    cache_dir: *const c_char,
    settings: *const c_char,
) -> *mut TorKmp {
    init_logging();
    let (Some(state_dir), Some(cache_dir)) = (
        arg_str(state_dir, "state_dir"),
        arg_str(cache_dir, "cache_dir"),
    ) else {
        return std::ptr::null_mut();
    };
    let settings = if settings.is_null() {
        None
    } else {
        match arg_str(settings, "settings") {
            Some(json) => Some(json),
            None => return std::ptr::null_mut(),
        }
    };
    // Errs only when a provider is already installed, which is fine.
    let _ = rustls::crypto::ring::default_provider().install_default();
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("tor-kmp")
        .build()
    {
        Ok(runtime) => Arc::new(runtime),
        Err(error) => {
            set_error(format!("could not start runtime: {}", chain(&error)));
            return std::ptr::null_mut();
        }
    };
    let mut builder = TorClientConfigBuilder::from_directories(state_dir, cache_dir);
    if let Some(json) = settings {
        if let Err(message) = apply_settings(&mut builder, json) {
            set_error(message);
            return std::ptr::null_mut();
        }
    }
    let config = match builder.build() {
        Ok(config) => config,
        Err(error) => {
            set_error(format!("invalid configuration: {}", chain(&error)));
            return std::ptr::null_mut();
        }
    };
    // PreferredRuntime binds to the tokio runtime it is created inside.
    let client = runtime.block_on(async {
        TorClient::builder()
            .config(config)
            .bootstrap_behavior(BootstrapBehavior::Manual)
            .create_unbootstrapped()
    });
    match client {
        Ok(client) => Box::into_raw(Box::new(TorKmp {
            runtime,
            circuits: Arc::new(Circuits {
                current: Mutex::new(client),
                isolation_groups: Mutex::new(HashMap::new()),
                socks_ip_connects: AtomicU64::new(0),
            }),
            socks: Mutex::new(None),
            running_bootstrap: Mutex::new(None),
        })),
        Err(error) => {
            set_error(format!("could not create client: {}", chain(&error)));
            std::ptr::null_mut()
        }
    }
}

/// Connects to the network and downloads a usable directory. Returns 0 when
/// ready for traffic, -1 on failure, -2 if `torkmp_cancel_bootstrap` stopped it.
/// Progress is observable meanwhile through `torkmp_status`.
#[no_mangle]
pub unsafe extern "C" fn torkmp_bootstrap(tor: *const TorKmp) -> i32 {
    let tor = &*tor;
    let client = tor.circuits.client();
    let (cancel, mut cancelled) = watch::channel(false);
    *tor.running_bootstrap.lock().unwrap() = Some(cancel);
    let outcome = tor.runtime.block_on(async {
        tokio::select! {
            result = client.bootstrap() => match result {
                Ok(()) => 0,
                Err(error) => {
                    set_error(format!("bootstrap failed: {}", chain(&error)));
                    -1
                }
            },
            _ = cancelled.wait_for(|cancel| *cancel) => {
                set_error("bootstrap cancelled");
                -2
            },
        }
    });
    tor.running_bootstrap.lock().unwrap().take();
    outcome
}

/// Makes a `torkmp_bootstrap` running on another thread return -2 promptly.
/// A no-op when none is running, so it can never cancel a later bootstrap.
#[no_mangle]
pub unsafe extern "C" fn torkmp_cancel_bootstrap(tor: *const TorKmp) {
    if let Some(cancel) = (*tor).running_bootstrap.lock().unwrap().as_ref() {
        cancel.send_replace(true);
    }
}

/// Writes bootstrap progress (0.0..=1.0) and readiness (0/1). Never blocks.
#[no_mangle]
pub unsafe extern "C" fn torkmp_status(tor: *const TorKmp, fraction: *mut f32, ready: *mut i32) {
    let status = (*tor).circuits.client().bootstrap_status();
    *fraction = status.as_frac();
    *ready = i32::from(status.ready_for_traffic());
}

/// Why bootstrap is stuck (e.g. the network is unreachable), or NULL if it is
/// not. Free the result with `torkmp_string_free`.
#[no_mangle]
pub unsafe extern "C" fn torkmp_blocked_reason(tor: *const TorKmp) -> *mut c_char {
    match (*tor).circuits.client().bootstrap_status().blocked() {
        Some(blockage) => CString::new(blockage.to_string().replace('\0', " "))
            .map(CString::into_raw)
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// Which kind of blockage `torkmp_blocked_reason` describes, as a stable code:
/// 0 none, 1 disabled, 2 offline, 3 filtering, 4 can't reach Tor, 5 clock skewed,
/// 6 can't bootstrap, 7 other (an Arti kind this build does not know).
#[no_mangle]
pub unsafe extern "C" fn torkmp_blocked_kind(tor: *const TorKmp) -> i32 {
    use arti_client::status::BlockageKind;
    match (&(*tor).circuits).client().bootstrap_status().blocked() {
        None => 0,
        Some(blockage) => match blockage.kind() {
            BlockageKind::Disabled => 1,
            BlockageKind::Offline => 2,
            BlockageKind::Filtering => 3,
            BlockageKind::CantReachTor => 4,
            BlockageKind::ClockSkewed => 5,
            BlockageKind::CantBootstrap => 6,
            _ => 7,
        },
    }
}

/// Makes every later stream use fresh circuits, unlinkable to earlier ones.
/// Streams already open are left alone.
#[no_mangle]
pub unsafe extern "C" fn torkmp_new_identity(tor: *const TorKmp) {
    let circuits = &(*tor).circuits;
    let mut current = circuits.current.lock().unwrap();
    *current = current.isolated_client();
    circuits.isolation_groups.lock().unwrap().clear();
}

/// Reads the (host, isolation) arguments shared by the connect functions.
unsafe fn connect_args<'a>(
    host: *const c_char,
    isolation: *const c_char,
) -> Option<(&'a str, Option<&'a str>)> {
    let host = arg_str(host, "host")?;
    let isolation = if isolation.is_null() {
        None
    } else {
        Some(arg_str(isolation, "isolation")?)
    };
    Some((host, isolation))
}

/// Opens a stream to `host:port` through Tor. The exit relay resolves `host`,
/// so no DNS query leaves the device; `.onion` hosts are supported. Streams
/// sharing a non-NULL `isolation` key may share circuits; different keys never do.
#[no_mangle]
pub unsafe extern "C" fn torkmp_connect(
    tor: *const TorKmp,
    host: *const c_char,
    port: u16,
    isolation: *const c_char,
) -> *mut TorKmpStream {
    let tor = &*tor;
    let Some((host, isolation)) = connect_args(host, isolation) else {
        return std::ptr::null_mut();
    };
    match tor
        .runtime
        .block_on(tor.circuits.open(host, port, isolation))
    {
        Ok(stream) => {
            let (reader, writer) = stream.split();
            TorKmpStream::new(tor.runtime.clone(), Box::new(reader), Box::new(writer))
        }
        Err(error) => {
            set_error(format!(
                "connect to {host}:{port} failed: {}",
                chain(&error)
            ));
            std::ptr::null_mut()
        }
    }
}

/// Like `torkmp_connect`, then completes a TLS handshake with `host` as the
/// server name, verifying the certificate against Mozilla's root store. The
/// returned stream carries plaintext; TLS happens inside.
#[no_mangle]
pub unsafe extern "C" fn torkmp_connect_tls(
    tor: *const TorKmp,
    host: *const c_char,
    port: u16,
    isolation: *const c_char,
) -> *mut TorKmpStream {
    let tor = &*tor;
    let Some((host, isolation)) = connect_args(host, isolation) else {
        return std::ptr::null_mut();
    };
    let Ok(server_name) = ServerName::try_from(host.to_owned()) else {
        set_error(format!("{host} is not a valid TLS server name"));
        return std::ptr::null_mut();
    };
    let connected = tor.runtime.block_on(async {
        let stream = tor
            .circuits
            .open(host, port, isolation)
            .await
            .map_err(|error| format!("connect to {host}:{port} failed: {}", chain(&error)))?;
        tls_connector()
            .connect(server_name, stream)
            .await
            .map_err(|error| format!("TLS handshake with {host} failed: {}", chain(&error)))
    });
    match connected {
        Ok(tls) => {
            let (reader, writer) = tls.split();
            TorKmpStream::new(tor.runtime.clone(), Box::new(reader), Box::new(writer))
        }
        Err(message) => {
            set_error(message);
            std::ptr::null_mut()
        }
    }
}

/// Reads up to `len` bytes. Returns the count, 0 at end of stream or after
/// `torkmp_stream_shutdown`, or -1 on error.
#[no_mangle]
pub unsafe extern "C" fn torkmp_stream_read(
    stream: *const TorKmpStream,
    buffer: *mut u8,
    len: usize,
) -> i64 {
    let stream = &*stream;
    let buffer = std::slice::from_raw_parts_mut(buffer, len);
    let mut stopped = stream.shutdown.subscribe();
    stream.runtime.block_on(async {
        if *stopped.borrow() {
            return 0;
        }
        let mut reader = stream.reader.lock().await;
        tokio::select! {
            result = reader.read(buffer) => match result {
                Ok(count) => count as i64,
                Err(error) => {
                    set_error(format!("read failed: {error}"));
                    -1
                }
            },
            _ = stopped.wait_for(|stop| *stop) => 0,
        }
    })
}

/// Writes all `len` bytes and flushes them onto the circuit. Returns 0, or -1
/// on error or after `torkmp_stream_shutdown`.
#[no_mangle]
pub unsafe extern "C" fn torkmp_stream_write(
    stream: *const TorKmpStream,
    buffer: *const u8,
    len: usize,
) -> i32 {
    let stream = &*stream;
    let buffer = std::slice::from_raw_parts(buffer, len);
    let mut stopped = stream.shutdown.subscribe();
    stream.runtime.block_on(async {
        if *stopped.borrow() {
            set_error("stream is closed");
            return -1;
        }
        let mut writer = stream.writer.lock().await;
        let result = tokio::select! {
            result = async {
                writer.write_all(buffer).await?;
                writer.flush().await
            } => result,
            _ = stopped.wait_for(|stop| *stop) => {
                Err(std::io::Error::other("stream is closed"))
            },
        };
        match result {
            Ok(()) => 0,
            Err(error) => {
                set_error(format!("write failed: {error}"));
                -1
            }
        }
    })
}

/// Wakes any blocked read or write and makes later ones fail fast. Safe to
/// call while other threads are inside read/write.
#[no_mangle]
pub unsafe extern "C" fn torkmp_stream_shutdown(stream: *const TorKmpStream) {
    (*stream).shutdown.send_replace(true);
}

/// Frees the stream and closes it on the circuit.
#[no_mangle]
pub unsafe extern "C" fn torkmp_stream_free(stream: *mut TorKmpStream) {
    if !stream.is_null() {
        drop(Box::from_raw(stream));
    }
}

/// Starts a SOCKS5 proxy on 127.0.0.1 for clients that only speak proxies
/// (WebViews, HTTP stacks you cannot change). `port` 0 picks a free one. With a
/// non-NULL `password`, clients must authenticate with it; anonymous clients are
/// refused. Returns the bound port, or -1. Calling it again returns the running
/// proxy's port and ignores the arguments.
#[no_mangle]
pub unsafe extern "C" fn torkmp_socks_start(
    tor: *const TorKmp,
    port: u16,
    password: *const c_char,
) -> i32 {
    let tor = &*tor;
    let password = if password.is_null() {
        None
    } else {
        match arg_str(password, "password") {
            Some(password) => Some(password.as_bytes().to_vec()),
            None => return -1,
        }
    };
    let mut slot = tor.socks.lock().unwrap();
    if let Some(server) = slot.as_ref() {
        return i32::from(server.port);
    }
    match socks::SocksServer::start(&tor.runtime, port, tor.circuits.clone(), password) {
        Ok(server) => {
            let bound = server.port;
            *slot = Some(server);
            i32::from(bound)
        }
        Err(error) => {
            set_error(format!("could not start SOCKS proxy: {}", chain(&error)));
            -1
        }
    }
}

/// How many SOCKS CONNECTs so far named an IP address rather than a hostname.
/// A client that resolves DNS itself leaks every lookup outside Tor; this makes
/// such a client visible in a test.
#[no_mangle]
pub unsafe extern "C" fn torkmp_socks_ip_connects(tor: *const TorKmp) -> u64 {
    (&(*tor).circuits).socks_ip_connects.load(Ordering::Relaxed)
}

/// Stops the SOCKS proxy if it is running. Open proxied connections end too.
#[no_mangle]
pub unsafe extern "C" fn torkmp_socks_stop(tor: *const TorKmp) {
    (*tor).socks.lock().unwrap().take();
}

/// Stops the proxy and frees the client. Streams still open keep working until
/// they are freed.
#[no_mangle]
pub unsafe extern "C" fn torkmp_destroy(tor: *mut TorKmp) {
    if tor.is_null() {
        return;
    }
    let tor = Box::from_raw(tor);
    tor.socks.lock().unwrap().take();
    drop(tor);
}

/// The calling thread's last error message, or NULL. Free with
/// `torkmp_string_free`.
#[no_mangle]
pub extern "C" fn torkmp_last_error() -> *mut c_char {
    LAST_ERROR.with(|slot| {
        slot.borrow_mut()
            .take()
            .map(CString::into_raw)
            .unwrap_or(std::ptr::null_mut())
    })
}

/// Frees a string returned by this library.
#[no_mangle]
pub unsafe extern "C" fn torkmp_string_free(text: *mut c_char) {
    if !text.is_null() {
        drop(CString::from_raw(text));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VANILLA: &str = "192.0.2.10:443 0123456789ABCDEF0123456789ABCDEF01234567";
    const OBFS4: &str = "Bridge obfs4 192.0.2.55:38114 316E643333645F6D79216558614D3931657A5F5F cert=YXJlIGZyZXF1ZW50bHkgZnVsbCBvZiBsaXR0bGUgbWVzc2FnZXMgeW91IGNhbiBmaW5kLg iat-mode=0";

    #[test]
    fn parse_bridge_accepts_lines_with_and_without_keyword() {
        assert!(parse_bridge(VANILLA).is_ok());
        assert!(parse_bridge(&format!("Bridge {VANILLA}")).is_ok());
        assert_eq!(parse_bridge(OBFS4).unwrap().get_transport(), Some("obfs4"));
    }

    #[test]
    fn parse_bridge_rejects_garbage() {
        let error = parse_bridge("not a bridge").unwrap_err();
        assert!(error.starts_with("invalid bridge line"), "{error}");
        assert!(parse_bridge("").is_err());
    }

    #[test]
    fn apply_settings_builds_a_bridge_config() {
        let mut builder = TorClientConfigBuilder::default();
        let json = format!(
            r#"{{"bridges":["{VANILLA}","{OBFS4}"],"transports":[{{"protocols":["obfs4"],"path":"/opt/lyrebird","arguments":["-enableLogging"]}}]}}"#
        );
        apply_settings(&mut builder, &json).unwrap();
        assert!(builder.build().is_ok());
    }

    #[test]
    fn apply_settings_reports_missing_transport() {
        let mut builder = TorClientConfigBuilder::default();
        apply_settings(&mut builder, &format!(r#"{{"bridges":["{OBFS4}"]}}"#)).unwrap();
        let error = builder.build().unwrap_err().to_string();
        assert!(error.contains("pluggable transport"), "{error}");
    }

    #[test]
    fn random_bytes_are_not_constant() {
        let mut first = [0u8; 32];
        let mut second = [0u8; 32];
        unsafe {
            assert_eq!(torkmp_random_bytes(first.as_mut_ptr(), first.len()), 0);
            assert_eq!(torkmp_random_bytes(second.as_mut_ptr(), second.len()), 0);
        }
        assert_ne!(first, second);
    }
}
