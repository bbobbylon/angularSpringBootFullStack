package com.bob.angularspringbootfullstack.repo;

/**
 * Minimal read-only data-access contract onto the {@code refreshsessions} table, used only for
 * the access-token revocation check.
 *
 * <p>Deliberately separate from {@code SessionServiceImpl} (which owns the rest of the
 * refresh-session store but talks to {@code NamedParameterJdbcTemplate} directly rather than
 * through a repo, per its own class Javadoc) so that {@code TokenProvider} can depend on this one
 * query without creating a circular dependency: {@code SessionServiceImpl} already depends on
 * {@code TokenProvider} to mint token pairs.
 */
public interface SessionRepo {

    /**
     * Returns {@code true} if any row for the given session family has been revoked — via a
     * user-initiated revoke, "log out everywhere else", password-change "revoke all", or
     * server-initiated reuse detection. A family with no rows at all (unknown or legacy
     * pre-M5 token) returns {@code false}, matching the JWT's own {@code sid} claim being
     * optional.
     *
     * @param family the refresh-session family (the JWT's {@code sid} claim)
     * @return {@code true} if the family has been revoked
     */
    boolean isFamilyRevoked(String family);
}
