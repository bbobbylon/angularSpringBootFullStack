package com.bob.angularspringbootfullstack.utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Verifies Cloudflare Turnstile CAPTCHA tokens submitted with registration
 * (FUTURE-ENHANCEMENTS.md §3.1, "Registration has no bot/abuse-specific protection").
 * <p>
 * Same shape as {@link SMSUtils}/{@link TwilioVerifyUtils}: credentials come from an environment
 * variable read once into a {@code static final} field, never from {@code application.yml}, and
 * {@link #isConfigured()} gates a graceful-degradation path rather than {@link #verify} throwing —
 * an unconfigured deployment (dev, CI, a fresh clone with no Cloudflare account yet) must still be
 * able to register users, exactly as an unconfigured Twilio still lets 2FA codes through via the
 * log. This is a deliberate, documented trade-off, not an oversight: a registration-blocking
 * feature that fails closed when unconfigured would break every environment that has not yet set
 * up the third-party account, which is a strictly worse default for this project than the small
 * window where an unconfigured deployment has no bot protection at all.
 * <p>
 * Unlike the Twilio classes, this is the codebase's first call to a third party over a plain REST
 * API rather than a vendor SDK — {@link RestClient} (Spring's synchronous HTTP client) already
 * ships with {@code spring-boot-starter-webmvc}, so no new dependency was needed.
 */
public class TurnstileUtils {

    private static final Logger log = LoggerFactory.getLogger(TurnstileUtils.class);

    private static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    /** Cloudflare Turnstile secret key, loaded from the TURNSTILE_SECRET_KEY env var. */
    public static final String SECRET_KEY = System.getenv("TURNSTILE_SECRET_KEY");

    private static final RestClient REST_CLIENT = RestClient.create();

    private TurnstileUtils() {
    }

    /**
     * Verifies a Turnstile token submitted with a registration attempt.
     *
     * @param token    the {@code cf-turnstile-response} value the widget produced client-side
     * @param remoteIp the registering client's IP, forwarded to Cloudflare for its own risk
     *                 scoring — the same {@link RequestUtils#getIpAddress} value the rate limiter
     *                 and anomaly detector already key on
     * @return {@code true} if Turnstile is unconfigured (graceful degradation), or if it is
     *         configured and confirms the token; {@code false} for a blank/missing token, a
     *         failed check, or a network/API error talking to Cloudflare (fail closed once this
     *         feature is actually turned on, since an error here is indistinguishable from an
     *         attacker's malformed request)
     */
    public static boolean verify(String token, String remoteIp) {
        return verify(TurnstileUtils::callSiteverify, SECRET_KEY, token, remoteIp);
    }

    /**
     * Package-private, decision-logic overload — see {@link #verify(String, String)}. Takes the
     * actual network call as a {@link SiteverifyCaller} rather than a mocked {@link RestClient} so
     * {@code TurnstileUtilsTest} can exercise every branch (unconfigured, blank token, success,
     * rejection, thrown exception) with a plain lambda instead of stubbing {@link RestClient}'s
     * fluent chain, which Mockito handles poorly.
     */
    static boolean verify(SiteverifyCaller caller, String secretKey, String token, String remoteIp) {
        if (!isConfigured(secretKey)) {
            log.warn("Turnstile is not configured; registration CAPTCHA check skipped.");
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            return caller.call(secretKey, token, remoteIp);
        } catch (Exception e) {
            log.error("Turnstile verification call failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * True only when a Turnstile secret key is present.
     *
     * @return whether {@link #verify} should attempt a real Cloudflare call
     */
    public static boolean isConfigured() {
        return isConfigured(SECRET_KEY);
    }

    /**
     * The configuration rule, expressed over an explicit value so it can be tested — mirrors
     * {@link SMSUtils#isConfigured(String, String, String)} for the same reason: {@link #SECRET_KEY}
     * is {@code static final} and can't be rebound from a test.
     *
     * @param secretKey the Turnstile secret key
     * @return {@code true} when the key is present and non-blank
     */
    static boolean isConfigured(String secretKey) {
        return secretKey != null && !secretKey.isBlank();
    }

    /**
     * The real network call, extracted to a method reference so {@link #verify} keeps a single
     * code path for both production and test callers of the package-private overload.
     */
    private static boolean callSiteverify(String secretKey, String token, String remoteIp) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secretKey);
        form.add("response", token);
        if (remoteIp != null && !remoteIp.isBlank()) {
            form.add("remoteip", remoteIp);
        }
        SiteverifyResponse response = REST_CLIENT.post()
                .uri(SITEVERIFY_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(SiteverifyResponse.class);
        return response != null && response.success();
    }

    @FunctionalInterface
    interface SiteverifyCaller {
        boolean call(String secretKey, String token, String remoteIp);
    }

    /**
     * Turnstile's siteverify JSON response, trimmed to the one field this class acts on.
     * {@code ignoreUnknown = true} because Cloudflare's real response also carries
     * {@code error-codes}, {@code challenge_ts}, {@code action}, {@code cdata}, and others.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SiteverifyResponse(boolean success) {
    }
}
