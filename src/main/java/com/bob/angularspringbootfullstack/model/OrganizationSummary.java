package com.bob.angularspringbootfullstack.model;

/**
 * An organization's id and name — the minimal projection {@code ReportDigestServiceImpl} needs
 * to iterate every organization when assembling the scheduled report digest
 * (POST-SUBMISSION-UPGRADES.md "Scheduled/on-demand report emails").
 *
 * <p>Deliberately not the full {@code Organization} row: nothing that walks this list needs more
 * than the id (to resolve org-scoped stats and recipients) and the name (to label the digest).
 * Mirrors {@link DailyEventCount}'s role as an internal, read-only projection type that never
 * reaches the client.
 *
 * @param id   the organization's primary key
 * @param name the organization's display name
 */
public record OrganizationSummary(Long id, String name) {
}
