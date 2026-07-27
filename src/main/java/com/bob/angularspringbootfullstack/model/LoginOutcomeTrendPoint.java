package com.bob.angularspringbootfullstack.model;

import java.time.LocalDate;

/**
 * One day of the security dashboard's login-outcome trend (SRS FR-TPF-2).
 *
 * <p>The three series are carried together per day rather than as three independent series
 * because they are only meaningful in relation to one another. Forty failures is alarming against
 * fifty successes and unremarkable against four thousand; a chart of failures alone invites the
 * wrong conclusion in both directions.
 *
 * <p>Days with no activity are represented explicitly with zeros. The underlying {@code GROUP BY}
 * cannot emit a row for a day that produced none, and a chart that simply omits quiet days
 * compresses the time axis — a weekend of silence followed by a Monday burst renders as a steady
 * climb, which is precisely the misreading a trend chart exists to prevent.
 *
 * @param day        the calendar day these counts cover
 * @param successful completed sign-ins that day ({@code LOGIN_ATTEMPT_SUCCESS})
 * @param failed     rejected sign-ins that day ({@code LOGIN_ATTEMPT_FAILURE})
 * @param suspicious sign-ins that passed the first factor but were flagged and escalated by
 *                   FR-TPF-1 ({@code SUSPICIOUS_LOGIN}) — a subset of neither of the other two,
 *                   since a flagged attempt may go on to succeed or be abandoned
 */
public record LoginOutcomeTrendPoint(LocalDate day, long successful, long failed, long suspicious) {
}
