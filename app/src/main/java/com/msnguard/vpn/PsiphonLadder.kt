package com.msnguard.vpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * One rung of the Psiphon escalation ladder.
 *
 * Each rung is a complete, self-contained Psiphon config variant plus the time
 * we are willing to spend on it before moving to the next rung. The ladder is
 * ordered by *expected time to first connection on a hostile carrier*, not by
 * how clever the technique is — the cheapest thing that plausibly works goes
 * first so the common case stays fast.
 */
internal class PsiphonStrategy(
    val name: String,
    val label: String,
    val timeoutSeconds: Int,
    /**
     * Protocols this rung asks Psiphon to try first.
     *
     * This is only a *preference*: Psiphon falls back to its full protocol set
     * once InitialLimitTunnelProtocolsCandidateCount candidates are exhausted.
     * So the protocol that ends up carrying the tunnel is often not from this
     * list, which is exactly why winner detection reads the live ActiveTunnel
     * notice instead of assuming the active rung won.
     */
    val preferredProtocols: List<String>,
    val configure: (JSONObject) -> Unit,
)

/**
 * The Psiphon escalation ladder and the protocol sets it is built from.
 *
 * Extracted out of MsnGuardVpnService: none of this touches service state — the
 * rungs are pure config mutations on a JSONObject — and keeping 150 lines of
 * carrier-specific tuning notes inside the service made the actual tunnel
 * lifecycle hard to follow.
 */
internal object PsiphonLadder {

    /**
     * Domain-fronted protocols.
     *
     * Every name here was verified to exist as a substring in libgojni.so. The
     * INPROXY-* names are deliberately absent: they are assembled at runtime and
     * do not appear as literals, so passing one risks failing config validation.
     */
    val FRONTED = listOf(
        "FRONTED-MEEK-OSSH",
        "FRONTED-MEEK-HTTP-OSSH",
        "FRONTED-MEEK-QUIC-OSSH",
    )

    /**
     * Direct-dial protocols, i.e. everything that connects straight to a Psiphon
     * server IP. Used only for winner detection — the direct rung passes no
     * protocol limit at all and lets Psiphon pick.
     */
    val DIRECT = listOf(
        "QUIC-OSSH",
        "TLS-OSSH",
        "UNFRONTED-MEEK-HTTPS-OSSH",
        "UNFRONTED-MEEK-OSSH",
        "SHADOWSOCKS-OSSH",
        "CONJURE-OSSH",
        "OSSH",
        "SSH",
    )

    /**
     * The ladder, ordered by *measured* time-to-connect on a hostile carrier,
     * using the Build #65 field logs from Hamrah-e-Aval and SamanTel.
     *
     * What those logs proved:
     *
     *  - On Hamrah-e-Aval every direct dial fails at the TCP layer: TLS-OSSH,
     *    UNFRONTED-MEEK-HTTPS-OSSH, OSSH and SSH candidates all end in
     *    "connect: connection timed out" / "i/o timeout" from tcpDial#308. Not
     *    resets, not TLS errors — the packets never arrive. The carrier
     *    null-routes Psiphon server IPs.
     *  - Only FRONTED-MEEK works there, because it dials a CDN edge instead of a
     *    Psiphon-owned IP. It connected on FRONTED-MEEK-HTTP-OSSH in 33s.
     *  - The old first rung ("443-only protocols") therefore burned its entire
     *    45s budget for nothing before the fronted rung even started, which is
     *    the whole reason connecting felt slow.
     *  - On SamanTel a plain direct QUIC-OSSH dial won in seconds, so direct
     *    protocols must stay reachable early for carriers that do not block.
     *
     * Hence the order: fronted first (the only path that works on the hostile
     * carrier), then wide-open direct (fast where nothing is blocked), then
     * in-proxy (slowest, needs a broker plus WebRTC/ICE negotiation).
     *
     * The rung that actually carries the tunnel is remembered per device, so
     * after one successful connect each SIM starts on its own best rung and the
     * ordering here only matters for the very first attempt.
     */
    val RUNGS: List<PsiphonStrategy> = listOf(
        PsiphonStrategy(
            name = "A",
            label = "domain-fronted (CDN)",
            timeoutSeconds = 60,
            preferredProtocols = FRONTED,
        ) { config ->
            // Fronted protocols terminate on an Amazon/Cloudflare edge address,
            // never on a Psiphon-owned IP, so a carrier IP blocklist cannot see
            // or drop them. They do need working DNS to resolve the front, which
            // is what the public resolvers on the TUN provide.
            //
            // Only 5 of the 430 bundled server entries advertise FRONTED-MEEK
            // (4x US, 1x GB) — that is why a fronted connection always lands in
            // the US. A low candidate count keeps Psiphon cycling those few
            // entries with fresh dial parameters instead of opening up to the
            // 425 direct entries that are known-dead on this carrier.
            config.put("InitialLimitTunnelProtocols", JSONArray(FRONTED))
            config.put("InitialLimitTunnelProtocolsCandidateCount", 30)
            config.put("ConnectionWorkerPoolSize", 12)
            // CDN paths are legitimately slower than a direct dial; without this
            // Psiphon abandons them as if they were dead.
            config.put("NetworkLatencyMultiplier", 2.0)
        },
        PsiphonStrategy(
            name = "D",
            label = "all protocols (direct)",
            timeoutSeconds = 45,
            preferredProtocols = DIRECT,
        ) { config ->
            // No InitialLimitTunnelProtocols at all: Psiphon uses its own full
            // protocol set and its own replay/tactics ordering. This is the rung
            // that wins on a carrier which is not blocking anything — SamanTel
            // connected this way on QUIC-OSSH — and it is also the safety net if
            // the CDN fronts themselves ever get blocked.
            config.put("ConnectionWorkerPoolSize", 16)
        },
        PsiphonStrategy(
            name = "C",
            label = "in-proxy (peer relay)",
            timeoutSeconds = 75,
            preferredProtocols = emptyList(),
        ) { config ->
            // In-proxy routes through other Psiphon users' devices over WebRTC.
            // Their addresses are residential and not in any carrier blocklist,
            // which is what makes this rung the last resort that can still work
            // when every server IP and every CDN front is unreachable.
            //
            // Deliberately NOT setting InitialLimitTunnelProtocols here: the
            // INPROXY-* protocol names do not exist as literals in libgojni.so
            // (verified with strings — they are assembled at runtime), so passing
            // one risks failing config validation and killing the whole rung.
            // The flags below are enough; the log confirms Psiphon then reports
            // "in-proxy protocol preferred" and dials INPROXY-WEBRTC-OSSH itself.
            config.put("InproxyEnabled", true)
            config.put("InproxyAllowClient", true)
            config.put("InproxySkipAwaitFullyConnected", true)
            config.put("ConnectionWorkerPoolSize", 16)
            config.put("NetworkLatencyMultiplier", 3.0)
        },
    )

    /** Index of the peer-relay rung, or -1 if it were ever removed. */
    val INPROXY_RUNG: Int = RUNGS.indexOfFirst { it.name == "C" }
}
