package com.bob.angularspringbootfullstack.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link HttpCacheHeadersFilter} actually delivers the two things
 * POST-SUBMISSION-UPGRADES.md #3 asked for — a browser can save bandwidth via a conditional GET,
 * but never serve a response it has not re-validated with the server on this request — and that
 * the bypass list keeps auth/verification/download traffic untouched exactly like
 * {@code cacheInterceptor}'s {@code bypassRoutes} did on the frontend.
 *
 * <p>Each case constructs its own filter; {@link HttpCacheHeadersFilter} holds no per-request
 * state itself (unlike {@link RateLimitFilter}'s buckets), so a shared instance would have been
 * fine too, but a fresh one per test keeps every case self-contained.
 */
class HttpCacheHeadersFilterTest {

    /** A chain standing in for the controller: writes a fixed body and records how often it ran. */
    private static final class EchoFilterChain implements FilterChain {
        private final String body;
        private final String contentType;
        private int invocations;

        EchoFilterChain(String body) {
            this(body, "application/json");
        }

        EchoFilterChain(String body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
            invocations++;
            response.setContentType(contentType);
            response.getWriter().write(body);
        }
    }

    private static MockHttpServletResponse fire(HttpCacheHeadersFilter filter, String method, String path,
                                                  String ifNoneMatch, EchoFilterChain chain)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (ifNoneMatch != null) {
            request.addHeader("If-None-Match", ifNoneMatch);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("a cacheable GET gets Cache-Control: private, no-cache and an ETag")
    void cacheableGetIsAnnotated() throws Exception {
        HttpCacheHeadersFilter filter = new HttpCacheHeadersFilter(new ObjectMapper());
        EchoFilterChain chain = new EchoFilterChain("{\"data\":[]}");

        MockHttpServletResponse response = fire(filter, "GET", "/customer/list", null, chain);

        assertEquals(200, response.getStatus());
        assertEquals("private, no-cache", response.getHeader("Cache-Control"),
                "must be private — a shared cache in front of the app (CloudFront) must never reuse this");
        assertNotNull(response.getHeader("ETag"), "no ETag means the browser has nothing to revalidate against");
        assertEquals("{\"data\":[]}", response.getContentAsString());
    }

    @Test
    @DisplayName("an unchanged response on the next request comes back as an empty 304")
    void matchingEtagShortCircuitsTo304() throws Exception {
        HttpCacheHeadersFilter filter = new HttpCacheHeadersFilter(new ObjectMapper());

        MockHttpServletResponse first = fire(filter, "GET", "/customer/list", null, new EchoFilterChain("{\"data\":[]}"));
        String etag = first.getHeader("ETag");
        assertNotNull(etag);

        EchoFilterChain secondChain = new EchoFilterChain("{\"data\":[]}");
        MockHttpServletResponse second = fire(filter, "GET", "/customer/list", etag, secondChain);

        assertEquals(304, second.getStatus());
        assertEquals("", second.getContentAsString(),
                "the whole point of a 304 is that the body is not re-sent");
        assertEquals(1, secondChain.invocations,
                "the controller still runs — the filter only short-circuits the response Spring already produced, "
                        + "so a write by another user is always seen (that is what makes this safe across users)");
    }

    @Test
    @DisplayName("data that actually changed since the stored ETag comes back as a fresh 200")
    void changedDataNeverServed304() throws Exception {
        HttpCacheHeadersFilter filter = new HttpCacheHeadersFilter(new ObjectMapper());

        MockHttpServletResponse first = fire(filter, "GET", "/customer/list", null, new EchoFilterChain("{\"data\":[]}"));
        String staleEtag = first.getHeader("ETag");

        // Simulates a different user's write having changed the data in between the two requests.
        MockHttpServletResponse second = fire(filter, "GET", "/customer/list", staleEtag,
                new EchoFilterChain("{\"data\":[{\"id\":1}]}"));

        assertEquals(200, second.getStatus());
        assertEquals("{\"data\":[{\"id\":1}]}", second.getContentAsString(),
                "a stale If-None-Match must never suppress a body that actually changed");
    }

    @Test
    @DisplayName("two responses that differ only in the envelope's volatile timeStamp field hash identically")
    void volatileTimeStampFieldDoesNotBustTheEtag() throws Exception {
        // Regression test for the defect curl-smoke-test.sh caught live: HttpResponse.timeStamp is
        // stamped fresh (nanosecond LocalTime) on every response, so hashing the raw body — as a
        // stock ShallowEtagHeaderFilter would — meant no two calls could ever hash the same and a
        // 304 could never fire, even when the underlying customer/user data was byte-identical.
        HttpCacheHeadersFilter filter = new HttpCacheHeadersFilter(new ObjectMapper());

        MockHttpServletResponse first = fire(filter, "GET", "/customer/list", null,
                new EchoFilterChain("{\"timeStamp\":\"10:00:00.111111111\",\"data\":{\"page\":{\"content\":[]}}}"));
        String etag = first.getHeader("ETag");
        assertNotNull(etag);

        EchoFilterChain secondChain = new EchoFilterChain(
                "{\"timeStamp\":\"10:00:05.999999999\",\"data\":{\"page\":{\"content\":[]}}}");
        MockHttpServletResponse second = fire(filter, "GET", "/customer/list", etag, secondChain);

        assertEquals(304, second.getStatus(),
                "the ETag must be computed with timeStamp stripped, or a fresh timestamp on every response "
                        + "makes a 304 impossible even when the real data never changed");
        assertEquals(1, secondChain.invocations);
    }

    @Test
    @DisplayName("a differing timeStamp never masks data that actually changed")
    void volatileTimeStampFieldDoesNotMaskRealChanges() throws Exception {
        HttpCacheHeadersFilter filter = new HttpCacheHeadersFilter(new ObjectMapper());

        MockHttpServletResponse first = fire(filter, "GET", "/customer/list", null,
                new EchoFilterChain("{\"timeStamp\":\"10:00:00.111111111\",\"data\":{\"page\":{\"content\":[]}}}"));
        String staleEtag = first.getHeader("ETag");

        String changedBody = "{\"timeStamp\":\"10:00:05.999999999\",\"data\":{\"page\":{\"content\":[{\"id\":1}]}}}";
        MockHttpServletResponse second = fire(filter, "GET", "/customer/list", staleEtag, new EchoFilterChain(changedBody));

        assertEquals(200, second.getStatus(),
                "stripping timeStamp from the hash must not make an unrelated real data change invisible too");
        assertEquals(changedBody, second.getContentAsString());
    }

    @Test
    @DisplayName("a non-JSON body (e.g. the profile-image PNG bytes) still gets a stable ETag from its raw bytes")
    void nonJsonBodyFallsBackToRawByteHash() throws Exception {
        HttpCacheHeadersFilter filter = new HttpCacheHeadersFilter(new ObjectMapper());

        MockHttpServletResponse first = fire(filter, "GET", "/user/image/eve.admin@tessera.dev.png", null,
                new EchoFilterChain("not-really-png-bytes-but-stable", "image/png"));
        String etag = first.getHeader("ETag");
        assertNotNull(etag);

        EchoFilterChain secondChain = new EchoFilterChain("not-really-png-bytes-but-stable", "image/png");
        MockHttpServletResponse second = fire(filter, "GET", "/user/image/eve.admin@tessera.dev.png", etag, secondChain);

        assertEquals(304, second.getStatus(), "identical non-JSON bytes must still short-circuit to a 304");
    }

    @Test
    @DisplayName("auth, verification, and download paths are left completely untouched")
    void bypassPathsGetNoHeaders() throws Exception {
        HttpCacheHeadersFilter filter = new HttpCacheHeadersFilter(new ObjectMapper());
        String[] bypassPaths = {
                "/user/login", "/user/register", "/user/refresh/token",
                "/user/verify/account/abc", "/user/resetpassword/a@b.com",
                "/user/new/password", "/customer/download/report",
        };

        for (String path : bypassPaths) {
            EchoFilterChain chain = new EchoFilterChain("irrelevant");
            MockHttpServletResponse response = fire(filter, "GET", path, null, chain);

            assertNull(response.getHeader("Cache-Control"), path + " must not gain a Cache-Control header");
            assertNull(response.getHeader("ETag"), path + " must not gain an ETag");
            assertEquals(1, chain.invocations, path + " must still reach the application");
        }
    }

    @Test
    @DisplayName("a non-GET request is passed through untouched regardless of path")
    void nonGetRequestsAreIgnored() throws Exception {
        HttpCacheHeadersFilter filter = new HttpCacheHeadersFilter(new ObjectMapper());
        EchoFilterChain chain = new EchoFilterChain("{\"ok\":true}");

        MockHttpServletResponse response = fire(filter, "POST", "/customer/create", null, chain);

        assertNull(response.getHeader("Cache-Control"));
        assertNull(response.getHeader("ETag"));
        assertEquals(1, chain.invocations);
    }
}
