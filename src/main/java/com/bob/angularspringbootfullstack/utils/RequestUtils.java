package com.bob.angularspringbootfullstack.utils;

import jakarta.servlet.http.HttpServletRequest;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;

import static com.bob.angularspringbootfullstack.constants.Constants.USER_AGENT_HEADER;
import static com.bob.angularspringbootfullstack.constants.Constants.X_FORWARDED_FOR_HEADER;
import static nl.basjes.parse.useragent.UserAgent.*;

/**
 * Static helpers for extracting client metadata from an incoming HTTP request.
 *
 * <p>Used by
 * {@link com.bob.angularspringbootfullstack.listener.NewUserEventListener} to
 * capture the device and IP address at the moment an audit event fires, without
 * requiring each caller to duplicate this extraction logic.
 */
public class RequestUtils {

    /**
     * Single shared user-agent analyzer, built once at class load and reused for every request.
     *
     * <p>{@link UserAgentAnalyzer} is expensive to construct — it loads ~114 rule files and builds
     * a ~200k-entry matcher table (~700ms), and logs a version banner while doing so. Building one
     * per call (the old behavior) therefore both spammed the logs on every audited request and added
     * that cost to each sign-in. A single static instance means the banner prints once at startup and
     * lookups are cheap thereafter (a 10k-entry result cache absorbs repeats).
     *
     * <p><b>Thread-safety:</b> the caching analyzer is NOT safe for concurrent {@code parse()} — its
     * result cache and matcher state are unsynchronized, so overlapping requests can corrupt it and
     * throw intermittently. {@link #getDevice} therefore serializes access on this instance (see its
     * note); a cached parse is microsecond-cheap, so the lock is effectively free.
     */
    private static final UserAgentAnalyzer USER_AGENT_ANALYZER = UserAgentAnalyzer.newBuilder()
            .hideMatcherLoadStats()
            .withCache(10_000)
            .build();


    /** Returned when no address can be determined; callers treat it as "unknown", never as a real network. */
    public static final String UNKNOWN_IP = "Unknown IP";

    /**
     * How many reverse proxies sit between the internet and this application.
     *
     * <p>Set once at startup from {@code app.security.trusted-proxy-count} by
     * {@link com.bob.angularspringbootfullstack.configuration.TrustedProxyConfigurer}. Static
     * (rather than injected) because {@link RequestUtils} is called statically from the audit
     * listener, the session service, and the anomaly detector; {@code volatile} so the value
     * published during startup is visible to every request thread thereafter.
     *
     * <p>Defaults to {@code 0} — no proxy — which is both the correct local-development value and
     * the safe failure mode: it ignores {@code X-Forwarded-For} entirely rather than trusting it.
     */
    private static volatile int trustedProxyCount = 0;

    /**
     * Publishes the deployment's proxy depth. Called once during startup.
     *
     * @param count number of trusted proxies in front of the app; values below zero are clamped
     */
    public static void configureTrustedProxyCount(int count) {
        trustedProxyCount = Math.max(count, 0);
    }

    /**
     * Returns the real client IP address, accounting for reverse proxies <em>without</em> trusting
     * a caller-supplied header.
     *
     * <h3>Why this is not simply "read X-Forwarded-For"</h3>
     * {@code X-Forwarded-For} is an ordinary request header, so anyone can send one. The previous
     * implementation returned it verbatim whenever present, which meant the client chose its own
     * recorded address. Two live controls depend on this value, and both were defeatable by a
     * one-line header:
     * <ul>
     *   <li>{@link com.bob.angularspringbootfullstack.filter.RateLimitFilter} keys its token
     *       buckets on it — a caller rotating forged addresses gets an unlimited request budget,
     *       which is precisely the brute-force protection the limiter exists to provide.</li>
     *   <li>{@link com.bob.angularspringbootfullstack.service.LoginRiskService} compares it against
     *       the account's known networks — an attacker who forges an address matching the victim's
     *       usual network suppresses the {@code NEW_NETWORK} anomaly signal and the step-up
     *       challenge that follows it.</li>
     * </ul>
     *
     * <h3>How the header is used safely</h3>
     * The header is only consulted when {@link #trustedProxyCount} says a proxy actually exists.
     * The list grows left-to-right, each hop appending the address it observed, so the
     * <em>rightmost</em> entries are the trustworthy ones — written by our own infrastructure —
     * and anything further left may have been supplied by the client. With {@code N} trusted
     * proxies the genuine client sits at index {@code length - N}.
     *
     * <p>Worked example with one ALB in front ({@code trustedProxyCount = 1}): an honest client
     * sends no header, the ALB sets {@code "203.0.113.7"}, and index {@code 1-1=0} yields the
     * client. A hostile client sends {@code "10.0.0.1"} hoping to be recorded as an internal
     * address; the ALB appends what it actually saw, giving {@code "10.0.0.1, 203.0.113.7"}, and
     * index {@code 2-1=1} yields the real address. The forged entry is ignored in both cases.
     *
     * <p>A header shorter than the configured proxy depth means the request did not traverse the
     * expected chain, so the header is discarded in favour of the transport-level peer address.
     *
     * @param request the current HTTP request, or {@code null}
     * @return the client IP address, or {@link #UNKNOWN_IP} if none can be determined
     */
    public static String getIpAddress(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_IP;
        }
        String peerAddress = normalize(request.getRemoteAddr());
        if (trustedProxyCount == 0) {
            // Nothing trustworthy can be learned from a header the caller controls, and the
            // transport-level peer address cannot be forged over an established TCP connection.
            return peerAddress;
        }
        String forwardedFor = request.getHeader(X_FORWARDED_FOR_HEADER);
        if (forwardedFor == null || forwardedFor.isBlank() || "unknown".equalsIgnoreCase(forwardedFor.trim())) {
            return peerAddress;
        }
        String[] hops = forwardedFor.split(",");
        int clientIndex = hops.length - trustedProxyCount;
        if (clientIndex < 0 || clientIndex >= hops.length) {
            return peerAddress;
        }
        String client = hops[clientIndex].trim();
        return client.isEmpty() ? peerAddress : client;
    }

    /**
     * Collapses a null or blank address to the unknown sentinel so callers never have to
     * distinguish "absent" from "empty string".
     *
     * @param address a raw address value
     * @return the trimmed address, or {@link #UNKNOWN_IP}
     */
    private static String normalize(String address) {
        return (address == null || address.isBlank()) ? UNKNOWN_IP : address.trim();
    }

    /**
     * Returns a human-readable string describing the client's OS, browser, and device.
     *
     * <p>Parses the {@code User-Agent} header using the Yauaa library, which
     * identifies hundreds of browser/OS/device combinations from the raw header
     * string.  The result is formatted as {@code "OS - Browser - Device"}
     * (e.g. {@code "Windows 11 - Chrome - Desktop"}).
     *
     * <p>Reuses the shared {@link #USER_AGENT_ANALYZER} rather than constructing one per call.
     *
     * @param request the current HTTP request
     * @return a formatted device description string
     */
    public static String getDevice(HttpServletRequest request) {
        // Yauaa's caching UserAgentAnalyzer is NOT safe for concurrent parse(): the result cache and
        // matcher state are unsynchronized, so overlapping requests (e.g. the burst of parallel calls a
        // single page triggers) can corrupt the cache and throw intermittently. Serialize the whole
        // interaction — parse plus field reads — on the shared analyzer. A cached parse is microsecond-
        // cheap, so the lock costs effectively nothing, and it makes the one shared instance safe on
        // every path, including SessionServiceImpl (login/refresh) where a throw would surface as a 500.
        synchronized (USER_AGENT_ANALYZER) {
            UserAgent agent = USER_AGENT_ANALYZER.parse(request.getHeader(USER_AGENT_HEADER));
            return agent.getValue(OPERATING_SYSTEM_NAME) + " - " + agent.getValue(AGENT_NAME) + " - " + agent.getValue(DEVICE_NAME);
        }
    }
}
