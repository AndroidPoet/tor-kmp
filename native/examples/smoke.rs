//! Live-network check of the C ABI: bootstrap, fetch over a raw stream, then
//! hold the SOCKS proxy open so external tools (curl) can verify IsTor.
//! Run: cargo run --example smoke -- <data-dir>

use std::ffi::{CStr, CString};
use std::time::{Duration, Instant};

use torkmp::*;

fn last_error() -> String {
    unsafe {
        let ptr = torkmp_last_error();
        if ptr.is_null() {
            return "(no error)".into();
        }
        let text = CStr::from_ptr(ptr).to_string_lossy().into_owned();
        torkmp_string_free(ptr);
        text
    }
}

fn main() {
    let dir = std::env::args().nth(1).expect("data dir");
    let state = CString::new(format!("{dir}/state")).unwrap();
    let cache = CString::new(format!("{dir}/cache")).unwrap();
    unsafe {
        let tor = torkmp_create(state.as_ptr(), cache.as_ptr());
        assert!(!tor.is_null(), "{}", last_error());

        let started = Instant::now();
        let address = tor as usize;
        let progress = std::thread::spawn(move || loop {
            let (mut fraction, mut ready) = (0f32, 0i32);
            torkmp_status(address as *const TorKmp, &mut fraction, &mut ready);
            println!("bootstrap {:>3.0}%", fraction * 100.0);
            if ready == 1 {
                break;
            }
            std::thread::sleep(Duration::from_millis(500));
        });
        assert_eq!(torkmp_bootstrap(tor), 0, "{}", last_error());
        progress.join().unwrap();
        println!("ready in {:?}", started.elapsed());

        let host = CString::new("check.torproject.org").unwrap();
        let opened = Instant::now();
        let stream = torkmp_connect(tor, host.as_ptr(), 80, std::ptr::null());
        assert!(!stream.is_null(), "{}", last_error());
        let request = b"GET /api/ip HTTP/1.1\r\nHost: check.torproject.org\r\nConnection: close\r\n\r\n";
        assert_eq!(torkmp_stream_write(stream, request.as_ptr(), request.len()), 0, "{}", last_error());
        let mut response = Vec::new();
        let mut buffer = [0u8; 4096];
        loop {
            let count = torkmp_stream_read(stream, buffer.as_mut_ptr(), buffer.len());
            assert!(count >= 0, "{}", last_error());
            if count == 0 {
                break;
            }
            response.extend_from_slice(&buffer[..count as usize]);
        }
        torkmp_stream_free(stream);
        let body = String::from_utf8_lossy(&response);
        println!("stream ({:?}): {}", opened.elapsed(), body.lines().next().unwrap_or(""));
        assert!(body.starts_with("HTTP/1.1 "), "no HTTP reply: {body}");

        let port = torkmp_socks_start(tor, 0);
        assert!(port > 0, "{}", last_error());
        println!("SOCKS_PORT={port}");
        let hold = std::env::var("HOLD_SECS").ok().and_then(|s| s.parse().ok()).unwrap_or(0);
        std::thread::sleep(Duration::from_secs(hold));
        torkmp_destroy(tor);
        println!("OK");
    }
}
