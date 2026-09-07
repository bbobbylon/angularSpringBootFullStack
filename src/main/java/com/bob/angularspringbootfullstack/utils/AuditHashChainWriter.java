package com.bob.angularspringbootfullstack.utils;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shared read-latest-hash-then-insert scaffolding for the tamper-evidence hash chains written by
 * {@code EventRepoImpl} (the {@code userevents} chain) and {@code OrganizationServiceImpl} (the
 * {@code organizationevents} chain) (FUTURE-ENHANCEMENTS §3.1).
 *
 * <p>Both chains need the exact same sequence — read the previous row's stored hash, compute this
 * row's chained hash via {@link AuditHashChain}, then insert — executed inside <em>one</em>
 * critical section. Splitting the read from the insert (e.g. reading the hash outside the lock)
 * would let two threads observe the same "previous hash" and each insert a row that claims to
 * extend the chain from it, forking the chain instead of extending it. Centralizing the sequence
 * here means that invariant only has to be gotten right in one place, rather than re-implemented
 * identically at every call site.
 */
public final class AuditHashChainWriter {

    private AuditHashChainWriter() {
    }

    /**
     * Resolves the previous hash for one table and inserts the next chained row, entirely inside
     * a critical section on {@code lock}.
     *
     * @param jdbcTemplate          used to read the latest stored hash
     * @param lock                  the calling table's own dedicated lock object — never share
     *                              one lock across two tables, or their unrelated chains would
     *                              serialize against each other for no reason
     * @param selectLatestHashQuery a {@code SELECT hash FROM <table> ORDER BY id DESC LIMIT 1} query
     * @param ownFields             this row's own fields, in the exact order the corresponding
     *                              {@code AuditIntegrityServiceImpl} verifier reads them back in
     * @param insert                writes the row using the hash this method computed; anything
     *                              this callback needs (e.g. a foreign key resolved from another
     *                              table) should already be resolved before calling this method,
     *                              not inside the callback, so the critical section stays as short
     *                              as the chain invariant actually requires
     */
    public static void writeChainedRow(NamedParameterJdbcTemplate jdbcTemplate, Object lock,
                                        String selectLatestHashQuery, Object[] ownFields,
                                        Consumer<String> insert) {
        synchronized (lock) {
            List<String> latest = jdbcTemplate.query(selectLatestHashQuery, Map.of(),
                    (rs, rowNum) -> rs.getString("hash"));
            String previousHash = latest.isEmpty() ? null : latest.get(0);
            String hash = AuditHashChain.computeHash(previousHash, ownFields);
            insert.accept(hash);
        }
    }
}
