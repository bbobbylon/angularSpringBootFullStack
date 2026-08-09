package com.bob.angularspringbootfullstack.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link PhonePolicy#PATTERN} directly via {@code String#matches}, the same mechanism
 * Bean Validation's {@code @Pattern} uses against {@code UpdateForm.phoneNumber}. The motivating
 * regression is {@code "1234567"} (7 digits, no letters) — a value the OLD pattern
 * (`^\+?[0-9. ()-]{7,25}$`) accepted outright.
 */
class PhonePolicyTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" is a valid US phone number")
    @ValueSource(strings = {
            "8084824518",
            "(808) 482-4518",
            "808-482-4518",
            "808.482.4518",
            "808 482 4518",
            "+18084824518",
            "18084824518",
            "1 808 482 4518",
            "1-808-482-4518",
    })
    @DisplayName("accepts every common US phone number shape")
    void acceptsValidShapes(String phoneNumber) {
        assertTrue(phoneNumber.matches(PhonePolicy.PATTERN));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is NOT a valid US phone number")
    @ValueSource(strings = {
            "1234567",             // the motivating regression: 7 digits, used to pass
            "",                    // blank
            "abc-def-ghij",        // letters, not digits
            "80848245180",         // 11 digits not starting with a leading 1
            "+448084824518",       // non-US country code
            "808-482-451",         // one digit short
            "808-482-45188",       // one digit too many
            "000000000000000000",  // 18-digit garbage the old {7,25}-length pattern would have accepted
    })
    @DisplayName("rejects malformed input, including the exact case that motivated this class")
    void rejectsMalformedShapes(String phoneNumber) {
        assertFalse(phoneNumber.matches(PhonePolicy.PATTERN));
    }
}
