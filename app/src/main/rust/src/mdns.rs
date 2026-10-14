//! mDNS (DNS-SD) discovery, the other way a file server announces itself.
//!
//! The point of asking this as well as WS-Discovery: a share on a host with
//! avahi advertises `_smb._tcp` over mDNS and may not speak WS-Discovery at
//! all, while a Samba server with `wsdd2` does the opposite. Neither covers
//! every host, and the two together cover the ones either misses.
//!
//! What mDNS gives that WS-Discovery does not is the port, in the same answer
//! as the name: a `PTR` names the instance, its `SRV` carries the port and the
//! host name, and the host name's `A` record carries the address. One query,
//! three records, no second request.
//!
//! Written out rather than pulled in: the messages are a header, a question and
//! a list of records, and the crate that would parse them would bring a runtime
//! and a dependency tree along with it.

use std::collections::HashMap;
use std::net::{Ipv4Addr, UdpSocket};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};

const GROUP: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 251);
const PORT: u16 = 5353;

/// The service a share answers to. `_smb._tcp` is what Samba and macOS
/// advertise for SMB, and it is the only one worth asking for here.
const SERVICE: &str = "_smb._tcp.local";

/// mDNS answers are immediate -- a responder that is going to answer does so at
/// once, the delay in the specification is for the multicast case it is
/// avoiding -- so the window is short on purpose.
const WINDOW: Duration = Duration::from_millis(1200);

const TYPE_A: u16 = 1;
const TYPE_PTR: u16 = 12;
const TYPE_SRV: u16 = 33;

/// A file server found by asking mDNS.
pub struct MdnsHost {
    pub name: String,
    pub address: Ipv4Addr,
    pub port: u16,
}

/// Ask the network which hosts advertise an SMB share over mDNS.
///
/// Takes the address of the interface to listen on, or `None` to let the
/// kernel choose, for the same reason WS-Discovery does: with more than one
/// interface up, the one the group is joined on is not always the one the
/// answers come back on.
pub fn discover(interface: Option<Ipv4Addr>) -> Result<Vec<MdnsHost>> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, PORT))
        .with_context(|| format!("binding UDP {PORT}"))?;
    let interface = interface.unwrap_or(Ipv4Addr::UNSPECIFIED);
    socket
        .join_multicast_v4(&GROUP, &interface)
        .with_context(|| format!("joining the mdns group on {interface}"))?;
    if !interface.is_unspecified() {
        crate::wsd::pin_multicast_interface(&socket, interface)?;
    }
    socket.set_multicast_loop_v4(true)?;

    let query = query();
    log::debug!(target: "mdns", "asking for {SERVICE} on {GROUP}:{PORT}");
    socket
        .send_to(&query, (GROUP, PORT))
        .context("sending the mdns query")?;

    let deadline = Instant::now() + WINDOW;
    let mut buffer = vec![0u8; 9000];
    let mut hosts: Vec<MdnsHost> = Vec::new();
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
                log::debug!(target: "mdns", "stopped listening: {err}");
                break;
            }
        };
        // Logged before parsing: a reply that arrives but does not parse is a
        // different bug from one that never arrives.
        log::debug!(target: "mdns", "datagram from {from} ({len} bytes)");
        for host in parse(&buffer[..len]) {
            log::debug!(target: "mdns", "{} at {}:{}", host.name, host.address, host.port);
            hosts.push(host);
        }
    }
    // A responder repeats its records, and two interfaces can deliver the same
    // answer twice; the list is a list of hosts, not of answers.
    hosts.sort_by_key(|host| (host.address, host.port));
    hosts.dedup_by_key(|host| (host.address, host.port));
    log::info!(target: "mdns", "discovery found {} host(s)", hosts.len());
    Ok(hosts)
}

/// A one-shot query for the service, asking for the answer to be unicast.
///
/// The top bit of the class is the unicast-response bit: a responder that
/// honours it answers to this socket rather than to the group, which is what a
/// client that is not listening on the group port needs.
fn query() -> Vec<u8> {
    let mut query = vec![0u8, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0];
    for label in SERVICE.split('.') {
        query.push(label.len() as u8);
        query.extend_from_slice(label.as_bytes());
    }
    query.push(0);
    query.extend_from_slice(&TYPE_PTR.to_be_bytes());
    query.extend_from_slice(&0x8001u16.to_be_bytes());
    query
}

/// Read a name, following compression pointers.
///
/// Returns the name and the offset just past it *in the record it came from*,
/// which is not where the pointer led: a name is usually a pointer, and what
/// follows it in the message is the rest of the record.
fn read_name(data: &[u8], offset: usize) -> Option<(String, usize)> {
    let mut name = String::new();
    let mut cursor = offset;
    let mut after: Option<usize> = None;
    let mut jumps = 0;
    loop {
        let length = *data.get(cursor)?;
        if length == 0 {
            cursor += 1;
            break;
        }
        if length & 0xc0 == 0xc0 {
            // A pointer is two bytes: the top two bits of the first are the
            // marker, and the remaining fourteen bits are the offset.
            let next = ((length as u16 & 0x3f) << 8) | *data.get(cursor + 1)? as u16;
            after = after.or(Some(cursor + 2));
            jumps += 1;
            // A pointer is meant to point backwards. Without a cap, a malformed
            // one turns this into an infinite loop.
            if jumps > 16 {
                return None;
            }
            cursor = next as usize;
            continue;
        }
        let label = data.get(cursor + 1..cursor + 1 + length as usize)?;
        if !name.is_empty() {
            name.push('.');
        }
        name.push_str(&String::from_utf8_lossy(label));
        cursor += 1 + length as usize;
    }
    Some((name, after.unwrap_or(cursor)))
}

/// One record of a response, kept as offsets into the message.
struct Record {
    name: String,
    rtype: u16,
    rdata: usize,
    length: usize,
}

fn records(data: &[u8]) -> Option<Vec<Record>> {
    if data.len() < 12 {
        return None;
    }
    let questions = u16::from_be_bytes([data[4], data[5]]) as usize;
    let answers = u16::from_be_bytes([data[6], data[7]]) as usize;
    let mut offset = 12;
    for _ in 0..questions {
        let (_, next) = read_name(data, offset)?;
        offset = next + 4;
    }
    let mut out = Vec::with_capacity(answers);
    for _ in 0..answers {
        let (name, next) = read_name(data, offset)?;
        let rtype = u16::from_be_bytes([*data.get(next)?, *data.get(next + 1)?]);
        let length = u16::from_be_bytes([*data.get(next + 8)?, *data.get(next + 9)?]) as usize;
        let rdata = next + 10;
        data.get(rdata..rdata + length)?;
        out.push(Record {
            name,
            rtype,
            rdata,
            length,
        });
        offset = rdata + length;
    }
    Some(out)
}

/// The hosts in one response.
///
/// The records arrive in no particular order and refer to each other by name,
/// so this reads them all first and then joins them: an instance from its
/// `PTR`, its port and host from the `SRV`, and the address from the host's `A`.
fn parse(data: &[u8]) -> Vec<MdnsHost> {
    let Some(records) = records(data) else {
        return Vec::new();
    };
    let mut addresses: HashMap<String, Ipv4Addr> = HashMap::new();
    let mut services: HashMap<String, (u16, String)> = HashMap::new();
    let mut instances: Vec<String> = Vec::new();
    for record in &records {
        match record.rtype {
            TYPE_A if record.length == 4 => {
                let octets: [u8; 4] = match data[record.rdata..record.rdata + 4].try_into() {
                    Ok(octets) => octets,
                    Err(_) => continue,
                };
                addresses.insert(record.name.clone(), Ipv4Addr::from(octets));
            }
            TYPE_SRV if record.length > 6 => {
                let port = u16::from_be_bytes([data[record.rdata + 4], data[record.rdata + 5]]);
                if let Some((target, _)) = read_name(data, record.rdata + 6) {
                    services.insert(record.name.clone(), (port, target));
                }
            }
            TYPE_PTR if record.name == SERVICE => {
                if let Some((instance, _)) = read_name(data, record.rdata) {
                    instances.push(instance);
                }
            }
            _ => {}
        }
    }
    instances
        .iter()
        .filter_map(|instance| {
            let (port, target) = services.get(instance)?;
            // The address record is for the host the service points at. A
            // responder that names it differently still starts with the same
            // label, which is the fallback that keeps it findable.
            let address = addresses.get(target).or_else(|| {
                let label = instance.split('.').next()?;
                addresses
                    .iter()
                    .find(|(name, _)| name.split('.').next() == Some(label))
                    .map(|(_, address)| address)
            })?;
            let name = instance
                .strip_suffix(&format!(".{SERVICE}"))
                .unwrap_or(instance)
                .to_owned();
            Some(MdnsHost {
                name,
                address: *address,
                port: *port,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(text: &str) -> Vec<u8> {
        text.split_whitespace()
            .map(|byte| u8::from_str_radix(byte, 16).expect("hex byte"))
            .collect()
    }

    /// A response avahi actually sent for `_smb._tcp.local`: one instance, its
    /// SRV, and the A and AAAA records for the host it points at.
    const AVAHI: &str = "
        00 00 84 00 00 01 00 05 00 00 00 00
        04 5f 73 6d 62 04 5f 74 63 70 05 6c 6f 63 61 6c 00 00 0c 00 01
        c0 0c 00 0c 00 01 00 00 00 0a 00 0a 07 68 65 72 6d 65 73 31 c0 0c
        c0 2d 00 10 00 01 00 00 00 0a 00 01 00
        c0 2d 00 21 00 01 00 00 00 0a 00 10 00 00 00 00 01 bd 07 68 65 72 6d 65 73 31 c0 16
        c0 56 00 1c 00 01 00 00 00 0a 00 10 fe 80 00 00 00 00 00 00 00 42 0f ff fe 18 69 a2
        c0 56 00 01 00 01 00 00 00 0a 00 04 ac 11 00 01
    ";

    #[test]
    fn reads_a_host_from_an_avahi_response() {
        let data = hex(AVAHI);
        assert_eq!(data.len(), 140, "the capture is 140 bytes");
        let parsed = records(&data).expect("the records parse");
        assert_eq!(parsed.len(), 5, "one ptr, one txt, one srv, two addresses");
        let hosts = parse(&data);
        assert_eq!(hosts.len(), 1, "one instance, one host");
        assert_eq!(hosts[0].name, "hermes1");
        assert_eq!(hosts[0].address, Ipv4Addr::new(172, 17, 0, 1));
        // The port comes from the SRV record, which is the whole reason this is
        // worth doing: WS-Discovery needs a second request to learn the same.
        assert_eq!(hosts[0].port, 445);
    }

    /// The query has to name the service, ask for a PTR, and set the
    /// unicast-response bit, or a responder answers to the group and this
    /// socket hears nothing.
    #[test]
    fn asks_for_the_service_by_name() {
        let query = query();
        assert_eq!(&query[..12], &[0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
        let (name, after) = read_name(&query, 12).expect("a name");
        assert_eq!(name, SERVICE);
        assert_eq!(&query[after..after + 2], &TYPE_PTR.to_be_bytes());
        assert_eq!(&query[after + 2..after + 4], &[0x80, 0x01]);
    }

    #[test]
    fn reads_a_name_that_points_at_itself_without_spinning() {
        // A pointer to the offset it sits at is malformed, and following it is
        // an infinite loop unless the reader stops.
        let data = [0xc0, 0x00];
        assert!(read_name(&data, 0).is_none());
    }
}
