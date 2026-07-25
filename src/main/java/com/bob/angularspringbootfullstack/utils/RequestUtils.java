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
     * per call (the old behaviour) therefore both spammed the logs on every audited request and added
     * that cost to each sign-in. A single static instance means the banner prints once at startup and
     * lookups are cheap thereafter (a 10k-entry result cache absorbs repeats).
     *
     * <p><b>Thread-safety:</b> the caching analyzer is NOT safe for concurrent {@code parse()} — its
     * result cache and matcher state are unsynchronized, so overlapping requests can corrupt it and
     * throw intermittently. {@link #getDevice} therefore serialises access on this instance (see its
     * note); a cached parse is microsecond-cheap, so the lock is effectively free.
     */
    private static final UserAgentAnalyzer USER_AGENT_ANALYZER = UserAgentAnalyzer.newBuilder()
            .hideMatcherLoadStats()
            .withCache(10_000)
            .build();


    /**
     * Returns the real client IP address, accounting for reverse proxies.
     *
     * <p>Checks the {@code X-Forwarded-For} header first because load balancers
     * and proxies put the original client IP there, overwriting
     * {@code getRemoteAddr()} with their own address.  Falls back to
     * {@code getRemoteAddr()} when the header is absent or unreadable.
     *
     * @param request the current HTTP request, or {@code null}
     * @return the client IP address, or {@code "Unknown IP"} if none can be determined
     */
    public static String getIpAddress(HttpServletRequest request) {
        String ipAddress = "Unknown IP";
        if (request != null) {
            ipAddress = request.getHeader(X_FORWARDED_FOR_HEADER);
            if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
                ipAddress = request.getRemoteAddr();
            }
        }
        return ipAddress;
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
        // single page triggers) can corrupt the cache and throw intermittently. Serialise the whole
        // interaction — parse plus field reads — on the shared analyzer. A cached parse is microsecond-
        // cheap, so the lock costs effectively nothing, and it makes the one shared instance safe on
        // every path, including SessionServiceImpl (login/refresh) where a throw would surface as a 500.
        synchronized (USER_AGENT_ANALYZER) {
            UserAgent agent = USER_AGENT_ANALYZER.parse(request.getHeader(USER_AGENT_HEADER));
            return agent.getValue(OPERATING_SYSTEM_NAME) + " - " + agent.getValue(AGENT_NAME) + " - " + agent.getValue(DEVICE_NAME);
        }
    }
}
