package com.bob.angularspringbootfullstack.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static com.bob.angularspringbootfullstack.constants.Constants.X_FORWARDED_FOR_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the client-IP resolution rule that two live security controls depend on.
 *
 * <p>{@code X-Forwarded-For} is an ordinary request header, so any caller can send one. Before
 * this rule existed the header was returned verbatim whenever present, which handed the client
 * the pen for its own recorded address — and both of these become defeatable as a result:
 * <ul>
 *   <li>{@code RateLimitFilter} keys token buckets on the address, so forged rotating values buy
 *       an unlimited request budget past the brute-force protection.</li>
 *   <li>{@code LoginRiskService} compares the address against the account's known networks, so a
 *       forged "familiar" value suppresses the {@code NEW_NETWORK} signal and the step-up
 *       challenge it triggers.</li>
 * </ul>
 *
 * <p>The forgery cases below are therefore the ones that matter most: {@link
 * #forgedHeaderIsIgnoredWhenNoProxyIsConfigured} and {@link #forgedLeadingEntryIsIgnoredBehindOneProxy}
 * are the actual attacks, and each asserts that the attacker-supplied value is <em>not</em> what
 * comes back.
 *
 * <p>{@code trustedProxyCount} is static process-wide state, so {@link #resetProxyCount()} restores
 * the default after every test — otherwise one case would silently configure the next.
 */
class RequestUtilsIpAddressTest {

    /** The address the TCP connection genuinely came from — not forgeable over an established connection. */
    private static final String PEER = "203.0.113.7";
    /** What an attacker would like to be recorded as. */
    private static final String FORGED = "10.0.0.1";

    @AfterEach
    void resetProxyCount() {
        RequestUtils.configureTrustedProxyCount(0);
    }

    private static MockHttpServletRequest requestFrom(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader(X_FORWARDED_FOR_HEADER, forwardedFor);
        }
        return request;
    }

    @Test
    @DisplayName("with no proxy configured, a forged X-Forwarded-For is ignored entirely")
    void forgedHeaderIsIgnoredWhenNoProxyIsConfigured() {
        RequestUtils.configureTrustedProxyCount(0);

        String resolved = RequestUtils.getIpAddress(requestFrom(PEER, FORGED));

        assertEquals(PEER, resolved,
                "Nothing rewrites the header in this topology, so it is purely caller-supplied and must not win.");
    }

    @Test
    @DisplayName("with no proxy and no header, the transport peer address is used")
    void peerAddressIsUsedWhenNoHeaderPresent() {
        RequestUtils.configureTrustedProxyCount(0);

        assertEquals(PEER, RequestUtils.getIpAddress(requestFrom(PEER, null)));
    }

    @Test
    @DisplayName("behind one proxy, a forged leading entry is discarded in favour of the proxy-appended one")
    void forgedLeadingEntryIsIgnoredBehindOneProxy() {
        RequestUtils.configureTrustedProxyCount(1);

        // The attacker sent "10.0.0.1"; the load balancer appended what it actually observed.
        String resolved = RequestUtils.getIpAddress(requestFrom("172.31.0.5", FORGED + ", " + PEER));

        assertEquals(PEER, resolved, "The rightmost entry is the one our own infrastructure wrote.");
    }

    @Test
    @DisplayName("behind one proxy, an honest single-entry header yields the client")
    void singleEntryHeaderResolvesBehindOneProxy() {
        RequestUtils.configureTrustedProxyCount(1);

        assertEquals(PEER, RequestUtils.getIpAddress(requestFrom("172.31.0.5", PEER)));
    }

    @Test
    @DisplayName("behind two proxies (CDN + load balancer), an honest chain resolves the client")
    void twoProxyChainResolvesTheClient() {
        RequestUtils.configureTrustedProxyCount(2);

        // Each trusted hop appends exactly one entry, so an HONEST request through two proxies
        // carries exactly two: the CDN records the client, then the load balancer records the CDN.
        // The app's own peer address is the load balancer.
        String resolved = RequestUtils.getIpAddress(requestFrom("172.31.0.5", PEER + ", 198.51.100.9"));

        assertEquals(PEER, resolved);
    }

    @Test
    @DisplayName("behind two proxies, an extra leading entry is the client's forgery and is discarded")
    void twoProxyChainIgnoresInjectedEntry() {
        RequestUtils.configureTrustedProxyCount(2);

        // A list LONGER than the proxy depth is the signature of injection: the caller supplied
        // the surplus leading entry, and only the trailing `trustedProxyCount` entries were
        // written by infrastructure we control.
        String resolved = RequestUtils.getIpAddress(
                requestFrom("172.31.0.5", FORGED + ", " + PEER + ", 198.51.100.9"));

        assertEquals(PEER, resolved);
    }

    @Test
    @DisplayName("a header shorter than the configured proxy depth is discarded, not trusted")
    void shorterChainThanExpectedFallsBackToPeer() {
        RequestUtils.configureTrustedProxyCount(2);

        // Only one entry where two hops were expected: the request did not traverse the expected
        // chain, so the header says nothing reliable about who called.
        String resolved = RequestUtils.getIpAddress(requestFrom(PEER, FORGED));

        assertEquals(PEER, resolved);
    }

    @Test
    @DisplayName("a blank or 'unknown' header falls back to the peer address")
    void blankAndUnknownHeadersFallBack() {
        RequestUtils.configureTrustedProxyCount(1);

        assertEquals(PEER, RequestUtils.getIpAddress(requestFrom(PEER, "   ")));
        assertEquals(PEER, RequestUtils.getIpAddress(requestFrom(PEER, "unknown")));
    }

    @Test
    @DisplayName("a null request resolves to the unknown sentinel rather than throwing")
    void nullRequestYieldsSentinel() {
        assertEquals(RequestUtils.UNKNOWN_IP, RequestUtils.getIpAddress(null));
    }

    @Test
    @DisplayName("an absent peer address resolves to the unknown sentinel, never an empty string")
    void blankPeerYieldsSentinel() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("");

        // LoginRiskServiceImpl special-cases this exact sentinel so an unresolvable address is
        // never mistaken for a distinct network; an empty string would slip past that check.
        assertEquals(RequestUtils.UNKNOWN_IP, RequestUtils.getIpAddress(request));
    }
}
