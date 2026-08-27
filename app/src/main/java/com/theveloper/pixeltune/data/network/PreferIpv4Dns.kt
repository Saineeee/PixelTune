package com.theveloper.pixeltune.data.network

import okhttp3.Dns
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * FIX(cloud-streaming-speed): DNS resolver that orders IPv4 addresses before
 * IPv6 addresses (keeping BOTH families in the result, so failover still
 * works in both directions).
 *
 * ──────────────────────────────────────────────────────────────────────
 * ROOT-CAUSE NOTE (why this exists)
 * ──────────────────────────────────────────────────────────────────────
 * Live-timing NewPipeExtractor v0.26.3 against the real YouTube /
 * YouTube Music / SoundCloud backends shows every extraction path is
 * intrinsically fast on a healthy network (YT Music search = 1 POST,
 * ~450 ms warm; SoundCloud search = 1 GET, ~500 ms warm; stream
 * extraction = 6-7 sequential requests, ~1.5-2 s; googlevideo /
 * sndcdn Range requests answer 206 in ~150 ms). Yet users consistently
 * reported 15+ second searches, slow playback start and extremely slow
 * seeks on BOTH providers — a delay that matches the OkHttp
 * `connectTimeout` of the extractor client exactly.
 *
 * The mechanism: on many mobile carriers (very common in the regions where
 * this was reported) the device gets an IPv6 address and the OS resolver
 * therefore returns AAAA records FIRST (RFC 6724 ordering), but the
 * carrier's IPv6 route to the service's edge server is broken — SYNs are
 * silently blackholed. OkHttp (4.x) does not race routes: its RouteSelector
 * tries addresses in order, so the FIRST connect attempt hangs until the
 * full `connectTimeout` (15 s on the extractor client, 30 s on the
 * streaming client) elapses, and only then falls back to IPv4 — which
 * works. Every FRESH connection therefore pays the whole connect timeout:
 *
 *   - search after the connection pool went idle  -> 15 s before results
 *   - playback start (several sequential requests) -> up to 15 s × N
 *   - EVERY seek: ExoPlayer re-opens the localhost proxy, which opens a
 *     NEW upstream connection (the previous one is discarded when a
 *     partially-read response is closed) -> 15 s per seek
 *   - album art via Coil's own client (10 s timeout) dies before the
 *     IPv4 fallback completes -> artwork sometimes never shows
 *
 * Ordering IPv4 first fixes the common broken-IPv6 case outright, and is
 * safe in every other topology:
 *
 *   - IPv4-only network: identical behavior.
 *   - Healthy dual-stack: IPv4 connects just as fast; at most a few ms of
 *     difference.
 *   - IPv6-only network with DNS64/NAT64: the system resolver returns
 *     only synthesized AAAA addresses (no real IPv4 routes exist), so the
 *     reordered list still contains the (only) working addresses; if real
 *     unroutable IPv4 literals are returned, `connect()` fails FAST with
 *     ENETUNREACH (no route), costing milliseconds — not a timeout.
 *   - IPv4-broken-but-IPv6-works network (rare): IPv4 fails fast or hits
 *     the (now shorter) connect timeout, then IPv6 succeeds — bounded
 *     by the reduced connectTimeout instead of the old 15-30 s.
 *
 * A single retry also guards against transient resolver hiccups: a flaky
 * DNS moment previously failed the whole search / extraction outright.
 *
 * This resolver is intentionally stateless (thread-safe) and is applied to
 * the NewPipe extractor client, the cloud-streaming proxy client and the
 * Coil artwork client — the three network paths cloud playback depends on.
 */
object PreferIpv4Dns : Dns {

    /** Two attempts total — enough to ride out a transient resolver blip. */
    private const val MAX_LOOKUP_ATTEMPTS = 2

    override fun lookup(hostname: String): List<InetAddress> {
        var lastError: IOException? = null
        repeat(MAX_LOOKUP_ATTEMPTS) {
            try {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isNotEmpty()) {
                    return orderIpv4First(addresses)
                }
            } catch (e: IOException) {
                // UnknownHostException extends IOException — retry once.
                lastError = e
            }
        }
        throw lastError ?: UnknownHostException("PreferIpv4Dns: no addresses for $hostname")
    }

    /**
     * Stable reordering: IPv4 addresses first (original relative order
     * preserved), then every other family (IPv6, synthesized AAAA, …).
     * Single-family results are returned untouched — no pointless copy and
     * no behavior change when reordering cannot help.
     */
    private fun orderIpv4First(addresses: List<InetAddress>): List<InetAddress> {
        val ipv4 = ArrayList<InetAddress>(addresses.size)
        val rest = ArrayList<InetAddress>(addresses.size)
        for (address in addresses) {
            if (address is Inet4Address) ipv4.add(address) else rest.add(address)
        }
        if (ipv4.isEmpty() || rest.isEmpty()) return addresses
        ipv4.addAll(rest)
        return ipv4
    }
}
