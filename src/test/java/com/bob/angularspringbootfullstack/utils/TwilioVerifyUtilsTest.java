package com.bob.angularspringbootfullstack.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the switch that decides whether phone 2FA goes through Twilio Verify or falls back to
 * the locally-generated {@link VoiceUtils} code path — the three-way analogue of
 * {@link SMSUtilsTest}'s two-way SMS/log toggle.
 *
 * <p><b>Why only the predicate is tested here.</b> {@link SMSUtilsTest} and {@link VoiceUtilsTest}
 * can safely call their real send methods when unconfigured because those methods carry their own
 * internal degrade branch (log the code, return). {@link TwilioVerifyUtils#startVerification} and
 * {@link TwilioVerifyUtils#checkVerification} deliberately do not: this class's contract is that
 * callers ({@code NotificationServiceImpl}, {@code UserRepoImpl}) check {@link
 * TwilioVerifyUtils#isConfigured()} themselves before calling either method, so there is no safe
 * unconfigured call to exercise here without either throwing (no Verify Service SID to address) or,
 * if real credentials happen to be present, sending a live billable OTP. The configuration rule
 * itself is the one pure, safely testable piece, for the same reason {@link SMSUtils#isConfigured}
 * is tested through its value-taking overload rather than by mutating the environment: the fields
 * are {@code static final}, read once at class-initialisation time.
 */
class TwilioVerifyUtilsTest {

    private static final String SID = "ACtest-account-sid";
    private static final String TOKEN = "test-auth-token";
    private static final String VERIFY_SID = "VAtest-verify-service-sid";

    @Test
    @DisplayName("all three of account SID, auth token, and Verify Service SID present means Verify should be used")
    void fullyConfiguredEnablesVerify() {
        assertTrue(TwilioVerifyUtils.isConfigured(SID, TOKEN, VERIFY_SID));
    }

    @ParameterizedTest(name = "[{index}] sid={0} token={1} verifySid={2} is not configured")
    @CsvSource(nullValues = "NULL", value = {
            // Nothing at all — the developer-machine and CI default, and every deployment before
            // the Verify Service is created in the Twilio console.
            "NULL,       NULL,          NULL",
            // The realistic transitional case: SMS/voice credentials already present (VoiceUtils
            // works today) but the Verify Service SID not yet added.
            "ACtest-sid, test-token,    NULL",
            // Any other single credential missing.
            "NULL,       test-token,    VAtest-sid",
            "ACtest-sid, NULL,          VAtest-sid",
            // Present but empty/whitespace-only, matching the env-file plumbing case SMSUtilsTest covers.
            "'',         test-token,    VAtest-sid",
            "ACtest-sid, '',            VAtest-sid",
            "ACtest-sid, test-token,    ''",
            "'   ',      test-token,    VAtest-sid"
    })
    void anyMissingCredentialFallsBackToLocalCode(String accountSid, String authToken, String verifyServiceSid) {
        assertFalse(TwilioVerifyUtils.isConfigured(accountSid, authToken, verifyServiceSid),
                "a partially configured deployment must fall back to the VoiceUtils/local-code path, not call Verify");
    }
}
