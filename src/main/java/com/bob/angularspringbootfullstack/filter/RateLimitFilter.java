package com.bob.angularspringbootfullstack.filter;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.utils.RequestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p><b>Storage:</b> in-memory {@link ConcurrentHashMap} keyed by client IP. This is correct
 * for single-instance deployments. For a multi-instance / horizontally-scaled deployment, replace
 * the bucket store with a shared backend (e.g. Bucket4j + Redis via {@code bucket4j-redis}
 * or Hazelcast via {@code bucket4j-hazelcast}) so limits are enforced across nodes.
 *
 * <p>This filter is registered as a plain servlet filter at {@code @Order(-200)}, placing it
 * BEFORE Spring Security's {@code FilterChainProxy} (default order -100). Rate limiting therefore
 * fires before JWT parsing or any authentication work begins, which is the correct architecture.
 */
@Component
@Order(-200)
@Slf4j
@RequiredArgsConstructor
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
            "/user/verify/code"
    );

    private static final int AUTH_CAPACITY     = 10;   // requests per minute on auth endpoints
    private static final int GLOBAL_CAPACITY   = 200;  // requests per minute on all other endpoints

    /** Per-IP buckets for the auth tier. Lazily created on first auth request from an IP. */
    private final ConcurrentHashMap<String, Bucket> authBuckets   = new ConcurrentHashMap<>();
    /** Per-IP buckets for the global tier. Lazily created on first request from an IP. */
    private final ConcurrentHashMap<String, Bucket> globalBuckets = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String clientIp = RequestUtils.getIpAddress(request);
        String path     = request.getRequestURI();

        boolean isAuthEndpoint = AUTH_PATHS.stream().anyMatch(path::startsWith);

        Bucket bucket = isAuthEndpoint
                ? authBuckets.computeIfAbsent(clientIp, ip -> buildBucket(AUTH_CAPACITY))
                : globalBuckets.computeIfAbsent(clientIp, ip -> buildBucket(GLOBAL_CAPACITY));

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
