<h1 align="center">Tor KMP</h1>

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

## Install

```kotlin
implementation("io.github.androidpoet:tor:0.1.0")
```

The native Tor client is inside the artifact for every platform; there is nothing else to add.

## Usage

One client per app, two ways to send traffic:

```kotlin
val tor = Tor(TorConfig(dataDirectory = "$appFilesDir/tor"))

// 1. Streams: a TCP-like connection with no local port. Bootstraps on first use.
tor.connect("example.com", 80).use { stream ->
    stream.write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".encodeToByteArray())
    val count = stream.read(buffer)   // -1 when the remote side closes
}

// 2. A SOCKS5 proxy, for clients that only speak proxies (OkHttp, Ktor, WebView).
val proxy = tor.startSocksProxy()     // SocksProxy(host = "127.0.0.1", port = 41823)
```

Watch it connect:

```kotlin
tor.state.collect { state ->
    when (state) {
        TorState.Idle -> {}
        is TorState.Bootstrapping -> showProgress(state.progress, state.blockedReason)
        TorState.Ready -> showConnected()
        is TorState.Failed -> showError(state.error.message)
        TorState.Closed -> {}
    }
}
```

`.onion` addresses work out of the box, and hostnames are resolved by the exit relay,
so no DNS query leaves the device.

## Platforms

| Platform | Targets | How it's loaded | Verified |
| --- | --- | --- | --- |
| Android (API 24+) | `arm64-v8a` `armeabi-v7a` `x86_64` `x86` | `libtorkmp.so` in the AAR | PLATFORM_ANDROID |
| iOS | `iosArm64` `iosSimulatorArm64` `iosX64` | static, linked into your framework | PLATFORM_IOS |
| macOS | `macosArm64` `macosX64` | static | PLATFORM_MACOS |
| JVM / Compose Desktop | macOS, Linux, Windows (x64 + arm64 where available) | bundled in the jar, extracted at runtime | PLATFORM_JVM |
| Linux | `linuxX64` `linuxArm64` (glibc 2.17+) | static | PLATFORM_LINUX |
| Windows | `mingwX64` | static | PLATFORM_WINDOWS |

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

## Streams or SOCKS?

| | `tor.connect()` | `tor.startSocksProxy()` |
| --- | --- | --- |
| Local port | none | `127.0.0.1:<port>` |
| Other apps on the device can use it | no | **yes**, anything can connect to a loopback port |
| Works with | your own networking code | any proxy-aware client |
| Isolation | `isolation =` argument | SOCKS username/password |

Prefer streams when you own the networking code. Use the proxy for WebViews and HTTP
stacks you can't change.

<details>
<summary><b>OkHttp</b></summary>

```kotlin
val proxy = tor.startSocksProxy()
val client = OkHttpClient.Builder()
    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port)))
    .build()
```
OkHttp hands the hostname to the proxy, so DNS also goes through Tor.
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
</details>

<details>
<summary><b>Plain JVM (<code>java.net</code>)</b></summary>

```kotlin
val proxy = tor.startSocksProxy()
val connection = URI("https://check.torproject.org/api/ip").toURL()
    .openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port)))
```
</details>

## Lifecycle

- Create **one `Tor` per data directory** and keep it as long as you need Tor. Reusing the
  directory across launches makes later bootstraps much faster (cached directory, same guards).
- `bootstrap()` is optional: `connect()` and `startSocksProxy()` call it for you.
- **Cancellation is real.** Cancelling a coroutine stops the native work: a cancelled
  `bootstrap()` stops Arti's bootstrap, and a cancelled `read()`/`write()` closes its stream.
- `close()` cancels any bootstrap and stops the proxy. Open `TorStream`s keep working until
  you close them.

## How it works

```
 Kotlin API (Tor, TorStream, SocksProxy)
     │  JNI (JVM, Android)  ·  cinterop (iOS, macOS, Linux, Windows)
     ▼
 libtorkmp (Rust): a small C ABI + SOCKS5 server
     ▼
 arti-client: Tor circuits, directory, onion services
```

All the glue is Rust; there is no hand-written C. The crate lives in [`native/`](native)
and Gradle compiles it per target, so no binary is committed to this repository.

## Build & test

You need Rust (`rustup`), plus [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) for Android
and [cargo-zigbuild](https://github.com/rust-cross/cargo-zigbuild) with `zig` for Linux and Windows.

```sh
rustup target add aarch64-apple-darwin x86_64-apple-darwin aarch64-apple-ios aarch64-apple-ios-sim \
  x86_64-apple-ios aarch64-linux-android armv7-linux-androideabi x86_64-linux-android \
  i686-linux-android x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu x86_64-pc-windows-gnu

./gradlew :tor:jvmTest :tor:macosArm64Test           # desktop
./gradlew :tor:iosSimulatorArm64Test                 # iOS simulator (-Ptor.simulator="iPhone 17")
./gradlew :tor:connectedAndroidTest                  # Android device or emulator
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
