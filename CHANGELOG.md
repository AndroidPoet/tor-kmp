# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 0.2.0 - 2026-10-27

### Added

- **Bridges and pluggable transports.** `TorConfig(bridges, transports)` takes
  `Bridge.parse(line)` values (validated by Arti up front) and
  `PluggableTransport(protocols, executable)` entries; Arti launches the
  transport as a child process. Direct bridges work on every target; transports
  on Android, the JVM, macOS, Linux and Windows. iOS cannot spawn processes and
  rejects them with a clear message.
- **`tor-lyrebird`** (Android, JVM): the Tor Project's lyrebird 0.8.1 (obfs4,
  webtunnel, meek_lite, snowflake) built from source into the artifact.
  `Lyrebird.transport()` / `Lyrebird.transport(context)` returns it as a
  `PluggableTransport`. Live-tested against Tor Browser's built-in obfs4 bridges.
- **WebSockets** in `tor-ktor`: install Ktor's `WebSockets` plugin and use
  `client.webSocket(...)` as with any engine, including `wss://` and onion hosts.
- `Isolation` value class and `Isolation.random()`; `SocksAuth` with
  `Generated` (default), `Password` and `None`.

### Changed

- **Breaking:** isolation keys are `Isolation` instead of `String?` in
  `Tor.connect`, `Tor.connectTls`, `SocksProxy.url`, `TorEngineConfig.isolation`
  and `OkHttpClient.Builder.tor`. The `String` overloads remain, deprecated;
  a `String?` variable needs `key?.let(::Isolation)`.
- **Breaking:** `startSocksProxy()` now authenticates by default with a random
  192-bit password (`SocksAuth.Generated`). Pass `SocksAuth.None` for a client
  that cannot send credentials. `startSocksProxy(port, password)` remains,
  deprecated.
- `TorConfig` gained `bridges` and `transports`; the JSON it hands to the
  native side is internal.

### Fixed

- A stream whose `BEGIN` times out at the exit is retried twice on a fresh circuit
  instead of failing at once; Arti keeps the slow circuit and makes one attempt per
  call, which left every connect failing for a while behind a congested bridge.
- A TLS peer that closes without `close_notify` now reads as end of stream, as in
  browsers, instead of failing the read with "peer closed connection without
  sending TLS close_notify".
- Native errors now carry their whole cause chain, so a failure deep in Arti names
  its root cause (a locked state file, an unreadable directory) instead of only the
  layer that wrapped it.
- `tor-ktor` delivered a response only once the server closed the connection or
  a large buffer filled: the stream-to-channel pump never flushed. Responses on
  keep-alive connections now arrive as soon as they are read (a POST to httpbin
  dropped from 64 s to 4 s in the live test), and WebSocket frames flow at all.

## 0.1.2 - 2026-10-27

### Added

- Consumer R8/ProGuard rules inside the Android AAR: the JNI bridge and the
  `TorException(String)` constructor that the native side looks up by name are
  kept automatically in shrunk apps.
- README rewritten after a documentation audit: a compilable quickstart, a
  transport chooser, per-platform data-directory and integration notes, error
  and timeout handling, consumer testing advice, FAQ, alternatives, a
  versioning and Arti-upgrade policy, measured artifact sizes, and an honest
  statement of what is verified locally versus in CI.

### Changed

- `startSocksProxy(password)` and `SocksProxy.url(isolation)` reject credentials
  outside SOCKS5's limits (1–255 UTF-8 bytes, no NUL) up front instead of failing
  at the handshake.

## 0.1.1 - 2026-10-27

Follow-up to an architecture review: tighter identity and SOCKS contracts while
they are young.

### Added

- `Tor.startSocksProxy(port, password)` — with a password the proxy refuses clients
  that do not authenticate with it (RFC 1929), so other processes on the device
  cannot use it. The SOCKS username is then the isolation key.
- `SocksProxy.password` and `SocksProxy.close()`; `SocksProxy.url()` percent-encodes
  the isolation key and password, so any string is safe.
- `Tor.identityGeneration` — increases on every `newIdentity()`. `tor-ktor` and
  `tor-okhttp` retire idle pooled connections opened under an older generation, so
  a request issued after `newIdentity()` does not reuse a pre-rotation connection
  (one already in flight finishes where it is).
- README: "What it is, and what it is not" (not a VPN or kill switch, not Tor
  Browser, what isolation keys and the data directory mean), a SOCKS section, and
  "Maturity and provenance" (pins, toolchain, how to verify artifacts).

### Changed

- `SocksProxy` can no longer be constructed directly; it comes from
  `Tor.startSocksProxy()`, which now returns the same instance while the proxy runs.
- Rust builds run `cargo --locked`, so a release always matches `Cargo.lock`.

## 0.1.0 - 2026-10-27

First release: an embedded Tor client for Kotlin Multiplatform, built on
[Arti](https://gitlab.torproject.org/tpo/core/arti) 0.47.0.

### Added

- **`Tor`** — one client per data directory. `bootstrap()` publishes progress on
  `state: StateFlow<TorState>` (`Idle`, `Bootstrapping(progress, blockage)`,
  `Ready`, `Failed`, `Closed`); `newIdentity()` moves later streams onto fresh
  circuits; `close()` cancels a running bootstrap and stops the proxy.
- **`Blockage`** on `TorState.Bootstrapping` — why bootstrapping is stuck, as a
  `BlockageKind` (`OFFLINE`, `FILTERING`, `CLOCK_SKEWED`, ...) plus Arti's message.
- **`TorStream`** from `tor.connect(host, port, isolation)` — a TCP-like byte
  stream through Tor with no local port. Hostnames are resolved by the exit relay,
  `.onion` addresses work, and streams with different `isolation` keys never share
  a circuit. Cancelling a coroutine blocked in `read()`/`write()` closes the stream.
- **`tor.connectTls(host, port)`** — the same stream with TLS done inside the Tor
  client (rustls, Mozilla root store, ALPN `http/1.1`).
- **`SocksProxy`** from `tor.startSocksProxy(port)` — a SOCKS5 server on
  `127.0.0.1` for WebViews and HTTP stacks that only speak proxies. SOCKS
  credentials select an isolation group; `proxy.url(isolation)` builds the
  `socks5h://` URL.
- **`tor-ktor`** — `HttpClient(TorEngine) { engine { tor = myTor } }`: a Ktor
  engine on Tor streams for every target. HTTP/1.1 with keep-alive, request bodies,
  connect timeouts, per-client `isolation`. No proxy, no port.
- **`tor-okhttp`** — `OkHttpClient.Builder().tor(myTor)`: OkHttp on Tor sockets for
  Android and the JVM. OkHttp keeps its own TLS (platform trust store, pinning,
  HTTP/2); `TorDns` leaves hostnames unresolved so `.onion` works and DNS never leaves
  the device.
- `tor.socksIpLiteralConnections` — counts SOCKS requests that carried an IP
  literal instead of a hostname, to spot clients that resolve DNS locally.
- Targets: Android (API 24+, four ABIs), `jvm` (macOS, Linux, Windows libraries
  bundled in the jar), `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`,
  `macosX64`, `linuxX64`, `linuxArm64`, `mingwX64`. The native client ships inside
  every artifact; consumers add nothing else.
- README guidance on which HTTP clients honour a SOCKS proxy without leaking DNS
  (OkHttp, Android `HttpURLConnection`, Ktor `OkHttp`/`Android`/`Curl`, Android
  WebView) and which silently bypass it (Ktor `CIO`/`Java`, `java.net.http`,
  desktop `HttpURLConnection`).
