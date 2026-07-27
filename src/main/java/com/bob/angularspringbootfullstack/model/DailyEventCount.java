package com.bob.angularspringbootfullstack.model;

import java.time.LocalDate;

/**
 * A raw {@code (day, event type, count)} tuple straight out of the trend query — the long-format
 * intermediate the service pivots into {@link LoginOutcomeTrendPoint}s (SRS FR-TPF-2).
 *
 * <p>Kept as its own type rather than mapped directly into the wide per-day shape because the
 * database returns one row per {@code (day, type)} pair and cannot return a row for a combination
 * that produced no events. Pivoting and gap-filling are therefore transformations the SQL cannot
 * perform, and doing them in the service keeps the query free of a hard-coded column per tracked
 * event type — adding a fourth series becomes a change in one place instead of three.
 *
 * <p>This type is internal to the dashboard's assembly and never reaches the client.
 *
 * @param day       the calendar day
 * @param eventType the {@code EventType} name as stored in the {@code events} reference table
 * @param total     how many such events were recorded that day
 */
public record DailyEventCount(LocalDate day, String eventType, long total) {
}
