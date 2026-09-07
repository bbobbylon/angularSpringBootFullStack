package com.bob.angularspringbootfullstack.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the SHA-256 hash-chain digest backing the audit-trail tamper-evidence feature
 * (FUTURE-ENHANCEMENTS §3.1, "Audit trail has no tamper-evidence mechanism").
 *
 * <p>Shared by {@code EventRepoImpl} (the {@code userevents} chain) and
 * {@code OrganizationServiceImpl} (the {@code organizationevents} chain) so both tables use
 * <em>exactly</em> the same digest construction — a write path and
 * {@code AuditIntegrityServiceImpl}'s verification path computing the hash even slightly
 * differently (field order, null handling, separator choice) would make every row look tampered
 * the moment it was written.
 *
 * <p>Each row's hash covers its own fields plus the hash of the row immediately before it in the
 * same table, so altering or deleting any one row invalidates every hash computed after it —
 * "tamper-evident", not tamper-proof: a privileged attacker who rewrites the entire remainder of
 * the chain after their edit can still make it look consistent. That is the deliberately
 * "lightweight" scope this backlog item asked for, not an append-only external ledger.
 *
 * <p>{@code null} previous-hash means "this is the first row in the chain" — either genuinely the
 * first row ever written to the table, or the first one written after this feature shipped (every
 * row before that predates the chain and carries a {@code NULL} hash column, never retroactively
 * backfilled — see the {@code schema.sql} comment on the {@code hash} columns for why).
 */
public final class AuditHashChain {

    private AuditHashChain() {
    }

    /**
     * Computes the chained hash for one audit row.
     *
     * @param previousHash the hash stored on the immediately preceding row in the same table, or
     *                     {@code null} to start a new chain
     * @param ownFields    the row's own fields, in a fixed order the caller must keep identical
     *                     between writing and verifying; a {@code null} field hashes as an empty
     *                     string
     * @return a 64-character lowercase hex-encoded SHA-256 digest
     */
    public static String computeHash(String previousHash, Object... ownFields) {
        StringBuilder content = new StringBuilder();
        for (Object field : ownFields) {
            content.append(field == null ? "" : field.toString()).append('|');
        }
        content.append(previousHash == null ? "" : previousHash);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory for every JDK implementation (Java Cryptography Architecture
            // Standard Algorithm Name spec) — this can only happen on a broken JRE installation.
            throw new IllegalStateException("SHA-256 MessageDigest is unavailable", e);
        }
    }
}
