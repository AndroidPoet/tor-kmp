# Roadmap

What is planned, roughly in order. Nothing here is a promise or a date; the
[changelog](CHANGELOG.md) records what shipped. Open an issue to argue for
reordering.

## Shipped

- **0.1** (2026-10): embedded Arti on Android, JVM, iOS, macOS, Linux and
  Windows; streams, TLS, SOCKS5 proxy, Ktor engine, OkHttp adapter, circuit
  isolation, new identity.
- **0.2** (2026-10): bridges and pluggable transports, bundled lyrebird for
  Android and the JVM, WebSockets in the Ktor engine, typed `Isolation`,
  authenticated SOCKS by default, a dispatch-only CI workflow that runs the
  live suites on iOS, Android, Linux and Windows runners.

## Next

- **Snowflake and obfs4 on iOS.** iOS cannot spawn a process, so a transport
  there must run inside the app: either Arti's in-process transport work as it
  lands, or a Go-mobile build of lyrebird talking PT 1.0 over loopback.
- **Logs as a `Flow`.** Arti's tracing output, filtered and rate-limited,
  for in-app diagnostics and bug reports.
- **Dormant mode.** Let Arti drop circuits and stop directory fetches when the
  app is backgrounded, and resume on demand.
- **Hidden-service hosting.** Arti's onion-service support behind a small
  `TorListener` API.
- **HTTP/2 in the Ktor engine**, once Ktor exposes its HTTP/2 client codec to
  engines.
- **Reproducible builds.** Bit-for-bit identical native libraries from the
  release workflow and from a clean machine.
- **Docs site** with per-platform guides and a security notes page.

## Not planned

- Wrapping the C Tor daemon. Arti is the only engine; features C Tor has and
  Arti lacks arrive when Arti grows them.
- A VPN or device-wide mode. This is a library for your app's own traffic;
  Orbot does the rest.
