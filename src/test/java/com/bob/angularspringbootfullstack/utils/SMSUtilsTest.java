package com.bob.angularspringbootfullstack.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Covers the switch that decides whether two-factor codes are delivered by SMS or written to the log.
 *
 * <p><b>Why this toggle is load-bearing.</b> {@link SMSUtils#sendSMS} sits inside the 2FA path, and
 * Twilio's client throws when initialised with absent credentials. Without the guard, every
 * deployment that has not bought a Twilio account — which is every developer machine and the CI
 * runner — would see second-factor delivery fail as a server error rather than degrade to a logged
 * code. The guard is what lets the same code run unconfigured in development and configured in
 * production, so it needs to hold in both directions: attempt the call when everything is present,
 * and never attempt it otherwise.
 *
 * <p><b>Why the rule is tested through the value-taking overload.</b> The credentials are
 * {@code static final} fields initialised from {@link System#getenv} when the class loads. Java 21
 * blocks the reflective {@code Field.modifiers} trick that used to rebind such fields, and the JVM is
 * free to constant-fold them, so there is no way to test the environment-reading form by mutating
 * the environment. Extracting the decision into a pure function is what makes it testable at all —
 * the alternative is an untested branch guarding a network call.
 *
 * <p><b>The partial-configuration cases are the point.</b> A deployment with a SID and token but no
 * sender number is the realistic failure — someone populates two of three secrets — and it must fall
 * to the logging path rather than attempt a call that throws. Blank counts as absent because an unset
 * variable often reaches a container as an empty string rather than as {@code null}.
 */
class SMSUtilsTest {

    private static final String NUMBER = "5555550123";
    private static final String SID = "ACtest-account-sid";
    private static final String TOKEN = "test-auth-token";

    @Test
    @DisplayName("all three credentials present means a real Twilio call should be attempted")
    void fullyConfiguredEnablesDelivery() {
        assertTrue(SMSUtils.isConfigured(NUMBER, SID, TOKEN));
    }

    @ParameterizedTest(name = "[{index}] from={0} sid={1} token={2} is not configured")
    @CsvSource(nullValues = "NULL", value = {
            // Nothing at all — the developer-machine and CI default.
            "NULL,       NULL,          NULL",
            // One credential missing: the realistic half-populated-secrets case.
            "NULL,       ACtest-sid,    test-token",
            "5555550123, NULL,          test-token",
            "5555550123, ACtest-sid,    NULL",
            // Present but empty, which is how an unset variable often arrives through env-file plumbing.
            "'',         ACtest-sid,    test-token",
            "5555550123, '',            test-token",
            "5555550123, ACtest-sid,    ''",
            // Whitespace-only, the same mistake with an invisible cause.
            "'   ',      ACtest-sid,    test-token"
    })
    void anyMissingCredentialFallsBackToLogging(String fromNumber, String accountSid, String authToken) {
        assertFalse(SMSUtils.isConfigured(fromNumber, accountSid, authToken),
                "a partially configured deployment must log the code, not attempt a call that throws");
    }

    /**
     * The end-to-end shape of the degradation: unconfigured, {@code sendSMS} returns normally instead
     * of propagating a Twilio failure into the caller's 2FA response.
     *
     * <p>Skipped rather than failed when the running environment genuinely has Twilio credentials,
     * because the assertion would otherwise send a real, billable text message.
     */
    @Test
    @DisplayName("unconfigured, sendSMS degrades quietly instead of throwing into the 2FA flow")
    void sendSmsDoesNotThrowWhenUnconfigured() {
        assumeFalse(SMSUtils.isConfigured(),
                "this environment has real Twilio credentials; skipping rather than sending a live SMS");

        assertDoesNotThrow(() -> SMSUtils.sendSMS(NUMBER, "Your verification code is 123456"));
    }

    /**
     * Regression coverage for the 2026-08-08 delivery bug: a number already carrying the leading
     * {@code 1} (e.g. from a user who typed it that way) blindly got a second {@code "+1"}
     * prepended, producing an invalid 13-character string Twilio could never deliver. The Security
     * Center's phone field (pattern {@code ^\+?[0-9. ()-]{7,25}$}) accepts every shape below, so
     * {@link SMSUtils#toE164US} has to collapse them all to the same valid E.164 result.
     */
    @ParameterizedTest(name = "[{index}] \"{0}\" normalises to +18084824518")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "8084824518",         // bare 10 digits — the common case
            "18084824518",        // 10 digits already carrying the leading 1 (the bug's trigger)
            "+18084824518",       // already E.164
            "(808) 482-4518",     // formatted with separators, no country code
            "1 (808) 482-4518",   // formatted with separators, with country code
    })
    void toE164USNormalisesEveryAcceptedInputShape(String input) {
        org.junit.jupiter.api.Assertions.assertEquals("+18084824518", SMSUtils.toE164US(input));
    }
}
