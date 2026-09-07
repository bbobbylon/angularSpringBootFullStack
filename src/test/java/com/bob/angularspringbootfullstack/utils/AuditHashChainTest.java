package com.bob.angularspringbootfullstack.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies {@link AuditHashChain}'s digest is deterministic and sensitive to every input it is
 * supposed to bind — the properties {@code EventRepoImpl}, {@code OrganizationServiceImpl}, and
 * {@code AuditIntegrityServiceImpl} all depend on to write and re-verify the audit-trail hash chain
 * consistently (FUTURE-ENHANCEMENTS §3.1).
 */
class AuditHashChainTest {

    @Test
    @DisplayName("the same inputs always produce the same hash")
    void deterministic() {
        String first = AuditHashChain.computeHash("prev", "a", 1L, "b");
        String second = AuditHashChain.computeHash("prev", "a", 1L, "b");

        assertEquals(first, second);
    }

    @Test
    @DisplayName("changing the previous hash changes the result")
    void sensitiveToPreviousHash() {
        String withOnePrevious = AuditHashChain.computeHash("prev-a", "field");
        String withAnotherPrevious = AuditHashChain.computeHash("prev-b", "field");

        assertNotEquals(withOnePrevious, withAnotherPrevious);
    }

    @Test
    @DisplayName("changing any own field changes the result")
    void sensitiveToOwnFields() {
        String original = AuditHashChain.computeHash("prev", "device-a", "1.2.3.4");
        String changedField = AuditHashChain.computeHash("prev", "device-b", "1.2.3.4");

        assertNotEquals(original, changedField);
    }

    @Test
    @DisplayName("a null own field hashes distinctly from an empty-string field, not identically")
    void nullFieldIsNotConfusedWithAdjacentContent() {
        // A naive "join with a separator, drop nulls" implementation could make
        // computeHash(prev, "a", null, "b") collide with computeHash(prev, "a", "b") — both would
        // serialize to "a||b" if a null field were skipped instead of contributing its own segment.
        String withNullMiddleField = AuditHashChain.computeHash("prev", "a", null, "b");
        String withoutMiddleField = AuditHashChain.computeHash("prev", "a", "b");

        assertNotEquals(withNullMiddleField, withoutMiddleField);
    }

    @Test
    @DisplayName("a null previous hash produces a different result than the literal string \"null\"")
    void nullPreviousHashIsNotConfusedWithLiteralNull() {
        String withNullPrevious = AuditHashChain.computeHash(null, "field");
        String withLiteralNullPrevious = AuditHashChain.computeHash("null", "field");

        assertNotEquals(withNullPrevious, withLiteralNullPrevious);
    }

    @Test
    @DisplayName("the digest is a 64-character lowercase hex string (SHA-256)")
    void producesLowercaseHexSha256() {
        String hash = AuditHashChain.computeHash(null, "anything");

        assertEquals(64, hash.length());
        assertEquals(hash, hash.toLowerCase());
        assertEquals(hash, hash.replaceAll("[^0-9a-f]", ""));
    }
}
