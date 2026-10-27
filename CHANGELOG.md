# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
