package com.baitblock.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.baitblock.app.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class BaitBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "BaitBlockVpn"
        private const val CHANNEL_ID = "baitblock_protection"
        private const val NOTIFICATION_ID = 1
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val DNS_ADDRESS = "10.0.0.1"
        private val DNS_ADDRESS_BYTES = byteArrayOf(10, 0, 0, 1)
        private const val UPSTREAM_RESOLVER = "8.8.8.8"

        // Milestone 2 stub: hardcode a domain to block regardless of the
        // (not yet built) detector. Type this into Chrome's address bar to
        // test the block path; it never needs to resolve for real, since a
        // blocked query is answered with NXDOMAIN and never forwarded.
        private const val BLOCKED_TEST_DOMAIN = "youtube.com"
    }

    /** Everything extracted from one raw DNS query packet, needed to reply. */
    private data class DnsQuery(
        val domain: String,
        val sourceAddress: ByteArray,
        val sourcePort: Int,
        val rawQuery: ByteArray
    )

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunOutput: FileOutputStream? = null

    @Volatile
    private var running = false
    private var readThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        establishVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        // System or user revoked VPN permission (e.g. another VPN app took over).
        stopVpn()
        super.onRevoke()
    }

    private fun establishVpn() {
        if (vpnInterface != null) return // already running

        val builder = Builder()
            .setSession("BaitBlock")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32) // only DNS traffic enters the tunnel

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            null
        }

        val fd = vpnInterface ?: run {
            stopSelf()
            return
        }

        tunOutput = FileOutputStream(fd.fileDescriptor)
        running = true
        readThread = Thread({ readLoop(fd) }, "BaitBlockReadLoop").apply { start() }
    }

    private fun stopVpn() {
        running = false
        readThread?.interrupt()
        readThread = null
        try {
            tunOutput?.close()
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        tunOutput = null
        vpnInterface = null
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (running) {
            val length = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Error reading from VPN interface", e)
                break
            }
            if (length <= 0) continue

            val output = tunOutput ?: continue

            try {
                val query = parseDnsQuery(buffer, length)
                if (query != null) {
                    Log.d(TAG, "DNS query: ${query.domain}")
                    checkDomain(query, output)
                }
            } catch (e: Exception) {
                // Malformed/unexpected packet shape — never let one bad packet
                // kill the read loop.
                Log.w(TAG, "Failed to parse packet", e)
            }
        }
    }

    /**
     * Decides whether to block or forward, and writes the reply back into
     * the tunnel either way.
     *
     * Currently checks against one hardcoded domain (milestone 2 stub).
     * Replace this check with the real detector (Bloom filter + rules) once
     * it exists — the forward/block plumbing below it does not need to
     * change.
     */
    private fun checkDomain(query: DnsQuery, tunOutput: FileOutputStream) {
        val isBlocked = query.domain.equals(BLOCKED_TEST_DOMAIN, ignoreCase = true) ||
            query.domain.endsWith(".$BLOCKED_TEST_DOMAIN", ignoreCase = true)
        if (isBlocked) {
            Log.d(TAG, "BLOCKED: ${query.domain}")
            respondNxDomain(query, tunOutput)
        } else {
            forwardQuery(query, tunOutput)
        }
    }

    /**
     * Builds an NXDOMAIN reply by flipping two flag bytes on the original
     * query and sending it straight back — cheaper and less error-prone
     * than constructing a DNS response from scratch, since the question
     * section (and transaction ID) are already correct as-is.
     */
    private fun respondNxDomain(query: DnsQuery, tunOutput: FileOutputStream) {
        val response = query.rawQuery.copyOf()
        response[2] = 0x81.toByte() // QR=1 (response), RD copied through
        response[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        val packet = buildIpUdpPacket(
            sourceAddr = DNS_ADDRESS_BYTES, sourcePort = 53,
            destAddr = query.sourceAddress, destPort = query.sourcePort,
            payload = response
        )
        writeToTun(tunOutput, packet)
    }

    /**
     * Forwards the raw query to a real resolver and relays the raw reply
     * back into the tunnel. Runs synchronously on the read-loop thread, so
     * for now DNS lookups are serialized one at a time — fine for a spike,
     * but worth revisiting (e.g. a small thread pool) if lookups start
     * feeling slow with real browsing traffic.
     */
    private fun forwardQuery(query: DnsQuery, tunOutput: FileOutputStream) {
        try {
            DatagramSocket().use { socket ->
                protect(socket) // must be called before use, or this forwarded
                // packet re-enters our own tunnel and loops
                socket.soTimeout = 5000
                val resolverAddress = InetAddress.getByName(UPSTREAM_RESOLVER)
                socket.send(DatagramPacket(query.rawQuery, query.rawQuery.size, resolverAddress, 53))

                val responseBuffer = ByteArray(1024)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)

                val dnsResponse = responseBuffer.copyOf(responsePacket.length)
                val packet = buildIpUdpPacket(
                    sourceAddr = DNS_ADDRESS_BYTES, sourcePort = 53,
                    destAddr = query.sourceAddress, destPort = query.sourcePort,
                    payload = dnsResponse
                )
                writeToTun(tunOutput, packet)
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Resolver timed out for ${query.domain}")
        } catch (e: IOException) {
            Log.e(TAG, "Error forwarding query for ${query.domain}", e)
        }
    }

    private fun writeToTun(tunOutput: FileOutputStream, packet: ByteArray) {
        try {
            tunOutput.write(packet)
        } catch (e: IOException) {
            Log.e(TAG, "Error writing response to VPN interface", e)
        }
    }

    /**
     * Parses a raw IP packet read from the tun interface and, if it is an
     * IPv4/UDP/port-53 DNS query, returns everything needed to reply to it.
     * Returns null for anything else (non-IPv4, non-UDP, non-DNS, or a
     * packet too short to be valid).
     */
    private fun parseDnsQuery(buffer: ByteArray, length: Int): DnsQuery? {
        if (length < 20) return null // shorter than a minimal IPv4 header

        val versionAndIhl = buffer[0].toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return null // spike only handles IPv4

        val ipHeaderLength = (versionAndIhl and 0x0F) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength + 8) return null

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != 17) return null // not UDP

        val udpOffset = ipHeaderLength
        val destPort = readUInt16(buffer, udpOffset + 2)
        if (destPort != 53) return null

        val udpLength = readUInt16(buffer, udpOffset + 4)
        if (udpLength < 8) return null

        val dnsOffset = udpOffset + 8
        if (length < dnsOffset + 12) return null // shorter than a DNS header

        val questionCount = readUInt16(buffer, dnsOffset + 4)
        if (questionCount < 1) return null

        val domain = parseQName(buffer, dnsOffset + 12, length) ?: return null
        val sourcePort = readUInt16(buffer, udpOffset)

        return DnsQuery(
            domain = domain,
            sourceAddress = buffer.copyOfRange(12, 16),
            sourcePort = sourcePort,
            rawQuery = buffer.copyOfRange(dnsOffset, length)
        )
    }

    /** Reads a big-endian unsigned 16-bit value at [offset]. */
    private fun readUInt16(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    /**
     * Parses a length-prefixed DNS QNAME (e.g. 3www6google3com0) starting
     * at [offset] into a dotted domain string. Returns null if the name
     * runs past [limit] or the encoding looks malformed.
     */
    private fun parseQName(buffer: ByteArray, offset: Int, limit: Int): String? {
        val labels = StringBuilder()
        var pos = offset

        while (pos < limit) {
            val labelLength = buffer[pos].toInt() and 0xFF
            if (labelLength == 0) {
                return if (labels.isEmpty()) null else labels.toString()
            }
            // Bits 11000000 mark a compression pointer, which real queries
            // shouldn't use in the question section. Bail rather than
            // mis-parse.
            if (labelLength and 0xC0 == 0xC0) return null

            pos += 1
            if (pos + labelLength > limit) return null

            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(buffer, pos, labelLength, Charsets.US_ASCII))
            pos += labelLength
        }
        return null // ran off the end without a terminating zero
    }

    /**
     * Wraps a DNS payload in a minimal IPv4 + UDP header addressed from
     * (sourceAddr, sourcePort) to (destAddr, destPort). UDP checksum is set
     * to 0 (valid/optional for IPv4); the IPv4 header checksum is computed
     * properly since routers and the OS network stack do check it.
     */
    private fun buildIpUdpPacket(
        sourceAddr: ByteArray, sourcePort: Int,
        destAddr: ByteArray, destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)

        // --- IPv4 header ---
        packet[0] = 0x45 // version 4, IHL 5 (20-byte header, no options)
        packet[1] = 0
        packet[2] = (totalLength shr 8).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0; packet[5] = 0 // identification
        packet[6] = 0x40; packet[7] = 0 // flags: don't fragment
        packet[8] = 64 // TTL
        packet[9] = 17 // protocol: UDP
        packet[10] = 0; packet[11] = 0 // checksum placeholder, filled below
        System.arraycopy(sourceAddr, 0, packet, 12, 4)
        System.arraycopy(destAddr, 0, packet, 16, 4)

        val checksum = computeIpChecksum(packet, 0, 20)
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        // --- UDP header ---
        packet[20] = (sourcePort shr 8).toByte()
        packet[21] = (sourcePort and 0xFF).toByte()
        packet[22] = (destPort shr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        packet[24] = (udpLength shr 8).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        packet[26] = 0; packet[27] = 0 // UDP checksum: 0 = not computed, valid for IPv4

        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    /** Standard one's-complement IPv4 header checksum over [length] bytes starting at [offset]. */
    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end) {
            val high = data[i].toInt() and 0xFF
            val low = if (i + 1 < end) data[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Protection active",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BaitBlock is protecting this device")
            .setSmallIcon(android.R.drawable.ic_lock_lock) // TODO: swap for app icon
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}