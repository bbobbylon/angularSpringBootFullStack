package com.bob.angularspringbootfullstack.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Set;

/**
 * Servlet filter giving GET responses on data-bearing endpoints a {@code Cache-Control} header
 * and an ETag, replacing the client-only cache {@code cacheInterceptor} used to provide on the
 * Angular side (POST-SUBMISSION-UPGRADES.md #3, FUTURE-ENHANCEMENTS.md §3.4).
 *
 * <p><b>Why this closes a real bug, not just relocates one.</b> The old frontend cache lived in
 * one browser tab's memory, keyed by URL with no freshness check at all. If User A edited a
 * customer, User B's tab had no way to find out — its cache kept serving the pre-edit response
 * until B triggered a mutation themself or reloaded the page; that limitation was the whole
 * reason this item was on the backlog ("caching is client-side only, so it cannot be invalidated
 * by a write from another user"). {@code Cache-Control: no-cache} does not mean "do not cache" —
 * paired with an ETag it means "never use a cached copy without asking the server first." Every
 * GET therefore does a real network round trip: if the current response's hash still matches what
 * the browser already has, the server answers with an empty {@code 304 Not Modified} (cheap — no
 * body is re-sent); if User A's edit changed the data, the hash differs and the browser gets a
 * full, fresh body. The fix needs no shared store (Redis) to be correct, because the ETag is
 * derived fresh from each request's own response rather than read from server-side state.
 *
 * <p><b>Why the hash is computed over a sanitized copy of the body, not the raw bytes.</b> Every
 * controller returns {@code ResponseEntity<HttpResponse>}, and {@code HttpResponse.timeStamp} is
 * stamped fresh ({@code now().toString()}, nanosecond precision) on every single response — it
 * carries no business data (the frontend never reads it) but it does change on literally every
 * call. Hashing the raw body, as a stock {@link org.springframework.web.filter.ShallowEtagHeaderFilter}
 * would, means two calls returning identical customer/user data still produce two different
 * hashes purely because of when the server happened to answer — the 304 short-circuit above could
 * then never fire, defeating the entire point of this filter. {@link #hashableBytes} strips that
 * one volatile field before hashing (JSON envelopes only — see its Javadoc) while
 * {@link #doFilterInternal} still writes the client the real, untouched body, real timestamp
 * included, on every non-304 response.
 *
 * <p><b>Why {@code private}, not the default.</b> Every response here is stateless-JWT
 * authenticated and varies by caller. {@code Cache-Control: private} tells any shared or
 * intermediary cache — notably the CloudFront distribution this app deploys behind, see
 * {@code aws/RUNBOOK.md} — that the response belongs to one client only and must never be reused
 * for a different one. Omitting it would let a CDN legally serve one organization's customer list
 * to a different organization's admin hitting the same URL.
 *
 * <p><b>The bypass list mirrors the old {@code cacheInterceptor}'s {@code bypassRoutes} exactly</b>
 * (auth, verification, and download endpoints) via a substring match against the request URI, so
 * nothing deliberately excluded from caching before becomes accidentally cacheable now.
 * Verification and password-reset codes are single-use; an ETag/304 could otherwise let a client
 * treat a code the server has already invalidated as still outstanding.
 *
 * <p><b>The cross-session half of this fix lives in {@code SessionController#logout}</b>, which
 * sends {@code Clear-Site-Data: "cache"} on sign-out — see that method's Javadoc for why the
 * always-revalidate model here does not by itself guarantee a second user signing in on the same
 * tab can never observe the first user's cached bytes.
 *
 * <p>Registered as a plain {@code @Component}-scanned servlet filter, the same convention
 * {@link RateLimitFilter} uses. No explicit ordering relative to Spring Security is required —
 * this filter only inspects the request method/URI and the response Spring Security/the
 * controller already produced, so it is correct at any position outside the servlet chain's
 * entry point.
 */
@Component
@Order(100)
@RequiredArgsConstructor
public class HttpCacheHeadersFilter extends OncePerRequestFilter {

    /**
     * Any request URI containing one of these is left completely untouched — no
     * {@code Cache-Control} header, no ETag. Matches {@code cacheInterceptor}'s
     * {@code bypassRoutes} plus its separate {@code download} exclusion.
     */
    private static final Set<String> BYPASS_SUBSTRINGS = Set.of(
            "verify", "login", "register", "refresh", "resetpassword", "new/password", "download"
    );

    /**
     * The {@code HttpResponse} envelope field every controller stamps fresh on every response —
     * see the class Javadoc for why it is excluded from the ETag hash. Public field name, not the
     * wire/JSON one; they're identical here since {@code HttpResponse} has no {@code @JsonProperty}
     * override on it.
     */
    private static final String VOLATILE_ENVELOPE_FIELD = "timeStamp";

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!"GET".equalsIgnoreCase(request.getMethod()) || isBypassed(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader("Cache-Control", "private, no-cache");

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapper);

        byte[] body = wrapper.getContentAsByteArray();
        boolean eligible = body.length > 0 && wrapper.getStatus() >= 200 && wrapper.getStatus() < 300;
        if (!eligible) {
            wrapper.copyBodyToResponse();
            return;
        }

        String etag = "\"0" + DigestUtils.md5DigestAsHex(hashableBytes(body, wrapper.getContentType())) + "\"";
        response.setHeader("ETag", etag);

        if (etag.equals(request.getHeader("If-None-Match"))) {
            // A 304 carries no body — the buffered bytes are deliberately never copied here.
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        wrapper.copyBodyToResponse();
    }

    /**
     * Returns the bytes the ETag is computed over.
     * <p>
     * For the JSON {@code HttpResponse} envelope (the shape every controller returns), this is
     * the real body with the top-level {@link #VOLATILE_ENVELOPE_FIELD} removed, so two calls
     * that differ only in <em>when</em> the server happened to answer still hash identically. Any
     * other field that legitimately changes between calls — updated customer data, a new
     * {@code lastLogin}, a different page of results — still changes the hash exactly as before,
     * since only the one named field is ever touched.
     * <p>
     * For anything that isn't a JSON object carrying that field (a non-JSON content type, or JSON
     * without it — e.g. an actuator endpoint), this returns the raw body untouched: the same
     * plain body hash a stock {@link org.springframework.web.filter.ShallowEtagHeaderFilter}
     * always used, so those responses keep behaving exactly as they did before this filter
     * existed. This also covers the one non-JSON GET route this filter still reaches —
     * {@code UserController#getProfileImage} ({@code GET /user/image/{fileName}}, PNG bytes) —
     * which the {@code contentType} check routes straight past the JSON parse attempt.
     *
     * @param body        the full, real response body already captured by the wrapper
     * @param contentType the response's {@code Content-Type}, or {@code null}
     * @return the bytes to feed to the MD5 digest
     */
    private byte[] hashableBytes(byte[] body, String contentType) {
        if (contentType == null || !contentType.contains("json")) {
            return body;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root instanceof ObjectNode envelope && envelope.has(VOLATILE_ENVELOPE_FIELD)) {
                envelope.remove(VOLATILE_ENVELOPE_FIELD);
                return objectMapper.writeValueAsBytes(envelope);
            }
        } catch (IOException malformedJson) {
            // Not parseable JSON despite the content type claiming so — fall through and hash
            // the raw bytes exactly as ShallowEtagHeaderFilter would have.
        }
        return body;
    }

    private static boolean isBypassed(String uri) {
        return BYPASS_SUBSTRINGS.stream().anyMatch(uri::contains);
    }
}
