package com.bob.angularspringbootfullstack.filter;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.utils.RequestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Servlet filter that enforces two-tier request rate limiting (SRS NFR-SEC-RL / FR-TPF-3).
 *
 * <p><b>Tiers:</b>
 * <ul>
 *   <li><b>Auth tier (10 req/min per IP)</b> — applied to endpoints that accept credentials or
 *       verification codes: login, registration, refresh, TOTP verify, password-reset, and
 *       account-verify. Keeps brute-force cost prohibitively high even beyond the per-account
 *       lockout that already exists in {@code UserController.authenticate()}.</li>
 *   <li><b>Global tier (200 req/min per IP)</b> — applied to every other path. Prevents
 *       general API abuse from a single client while leaving normal SPA usage unaffected.</li>
 * </ul>
 *
 * <p><b>When limits are exceeded:</b> responds immediately with {@code 429 Too Many Requests},
 * a {@code Retry-After} header containing the number of seconds until the bucket refills, and a
 * JSON body in the application's standard {@link HttpResponse} envelope so the Angular frontend
 * can surface a human-readable message.
 *
 * <p><b>Bucket key:</b> the client IP as resolved by
 * {@link com.bob.angularspringbootfullstack.utils.RequestUtils#getIpAddress}, never by reading
 * {@code X-Forwarded-For} directly. A token bucket is only as strong as its key: because
 * {@code X-Forwarded-For} is an ordinary request header that any caller can set, taking its leading
 * entry would let a client pick its own bucket and rotate to a fresh allowance on every request,
 * making this limiter decorative. {@code RequestUtils} ignores the header entirely unless a trusted
 * proxy depth is configured, and otherwise counts from the right-hand end where our own
 * infrastructure writes. Requests whose address cannot be determined share the single
 * {@code "Unknown IP"} bucket, which throttles them together rather than exempting them.
 *
 * <p><b>Storage:</b> in-memory Caffeine caches keyed by client IP, bounded by both
 * {@link #MAX_TRACKED_CLIENTS} and {@link #IDLE_RETENTION}. This is correct for single-instance
 * deployments. For a multi-instance / horizontally-scaled deployment, replace the bucket store with
 * a shared backend (e.g. Bucket4j + Redis via {@code bucket4j-redis} or Hazelcast via
 * {@code bucket4j-hazelcast}) so limits are enforced across nodes — see
 * {@code documentation/FUTURE-ENHANCEMENTS.md} §2.4, which explains why that is an infrastructure
 * decision rather than something to push into MySQL.
 *
 * <p><b>What the bounds do and do not buy.</b> They fix a single-instance memory-exhaustion vector
 * (the stores previously grew without limit, one entry per client IP forever). They do <em>not</em>
 * make the limiter correct across instances: N nodes still means N independent allowances for the
 * same caller, so the effective limit is N× the configured one. Those are separate problems with
 * separate fixes, and only the first is addressed here.
 *
 * <p>This filter is registered as a plain servlet filter at {@code @Order(-200)}, placing it
 * BEFORE Spring Security's {@code FilterChainProxy} (default order -100). Rate limiting therefore
 * fires before JWT parsing or any authentication work begins, which is the correct architecture.
 */
@Component
@Order(-200)
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Paths that receive the tighter auth-tier bucket (10 req/min per IP).
     * Any request whose URI *starts with* one of these strings is treated as an auth request.
     */
    private static final Set<String> AUTH_PATHS = Set.of(
            "/user/login",
            "/user/register",
            "/user/refresh/token",
            "/user/verify/totp",
            "/user/new/password",
            "/user/verify/account",
            "/user/resetpassword",
            "/user/verify/code",
            "/user/verify/resend"
    );

    /**
     * Requests per minute per IP on {@link #AUTH_PATHS}. Defaults to 10 — deliberately tight,
     * because these are the endpoints worth brute-forcing.
     *
     * <p>Externalised rather than hardcoded for one concrete reason: the Playwright E2E suite runs
     * every test from a single client IP (inside Docker the whole host collapses to one bridge
     * gateway address), so a normal 14-test run looks exactly like an attack and the suite starts
     * failing on 429s that have nothing to do with the behaviour under test. Rather than weaken the
     * limit for everyone, {@code .env.e2e} raises it for that stack alone, and
     * {@code e2e/rate-limit.spec.ts} then asserts the limiter still rejects a genuine burst.
     *
     * <p>The default is the production value, so every environment that sets nothing is unchanged.
     */
    private final int authCapacity;

    /** Requests per minute per IP on every other path. Defaults to 200. See {@link #authCapacity}. */
    private final int globalCapacity;

    /**
     * How long an unused per-IP bucket is kept before it is discarded.
     *
     * <p>Must be comfortably longer than the bucket's own refill period (one minute), and that
     * constraint is what makes eviction safe rather than a loophole. A greedy bucket that has not
     * been touched for a full minute has already refilled to capacity, so it is byte-for-byte
     * equivalent to the fresh bucket that would replace it. Evicting after ten idle minutes
     * therefore cannot hand anyone an allowance they had not already regained by waiting — an
     * attacker who stops for ten minutes to get their bucket dropped has simply been rate limited
     * for ten minutes, which is the point.
     */
    private static final Duration IDLE_RETENTION = Duration.ofMinutes(10);

    /**
     * Hard ceiling on how many client IPs are tracked per tier.
     *
     * <p>Backstop for the case {@link #IDLE_RETENTION} alone does not bound: a flood from many
     * distinct source addresses, which would otherwise mint a bucket per address faster than idle
     * expiry retires them. Caffeine evicts by frequency/recency, so an address that is actively
     * being throttled is among the last to be dropped, while the one-shot addresses that scanners
     * and botnets generate are the first.
     *
     * <p>50,000 entries per tier is a few megabytes and far above any plausible legitimate
     * concurrent-client count for this application. Raise it only alongside a heap increase.
     */
    private static final long MAX_TRACKED_CLIENTS = 50_000L;

    /**
     * Per-IP buckets for the auth tier. Lazily created on first auth request from an IP.
     *
     * <p>Bounded caches rather than plain {@link ConcurrentHashMap}s, which is a correctness fix and
     * not a tidy-up. The maps here previously had no eviction of any kind, so every distinct client
     * IP the application ever saw left a permanent entry in two of them. On a public-facing service
     * that set is not bounded by the user base — background internet scanning alone grows it
     * continuously, and an attacker rotating source addresses grows it deliberately. The limiter
     * that exists to make the service hard to exhaust was itself an unbounded allocation driven by
     * unauthenticated input, reachable at {@code @Order(-200)} before any authentication runs.
     */
    private final Cache<String, Bucket> authBuckets = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_CLIENTS)
            .expireAfterAccess(IDLE_RETENTION)
            .build();

    /** Per-IP buckets for the global tier. Lazily created on first request from an IP. */
    private final Cache<String, Bucket> globalBuckets = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_CLIENTS)
            .expireAfterAccess(IDLE_RETENTION)
            .build();

    private final ObjectMapper objectMapper;

    /**
     * Constructor injection, deliberately, rather than {@code @Value} on the fields.
     *
     * <p>Field injection would have been fewer lines and would have silently broken every test in
     * {@code RateLimitFilterTest}: those construct the filter directly with {@code new}, which no
     * Spring post-processor ever sees, so both capacities would have stayed at {@code int}'s default
     * of zero — a bucket that admits nothing and answers 429 to the very first request. Taking them
     * as constructor arguments makes the filter impossible to build in that half-initialised state,
     * and lets a test state outright which limits it is exercising.
     */
    public RateLimitFilter(ObjectMapper objectMapper,
                           @Value("${security.rate-limit.auth-capacity:10}") int authCapacity,
                           @Value("${security.rate-limit.global-capacity:200}") int globalCapacity) {
        this.objectMapper = objectMapper;
        this.authCapacity = authCapacity;
        this.globalCapacity = globalCapacity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String clientIp = RequestUtils.getIpAddress(request);
        String path     = request.getRequestURI();

        boolean isAuthEndpoint = AUTH_PATHS.stream().anyMatch(path::startsWith);

        Bucket bucket = isAuthEndpoint
                ? authBuckets.get(clientIp, ip -> buildBucket(authCapacity))
                : globalBuckets.get(clientIp, ip -> buildBucket(globalCapacity));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
        log.warn("Rate limit exceeded for IP {} on path {} — retry after {}s", clientIp, path, retryAfterSeconds);

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        HttpResponse httpResponse = HttpResponse.builder()
                .timeStamp(LocalDateTime.now().toString())
                .statusCode(429)
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .reason("Too Many Requests")
                .message("Rate limit exceeded. Please wait " + retryAfterSeconds + " seconds before retrying.")
                .path(path)
                .build();

        response.getWriter().write(objectMapper.writeValueAsString(httpResponse));
    }

    /**
     * How many distinct client IPs are currently tracked, across both tiers.
     *
     * <p>Package-private and present solely so {@code RateLimitFilterTest} can assert that the
     * bucket stores are actually bounded. That property is invisible from the outside — an
     * unbounded store and a bounded one answer every request identically and differ only in what
     * they do to the heap over days — so without this the regression it guards against could
     * return unnoticed.
     *
     * <p>Runs Caffeine's maintenance first: eviction is amortised onto later operations rather than
     * performed inline, so {@code estimatedSize()} can otherwise still count entries that are
     * already logically evicted.
     *
     * @return the combined number of live entries in the auth and global stores
     */
    long trackedClients() {
        authBuckets.cleanUp();
        globalBuckets.cleanUp();
        return authBuckets.estimatedSize() + globalBuckets.estimatedSize();
    }

    /**
     * Builds a Bucket4j token-bucket that refills {@code capacity} tokens every minute
     * using a greedy (spread) refill strategy so tokens are available throughout the
     * minute rather than all at once at reset time.
     *
     * @param capacity maximum number of requests allowed per minute
     * @return a configured {@link Bucket} ready for use
     */
    private static Bucket buildBucket(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, Duration.ofMinutes(1))
                        .build())
                .build();
    }

}
