//! Minimal SOCKS5 (RFC 1928) front door to the Tor client, for HTTP stacks and
//! WebViews that can only be pointed at a proxy.
//!
//! Supports CONNECT with domain, IPv4 and IPv6 targets. Username/password
//! (RFC 1929) is accepted but not checked: like Tor's own SocksPort, the
//! credentials select an isolation group, so two tabs using different
//! credentials never share circuits.

use std::io;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::sync::atomic::Ordering;
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::runtime::Runtime;
use tokio::task::{JoinHandle, JoinSet};

use crate::Circuits;

const VERSION: u8 = 0x05;
const NO_AUTH: u8 = 0x00;
const USER_PASS: u8 = 0x02;
const NO_ACCEPTABLE_METHOD: u8 = 0xFF;
const CMD_CONNECT: u8 = 0x01;
const ATYP_IPV4: u8 = 0x01;
const ATYP_DOMAIN: u8 = 0x03;
const ATYP_IPV6: u8 = 0x04;
const REPLY_OK: u8 = 0x00;
const REPLY_HOST_UNREACHABLE: u8 = 0x04;
const REPLY_COMMAND_UNSUPPORTED: u8 = 0x07;
const REPLY_ADDRESS_UNSUPPORTED: u8 = 0x08;

/// A listening proxy. Dropping it closes the listener and every connection.
pub(crate) struct SocksServer {
    pub(crate) port: u16,
    accept_loop: JoinHandle<()>,
}

impl SocksServer {
    /// With a `password`, clients must authenticate (RFC 1929) and present it;
    /// anonymous clients are refused. Without one, both are accepted.
    pub(crate) fn start(
        runtime: &Runtime,
        port: u16,
        circuits: Arc<Circuits>,
        password: Option<Vec<u8>>,
    ) -> io::Result<Self> {
        let listener = runtime.block_on(TcpListener::bind((Ipv4Addr::LOCALHOST, port)))?;
        let port = listener.local_addr()?.port();
        let password = Arc::new(password);
        let accept_loop = runtime.spawn(async move {
            // Owned by this task, so aborting the task aborts every connection.
            let mut connections = JoinSet::new();
            loop {
                tokio::select! {
                    accepted = listener.accept() => {
                        let Ok((client, _)) = accepted else { continue };
                        let circuits = circuits.clone();
                        let password = password.clone();
                        connections.spawn(async move {
                            // A failed connection only affects that client.
                            let _ = serve(client, &circuits, password.as_deref()).await;
                        });
                    }
                    // Reap finished connections so the set does not grow forever.
                    Some(_) = connections.join_next(), if !connections.is_empty() => {}
                }
            }
        });
        Ok(Self { port, accept_loop })
    }
}

impl Drop for SocksServer {
    fn drop(&mut self) {
        self.accept_loop.abort();
    }
}

async fn serve(mut client: TcpStream, circuits: &Circuits, password: Option<&[u8]>) -> io::Result<()> {
    client.set_nodelay(true)?;
    let isolation = negotiate_auth(&mut client, password).await?;

    let mut request = [0u8; 4];
    client.read_exact(&mut request).await?;
    let [version, command, _reserved, address_type] = request;
    if version != VERSION {
        return Err(io::Error::other("not SOCKS5"));
    }
    let Some(host) = read_host(&mut client, address_type).await? else {
        return reply(&mut client, REPLY_ADDRESS_UNSUPPORTED).await;
    };
    if address_type != ATYP_DOMAIN {
        circuits.socks_ip_connects.fetch_add(1, Ordering::Relaxed);
    }
    let port = client.read_u16().await?;
    if command != CMD_CONNECT {
        return reply(&mut client, REPLY_COMMAND_UNSUPPORTED).await;
    }

    match circuits.open(&host, port, isolation.as_deref()).await {
        Ok(mut stream) => {
            reply(&mut client, REPLY_OK).await?;
            tokio::io::copy_bidirectional(&mut client, &mut stream).await?;
            Ok(())
        }
        Err(_) => reply(&mut client, REPLY_HOST_UNREACHABLE).await,
    }
}

/// Picks an auth method; returns the isolation key when credentials were sent.
/// With `required_password`, only authenticated clients with that password get in.
async fn negotiate_auth(client: &mut TcpStream, required_password: Option<&[u8]>) -> io::Result<Option<String>> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).await?;
    if greeting[0] != VERSION {
        return Err(io::Error::other("not SOCKS5"));
    }
    let mut methods = vec![0u8; usize::from(greeting[1])];
    client.read_exact(&mut methods).await?;

    if methods.contains(&USER_PASS) {
        client.write_all(&[VERSION, USER_PASS]).await?;
        // RFC 1929: VER ULEN UNAME PLEN PASSWD
        let _subnegotiation_version = client.read_u8().await?;
        let username = read_short_bytes(client).await?;
        let password = read_short_bytes(client).await?;
        if let Some(required) = required_password {
            if !constant_time_eq(&password, required) {
                client.write_all(&[0x01, 0x01]).await?;
                return Err(io::Error::other("wrong SOCKS password"));
            }
            client.write_all(&[0x01, 0x00]).await?;
            // The password is the proxy's secret, shared by every client; only the
            // username says which isolation group the client wants.
            return Ok(Some(isolation_key(&username, &[])));
        }
        client.write_all(&[0x01, 0x00]).await?;
        return Ok(Some(isolation_key(&username, &password)));
    }
    if required_password.is_none() && methods.contains(&NO_AUTH) {
        client.write_all(&[VERSION, NO_AUTH]).await?;
        return Ok(None);
    }
    client.write_all(&[VERSION, NO_ACCEPTABLE_METHOD]).await?;
    Err(io::Error::other("no acceptable SOCKS auth method"))
}

/// Compares without short-circuiting on the first differing byte.
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// Maps credentials to an isolation key. Hex keeps the encoding unambiguous:
/// ("a:b", "c") and ("a", "b:c") must not land in the same group.
fn isolation_key(username: &[u8], password: &[u8]) -> String {
    let hex = |bytes: &[u8]| bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
    format!("socks:{}:{}", hex(username), hex(password))
}

/// Reads the target host. Domains stay names so the exit relay resolves them.
async fn read_host(client: &mut TcpStream, address_type: u8) -> io::Result<Option<String>> {
    Ok(match address_type {
        ATYP_IPV4 => {
            let mut octets = [0u8; 4];
            client.read_exact(&mut octets).await?;
            Some(Ipv4Addr::from(octets).to_string())
        }
        ATYP_IPV6 => {
            let mut octets = [0u8; 16];
            client.read_exact(&mut octets).await?;
            Some(Ipv6Addr::from(octets).to_string())
        }
        ATYP_DOMAIN => {
            let name = read_short_bytes(client).await?;
            Some(String::from_utf8(name).map_err(|_| io::Error::other("host is not UTF-8"))?)
        }
        _ => None,
    })
}

async fn read_short_bytes(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let len = client.read_u8().await?;
    let mut bytes = vec![0u8; usize::from(len)];
    client.read_exact(&mut bytes).await?;
    Ok(bytes)
}

/// Sends a reply. The bound address is always 0.0.0.0:0; clients ignore it.
async fn reply(client: &mut TcpStream, code: u8) -> io::Result<()> {
    client
        .write_all(&[VERSION, code, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0])
        .await
}

#[cfg(test)]
mod tests {
    use super::{constant_time_eq, isolation_key};

    #[test]
    fn constant_time_eq_matches_only_identical_bytes() {
        assert!(constant_time_eq(b"secret", b"secret"));
        assert!(!constant_time_eq(b"secret", b"secreT"));
        assert!(!constant_time_eq(b"secret", b"secre"));
        assert!(constant_time_eq(b"", b""));
    }

    #[test]
    fn isolation_key_with_colons_in_credentials_does_not_collide() {
        let first = isolation_key(b"a:b", b"c");
        let second = isolation_key(b"a", b"b:c");

        assert_ne!(first, second);
    }

    #[test]
    fn isolation_key_with_invalid_utf8_keeps_distinct_bytes_distinct() {
        let first = isolation_key(&[0xff], b"");
        let second = isolation_key(&[0xfe], b"");

        assert_ne!(first, second);
    }
}
