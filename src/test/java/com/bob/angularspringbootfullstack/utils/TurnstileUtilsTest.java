package com.bob.angularspringbootfullstack.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TurnstileUtils}'s decision logic. Exercised through the package-private
 * {@code verify(SiteverifyCaller, String, String, String)} overload with a plain lambda standing
 * in for the real Cloudflare call — see that overload's Javadoc for why a mocked {@link
 * org.springframework.web.client.RestClient} was deliberately avoided. The real
 * {@link TurnstileUtils#verify(String, String)}/{@link TurnstileUtils#isConfigured()} entry points
 * read a {@code static final} field bound once from {@code System.getenv} at class-load time, so —
 * exactly like {@code SMSUtilsTest}/{@code TwilioVerifyUtilsTest} — they can't be exercised under
 * both a configured and an unconfigured state from the same test run.
 */
class TurnstileUtilsTest {

    private static final String SECRET_KEY = "test-secret";

    @Test
    @DisplayName("unconfigured deployment: verification is skipped and treated as passed")
    void unconfiguredSkipsVerification() {
        boolean result = TurnstileUtils.verify(
                (secret, token, ip) -> {
                    throw new AssertionError("must not call Cloudflare when unconfigured");
                },
                null, "some-token", "1.2.3.4");

        assertTrue(result);
    }

    @Test
    @DisplayName("configured but blank token: rejected without calling Cloudflare")
    void configuredWithBlankTokenIsRejected() {
        boolean result = TurnstileUtils.verify(
                (secret, token, ip) -> {
                    throw new AssertionError("must not call Cloudflare with no token");
                },
                SECRET_KEY, "  ", "1.2.3.4");

        assertFalse(result);
    }

    @Test
    @DisplayName("configured with a missing (null) token: rejected without calling Cloudflare")
    void configuredWithNullTokenIsRejected() {
        boolean result = TurnstileUtils.verify(
                (secret, token, ip) -> {
                    throw new AssertionError("must not call Cloudflare with no token");
                },
                SECRET_KEY, null, "1.2.3.4");

        assertFalse(result);
    }

    @Test
    @DisplayName("configured, Cloudflare approves the token: verification passes")
    void configuredAndApprovedPasses() {
        boolean result = TurnstileUtils.verify(
                (secret, token, ip) -> secret.equals(SECRET_KEY) && token.equals("good-token"),
                SECRET_KEY, "good-token", "1.2.3.4");

        assertTrue(result);
    }

    @Test
    @DisplayName("configured, Cloudflare rejects the token: verification fails")
    void configuredAndRejectedFails() {
        boolean result = TurnstileUtils.verify(
                (secret, token, ip) -> false,
                SECRET_KEY, "bad-token", "1.2.3.4");

        assertFalse(result);
    }

    @Test
    @DisplayName("configured, the Cloudflare call throws: fails closed rather than propagating")
    void networkErrorFailsClosed() {
        boolean result = TurnstileUtils.verify(
                (secret, token, ip) -> {
                    throw new RuntimeException("simulated network failure");
                },
                SECRET_KEY, "good-token", "1.2.3.4");

        assertFalse(result);
    }

    @Test
    @DisplayName("isConfigured is false for null/blank, true for a real value")
    void isConfiguredRule() {
        assertFalse(TurnstileUtils.isConfigured(null));
        assertFalse(TurnstileUtils.isConfigured(""));
        assertFalse(TurnstileUtils.isConfigured("   "));
        assertTrue(TurnstileUtils.isConfigured(SECRET_KEY));
    }
}
