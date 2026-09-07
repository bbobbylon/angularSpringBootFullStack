package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.model.AuditChainVerificationResult;
import com.bob.angularspringbootfullstack.service.AuditIntegrityService;
import com.bob.angularspringbootfullstack.utils.AuditHashChain;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.bob.angularspringbootfullstack.query.EventQuery.SELECT_ALL_USEREVENTS_FOR_VERIFICATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ALL_ORGANIZATIONEVENTS_FOR_VERIFICATION_QUERY;

/**
 * JDBC-backed implementation of {@link AuditIntegrityService}, following the same
 * service-owns-its-SQL shape as {@link SecuritySettingsServiceImpl} — this is a read-only
 * recomputation over two existing tables, so a dedicated {@code Repo}/{@code RepoImpl} pair would
 * add a layer with nothing to abstract.
 *
 * <p>Each table is walked exactly once, in ascending {@code id} order, carrying a running
 * {@code previousHash} forward exactly as {@link EventRepoImpl#addUserEvent(String, com.bob.angularspringbootfullstack.enumeration.EventType, String, String, String)}
 * and {@link OrganizationServiceImpl#recordOrganizationEvent} computed it at write time — see
 * {@link AuditHashChain} for why both paths must stay byte-for-byte identical. A row whose stored
 * {@code hash} is {@code null} predates this feature and is skipped without disturbing
 * {@code previousHash}, so the chain picks back up correctly at the first row written after the
 * feature shipped.
 *
 * <h3>A known, currently-unreachable edge case</h3>
 * {@code organizationevents.actor_user_id} is declared {@code ON DELETE SET NULL} against
 * {@code users}, so if a user were ever hard-deleted, every organization-event row they acted on
 * would have this column silently nulled by MySQL — which would change what
 * {@link #verifyOrganizationEvents()} recomputes for that row and report it as broken, even though
 * nothing adversarial happened. This is not fixed here because nothing in the application actually
 * hard-deletes a user today (accounts are locked/disabled, mirroring organizations having no hard
 * delete); it is noted so a future "delete my account" feature does not reintroduce this as a
 * surprise.
 */
@Service
@RequiredArgsConstructor
public class AuditIntegrityServiceImpl implements AuditIntegrityService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     */
    @Override
    public AuditChainVerificationResult verifyUserEvents() {
        List<ChainedRow> rows = jdbcTemplate.query(SELECT_ALL_USEREVENTS_FOR_VERIFICATION_QUERY, (rs, rowNum) ->
                new ChainedRow(rs.getLong("id"), rs.getString("hash"), new Object[]{
                        rs.getLong("user_id"),
                        rs.getString("type"),
                        rs.getString("device"),
                        rs.getString("ip_address"),
                        rs.getString("detail"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                }));
        return verifyChain(rows);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AuditChainVerificationResult verifyOrganizationEvents() {
        List<ChainedRow> rows = jdbcTemplate.query(SELECT_ALL_ORGANIZATIONEVENTS_FOR_VERIFICATION_QUERY, (rs, rowNum) -> {
            long actorUserId = rs.getLong("actor_user_id");
            boolean actorUserIdIsNull = rs.wasNull();
            return new ChainedRow(rs.getLong("id"), rs.getString("hash"), new Object[]{
                    rs.getLong("organization_id"),
                    actorUserIdIsNull ? null : actorUserId,
                    rs.getString("type"),
                    rs.getString("detail"),
                    rs.getTimestamp("created_at").toLocalDateTime()
            });
        });
        return verifyChain(rows);
    }

    /**
     * Recomputes each chained row's hash from a running {@code previousHash} and compares it to
     * what was stored, stopping at (and reporting) the first mismatch.
     *
     * @param rows every row of one table, in ascending {@code id} order
     * @return the verification outcome for that table
     */
    private AuditChainVerificationResult verifyChain(List<ChainedRow> rows) {
        String previousHash = null;
        long checked = 0;
        for (ChainedRow row : rows) {
            if (row.hash() == null) {
                continue;
            }
            checked++;
            String expected = AuditHashChain.computeHash(previousHash, row.fields());
            if (!expected.equals(row.hash())) {
                return AuditChainVerificationResult.broken(checked, row.id());
            }
            previousHash = row.hash();
        }
        return AuditChainVerificationResult.intact(checked);
    }

    /**
     * One row's chain-relevant data: its own {@code id} (for reporting where a break occurs), its
     * stored {@code hash} (or {@code null} for a legacy pre-feature row), and its own fields in the
     * exact order {@link AuditHashChain#computeHash} expects them.
     *
     * <p>Package-private rather than {@code private} so {@code AuditIntegrityServiceImplTest} can
     * construct rows directly instead of mocking a JDBC {@code ResultSet} to exercise the chain-
     * walking logic in {@link #verifyChain}, which is what these tests actually target.
     */
    record ChainedRow(long id, String hash, Object[] fields) {
    }
}
