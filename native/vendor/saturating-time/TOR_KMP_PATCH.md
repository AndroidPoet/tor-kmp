# Vendored saturating-time 0.5.0 with a Windows fix

Source: <https://crates.io/crates/saturating-time/0.5.0> (MIT OR Apache-2.0, part of
<https://gitlab.torproject.org/tpo/core/arti>), unpacked without the crates.io
metadata files.

Change: `find_limit` in `src/internal.rs` treats a step that leaves the value
unchanged like `None`, and `saturating_add`/`saturating_sub` in `src/lib.rs` use
`unwrap_or_else`, so the limit search only runs when an operation overflows.
Without it, `SystemTime::min_value()` never returns on Windows, where the clock
has 100 ns resolution, and Arti's consensus parser hangs the bootstrap at 15 %.
Upstream: <https://gitlab.torproject.org/tpo/core/arti/-/issues/2678>,
<https://gitlab.torproject.org/tpo/core/arti/-/issues/2823>.

A regression test with a 100 ns clock lives in `src/internal.rs`. Remove this
directory and the `[patch.crates-io]` entry in `native/Cargo.toml` once a fixed
release is on crates.io.
