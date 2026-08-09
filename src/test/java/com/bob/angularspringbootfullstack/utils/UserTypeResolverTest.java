package com.bob.angularspringbootfullstack.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the admin user-type badge derivation (P2-1): FEDERATED beats everything (it is an
 * immutable, already-decided fact), and the INTERNAL/EXTERNAL split is a fresh domain-allowlist
 * check on every call rather than anything stored.
 */
class UserTypeResolverTest {

    private static final String ALLOWLIST = "lewisu.edu, tesseraapp.dev";

    @Test
    @DisplayName("any FEDERATED_* origin wins outright, regardless of email domain")
    void federatedOriginTakesPrecedence() {
        assertEquals(UserTypeResolver.FEDERATED,
                UserTypeResolver.resolve("someone@lewisu.edu", "FEDERATED_GOOGLE", ALLOWLIST));
    }

    @Test
    @DisplayName("a null origin (password registration) falls through to the domain check")
    void nullOriginFallsThroughToDomainCheck() {
        assertEquals(UserTypeResolver.INTERNAL,
                UserTypeResolver.resolve("student@lewisu.edu", null, ALLOWLIST));
    }

    @Test
    @DisplayName("a matching domain reads INTERNAL")
    void matchingDomainIsInternal() {
        assertEquals(UserTypeResolver.INTERNAL,
                UserTypeResolver.resolve("bob@tesseraapp.dev", null, ALLOWLIST));
    }

    @Test
    @DisplayName("a non-matching domain reads EXTERNAL")
    void nonMatchingDomainIsExternal() {
        assertEquals(UserTypeResolver.EXTERNAL,
                UserTypeResolver.resolve("someone@gmail.com", null, ALLOWLIST));
    }

    @Test
    @DisplayName("a blank allowlist means nothing qualifies as INTERNAL — the safe default")
    void blankAllowlistMeansEverythingIsExternal() {
        assertEquals(UserTypeResolver.EXTERNAL,
                UserTypeResolver.resolve("student@lewisu.edu", null, ""));
        assertEquals(UserTypeResolver.EXTERNAL,
                UserTypeResolver.resolve("student@lewisu.edu", null, null));
    }

    @Test
    @DisplayName("the domain match is case-insensitive on both sides")
    void domainMatchIsCaseInsensitive() {
        assertEquals(UserTypeResolver.INTERNAL,
                UserTypeResolver.resolve("Bob@LewisU.EDU", null, "lewisu.edu"));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is not internal against \"{1}\"")
    @CsvSource({
            "no-at-sign, lewisu.edu",
            "'', lewisu.edu",
            "trailing-at@, lewisu.edu",
    })
    void malformedOrEmptyEmailIsNeverInternal(String email, String allowlist) {
        assertFalse(UserTypeResolver.isInternalDomain(email, allowlist));
    }

    @Test
    @DisplayName("a domain that is merely a substring of an allowed one does not match")
    void substringDomainDoesNotMatch() {
        // 'notlewisu.edu' contains 'lewisu.edu' as a suffix but is a different domain entirely.
        assertFalse(UserTypeResolver.isInternalDomain("someone@notlewisu.edu", "lewisu.edu"));
        assertTrue(UserTypeResolver.isInternalDomain("someone@lewisu.edu", "lewisu.edu"));
    }
}
