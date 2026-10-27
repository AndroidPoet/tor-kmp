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

use arti_client::config::TorClientConfigBuilder;
use arti_client::{
    BootstrapBehavior, DataReader, DataStream, DataWriter, IsolationToken, StreamPrefs, TorClient,
};
use futures::{AsyncReadExt, AsyncWriteExt};
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
}

/// One Tor stream, split so a blocked read never stalls a write.
pub struct TorKmpStream {
    runtime: Arc<Runtime>,
    reader: tokio::sync::Mutex<DataReader>,
    writer: tokio::sync::Mutex<DataWriter>,
    shutdown: watch::Sender<bool>,
}

thread_local! {
    static LAST_ERROR: RefCell<Option<CString>> = const { RefCell::new(None) };
}

fn set_error(message: impl std::fmt::Display) {
    let text = CString::new(message.to_string().replace('\0', " ")).unwrap_or_default();
    LAST_ERROR.with(|slot| *slot.borrow_mut() = Some(text));
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

    pub(crate) async fn open(
        &self,
        host: &str,
        port: u16,
        isolation: Option<&str>,
    ) -> arti_client::Result<DataStream> {
        let prefs = self.prefs_for(isolation);
        self.client().connect_with_prefs((host, port), &prefs).await
    }
}

/// Creates a client that keeps its consensus in `cache_dir` and its guards and
/// keys in `state_dir`. Does not touch the network until `torkmp_bootstrap`.
#[no_mangle]
pub unsafe extern "C" fn torkmp_create(
    state_dir: *const c_char,
    cache_dir: *const c_char,
) -> *mut TorKmp {
    let (Some(state_dir), Some(cache_dir)) =
        (arg_str(state_dir, "state_dir"), arg_str(cache_dir, "cache_dir"))
    else {
        return std::ptr::null_mut();
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
            set_error(format!("could not start runtime: {error}"));
            return std::ptr::null_mut();
        }
    };
    let config = match TorClientConfigBuilder::from_directories(state_dir, cache_dir).build() {
        Ok(config) => config,
        Err(error) => {
            set_error(format!("invalid configuration: {error}"));
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
            }),
            socks: Mutex::new(None),
            running_bootstrap: Mutex::new(None),
        })),
        Err(error) => {
            set_error(format!("could not create client: {error}"));
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
                    set_error(format!("bootstrap failed: {error}"));
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

/// Makes every later stream use fresh circuits, unlinkable to earlier ones.
/// Streams already open are left alone.
#[no_mangle]
pub unsafe extern "C" fn torkmp_new_identity(tor: *const TorKmp) {
    let circuits = &(*tor).circuits;
    let mut current = circuits.current.lock().unwrap();
    *current = current.isolated_client();
    circuits.isolation_groups.lock().unwrap().clear();
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
    let Some(host) = arg_str(host, "host") else {
        return std::ptr::null_mut();
    };
    let isolation = if isolation.is_null() {
        None
    } else {
        match arg_str(isolation, "isolation") {
            Some(key) => Some(key),
            None => return std::ptr::null_mut(),
        }
    };
    match tor.runtime.block_on(tor.circuits.open(host, port, isolation)) {
        Ok(stream) => {
            let (reader, writer) = stream.split();
            Box::into_raw(Box::new(TorKmpStream {
                runtime: tor.runtime.clone(),
                reader: tokio::sync::Mutex::new(reader),
                writer: tokio::sync::Mutex::new(writer),
                shutdown: watch::channel(false).0,
            }))
        }
        Err(error) => {
            set_error(format!("connect to {host}:{port} failed: {error}"));
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
/// (WebViews, OkHttp, Ktor). `port` 0 picks a free one. Returns the bound port,
/// or -1. Calling it again returns the running proxy's port.
#[no_mangle]
pub unsafe extern "C" fn torkmp_socks_start(tor: *const TorKmp, port: u16) -> i32 {
    let tor = &*tor;
    let mut slot = tor.socks.lock().unwrap();
    if let Some(server) = slot.as_ref() {
        return i32::from(server.port);
    }
    match socks::SocksServer::start(&tor.runtime, port, tor.circuits.clone()) {
        Ok(server) => {
            let bound = server.port;
            *slot = Some(server);
            i32::from(bound)
        }
        Err(error) => {
            set_error(format!("could not start SOCKS proxy: {error}"));
            -1
        }
    }
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
