package com.bob.angularspringbootfullstack.utils;

import jakarta.servlet.http.HttpServletRequest;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;

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
            ipAddress = request.getHeader("X-Forwarded-For");
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
     * <p>Note: {@link UserAgentAnalyzer} is constructed on every call — acceptable
     * for now but expensive under high load.  If this becomes a bottleneck,
     * promote the analyzer to a singleton Spring bean.
     *
     * @param request the current HTTP request
     * @return a formatted device description string
     */
    public static String getDevice(HttpServletRequest request) {
        UserAgentAnalyzer userAgentAnalyzer = UserAgentAnalyzer.newBuilder().hideMatcherLoadStats().withCache(10000).build();
        UserAgent agent = userAgentAnalyzer.parse(request.getHeader("User-Agent"));
        return agent.getValue(OPERATING_SYSTEM_NAME) + " - " + agent.getValue(AGENT_NAME) + " - " + agent.getValue(DEVICE_NAME);
    }
}
