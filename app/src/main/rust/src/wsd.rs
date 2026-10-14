//! WS-Discovery, the protocol a Samba share on a home network answers.
//!
//! The point of asking this rather than mDNS: a Samba server with `wsdd2`
//! advertises `wsdp:Device`/`pub:Computer` over WS-Discovery and nothing over
//! mDNS, so an mDNS browse finds the machines that are not there and misses
//! the one that is. `smb2` has no discovery of its own.
//!
//! Two things this has to get right, both learned from a real responder:
//! a probe is multicast to the group it is listening on, so the socket
//! receives its own probe back and has to tell the two apart by `Action`; and
//! one host answers a probe more than once, so the results have to be
//! deduplicated by endpoint.

use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpStream, ToSocketAddrs, UdpSocket};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, ensure};
use quick_xml::Reader;
use quick_xml::events::Event;

const GROUP: Ipv4Addr = Ipv4Addr::new(239, 255, 255, 250);
const PORT: u16 = 3702;
const PROBE_ACTION: &str = "http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe";
const MATCHES_ACTION: &str = "http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches";

/// NetBIOS name service, where a host answers what it calls itself.
const NBNS_PORT: u16 = 137;

/// The workstation service's NetBIOS suffix: the machine's own name, as
/// opposed to a service it offers or the workgroup it belongs to.
const NBNS_WORKSTATION: u8 = 0x00;

/// One UDP round trip on a LAN takes single-digit milliseconds; this is only
/// long enough to cover a host that is going to answer at all. A host with no
/// name service at all -- an OpenWrt router, for one -- costs this much and
/// then falls through to the metadata request.
const NBNS_TIMEOUT: Duration = Duration::from_millis(800);

/// How long to keep listening after the probe goes out. The specification
/// allows a responder three seconds of randomised delay; waiting longer only
/// delays the list, since a host that answers at all answers at once.
const WINDOW: Duration = Duration::from_millis(2500);

/// A host that answered, named by the address it can be reached at.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Host {
    /// The endpoint reference, used only to drop duplicate answers.
    pub endpoint: String,
    /// The host part of `XAddrs`, which is what a connection needs.
    pub address: String,
    /// The full `XAddrs`, which is where the device answers a metadata request.
    pub xaddrs: String,
    /// The device's advertised types, e.g. `wsdp:Device pub:Computer`.
    pub types: String,
    /// The device's own name, when it gave one. A label only: it is not what
    /// the connection uses, and on a home network it often does not resolve.
    pub name: Option<String>,
}

/// How long to wait for one device's metadata. Short: this runs once per host
/// and a device that is slow to answer is not worth stalling the list for.
const GET_TIMEOUT: Duration = Duration::from_millis(1200);

/// A device that advertises itself as a computer, which is what a file server
/// does. A probe with no `Types` is answered by every device on the network --
/// televisions, printers, cameras -- and only the computers are worth listing
/// in a browser for SMB shares.
const COMPUTER_TYPE: &str = "pub:Computer";

/// Send multicast out one named interface rather than whichever the kernel
/// would pick.
///
/// Shared with the mDNS client, which needs the same thing for the same reason:
/// the group is joined on an interface, and a probe that leaves by another one
/// is answered to an address the join does not cover.
pub(crate) fn pin_multicast_interface(socket: &UdpSocket, interface: Ipv4Addr) -> Result<()> {
    let raw = std::os::fd::AsRawFd::as_raw_fd(socket);
    let address = u32::from(interface).to_be();
    // SAFETY: `raw` is a live socket owned by the caller, and `address` is the
    // network-byte-order form of the interface address.
    let result = unsafe {
        libc::setsockopt(
            raw,
            libc::IPPROTO_IP,
            libc::IP_MULTICAST_IF,
            &address as *const u32 as *const libc::c_void,
            std::mem::size_of::<u32>() as libc::socklen_t,
        )
    };
    ensure!(
        result == 0,
        "pinning the probe to {interface}: {}",
        std::io::Error::last_os_error()
    );
    Ok(())
}

/// Ask the network which hosts speak WS-Discovery.
///
/// Takes the address of the interface to listen on, or `None` to let the
/// kernel choose. Naming it matters on a device with more than one interface:
/// joining the group with `UNSPECIFIED` picks one, and when it is not the
/// interface the answers come back on, the probe goes out and the replies
/// never arrive -- which is indistinguishable from a silent network.
pub fn discover(interface: Option<Ipv4Addr>) -> Result<Vec<Host>> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, PORT))
        .with_context(|| format!("binding UDP {PORT}"))?;
    let interface = interface.unwrap_or(Ipv4Addr::UNSPECIFIED);
    socket
        .join_multicast_v4(&GROUP, &interface)
        .with_context(|| format!("joining the discovery group on {interface}"))?;
    if !interface.is_unspecified() {
        pin_multicast_interface(&socket, interface)?;
    }
    socket.set_multicast_loop_v4(true)?;
    socket.set_read_timeout(Some(WINDOW))?;

    let message_id = message_id();
    let probe = probe(&message_id);
    log::debug!(target: "wsd", "probing {GROUP}:{PORT} as {message_id}");
    socket
        .send_to(probe.as_bytes(), (GROUP, PORT))
        .context("sending the probe")?;

    let mut hosts: Vec<Host> = Vec::new();
    let deadline = Instant::now() + WINDOW;
    let mut buffer = vec![0u8; 65535];
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        socket.set_read_timeout(Some(remaining))?;
        let (len, from) = match socket.recv_from(&mut buffer) {
            Ok(received) => received,
            // The timeout is the end of the window, not a failure.
            Err(err) => {
                log::debug!(target: "wsd", "stopped listening: {err}");
                break;
            }
        };
        // Logged before parsing: a reply that arrives but does not parse is a
        // different bug from a reply that never arrives, and the count of
        // datagrams is the only thing that tells them apart.
        log::debug!(target: "wsd", "datagram from {from} ({len} bytes)");
        let Ok(text) = std::str::from_utf8(&buffer[..len]) else {
            log::debug!(target: "wsd", "not UTF-8, skipped");
            continue;
        };
        if let Some(host) = parse_match(text, from.ip().to_string())
            && !hosts.iter().any(|seen| seen.endpoint == host.endpoint)
        {
            if !host.types.contains(COMPUTER_TYPE) {
                log::debug!(target: "wsd", "{} is not a computer ({})", host.address, host.types);
                continue;
            }
            log::debug!(target: "wsd", "host {} at {}", host.endpoint, host.address);
            hosts.push(host);
        }
    }
    // Metadata needs one HTTP request per host. Do it after collecting the UDP
    // replies, so a slow metadata endpoint cannot cost us another host.
    for host in &mut hosts {
        // The address in `XAddrs` is authoritative, but it is the address the
        // device sees for itself, which is not always the address this device
        // reached it by -- behind a NAT, or with more than one interface, they
        // differ. Falling back to the source of the datagram is what makes the
        // name survive that, and it costs nothing when they already agree.
        // The NetBIOS name first: it is one UDP round trip and it is what the
        // host calls itself. The metadata request is the fallback, for the
        // hosts with no name service -- and it is the only source for those.
        host.name = host
            .address
            .parse::<Ipv4Addr>()
            .ok()
            .and_then(nbns_name)
            .or_else(|| metadata_name(&host.xaddrs, &host.address));
        // Lengths, not the strings: this line is the only place the two fields
        // the request is built from can be seen together, and an empty one is
        // a parsing bug rather than a network one.
        log::debug!(
            target: "wsd",
            "metadata for {}: address={} xaddrs={} name={}",
            host.address,
            host.address.len(),
            host.xaddrs.len(),
            host.name.as_ref().map_or(0, |name| name.len())
        );
    }
    log::info!(target: "wsd", "discovery found {} host(s)", hosts.len());
    Ok(hosts)
}

/// A probe with no `Types`, which by the specification every device must
/// answer. Asking for `wsdp:Device` instead would be narrower and would still
/// match, but a device that advertises something else is still worth showing.
fn probe(message_id: &str) -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery">
<soap:Header>
<wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
<wsa:Action>{PROBE_ACTION}</wsa:Action>
<wsa:MessageID>{message_id}</wsa:MessageID>
</soap:Header>
<soap:Body><wsd:Probe/></soap:Body>
</soap:Envelope>"#
    )
}

/// A message id has to be unique per probe, and it has to *look* like a UUID.
///
/// The shape matters more than the uniqueness: a responder that validates the
/// format drops the whole probe, and a probe that was dropped looks exactly
/// like a host that is not there. Formatting nanoseconds with a minimum width
/// produces nineteen digits in the last group, which is not a UUID, and that
/// is what cost a Windows host its reply.
fn message_id() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or_default();
    // Mixing the pid in keeps probes from one run apart as well, and rotating
    // it keeps the low bits from being where the clock's own low bits are.
    let mixed = nanos ^ (std::process::id() as u128).rotate_left(32);
    format!(
        "urn:uuid:{:08x}-{:04x}-{:04x}-{:04x}-{:012x}",
        (mixed >> 96) & 0xffff_ffff,
        (mixed >> 80) & 0xffff,
        (mixed >> 64) & 0xffff,
        (mixed >> 48) & 0xffff,
        mixed & 0xfff_ffff_ffff
    )
}

/// Read one reply. Returns `None` for anything that is not a `ProbeMatches`
/// for a host we can name -- including our own probe, which the group hands
/// back to us.
fn parse_match(text: &str, fallback: String) -> Option<Host> {
    let mut reader = Reader::from_str(text);
    reader.config_mut().trim_text(true);

    let mut is_match = false;
    let mut endpoint: Option<String> = None;
    let mut address: Option<String> = None;
    let mut xaddrs: Option<String> = None;
    let mut types = String::new();
    let mut field = String::new();
    let mut text_of: Option<&'static str> = None;

    loop {
        match reader.read_event() {
            Ok(Event::Start(element)) => {
                let name = String::from_utf8_lossy(element.name().as_ref()).to_string();
                if name.ends_with("Action") {
                    text_of = Some("action");
                } else if name.ends_with("Address") {
                    text_of = Some("endpoint");
                } else if name.ends_with("XAddrs") {
                    text_of = Some("xaddrs");
                } else if name.ends_with("Types") {
                    text_of = Some("types");
                }
            }
            Ok(Event::Text(value)) => {
                field.clear();
                if let Ok(decoded) = value.decode() {
                    field.push_str(decoded.as_ref());
                }
                match text_of {
                    Some("action") => is_match = field.contains(MATCHES_ACTION),
                    Some("endpoint") if endpoint.is_none() => endpoint = Some(field.clone()),
                    Some("xaddrs") if address.is_none() => {
                        xaddrs = field.split_whitespace().next().map(str::to_owned);
                        address = Some(host_of(&field).unwrap_or_else(|| fallback.clone()));
                    }
                    Some("types") => types = field.clone(),
                    _ => {}
                }
            }
            Ok(Event::End(_)) => text_of = None,
            Ok(Event::Eof) => break,
            Err(_) => break,
            _ => {}
        }
    }

    if !is_match {
        return None;
    }
    let endpoint = endpoint?;
    Some(Host {
        endpoint,
        address: address.unwrap_or(fallback),
        xaddrs: xaddrs.unwrap_or_default(),
        types,
        name: None,
    })
}

/// Ask the NetBIOS name service what the host calls itself.
///
/// One UDP round trip to port 137, answered by Windows, Samba and most NAS.
/// It is here because the WSD metadata endpoint -- the protocol-correct source
/// of the same name -- refuses every shape of request with 500 on Windows,
/// while its NetBIOS name is exactly what is wanted on screen. Neither source
/// covers everything: a host with no name service at all still answers the
/// metadata request, which is why that is kept as the fallback.
fn nbns_name(address: Ipv4Addr) -> Option<String> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    socket.set_read_timeout(Some(NBNS_TIMEOUT)).ok()?;
    let mut query = Vec::with_capacity(50);
    // Transaction id, then one question and nothing else.
    query.extend_from_slice(&[0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
    // The wildcard name in the length-prefixed form NBNS uses: thirty-two
    // bytes of half-ASCII, then the node status type and the internet class.
    query.push(0x20);
    query.extend_from_slice(b"CK");
    query.extend_from_slice(&[b'A'; 30]);
    query.push(0);
    query.extend_from_slice(&[0x00, 0x21, 0x00, 0x01]);
    socket.send_to(&query, (address, NBNS_PORT)).ok()?;
    let mut buffer = [0u8; 1024];
    let len = socket.recv(&mut buffer).ok()?;
    let names = parse_name_table(name_table(&buffer[..len])?);
    machine_name(&names)
}

/// The machine's own name out of a name table.
///
/// The workstation suffix is the machine; the same name with the file-server
/// suffix is a service it offers, and the workgroup carries the group bit.
/// Falling back to any unique name keeps a host with an unusual table usable.
fn machine_name(names: &[NbnsName]) -> Option<String> {
    names
        .iter()
        .find(|name| name.suffix == NBNS_WORKSTATION && !name.group)
        .or_else(|| names.iter().find(|name| !name.group))
        .map(|name| name.name.clone())
}

/// Step over a length-prefixed name, returning the offset just past it.
fn skip_name(data: &[u8], mut offset: usize) -> Option<usize> {
    while let Some(&length) = data.get(offset) {
        if length == 0 {
            return Some(offset + 1);
        }
        if length & 0xc0 == 0xc0 {
            // A compression pointer is the whole name.
            return Some(offset + 2);
        }
        offset += length as usize + 1;
    }
    None
}

/// The name table out of a node status reply.
///
/// A node status reply does not echo the question -- the name at the front is
/// the answer's own -- so the table sits behind the header, that name, the
/// type and class, the time to live and the length. Reading it as a reply that
/// does echo the question lands the length in the middle of a name.
fn name_table(reply: &[u8]) -> Option<&[u8]> {
    if reply.len() < 12 || u16::from_be_bytes([reply[6], reply[7]]) == 0 {
        return None;
    }
    let offset = skip_name(reply, 12)? + 4 + 4;
    let length = u16::from_be_bytes([*reply.get(offset)?, *reply.get(offset + 1)?]) as usize;
    reply.get(offset + 2..offset + 2 + length)
}

/// A name from a node status table, and what it says about itself.
#[derive(Debug)]
struct NbnsName {
    name: String,
    suffix: u8,
    group: bool,
}

/// The names in a node status table.
///
/// Each entry is the fifteen-byte name and the one-byte suffix; Samba's
/// replies carry two more bytes of flags, an eighteen-byte stride, where the
/// documented layout has sixteen and puts the group bit in the suffix. Both
/// strides are tried and the one whose names read as text wins, because the
/// wrong one puts a flag byte inside every name. The browse master's name
/// really does contain control bytes, so it cannot decide this on its own.
fn parse_name_table(table: &[u8]) -> Vec<NbnsName> {
    let count = table.first().copied().unwrap_or(0) as usize;
    let mut best: Vec<NbnsName> = Vec::new();
    let mut best_readable = 0;
    for stride in [18usize, 16] {
        let mut names = Vec::new();
        let mut readable = 0;
        for index in 0..count {
            let start = 1 + index * stride;
            let Some(entry) = table.get(start..start + stride) else {
                break;
            };
            let name = String::from_utf8_lossy(&entry[..15])
                .trim_end_matches([' ', '\0'])
                .to_owned();
            if name.is_empty() {
                continue;
            }
            if name.chars().all(|c| (' '..'\x7f').contains(&c)) {
                readable += 1;
            }
            // Sixteen-byte entries keep the group bit in the suffix byte;
            // eighteen-byte ones in the low byte of the two-byte flags.
            let (suffix, group) = if stride == 18 {
                (entry[15], entry[17] & 0x80 != 0)
            } else {
                (entry[15] & 0x7f, entry[15] & 0x80 != 0)
            };
            names.push(NbnsName {
                name,
                suffix,
                group,
            });
        }
        if readable > best_readable {
            best_readable = readable;
            best = names;
        }
    }
    best
}

/// SOAP 1.2 puts the action in the content type, and a conforming responder
/// will not answer without it. wsdd2 refuses any parameter on the content type
/// outright -- with the action appended it answers 400 -- so the plain form
/// has to be tried first and the conforming one second.
const CONTENT_TYPE: &str = "application/soap+xml";
const CONTENT_TYPE_WITH_ACTION: &str =
    "application/soap+xml; action=\"http://schemas.xmlsoap.org/ws/2004/09/transfer/Get\"";

/// Ask a host for its WS-Device Profile metadata and take the advertised
/// computer name as a display label. The address stays the connection target.
///
/// The advertised URL is asked first, and it is the address the host sees *for
/// itself*: behind a NAT, or with more than one interface, that is not always
/// the address this one reached it by. When the request there fails it is
/// tried again at the source of the datagram, keeping the advertised port and
/// path, which belong to the service rather than to the address.
fn metadata_name(xaddrs: &str, fallback_host: &str) -> Option<String> {
    let (host, port, path) = match endpoint_parts(xaddrs) {
        Some(parts) => parts,
        // Nothing to parse: the datagram's source, on the port the profile
        // service answers on.
        None => {
            log::debug!(
                target: "wsd",
                "metadata: {xaddrs:?} is not an XAddr, asking {fallback_host}:{PORT}"
            );
            (fallback_host.to_owned(), PORT, "/".to_owned())
        }
    };
    let url = format!("http://{host}:{port}{path}");
    if let Some(name) = ask(&host, port, &path, &url) {
        return Some(name);
    }
    if host != fallback_host {
        log::debug!(
            target: "wsd",
            "metadata: {host}:{port} did not answer, asking {fallback_host}:{port}"
        );
        let url = format!("http://{fallback_host}:{port}{path}");
        return ask(fallback_host, port, &path, &url);
    }
    None
}

/// The two content types, in the order that works for both responders: the
/// plain one is the only one wsdd2 accepts, and the one with the action is the
/// only one Windows accepts.
fn ask(host: &str, port: u16, path: &str, url: &str) -> Option<String> {
    request_name(host, port, path, url, CONTENT_TYPE).or_else(|| {
        log::debug!(
            target: "wsd",
            "metadata: {host}:{port} did not answer without the action, asking again with it"
        );
        request_name(host, port, path, url, CONTENT_TYPE_WITH_ACTION)
    })
}

/// One metadata request, to one address, with one content type.
fn request_name(
    host: &str,
    port: u16,
    path: &str,
    url: &str,
    content_type: &str,
) -> Option<String> {
    // Every remaining exit has to say why it left: a name that is missing
    // because a connection was refused, because the address did not resolve,
    // and because the answer was not what was expected are three different
    // bugs, and `None` tells none of them apart.
    let addresses = match (host, port).to_socket_addrs() {
        Ok(addresses) => addresses,
        Err(err) => {
            log::debug!(target: "wsd", "metadata: {host}:{port} does not resolve: {err}");
            return None;
        }
    };
    let address = match addresses.into_iter().next() {
        Some(address) => address,
        None => {
            log::debug!(target: "wsd", "metadata: {host}:{port} resolved to nothing");
            return None;
        }
    };
    let mut stream = match TcpStream::connect_timeout(&address, GET_TIMEOUT) {
        Ok(stream) => stream,
        Err(err) => {
            log::debug!(target: "wsd", "metadata: connect {address} failed: {err}");
            return None;
        }
    };
    for (what, result) in [
        ("read", stream.set_read_timeout(Some(GET_TIMEOUT))),
        ("write", stream.set_write_timeout(Some(GET_TIMEOUT))),
    ] {
        if let Err(err) = result {
            log::debug!(target: "wsd", "metadata: {what} timeout failed: {err}");
            return None;
        }
    }

    let message_id = message_id();
    let body = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wst="http://schemas.xmlsoap.org/ws/2004/09/transfer">
<soap:Header><wsa:To>{url}</wsa:To><wsa:Action>http://schemas.xmlsoap.org/ws/2004/09/transfer/Get</wsa:Action><wsa:MessageID>{message_id}</wsa:MessageID></soap:Header>
<soap:Body><wst:Get/></soap:Body></soap:Envelope>"#
    );
    let request = format!(
        "POST {path} HTTP/1.1\r\nHost: {host}:{port}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    stream
        .write_all(request.as_bytes())
        .map_err(|err| log::debug!(target: "wsd", "metadata: write failed: {err}"))
        .ok()?;

    // wsdd2 and conforming responders close the connection after their reply.
    // Cap it so a broken endpoint cannot turn discovery into an unbounded read.
    let mut response = Vec::new();
    if let Err(err) = stream.take(64 * 1024).read_to_end(&mut response) {
        log::debug!(target: "wsd", "metadata: read from {host}:{port} failed: {err}");
        return None;
    }
    let separator = match response.windows(4).position(|w| w == b"\r\n\r\n") {
        Some(separator) => separator,
        None => {
            log::debug!(
                target: "wsd",
                "metadata: {host}:{port} sent {} bytes with no header separator",
                response.len()
            );
            return None;
        }
    };
    let headers = match std::str::from_utf8(&response[..separator]) {
        Ok(headers) => headers,
        Err(err) => {
            log::debug!(target: "wsd", "metadata: {host}:{port} sent a non-UTF-8 header: {err}");
            return None;
        }
    };
    let status = match headers
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
    {
        Some(status) => status.to_owned(),
        None => {
            log::debug!(
                target: "wsd",
                "metadata: {host}:{port} sent an unreadable status line: {:?}",
                headers.lines().next()
            );
            return None;
        }
    };
    if status != "200" {
        log::debug!(target: "wsd", "metadata: {host}:{port} answered HTTP {status}");
        return None;
    }
    let xml = match std::str::from_utf8(&response[separator + 4..]) {
        Ok(xml) => xml,
        Err(err) => {
            log::debug!(target: "wsd", "metadata: {host}:{port} sent a non-UTF-8 body: {err}");
            return None;
        }
    };
    let name = parse_metadata_name(xml);
    if name.is_none() {
        log::debug!(target: "wsd", "metadata: {host}:{port} sent no name");
    }
    name
}

/// Split the first (and in our caller, only) XAddr into the TCP destination
/// and HTTP request target. WS-Discovery devices advertise plain HTTP URLs.
fn endpoint_parts(url: &str) -> Option<(String, u16, String)> {
    let rest = url.strip_prefix("http://")?;
    let (authority, path) = rest.split_once('/')?;
    let path = format!("/{path}");
    let (host, port) = if let Some(bracketed) = authority.strip_prefix('[') {
        let (host, tail) = bracketed.split_once(']')?;
        let port = tail.strip_prefix(':')?.parse().ok()?;
        (host.to_owned(), port)
    } else if let Some((host, port)) = authority.rsplit_once(':') {
        (host.to_owned(), port.parse().ok()?)
    } else {
        (authority.to_owned(), 80)
    };
    (!host.is_empty()).then_some((host, port, path))
}

/// Prefer `pub:Computer` (wsdd2 uses `COMPUTER/Workgroup:DOMAIN`) over
/// `FriendlyName`, which wsdd2 reports as the generic "Microsoft Publication
/// Service Device Host". Other responders may only provide FriendlyName.
fn parse_metadata_name(xml: &str) -> Option<String> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);
    let mut computer = None;
    let mut friendly = None;
    let mut field: Option<&'static str> = None;
    loop {
        match reader.read_event() {
            Ok(Event::Start(element)) => {
                let tag = String::from_utf8_lossy(element.name().as_ref()).to_string();
                field = if tag.ends_with("Computer") {
                    Some("computer")
                } else if tag.ends_with("FriendlyName") {
                    Some("friendly")
                } else {
                    None
                };
            }
            Ok(Event::Text(value)) => {
                if let Some(which) = field
                    && let Ok(decoded) = value.decode()
                    && let Ok(text) = quick_xml::escape::unescape(&decoded)
                {
                    let value = text.trim();
                    if !value.is_empty() {
                        match which {
                            "computer" if computer.is_none() => {
                                computer = Some(value.split('/').next().unwrap_or(value).to_owned())
                            }
                            "friendly" if friendly.is_none() => friendly = Some(value.to_owned()),
                            _ => {}
                        }
                    }
                }
            }
            Ok(Event::End(_)) => field = None,
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
    }
    computer.or(friendly)
}

/// `XAddrs` is a list of URLs, space separated. The host of the first one is
/// the address to connect to; the path is a per-device identifier.
fn host_of(xaddrs: &str) -> Option<String> {
    let first = xaddrs.split_whitespace().next()?;
    let after_scheme = first.split("://").nth(1)?;
    let authority = after_scheme.split('/').next()?;
    let host = authority
        .rsplit_once(':')
        .map(|(h, _)| h)
        .unwrap_or(authority);
    (!host.is_empty()).then(|| host.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A ProbeMatch as a real `wsdd2` sends it, wrapped as received.
    const MATCH: &str = r#"<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:wsdp="http://schemas.xmlsoap.org/ws/2006/02/devprof" xmlns:pub="http://schemas.microsoft.com/windows/pub/2005/07"><soap:Header><wsa:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:To><wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action><wsa:MessageID>urn:uuid:71b1e493</wsa:MessageID><wsa:RelatesTo>urn:uuid:202168e8</wsa:RelatesTo></soap:Header><soap:Body><wsd:ProbeMatches><wsd:ProbeMatch><wsa:EndpointReference><wsa:Address>urn:uuid:194f1cb3-ebc6-4351-b046-610419940dfa</wsa:Address></wsa:EndpointReference><wsd:Types>wsdp:Device pub:Computer</wsd:Types><wsd:XAddrs>http://172.32.3.100:3702/194f1cb3-ebc6-4351-b046-610419940dfa</wsd:XAddrs><wsd:MetadataVersion>2</wsd:MetadataVersion></wsd:ProbeMatch></wsd:ProbeMatches></soap:Body></soap:Envelope>"#;

    /// Our own probe, handed back by the multicast group. It carries the
    /// `Probe` action, not `ProbeMatches`, and must not become a host.
    const ECHO: &str = r#"<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery"><soap:Header><wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To><wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action><wsa:MessageID>urn:uuid:202168e8</wsa:MessageID></soap:Header><soap:Body><wsd:Probe/></soap:Body></soap:Envelope>"#;

    /// A ProbeMatch from a Windows 10 machine, as received on a phone.
    ///
    /// It is a real responder, and it differs from `wsdd2` in ways that matter:
    /// the metadata address it advertises is port 5357 rather than 3702, and
    /// it carries an `AppSequence` in the header. Neither may be mistaken for a
    /// reason to drop it -- the port belongs to metadata only, and SMB is
    /// always contacted on the port the browser was configured with.
    const WINDOWS: &str = r#"<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:wsdp="http://schemas.xmlsoap.org/ws/2006/02/devprof" xmlns:pub="http://schemas.microsoft.com/windows/pub/2005/07"><soap:Header><wsa:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:To><wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action><wsa:MessageID>urn:uuid:f7a189d7-649a-48e0-bf29-4d7fedb24772</wsa:MessageID><wsa:RelatesTo>urn:uuid:48326a7c-2380-44ab-b63d-4e06645d4ce1</wsa:RelatesTo><wsd:AppSequence InstanceId="212" SequenceId="urn:uuid:45657af3-1a54-40a2-90c6-967c7d2329fb" MessageNumber="21"></wsd:AppSequence></soap:Header><soap:Body><wsd:ProbeMatches><wsd:ProbeMatch><wsa:EndpointReference><wsa:Address>urn:uuid:6b0383b4-9e60-4c45-88cf-4c0e9e3920f5</wsa:Address></wsa:EndpointReference><wsd:Types>wsdp:Device pub:Computer</wsd:Types><wsd:XAddrs>http://192.168.1.132:5357/6b0383b4-9e60-4c45-88cf-4c0e9e3920f5/</wsd:XAddrs><wsd:MetadataVersion>12</wsd:MetadataVersion></wsd:ProbeMatch></wsd:ProbeMatches></soap:Body></soap:Envelope>"#;

    #[test]
    fn message_ids_are_uuid_shaped_and_distinct() {
        // A responder that validates the format drops the whole probe, and a
        // dropped probe is indistinguishable from a host that is not there.
        let id = message_id();
        let digits = id.strip_prefix("urn:uuid:").expect("a urn:uuid prefix");
        let groups: Vec<&str> = digits.split('-').collect();
        assert_eq!(groups.len(), 5, "{id}");
        for (group, width) in groups.iter().zip([8, 4, 4, 4, 12]) {
            assert_eq!(group.len(), width, "group {group} of {id}");
            assert!(group.chars().all(|c| c.is_ascii_hexdigit()), "{id}");
        }
        // Two probes in the same run must not share an id: a responder echoes
        // it back, and a collision would let one host's reply answer another's.
        assert_ne!(message_id(), message_id());
    }

    #[test]
    fn reads_a_windows_10_match() {
        let host = parse_match(WINDOWS, "10.0.0.9".to_owned()).expect("a match");
        assert_eq!(
            host.endpoint,
            "urn:uuid:6b0383b4-9e60-4c45-88cf-4c0e9e3920f5"
        );
        // The SMB address is the host, whatever port the metadata lives on.
        assert_eq!(host.address, "192.168.1.132");
        assert_eq!(
            host.xaddrs,
            "http://192.168.1.132:5357/6b0383b4-9e60-4c45-88cf-4c0e9e3920f5/"
        );
        assert!(host.types.contains(COMPUTER_TYPE), "got {:?}", host.types);
    }

    #[test]
    fn reads_the_address_from_a_real_match() {
        let host = parse_match(MATCH, "10.0.0.9".to_owned()).expect("a match");
        assert_eq!(
            host.endpoint,
            "urn:uuid:194f1cb3-ebc6-4351-b046-610419940dfa"
        );
        assert_eq!(host.address, "172.32.3.100");
        assert_eq!(
            host.xaddrs,
            "http://172.32.3.100:3702/194f1cb3-ebc6-4351-b046-610419940dfa"
        );
        assert_eq!(host.name, None);
    }

    /// The `XAddrs` text has to reach the metadata step: an empty one sends the
    /// request nowhere, and the log then reads "tried  then " with nothing in
    /// it -- a mystery rather than the plain fact that the reply was misread.
    #[test]
    fn the_xaddrs_of_the_wsdd2_reply_is_kept() {
        let host = parse_match(MATCH, "10.0.0.9".to_owned()).expect("a match");
        assert!(
            !host.xaddrs.is_empty(),
            "xaddrs is empty while the address is {}",
            host.address
        );
        assert!(host.xaddrs.starts_with("http://"), "{:?}", host.xaddrs);
    }

    #[test]
    fn reads_the_advertised_types() {
        let host = parse_match(MATCH, "10.0.0.9".to_owned()).expect("a match");
        assert!(host.types.contains("pub:Computer"), "got {:?}", host.types);
    }

    #[test]
    fn ignores_the_probe_coming_back() {
        assert_eq!(parse_match(ECHO, "10.0.0.9".to_owned()), None);
    }

    #[test]
    fn falls_back_to_the_sender_when_xaddrs_is_absent() {
        let without = MATCH.replace("<wsd:XAddrs>http://172.32.3.100:3702/194f1cb3-ebc6-4351-b046-610419940dfa</wsd:XAddrs>", "");
        let host = parse_match(&without, "10.0.0.9".to_owned()).expect("a match");
        assert_eq!(host.address, "10.0.0.9");
    }

    #[test]
    fn reads_the_computer_name_from_wsdd2_metadata() {
        let metadata = r#"<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsx="http://schemas.xmlsoap.org/ws/2004/09/mex" xmlns:wsdp="http://schemas.xmlsoap.org/ws/2006/02/devprof" xmlns:pub="http://schemas.microsoft.com/windows/pub/2005/07"><soap:Body><wsx:Metadata><wsx:MetadataSection><wsdp:ThisDevice><wsdp:FriendlyName>Microsoft Publication Service Device Host</wsdp:FriendlyName></wsdp:ThisDevice></wsx:MetadataSection><wsx:MetadataSection><wsdp:Relationship><wsdp:Host><pub:Computer>HERMES1/Workgroup:WORKGROUP</pub:Computer></wsdp:Host></wsdp:Relationship></wsx:MetadataSection></wsx:Metadata></soap:Body></soap:Envelope>"#;
        assert_eq!(parse_metadata_name(metadata).as_deref(), Some("HERMES1"));
    }

    #[test]
    fn falls_back_to_friendly_name_when_computer_name_is_absent() {
        let metadata = r#"<root><wsdp:FriendlyName>My NAS</wsdp:FriendlyName></root>"#;
        assert_eq!(parse_metadata_name(metadata).as_deref(), Some("My NAS"));
    }

    #[test]
    fn parses_http_metadata_endpoint() {
        assert_eq!(
            endpoint_parts("http://192.0.2.1:3702/uuid"),
            Some(("192.0.2.1".to_owned(), 3702, "/uuid".to_owned()))
        );
        assert_eq!(
            endpoint_parts("http://[fe80::1]:3702/uuid"),
            Some(("fe80::1".to_owned(), 3702, "/uuid".to_owned()))
        );
    }

    #[test]
    fn parses_the_xaddr_a_wsdd2_actually_advertises() {
        let parts =
            endpoint_parts("http://192.168.1.240:3702/7e24ab30-200e-4fca-a527-e10fd7ea4d55");
        assert_eq!(
            parts,
            Some((
                "192.168.1.240".to_owned(),
                3702,
                "/7e24ab30-200e-4fca-a527-e10fd7ea4d55".to_owned()
            ))
        );
        let parts =
            endpoint_parts("http://192.168.1.132:5357/6b0383b4-9e60-4c45-88cf-4c0e9e3920f5/");
        assert_eq!(
            parts,
            Some((
                "192.168.1.132".to_owned(),
                5357,
                "/6b0383b4-9e60-4c45-88cf-4c0e9e3920f5/".to_owned()
            ))
        );
    }

    #[test]
    fn takes_the_host_out_of_a_url() {
        assert_eq!(
            host_of("http://172.32.3.100:3702/x").as_deref(),
            Some("172.32.3.100")
        );
        assert_eq!(host_of("http://nas.local/x").as_deref(), Some("nas.local"));
        assert_eq!(
            host_of("http://[fe80::1]:3702/x").as_deref(),
            Some("[fe80::1]")
        );
        assert_eq!(host_of(""), None);
    }

    /// A metadata request against a device that answers the way `wsdd2` does:
    /// an HTTP response whose only separator is CRLF, with the name inside
    /// `pub:Computer`. This is what proves the request goes out with real CRLF
    /// and the reply is split on the same, neither of which a reader can see in
    /// the source as reliably as a server can.
    #[test]
    fn reads_the_name_from_a_metadata_endpoint() {
        use std::io::Write as _;

        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let body = concat!(
            r#"<soap:Envelope xmlns:pub="http://schemas.microsoft.com/windows/pub/2005/07">"#,
            r#"<soap:Body><wsx:Metadata><pub:Computer>TESTHOST/Workgroup:WORKGROUP</pub:Computer>"#,
            r#"</wsx:Metadata></soap:Body></soap:Envelope>"#
        );
        std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            let mut seen = [0u8; 8192];
            let len = stream.read(&mut seen).expect("read the request");
            let request = std::str::from_utf8(&seen[..len]).expect("the request is text");
            // A header separator that is not CRLF makes the request line
            // unparseable and the device answers 400, so this is the assertion
            // that matters most in this test.
            assert!(request.starts_with("POST /uuid HTTP/1.1\r\n"), "{request}");
            assert!(request.contains("Host: 127.0.0.1:"), "{request}");
            // A charset parameter on the content type is answered with 400 by
            // at least one real responder, so it has to stay bare.
            assert!(
                request.contains("Content-Type: application/soap+xml\r\n"),
                "{request}"
            );
            assert!(request.contains("<wst:Get/>"), "{request}");
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/soap+xml\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            stream.write_all(response.as_bytes()).expect("write");
        });

        let name = metadata_name(&format!("http://127.0.0.1:{port}/uuid"), "127.0.0.1");
        assert_eq!(name.as_deref(), Some("TESTHOST"));
    }

    /// A conforming responder wants the action in the content type and answers
    /// a request without it with an error -- Windows answers 500 -- while wsdd2
    /// wants the bare type and answers 400 when a parameter is present. The
    /// request is therefore tried both ways, and this holds that the second one
    /// happens and that its answer is the one used.
    #[test]
    fn asks_again_with_the_action_when_the_bare_type_is_refused() {
        use std::io::Write as _;

        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let body = concat!(
            r#"<soap:Envelope xmlns:pub="http://schemas.microsoft.com/windows/pub/2005/07">"#,
            r#"<soap:Body><wsx:Metadata><pub:Computer>WINBOX/Workgroup:WORKGROUP</pub:Computer>"#,
            r#"</wsx:Metadata></soap:Body></soap:Envelope>"#
        );
        std::thread::spawn(move || {
            for attempt in 0..2 {
                let (mut stream, _) = listener.accept().expect("accept");
                let mut seen = [0u8; 8192];
                let len = stream.read(&mut seen).expect("read the request");
                let request = std::str::from_utf8(&seen[..len]).expect("text");
                if attempt == 0 {
                    assert!(
                        request.contains("Content-Type: application/soap+xml\r\n"),
                        "{request}"
                    );
                    stream
                        .write_all(
                            b"HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n",
                        )
                        .expect("write");
                } else {
                    assert!(
                        request.contains("Content-Type: application/soap+xml; action=\"http://schemas.xmlsoap.org/ws/2004/09/transfer/Get\"\r\n"),
                        "{request}"
                    );
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    );
                    stream.write_all(response.as_bytes()).expect("write");
                }
            }
        });

        let name = metadata_name(&format!("http://127.0.0.1:{port}/uuid"), "127.0.0.1");
        assert_eq!(name.as_deref(), Some("WINBOX"));
    }

    /// The advertised URL is the address the host sees for itself, which is not
    /// always the address it was reached at: behind a NAT it is a private one.
    /// The retry has to keep the advertised port, because the port belongs to
    /// the service -- retrying on the discovery port instead is a timeout for
    /// every host whose profile lives somewhere else, which is every Windows.
    #[test]
    fn falls_back_to_the_sender_on_the_advertised_port() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let asked = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let seen = asked.clone();
        std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            let mut buffer = [0u8; 4096];
            let len = stream.read(&mut buffer).expect("read");
            seen.lock()
                .expect("lock")
                .push(String::from_utf8_lossy(&buffer[..len]).to_string());
        });

        // The advertised host is unreachable, so the only way this can succeed
        // is the fallback, and it has to arrive at the advertised port.
        let name = metadata_name(&format!("http://192.0.2.1:{port}/uuid"), "127.0.0.1");
        assert_eq!(name, None, "nothing was served, so there is no name");
        let requests = asked.lock().expect("lock");
        assert_eq!(requests.len(), 1, "the fallback request never arrived");
        assert!(
            requests[0].starts_with("POST /uuid HTTP/1.1\r\n"),
            "{}",
            requests[0]
        );
    }

    /// The name table a Samba host really sent, stride and all.
    fn samba_table() -> Vec<u8> {
        let mut table = vec![7u8];
        for (name, suffix, flags) in [
            ("HERMES1", 0x00u8, [0x04u8, 0x00]),
            ("HERMES1", 0x03, [0x04, 0x00]),
            ("HERMES1", 0x20, [0x04, 0x00]),
            // The browse master's name is these bytes, control characters
            // included, and it is the reason a control byte cannot be taken as
            // proof that the stride is wrong.
            ("\u{1}\u{2}__MSBROWSE__\u{2}", 0x01, [0x84, 0x00]),
            ("WORKGROUP", 0x00, [0x84, 0x00]),
            ("WORKGROUP", 0x1d, [0x04, 0x00]),
            ("WORKGROUP", 0x1e, [0x84, 0x00]),
        ] {
            let mut entry = vec![b' '; 15];
            entry[..name.len()].copy_from_slice(name.as_bytes());
            entry.push(suffix);
            entry.extend_from_slice(&flags);
            table.extend_from_slice(&entry);
        }
        table
    }

    /// A node status reply around a table. The question is not echoed, and a
    /// reply read as if it were puts the length field in the middle of a name.
    fn nbns_reply(table: &[u8]) -> Vec<u8> {
        let mut reply = vec![0x12, 0x34, 0x84, 0x00, 0, 0, 0, 1, 0, 0, 0, 0];
        reply.push(0x20);
        reply.extend_from_slice(b"CK");
        reply.extend_from_slice(&[b'A'; 30]);
        reply.push(0);
        reply.extend_from_slice(&[0x00, 0x21, 0x00, 0x01]);
        reply.extend_from_slice(&[0, 0, 0, 0]);
        reply.extend_from_slice(&(table.len() as u16).to_be_bytes());
        reply.extend_from_slice(table);
        reply
    }

    #[test]
    fn reads_the_machine_name_from_a_samba_name_table() {
        let table = samba_table();
        assert_eq!(name_table(&nbns_reply(&table)), Some(&table[..]));
        let names = parse_name_table(&table);
        // The control bytes in the browse master's name must not be read as a
        // wrong stride, and the workgroup must not be read as the machine.
        assert!(names.iter().any(|name| name.name.contains("MSBROWSE")));
        assert_eq!(machine_name(&names).as_deref(), Some("HERMES1"));
    }

    /// Windows writes the documented sixteen-byte entries, where the group bit
    /// lives in the suffix byte rather than in a pair of flag bytes, and its
    /// name is the one the metadata request could not be made to return.
    #[test]
    fn reads_the_machine_name_from_a_windows_name_table() {
        let mut table = vec![3u8];
        for (name, suffix) in [
            ("DESKTOP-9CVQRS1", 0x00u8),
            // The workgroup, with the group bit in the suffix byte.
            ("WORKGROUP", 0x80),
            ("DESKTOP-9CVQRS1", 0x20),
        ] {
            let mut entry = vec![b' '; 15];
            entry[..name.len()].copy_from_slice(name.as_bytes());
            entry.push(suffix);
            table.extend_from_slice(&entry);
        }
        let names = parse_name_table(&table);
        assert_eq!(names.len(), 3, "{names:?}");
        assert_eq!(machine_name(&names).as_deref(), Some("DESKTOP-9CVQRS1"));
    }
}
