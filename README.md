# BaitBlock

## BaitBlock Interception Spike - How It Works

### Summary

Part 1 of BaitBlock is a VpnService that captures only DNS traffic (not full browsing traffic), parses each query down to the domain name, and decides whether to let it through or block it. It works by creating a minimal VPN tunnel that routes just the device's DNS queries into the app, leaving all other traffic untouched. Each query is read as a raw byte packet, parsed layer by layer (IP → UDP → DNS), and the domain is extracted. A stub check (currently one hardcoded domain) decides whether to forward the query to a real resolver or reply with NXDOMAIN to block it. Both reply paths write a hand-built IP+UDP+DNS response back into the tunnel.

As of the last test run, interception and domain logging work correctly, but forwarding fails with a SocketException: socket failed: EPERM when creating the DatagramSocket inside forwardQuery() - before protect() even runs. This is an open bug to debug next session.

### How It Works, Step By Step

1. Consent and setup (parts 2/3, already built): MainActivity requests notification permission, then calls VpnService.prepare(). If the user hasn't granted VPN access yet, Android shows a system consent dialog. Once accepted, the activity calls startForegroundService() to launch BaitBlockVpnService.
2. Foreground service requirement: onStartCommand() calls startForeground() immediately, with a notification built from a low-importance notification channel. This is mandatory - skipping it crashes the service within seconds on Android 8+. Because the app targets SDK 34, the manifest also needs a <property> tag declaring the specialUse foreground service subtype, or the service throws at startup.
3. Establishing the tunnel: establishVpn() builds a VpnService.Builder with: a private tunnel IP (10.0.0.2/32), a fake DNS server address (10.0.0.1), and a route that sends only traffic to 10.0.0.1 into the tunnel. This is the key trick - routing just the DNS server address, not 0.0.0.0/0, means normal app traffic (HTTP, HTTPS, etc.) is untouched and only DNS lookups get redirected into the app.
4. Read loop: a background Thread continuously reads raw IP packets from a FileInputStream wrapping the tunnel's file descriptor. Each packet is handed to a parser.
5. Packet parsing (parseDnsQuery): walks the byte buffer layer by layer:
    1. IPv4 header: checks version is 4, reads the header length (IHL field), confirms protocol byte is 17 (UDP).
    2. UDP header: confirms destination port is 53 (DNS).
    3. DNS header: skips the fixed 12-byte header, confirms at least one question.
    4. QNAME parsing: reads length-prefixed labels (e.g. 3www6google3com0) and joins them into a dotted domain string. Anything that doesn't match this shape (non-IPv4, non-UDP, not port 53, malformed) returns null and is silently skipped - this keeps one malformed packet from crashing the whole read loop.
6. Decision (checkDomain): currently a stub - checks the domain against one hardcoded string (example-blocked-test.com). This is designed to be swapped for a real Bloom filter / rules-based detector later without touching the surrounding forward/block plumbing.
7a. Block path (respondNxDomain): instead of building a DNS response from scratch, it copies the original query bytes and flips two flag bytes (byte 2 and 3) to mark the message as a response with RCODE = 3 (NXDOMAIN). This works because the question section and transaction ID in the query are already correct for the reply.
7b. Forward path (forwardQuery): opens a DatagramSocket, calls protect() on it (critical - without this, the forwarded packet re-enters the app's own tunnel and loops forever), sends the raw DNS query bytes to a real resolver (8.8.8.8:53), waits for a reply, and relays the raw response bytes back.
8. Building the reply packet (buildIpUdpPacket): constructs a minimal 20-byte IPv4 header + 8-byte UDP header + DNS payload, swapping source/destination so the reply appears to come from 10.0.0.1:53 back to the device. The IPv4 header checksum is computed properly (required); the UDP checksum is left at 0, which is valid for IPv4.
9. Writing back: the completed packet is written to a FileOutputStream wrapping the same tunnel file descriptor, which Android delivers back to the device's network stack as if it came from the DNS server.

### Known Limitations (by design, for a spike)

1. Forwarding runs synchronously on the read-loop thread - one DNS lookup at a time, no concurrency.
2. The domain check is a single hardcoded string, not a real detector.
3. isProtected state in MainActivity is still hardcoded false.
4. IPv4 only - no IPv6 handling.
