<p align="center">
  <img src="art/tor-kmp.png" alt="tor-kmp — Tor for Kotlin Multiplatform" width="800"/>
</p>

<p align="center"><b>Private. Embedded. Everywhere.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.androidpoet/tor"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.androidpoet/tor?color=blue&label=Maven%20Central"/></a>
  <a href="https://kotlinlang.org"><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?logo=kotlin&logoColor=white"/></a>
  <a href="https://gitlab.torproject.org/tpo/core/arti"><img alt="Arti" src="https://img.shields.io/badge/Arti-0.47.0-7D4698"/></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/License-MIT-green.svg"/></a>
</p>

<p align="center">
  <img alt="badge-android" src="http://img.shields.io/badge/-android-6EDB8D.svg?style=flat"/>
  <img alt="badge-ios" src="http://img.shields.io/badge/-ios-CDCDCD.svg?style=flat"/>
  <img alt="badge-macos" src="http://img.shields.io/badge/-macos-111111.svg?style=flat"/>
  <img alt="badge-windows" src="http://img.shields.io/badge/-windows-4D76CD.svg?style=flat"/>
  <img alt="badge-linux" src="http://img.shields.io/badge/-linux-FEC701.svg?style=flat"/>
  <img alt="badge-jvm" src="http://img.shields.io/badge/-jvm-DB413D.svg?style=flat"/>
</p>

<p align="center">
One common <b>Tor</b> API for Kotlin Multiplatform. The Tor client runs <i>inside your app</i>:
<a href="https://gitlab.torproject.org/tpo/core/arti">Arti</a>, the Tor Project's Rust
implementation, compiled for Android, iOS, macOS, Linux, Windows and the JVM.
No Orbot, no bundled <code>tor</code> process, no control port.
</p>

<p align="center">
  <a href="#install">Install</a> ·
  <a href="#usage">Usage</a> ·
  <a href="#what-it-is-and-what-it-is-not">What it is not</a> ·
  <a href="#isolation-and-new-identity">Isolation</a> ·
  <a href="#http-without-a-proxy">Ktor & OkHttp</a> ·
  <a href="#the-socks-proxy">SOCKS</a> ·
  <a href="#which-client-which-path">Client matrix</a> ·
  <a href="#lifecycle">Lifecycle</a> ·
  <a href="#maturity-and-provenance">Provenance</a>
</p>

## Install

```kotlin
implementation("io.github.androidpoet:tor:0.1.1")          // the client: streams, TLS, SOCKS
implementation("io.github.androidpoet:tor-ktor:0.1.1")     // optional: a Ktor engine, no proxy
implementation("io.github.androidpoet:tor-okhttp:0.1.1")   // optional: OkHttp on Tor sockets (Android, JVM)
```

The native Tor client is inside the artifact for every platform; there is nothing else to add.
Kotlin 2.2.21+. `tor-ktor` depends on Ktor 3.3.3 (the last release built for Kotlin 2.2);
if your app uses a newer Ktor, Gradle picks yours.

## Usage

One client per app, three ways to send traffic:

```kotlin
val tor = Tor(TorConfig(dataDirectory = "$appFilesDir/tor"))

// 1. Streams: a TCP-like connection with no local port. Bootstraps on first use.
tor.connect("example.com", 80).use { stream ->
    stream.write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".encodeToByteArray())
    val count = stream.read(buffer)   // -1 when the remote side closes
}

// 2. The same stream with TLS already done: certificate checked against Mozilla's roots.
//    A convenience for simple clients; for pinning or your platform's trust store, do TLS
//    yourself on top of connect() (tor-okhttp does exactly that).
tor.connectTls("example.com", 443).use { stream -> /* plaintext in, TLS over Tor out */ }

// 3. A SOCKS5 proxy, for clients that only speak proxies (WebViews).
val proxy = tor.startSocksProxy(password = secret)   // SocksProxy(127.0.0.1:41823, password)
proxy.url("tab-1")                                   // socks5h://tab-1:<secret>@127.0.0.1:41823
proxy.close()
```

For HTTP, skip the hand-written requests: [`tor-ktor`](#http-without-a-proxy) gives Ktor a
Tor engine and [`tor-okhttp`](#http-without-a-proxy) puts OkHttp on Tor sockets. Neither
opens a port.

Watch it connect:

```kotlin
tor.state.collect { state ->
    when (state) {
        TorState.Idle -> {}
        is TorState.Bootstrapping -> showProgress(state.progress, state.blockage)
        TorState.Ready -> showConnected()
        is TorState.Failed -> showError(state.error.message)
        TorState.Closed -> {}
    }
}
```

When bootstrapping stalls, `Bootstrapping.blockage` says why, as a category you can branch
on plus Arti's own words:

```kotlin
when (blockage.kind) {
    BlockageKind.OFFLINE -> "No internet connection"
    BlockageKind.FILTERING -> "This network seems to block Tor"
    BlockageKind.CLOCK_SKEWED -> "Check the device clock"
    else -> blockage.message
}
```

`.onion` addresses work out of the box, and hostnames are resolved by the exit relay,
so no DNS query leaves the device.

## What it is, and what it is not

tor-kmp routes **the connections you hand it** through the Tor network. That is the whole
promise, and it is worth being exact about:

- It is **not a VPN or a kill switch.** Any other socket your app (or a library in it) opens
  still goes straight to the internet. If "never direct" is a requirement, centralise your
  networking on `Tor`, `tor-ktor` or `tor-okhttp`, and fail closed when Tor is unavailable.
- It is **not Tor Browser.** It gives you Tor's routing, not Tor Browser's fingerprinting
  defences, first-party isolation, cookie clearing or safe defaults. A WebView behind the
  SOCKS proxy is a normal WebView whose traffic happens to exit through Tor.
- `isolation` keys separate **circuits**, not application state. Two requests with the same
  key may share a circuit; two with different keys never do. Reusing a key across accounts
  or tabs that must stay unlinkable undoes the separation.
- The data directory holds Tor state that persists across launches (directory cache, guard
  choice). It makes bootstrapping fast and is normal Tor behaviour, but it also means two
  launches with the same directory are the same Tor client. Keep it in app-private storage,
  exclude it from backups, and delete it when the user asks to start over.
- Arti, the engine underneath, is the Tor Project's production direction but its own
  documentation still lists [gaps relative to the C implementation](https://arti.torproject.org/guides/capability-limitations/).
  Read it before betting a high-risk use case on this library.

## Platforms

| Platform | Targets | How it's loaded | Live tests run on |
| --- | --- | --- | --- |
| Android (API 24+) | `arm64-v8a` `armeabi-v7a` `x86_64` `x86` | `libtorkmp.so` in the AAR | API 36 emulator (`x86_64`) |
| iOS | `iosArm64` `iosSimulatorArm64` `iosX64` | static, linked into your framework | iPhone 17 simulator |
| macOS | `macosArm64` `macosX64` | static | Apple silicon |
| JVM / Compose Desktop | macOS, Linux, Windows (x64 + arm64 where available) | bundled in the jar, extracted at runtime | macOS (Apple silicon) |
| Linux | `linuxX64` `linuxArm64` (glibc 2.17+) | static | Debian 12 containers, both architectures |
| Windows | `mingwX64` | static | builds and links; not yet executed |

Adds about **5–9 MB per architecture** to your app (stripped).

## Isolation and new identity

Tor keeps traffic unlinkable by giving unrelated activity separate circuits. Pass an
`isolation` key per tab, account or conversation:

```kotlin
tor.connect("example.com", 80, isolation = "tab-1")
tor.connect("example.com", 80, isolation = "tab-2")   // never shares a circuit with tab-1

proxy.url(isolation = "tab-1")   // "socks5h://tab-1:x@127.0.0.1:41823", same rule over SOCKS

tor.newIdentity()   // every later stream uses fresh circuits; open streams are untouched
```

`newIdentity()` is about circuits only. It does not clear cookies, caches, sessions or
anything else your app or a WebView remembers; do that yourself at the same time. Unlike
the C Tor daemon's `NEWNYM`, it is not rate-limited.

Pooled HTTP connections are covered: each call bumps `tor.identityGeneration`, and
`tor-ktor` and `tor-okhttp` retire idle connections opened under an older generation, so a
request issued after `newIdentity()` returns does not pick up a pre-rotation connection from
the pool. A request that had already taken a connection when you called it (in flight, or
between the pool's check and the first byte) finishes on that circuit; if that matters,
wait for outstanding calls before rotating. If you pool connections yourself, record the
generation when you open one and drop it when the value changes.

## Streams or SOCKS?

| | `tor.connect()` | `tor.startSocksProxy()` |
| --- | --- | --- |
| Local port | none | `127.0.0.1:<port>` |
| Other apps on the device can use it | no | only without a `password` |
| Works with | your code, Ktor (`tor-ktor`), OkHttp (`tor-okhttp`) | WebViews, HTTP stacks you can't change |
| Isolation | `isolation =` argument | SOCKS username |

Prefer streams when you own the networking code. Use the proxy for WebViews and HTTP
stacks you can't change.

## The SOCKS proxy

```kotlin
val proxy = tor.startSocksProxy(password = randomSecret())   // port 0: any free port
proxy.url("tab-1")        // socks5h://tab-1:<secret>@127.0.0.1:41823
proxy.url()               // socks5h://:<secret>@127.0.0.1:41823, the default group
proxy.close()             // or tor.stopSocksProxy()
```

- **Set a password** unless the client cannot send SOCKS credentials. A loopback port is
  reachable by every process on the device; with a password the proxy refuses anyone who
  does not present it (RFC 1929), and the username becomes the isolation key. Chromium
  WebViews cannot send credentials, so Android WebView needs an open proxy; keep its
  lifetime short and stop it when the WebView goes away.
- It speaks **SOCKS5 `CONNECT`** with hostname, IPv4 and IPv6 addresses. No `BIND`, no
  `UDP ASSOCIATE`, no SOCKS4.
- **Hostnames must reach the proxy unresolved** (`socks5h://`, "proxy DNS"). A client that
  resolves names itself leaks every lookup outside Tor. `tor.socksIpLiteralConnections`
  counts requests that arrived with an IP literal; if it climbs while you use a client,
  that client is resolving locally. The matrix below says which ones do.
- `startSocksProxy()` a second time returns the running proxy, whatever the arguments.

## HTTP without a proxy

**Ktor**, any target:

```kotlin
val client = HttpClient(TorEngine) {
    engine {
        tor = myTor
        isolation = "account-1"       // optional: a circuit group for this client
    }
}
client.get("https://check.torproject.org/api/ip")   // {"IsTor":true,...}
client.get("http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/")
```

HTTP/1.1 with keep-alive, request bodies, `HttpTimeout`'s connect timeout and every Ktor
plugin that works on top of an engine. TLS is done inside the Tor client against Mozilla's
root store, so there is no platform trust store or pinning here; use `tor-okhttp` if you
need those. WebSockets are not supported yet.

**OkHttp**, Android and JVM:

```kotlin
val client = OkHttpClient.Builder().tor(myTor, isolation = "account-1").build()
```

OkHttp keeps doing TLS itself (platform trust store, `CertificatePinner`, HTTP/2 via ALPN);
only the TCP layer is a Tor stream. `TorDns` hands hostnames to Tor unresolved, so `.onion`
hosts work and no DNS query leaves the device. The same client also serves Ktor's `OkHttp`
engine through `preconfigured`.

In both, the connect timeout (`connectTimeout` / `HttpTimeout.connectTimeoutMillis`) bounds
opening the Tor stream, like a TCP connect. It does not cover Tor's first bootstrap, which
runs before it; call `tor.bootstrap()` yourself if you want to bound or show that.

## Which client, which path

Some clients accept a SOCKS setting and then **resolve DNS locally or connect directly
anyway**, which leaks your traffic outside Tor without any error. This table is
checked against each client's source code:

| Client | Use | DNS through Tor | Per-client isolation |
| --- | --- | --- | --- |
| Your own code | `tor.connect()` | ✅ always | ✅ `isolation =` |
| Ktor, any engine-free target | `TorEngine` (`tor-ktor`) | ✅ always | ✅ `isolation` per client |
| OkHttp (+ Ktor `OkHttp` engine) | `.tor(tor)` (`tor-okhttp`) | ✅ always | ✅ `isolation` per client |
| OkHttp (+ Ktor `OkHttp` engine) | SOCKS proxy | ✅ OkHttp sends the hostname | ❌ (global `Authenticator` only) |
| Android `HttpURLConnection` (+ Ktor `Android` engine) | SOCKS proxy | ✅ | ❌ |
| Ktor `Curl` engine (Linux, Windows, macOS) | SOCKS proxy | ✅ rewritten to `socks5h://` | ✅ credentials in the proxy URL |
| Ktor `Darwin` engine | SOCKS proxy | ⚠️ unverified | ❌ credentials are dropped |
| Android WebView | SOCKS proxy (required, no password) | ✅ Chromium resolves on the proxy | ❌ one proxy per process |
| WKWebView (iOS 17 / macOS 14) | SOCKS proxy (required) | ⚠️ unverified | ✅ one data store per tab |
| Desktop `HttpURLConnection` (OpenJDK) | ❌ **don't** | ❌ resolves locally, leaks DNS | — |
| `java.net.http.HttpClient`, Ktor `Java` engine | ❌ **don't** | ignores SOCKS and connects **directly** | — |
| Ktor `CIO` engine | ❌ **don't** | ignores SOCKS and connects **directly** | — |

So yes, keep the proxy: WebViews can't use Tor any other way. But prefer streams
(`tor.connect()`, `tor-ktor`, `tor-okhttp`) wherever you own the networking code.

<details>
<summary><b>OkHttp</b></summary>

```kotlin
val client = OkHttpClient.Builder().tor(tor).build()          // tor-okhttp: no port, no proxy

val proxy = tor.startSocksProxy()                               // or, over SOCKS:
val client = OkHttpClient.Builder()
    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port)))
    .build()
```

Over SOCKS, OkHttp hands the hostname to the proxy (it never resolves it), so DNS also goes
through Tor; what you lose is per-client isolation.
</details>

<details>
<summary><b>Ktor</b></summary>

```kotlin
val client = HttpClient(TorEngine) { engine { tor = myTor } }   // tor-ktor: any target, no proxy

val proxy = tor.startSocksProxy()                                 // or, over SOCKS:
val client = HttpClient(OkHttp) {          // Android / JVM. On Linux & Windows: Curl
    engine { this.proxy = ProxyBuilder.socks(proxy.host, proxy.port) }
}
```

Over SOCKS, use the `OkHttp` or `Android` engine on Android and the JVM, and `Curl` on
desktop native. **Never `CIO` or `Java`:** both ignore a SOCKS proxy and connect straight
to the server. `Darwin` works, but its DNS behaviour is unverified and it can't isolate
clients.
</details>

<details>
<summary><b>Android WebView</b></summary>

```kotlin
val proxy = tor.startSocksProxy()
ProxyController.getInstance().setProxyOverride(
    // Chromium's socks5:// already resolves hostnames on the proxy side.
    ProxyConfig.Builder().addProxyRule("socks5://${proxy.host}:${proxy.port}").build(),
    ContextCompat.getMainExecutor(context),
) { /* applied */ }
```

Requires `androidx.webkit` and `WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)`.
The override covers every WebView in the process, and Chromium can't send SOCKS
credentials, so all WebViews share one isolation group.
</details>

<details>
<summary><b>WKWebView (iOS 17+ / macOS 14+)</b></summary>

```swift
let store = WKWebsiteDataStore(forIdentifier: tabId)   // a fresh store per tab
var socks = ProxyConfiguration(socksv5Proxy: .hostPort(host: "127.0.0.1", port: .init(integerLiteral: port)))
socks.applyCredential(username: "tab-1", password: secret)  // isolation key + proxy password
store.proxyConfigurations = [socks]
```

Set the proxy on a store before its first load; changing it on a store already in use
does not apply to pooled connections.
</details>

## Lifecycle

- Create **one `Tor` per data directory** and keep it as long as you need Tor. Reusing the
  directory across launches makes later bootstraps much faster (cached directory, same guards).
- `bootstrap()` is optional: `connect()` and `startSocksProxy()` call it for you.
- **Cancellation is real.** Cancelling a coroutine stops the native work: a cancelled
  `bootstrap()` stops Arti's bootstrap, and a cancelled `read()`/`write()` closes its stream.
  A cancelled `connect()` returns as soon as the native connect finishes or fails; put a
  `withTimeout` around it if you need a hard bound.
- `close()` cancels any bootstrap and stops the proxy. Open `TorStream`s keep working until
  you close them.
- The blocking adapters (`tor-okhttp`) follow socket semantics, not coroutine semantics:
  OkHttp's connect and read timeouts apply, coroutine cancellation does not.
- **iOS** suspends your process in the background and its connections drop. Keep the `Tor`
  instance; the next `connect()` after resuming rebuilds circuits from the cached directory.
  Wrap long transfers in a background task.
- **Android** may kill a backgrounded process. If Tor must stay up while the app is not on
  screen (a messenger, a long download), run it inside a foreground service; otherwise just
  create `Tor` again on the next launch, which is fast with a warm data directory.

## How it works

```
 tor-ktor (TorEngine)   tor-okhttp (TorSocketFactory)   your code
     └──────────────────────┬──────────────────────────────┘
 Kotlin API (Tor, TorStream, SocksProxy)
     │  JNI (JVM, Android)  ·  cinterop (iOS, macOS, Linux, Windows)
     ▼
 libtorkmp (Rust): a small C ABI + SOCKS5 server
     ▼
 arti-client: Tor circuits, directory, onion services
```

All the glue is Rust; there is no hand-written C. The crate lives in [`native/`](native)
and Gradle compiles it per target, so no binary is committed to this repository.

## Maturity and provenance

- **Arti is pinned**, not floated: `arti-client = "=0.47.0"` with a committed `Cargo.lock`,
  and every build runs `cargo --locked`. Arti ships monthly with breaking changes; each bump
  here is a reviewed release with its own changelog entry, never a silent dependency update.
- **Toolchain** for the published 0.1.x binaries: Rust 1.93.0 (pinned in
  [`native/rust-toolchain.toml`](native/rust-toolchain.toml)), Zig 0.17.0 and cargo-zigbuild
  0.24.0 (Linux and Windows cross-builds, glibc 2.17 floor), cargo-ndk 4.1.2 with NDK
  28.2.13676358, Xcode 27. [`publish.yml`](.github/workflows/publish.yml) pins the same versions.
- **Verifying what you download**: Maven Central serves PGP signatures and checksums next
  to every artifact. Native libraries live inside the artifacts, so one signature covers
  the Kotlin code and the Rust code it calls.
- **Size**: about 5–9 MB per architecture, release-built with LTO, symbols stripped.
- Bundled Rust crates keep their licenses (MIT/Apache-2.0/BSD/ISC family); run
  `cargo license` in [`native/`](native) for the exact inventory of a given revision.

## Build & test

You need Rust (`rustup`), plus [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) for Android
and [cargo-zigbuild](https://github.com/rust-cross/cargo-zigbuild) with `zig` for Linux and Windows.

```sh
rustup target add aarch64-apple-darwin x86_64-apple-darwin aarch64-apple-ios aarch64-apple-ios-sim \
  x86_64-apple-ios aarch64-linux-android armv7-linux-androideabi x86_64-linux-android \
  i686-linux-android x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu x86_64-pc-windows-gnu

./gradlew jvmTest macosArm64Test                     # desktop, all modules
./gradlew iosSimulatorArm64Test                      # iOS simulator (-Ptor.simulator="iPhone 17")
./gradlew connectedAndroidTest                       # Android device or emulator
./gradlew :tor:linkDebugTestLinuxArm64               # then run build/bin/linuxArm64/debugTest/test.kexe in a container
./gradlew spotlessCheck detekt apiCheck              # gates
```

The live tests talk to the real Tor network, so they need internet access.
Each Rust target takes 1–2 GB to build; `-Ptor.rust.prune=true` deletes cargo's
intermediates after each one. Release builds use `-Ptor.jvm.allPlatforms=true` so the
JVM jar carries every desktop OS.

## Contributing

Issues and PRs are welcome. Before opening a PR, run the gates:

```sh
./gradlew spotlessApply detekt apiCheck
```

`apiCheck` guards the public API. If you change it on purpose, regenerate the dump with
`./gradlew apiDump` and commit it.

## Find this useful?

Give the repo a ⭐. It helps others discover it.

## License

```
MIT License

Copyright (c) 2026 Ranbir Singh
```

See [LICENSE](LICENSE) for the full text. Arti is © The Tor Project, dual-licensed MIT/Apache-2.0.
"Tor" is a trademark of The Tor Project; this project is not affiliated with or endorsed by it.
