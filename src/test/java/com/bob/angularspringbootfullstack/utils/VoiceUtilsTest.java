package com.bob.angularspringbootfullstack.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Covers the two pure pieces of {@link VoiceUtils}: the TTS-readability formatting of an
 * alphanumeric code, and the unconfigured-Twilio degradation path shared with {@link SMSUtils}.
 *
 * <p>{@link VoiceUtils#buildTwiml} and {@link VoiceUtils#spellOut} are exercised directly rather
 * than through a real call, for the same reason {@link SMSUtilsTest} tests {@code toE164US}
 * directly — Twilio's client would either need real credentials or throw, and the value under test
 * is the string formatting, not the network call.
 */
class VoiceUtilsTest {

    @Test
    @DisplayName("spellOut comma-separates every character so TTS reads them individually")
    void spellOutSeparatesEveryCharacter() {
        assertEquals("A, 1, B, 2, C, 3, D", VoiceUtils.spellOut("A1B2C3D"));
    }

    @Test
    @DisplayName("spellOut handles a single character with no trailing separator")
    void spellOutHandlesSingleCharacter() {
        assertEquals("A", VoiceUtils.spellOut("A"));
    }

    @Test
    @DisplayName("buildTwiml produces well-formed XML containing a greeting and the spelled-out code twice")
    void buildTwimlProducesExpectedXml() throws Exception {
        String xml = VoiceUtils.buildTwiml("Bobby", "A1B2C3D");

        assertTrue(xml.contains("Hello Bobby"), "greeting should address the recipient by name");
        assertTrue(xml.contains("Tessera App"), "greeting should identify the caller");
        int firstOccurrence = xml.indexOf("A, 1, B, 2, C, 3, D");
        int secondOccurrence = xml.indexOf("A, 1, B, 2, C, 3, D", firstOccurrence + 1);
        assertTrue(firstOccurrence >= 0 && secondOccurrence > firstOccurrence,
                "the spelled-out code should appear twice, so a listener can catch it on the repeat");
        assertTrue(xml.contains("<Pause"), "a pause should separate the two readings");
    }

    /**
     * Same load-bearing toggle {@link SMSUtils} uses, reused rather than duplicated: Voice and SMS
     * share one set of Twilio credentials, so there is only one "is Twilio configured" decision in
     * this codebase, not two that could drift apart.
     */
    @ParameterizedTest(name = "[{index}] from={0} sid={1} token={2} is not configured")
    @CsvSource(nullValues = "NULL", value = {
            "NULL,       NULL,          NULL",
            "NULL,       ACtest-sid,    test-token",
            "5555550123, NULL,          test-token",
            "5555550123, ACtest-sid,    NULL"
    })
    void voiceFallbackSharesSmsConfigurationToggle(String fromNumber, String accountSid, String authToken) {
        org.junit.jupiter.api.Assertions.assertFalse(SMSUtils.isConfigured(fromNumber, accountSid, authToken));
    }

    /**
     * End-to-end shape of the degradation: unconfigured, {@code sendVerificationCall} returns
     * normally instead of throwing, exactly like {@link SMSUtils#sendSMS} does.
     *
     * <p>Skipped when the running environment has real Twilio credentials, because the assertion
     * would otherwise place a real, billable phone call.
     */
    @Test
    @DisplayName("unconfigured, sendVerificationCall degrades quietly instead of throwing")
    void sendVerificationCallDoesNotThrowWhenUnconfigured() {
        assumeFalse(SMSUtils.isConfigured(),
                "this environment has real Twilio credentials; skipping rather than placing a live call");

        assertDoesNotThrow(() -> VoiceUtils.sendVerificationCall("5555550123", "Bobby", "A1B2C3D"));
    }
}
