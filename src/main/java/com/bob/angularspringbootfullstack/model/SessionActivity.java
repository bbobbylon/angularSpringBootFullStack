package com.bob.angularspringbootfullstack.model;

/**
 * Live refresh-session totals for the security dashboard (SRS FR-TPF-2).
 *
 * <p>Both figures are counted in one query so their ratio can be trusted. That ratio is the whole
 * point of reporting them together: eighty sessions across seventy-five accounts is normal
 * multi-device use, while eighty across four is either a load test or something worth
 * investigating, and neither number alone can tell those apart.
 *
 * <p>"Live" means exactly what {@code SessionService} means by it — not revoked, not superseded by
 * rotation, not expired. Counting by any looser definition would report devices as signed in that
 * could not in fact refresh.
 *
 * @param activeSessions       live refresh sessions in scope
 * @param accountsWithSessions distinct accounts holding at least one of them
 */
public record SessionActivity(long activeSessions, long accountsWithSessions) {
}
