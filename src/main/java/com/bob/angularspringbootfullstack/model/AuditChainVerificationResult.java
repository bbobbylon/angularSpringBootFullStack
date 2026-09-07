package com.bob.angularspringbootfullstack.model;

/**
 * The outcome of walking one audit table's tamper-evidence hash chain end to end
 * (FUTURE-ENHANCEMENTS §3.1), returned by {@code AuditIntegrityService} and rendered by the
 * "Verify audit trail integrity" action on the security dashboard.
 *
 * <h3>Why {@code rowsChecked} is not just "every row in the table"</h3>
 * Rows written before this feature shipped carry a {@code NULL} hash and were deliberately never
 * backfilled — fabricating a hash for historical data would misrepresent when it was actually
 * attested to. Those legacy rows are skipped during verification rather than counted as either
 * passing or failing, so {@code rowsChecked} reports how many rows the chain actually covers, not
 * the table's total row count. A brand-new table (or one where the feature only just started
 * writing hashes) can legitimately report {@code intact = true, rowsChecked = 0} — that is a
 * "nothing to disprove yet" result, not a failure.
 *
 * @param intact       true if every chained row's stored hash matches its recomputed hash, or if
 *                      there were no chained rows at all to check
 * @param rowsChecked   how many rows carried a non-null hash and were actually verified
 * @param firstBrokenId the {@code id} of the first row (in ascending order) whose stored hash did
 *                      not match its recomputed value, or {@code null} when {@code intact} is true
 */
public record AuditChainVerificationResult(boolean intact, long rowsChecked, Long firstBrokenId) {

    /**
     * The result for a chain with nothing wrong (including a chain with zero rows to check).
     *
     * @param rowsChecked how many rows were verified
     * @return an intact result carrying no broken row id
     */
    public static AuditChainVerificationResult intact(long rowsChecked) {
        return new AuditChainVerificationResult(true, rowsChecked, null);
    }

    /**
     * The result for a chain where recomputation diverged from the stored hash partway through.
     *
     * @param rowsChecked   how many rows were verified before (and including) the break
     * @param firstBrokenId the {@code id} of the first row whose hash did not match
     * @return a broken result naming where the chain first diverges
     */
    public static AuditChainVerificationResult broken(long rowsChecked, Long firstBrokenId) {
        return new AuditChainVerificationResult(false, rowsChecked, firstBrokenId);
    }
}
