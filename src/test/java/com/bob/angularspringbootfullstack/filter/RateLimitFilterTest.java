package com.bob.angularspringbootfullstack.filter;

import com.bob.angularspringbootfullstack.utils.RequestUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static com.bob.angularspringbootfullstack.constants.Constants.X_FORWARDED_FOR_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link RateLimitFilter} actually refuses traffic once a bucket is drained, and —
 * more importantly — that the bucket key cannot be chosen by the caller.
 *
 * <p><b>Why the 429 itself is worth asserting.</b> The limiter is the outermost brute-force control
 * in the stack: it is registered at {@code @Order(-200)}, ahead of Spring Security's
 * {@code FilterChainProxy}, so it fires before any JWT parsing or credential check happens. Nothing
 * downstream can compensate if it silently stops rejecting — a bucket misconfiguration would simply
 * let every request through, and every other test in this suite would still pass. The refusal
 * contract has three visible parts and all three are checked here: the {@code 429} status, a
 * {@code Retry-After} header the SPA can act on, and the standard {@code HttpResponse} envelope so
 * the client renders a real message instead of a parse error.
 *
 * <p><b>Why the forged-header case is the one that matters.</b> A token bucket is only as good as
 * its key. This filter keys on the client IP, so if a caller can influence which key it lands on it
 * gets a fresh allowance on demand and the limit becomes decorative. {@link RequestUtils#getIpAddress}
 * exists precisely to prevent that — it ignores {@code X-Forwarded-For} entirely unless a proxy depth
 * has been configured — and its own Javadoc names this filter as a protected consumer.
 * {@link #forgedForwardedForCannotMintFreshBuckets()} is the test that holds that claim to account;
 * it fails against any implementation that reads the header directly.
 *
 * <p>Each case constructs its own {@link RateLimitFilter}, because the buckets are instance state
 * that no case is fast enough to age out (the stores retain an idle bucket for ten minutes);
 * sharing one instance would let an earlier case drain the allowance of a later one.
 *
 * @see RequestUtils#getIpAddress(jakarta.servlet.http.HttpServletRequest) the trusted-proxy-aware
 * resolution rule this filter must not bypass
 */
class RateLimitFilterTest {

    /** An auth-tier path: 10 requests per minute per client. */
    private static final String LOGIN_PATH = "/user/login";
    /** A path in no auth-tier prefix, so it draws on the 200/minute global bucket. */
    private static final String GLOBAL_PATH = "/customer/list";

    /**
     * The capacities these tests run against, passed explicitly to the filter's constructor.
     *
     * <p>They are the same values {@code application.yml} defaults to, so this suite still describes
     * the shipped configuration — but it no longer depends on that being the default. The filter
     * takes both limits as constructor arguments (see its Javadoc), which is what lets a case state
     * the numbers it is asserting against instead of restating a constant defined elsewhere.
     */
    private static final int AUTH_CAPACITY = 10;
    private static final int GLOBAL_CAPACITY = 200;

    /** The address the TCP connection genuinely came from — not forgeable over an established connection. */
    private static final String PEER = "203.0.113.7";

    /**
     * Proxy depth is process-wide static state on {@link RequestUtils}, so it is restored after
     * every case; otherwise one test would silently configure the next.
     */
    @AfterEach
    void resetProxyCount() {
        RequestUtils.configureTrustedProxyCount(0);
    }

    /** Counts how many requests reached the application, cumulatively across a case. */
    private static final class CountingFilterChain implements FilterChain {
        private int invocations;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            invocations++;
        }
    }

    /**
     * Drives one request through the filter.
     *
     * @param filter       the filter under test, carrying its accumulated buckets
     * @param path         request URI, which decides the tier
     * @param peer         transport-level peer address
     * @param forwardedFor value for {@code X-Forwarded-For}, or {@code null} to send no such header
     * @param chain        the shared chain whose invocation count records what got through
     * @return the completed response, ready to assert on
     */
    private static MockHttpServletResponse fire(RateLimitFilter filter,
                                                String path,
                                                String peer,
                                                String forwardedFor,
                                                CountingFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader(X_FORWARDED_FOR_HEADER, forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static RateLimitFilter newFilter() {
        return new RateLimitFilter(new ObjectMapper(), AUTH_CAPACITY, GLOBAL_CAPACITY);
    }

    @Test
    @DisplayName("the auth tier admits exactly 10 requests a minute, then answers 429")
    void authTierRefusesTheEleventhRequest() throws Exception {
        RateLimitFilter filter = newFilter();
        CountingFilterChain chain = new CountingFilterChain();

        for (int i = 1; i <= AUTH_CAPACITY; i++) {
            MockHttpServletResponse allowed = fire(filter, LOGIN_PATH, PEER, null, chain);
            assertEquals(200, allowed.getStatus(), "request " + i + " should have been admitted");
        }
        assertEquals(AUTH_CAPACITY, chain.invocations, "every request up to the capacity should reach the app");

        MockHttpServletResponse refused = fire(filter, LOGIN_PATH, PEER, null, chain);

        assertEquals(429, refused.getStatus());
        assertEquals(AUTH_CAPACITY, chain.invocations,
                "a throttled request must not reach the application at all");
    }

    @Test
    @DisplayName("a throttled response carries Retry-After and the standard HttpResponse envelope")
    void refusalIsActionableByTheClient() throws Exception {
        RateLimitFilter filter = newFilter();
        CountingFilterChain chain = new CountingFilterChain();
        for (int i = 0; i <= AUTH_CAPACITY; i++) {
            fire(filter, LOGIN_PATH, PEER, null, chain);
        }

        MockHttpServletResponse refused = fire(filter, LOGIN_PATH, PEER, null, chain);

        String retryAfter = refused.getHeader("Retry-After");
        assertNotNull(retryAfter, "Retry-After is the only thing telling the SPA when to try again");
        assertTrue(Integer.parseInt(retryAfter) > 0, "a Retry-After of 0 would invite an immediate retry");

        JsonNode body = new ObjectMapper().readTree(refused.getContentAsString());
        assertEquals(429, body.path("statusCode").asInt(),
                "the body must use the app's HttpResponse envelope, which the SPA parses unconditionally");
        assertEquals(LOGIN_PATH, body.path("path").asText());
        assertTrue(body.path("message").asText().toLowerCase().contains("rate limit"));
    }

    @Test
    @DisplayName("the global tier is a separate, far larger bucket than the auth tier")
    void globalTierIsNotDrainedByAuthTraffic() throws Exception {
        RateLimitFilter filter = newFilter();
        CountingFilterChain chain = new CountingFilterChain();

        for (int i = 0; i <= AUTH_CAPACITY; i++) {
            fire(filter, LOGIN_PATH, PEER, null, chain);
        }
        assertEquals(429, fire(filter, LOGIN_PATH, PEER, null, chain).getStatus(),
                "precondition: the auth bucket for this client is drained");

        assertEquals(200, fire(filter, GLOBAL_PATH, PEER, null, chain).getStatus(),
                "ordinary API traffic must survive a drained auth bucket, or one failed login storm "
                        + "would take the whole SPA down for that client");
    }

    @Test
    @DisplayName("one client draining its bucket does not throttle a different client")
    void bucketsAreKeyedPerClient() throws Exception {
        RateLimitFilter filter = newFilter();
        CountingFilterChain chain = new CountingFilterChain();

        for (int i = 0; i <= AUTH_CAPACITY; i++) {
            fire(filter, LOGIN_PATH, PEER, null, chain);
        }
        assertEquals(429, fire(filter, LOGIN_PATH, PEER, null, chain).getStatus());

        assertEquals(200, fire(filter, LOGIN_PATH, "198.51.100.4", null, chain).getStatus(),
                "the limiter is per-IP, not global");
    }

    /**
     * The attack the limiter has to survive: with no proxy configured, {@code X-Forwarded-For} is
     * attacker-controlled, so a caller that rotates it must not be handed a new bucket each time.
     *
     * <p>This is the regression guard for {@link RateLimitFilter} resolving its key through
     * {@link RequestUtils#getIpAddress} rather than reading the header itself. A direct read makes
     * every one of these requests look like a different client and admits all 30.
     */
    @Test
    @DisplayName("a forged X-Forwarded-For cannot mint fresh buckets when no proxy is configured")
    void forgedForwardedForCannotMintFreshBuckets() throws Exception {
        RequestUtils.configureTrustedProxyCount(0);
        RateLimitFilter filter = newFilter();
        CountingFilterChain chain = new CountingFilterChain();

        int refusals = 0;
        for (int i = 0; i < 30; i++) {
            // A different forged address on every single request.
            MockHttpServletResponse response = fire(filter, LOGIN_PATH, PEER, "10.0.0." + i, chain);
            if (response.getStatus() == 429) {
                refusals++;
            }
        }

        assertEquals(AUTH_CAPACITY, chain.invocations,
                "all 30 requests came from the same TCP peer, so only the bucket capacity may pass");
        assertEquals(30 - AUTH_CAPACITY, refusals);
    }

    /**
     * The honest-deployment counterpart: behind one trusted proxy the header is meaningful, and the
     * genuine client sits at {@code length - trustedProxyCount}. A hostile client prepending its own
     * entry shifts the list but not that index, so it still shares a bucket with itself.
     */
    @Test
    @DisplayName("behind one trusted proxy, the real client is throttled despite a prepended forgery")
    void forgedLeadingEntryDoesNotEscapeTheBucketBehindAProxy() throws Exception {
        RequestUtils.configureTrustedProxyCount(1);
        RateLimitFilter filter = newFilter();
        CountingFilterChain chain = new CountingFilterChain();

        // The proxy appends what it actually observed, so the real client is always the last entry.
        for (int i = 0; i < 30; i++) {
            fire(filter, LOGIN_PATH, PEER, "10.0.0." + i + ", 198.51.100.9", chain);
        }

        assertEquals(AUTH_CAPACITY, chain.invocations,
                "the trailing entry is the same client throughout, so one bucket governs all 30");
    }

    /**
     * The bucket stores must be bounded, because their keys come from unauthenticated input.
     *
     * <p>They were previously plain {@code ConcurrentHashMap}s with no eviction of any kind, so
     * every distinct client IP the process ever saw left an entry behind permanently. On a
     * public-facing service that set is not bounded by the user base: background internet scanning
     * grows it continuously, and an attacker rotating source addresses grows it on purpose. The
     * filter that exists to stop the service being exhausted was itself an unbounded allocation
     * driven by the caller, reachable at {@code @Order(-200)} before any authentication runs.
     *
     * <p>This is the one property of that fix that cannot be observed from a response: a bounded
     * store and an unbounded one answer every request identically and differ only in what they do
     * to the heap over days. Hence the reach into {@link RateLimitFilter#trackedClients()}.
     *
     * <p>Deliberately drives more distinct addresses than the cache is allowed to keep, which is
     * what makes the assertion meaningful — it takes a second or so, and that cost is the point.
     */
    @Test
    @DisplayName("the bucket store is bounded, so rotating source IPs cannot exhaust the heap")
    void bucketStoreIsBoundedAgainstAddressRotation() throws Exception {
        RateLimitFilter filter = newFilter();
        CountingFilterChain chain = new CountingFilterChain();

        // Comfortably past the 50,000-per-tier ceiling, each address used exactly once.
        int distinctClients = 60_000;
        for (int i = 0; i < distinctClients; i++) {
            fire(filter, GLOBAL_PATH, "10." + (i >> 16 & 0xFF) + "." + (i >> 8 & 0xFF) + "." + (i & 0xFF), null, chain);
        }

        long tracked = filter.trackedClients();
        assertTrue(tracked < distinctClients,
                "every address was retained (" + tracked + "), so the store is still unbounded");
        assertTrue(tracked <= 50_000,
                "the store grew past its configured ceiling: " + tracked);
        assertEquals(distinctClients, chain.invocations,
                "each address is a first request on its own bucket, so none should have been refused");
    }
}
