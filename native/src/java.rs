//! JNI entry points for `io.github.androidpoet.tor.internal.TorBridge` on the
//! JVM and Android. Each one forwards to the C ABI and turns a failure into a
//! `TorException`.

use std::ffi::{CStr, CString};
use std::ptr;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jfloat, jint, jlong, jstring};
use jni::JNIEnv;

use crate::*;

const TOR_EXCEPTION: &str = "io/github/androidpoet/tor/TorException";

fn throw(env: &mut JNIEnv, message: &str) {
    // Fails only if an exception is already pending, which is what we want anyway.
    let _ = env.throw_new(TOR_EXCEPTION, message);
}

fn throw_last_error(env: &mut JNIEnv) {
    let message = unsafe {
        let text = torkmp_last_error();
        if text.is_null() {
            "unknown native error".to_owned()
        } else {
            let owned = CStr::from_ptr(text).to_string_lossy().into_owned();
            torkmp_string_free(text);
            owned
        }
    };
    throw(env, &message);
}

/// Copies a Java string into a C string, throwing on failure.
fn c_string(env: &mut JNIEnv, value: &JString) -> Option<CString> {
    let text: String = match env.get_string(value) {
        Ok(text) => text.into(),
        Err(_) => {
            throw(env, "invalid string argument");
            return None;
        }
    };
    match CString::new(text) {
        Ok(text) => Some(text),
        Err(_) => {
            throw(env, "string argument contains NUL");
            None
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_create(
    mut env: JNIEnv,
    _class: JClass,
    state_dir: JString,
    cache_dir: JString,
) -> jlong {
    let (Some(state_dir), Some(cache_dir)) =
        (c_string(&mut env, &state_dir), c_string(&mut env, &cache_dir))
    else {
        return 0;
    };
    let tor = unsafe { torkmp_create(state_dir.as_ptr(), cache_dir.as_ptr()) };
    if tor.is_null() {
        throw_last_error(&mut env);
    }
    tor as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_bootstrap(
    mut env: JNIEnv,
    _class: JClass,
    tor: jlong,
) {
    if unsafe { torkmp_bootstrap(tor as *const TorKmp) } != 0 {
        throw_last_error(&mut env);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_cancelBootstrap(
    _env: JNIEnv,
    _class: JClass,
    tor: jlong,
) {
    unsafe { torkmp_cancel_bootstrap(tor as *const TorKmp) }
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_progress(
    _env: JNIEnv,
    _class: JClass,
    tor: jlong,
) -> jfloat {
    let (mut fraction, mut ready) = (0f32, 0i32);
    unsafe { torkmp_status(tor as *const TorKmp, &mut fraction, &mut ready) };
    fraction
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_blockedReason(
    env: JNIEnv,
    _class: JClass,
    tor: jlong,
) -> jstring {
    unsafe {
        let reason = torkmp_blocked_reason(tor as *const TorKmp);
        if reason.is_null() {
            return ptr::null_mut();
        }
        let text = CStr::from_ptr(reason).to_string_lossy().into_owned();
        torkmp_string_free(reason);
        env.new_string(text)
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut())
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_newIdentity(
    _env: JNIEnv,
    _class: JClass,
    tor: jlong,
) {
    unsafe { torkmp_new_identity(tor as *const TorKmp) }
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_connect(
    mut env: JNIEnv,
    _class: JClass,
    tor: jlong,
    host: JString,
    port: jint,
    isolation: JString,
) -> jlong {
    let Some(host) = c_string(&mut env, &host) else {
        return 0;
    };
    let isolation = if isolation.is_null() {
        None
    } else {
        match c_string(&mut env, &isolation) {
            Some(key) => Some(key),
            None => return 0,
        }
    };
    let Ok(port) = u16::try_from(port) else {
        throw(&mut env, "port out of range");
        return 0;
    };
    let stream = unsafe {
        torkmp_connect(
            tor as *const TorKmp,
            host.as_ptr(),
            port,
            isolation.as_ref().map_or(ptr::null(), |key| key.as_ptr()),
        )
    };
    if stream.is_null() {
        throw_last_error(&mut env);
    }
    stream as jlong
}

/// Returns the byte count, or -1 at end of stream (Java convention).
#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_read(
    mut env: JNIEnv,
    _class: JClass,
    stream: jlong,
    buffer: JByteArray,
    offset: jint,
    length: jint,
) -> jint {
    let mut chunk = vec![0u8; length.max(0) as usize];
    let count = unsafe {
        torkmp_stream_read(stream as *const TorKmpStream, chunk.as_mut_ptr(), chunk.len())
    };
    if count < 0 {
        throw_last_error(&mut env);
        return -1;
    }
    if count == 0 {
        return -1;
    }
    // SAFETY: u8 and i8 (jbyte) share size and alignment.
    let read = unsafe { std::slice::from_raw_parts(chunk.as_ptr().cast::<i8>(), count as usize) };
    if env.set_byte_array_region(&buffer, offset, read).is_err() {
        throw(&mut env, "buffer too small");
        return -1;
    }
    count as jint
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_write(
    mut env: JNIEnv,
    _class: JClass,
    stream: jlong,
    buffer: JByteArray,
    offset: jint,
    length: jint,
) {
    let mut chunk = vec![0i8; length.max(0) as usize];
    if env.get_byte_array_region(&buffer, offset, &mut chunk).is_err() {
        throw(&mut env, "range outside buffer");
        return;
    }
    let written = unsafe {
        torkmp_stream_write(stream as *const TorKmpStream, chunk.as_ptr().cast(), chunk.len())
    };
    if written != 0 {
        throw_last_error(&mut env);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_shutdownStream(
    _env: JNIEnv,
    _class: JClass,
    stream: jlong,
) {
    unsafe { torkmp_stream_shutdown(stream as *const TorKmpStream) }
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_freeStream(
    _env: JNIEnv,
    _class: JClass,
    stream: jlong,
) {
    unsafe { torkmp_stream_free(stream as *mut TorKmpStream) }
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_startSocks(
    mut env: JNIEnv,
    _class: JClass,
    tor: jlong,
    port: jint,
) -> jint {
    let Ok(port) = u16::try_from(port) else {
        throw(&mut env, "port out of range");
        return -1;
    };
    let bound = unsafe { torkmp_socks_start(tor as *const TorKmp, port) };
    if bound < 0 {
        throw_last_error(&mut env);
    }
    bound
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_stopSocks(
    _env: JNIEnv,
    _class: JClass,
    tor: jlong,
) {
    unsafe { torkmp_socks_stop(tor as *const TorKmp) }
}

#[no_mangle]
pub extern "system" fn Java_io_github_androidpoet_tor_internal_TorBridge_destroy(
    _env: JNIEnv,
    _class: JClass,
    tor: jlong,
) {
    unsafe { torkmp_destroy(tor as *mut TorKmp) }
}
