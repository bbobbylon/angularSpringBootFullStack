package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.OrganizationSummary;

import java.util.Collection;

/**
 * Assembles and dispatches the report digest — a business + security snapshot emailed to
 * administrators (POST-SUBMISSION-UPGRADES.md "Scheduled/on-demand report emails").
 *
 * <p>This service owns exactly one thing: given a resolved scope, build the {@code Stats} +
 * {@code SecurityOverview} pair for it and hand it to {@link EmailService#sendReportDigestEmail}.
 * It deliberately does <em>not</em> own who receives a digest or when — that is two separate
 * decisions made by its two callers:
 * <ul>
 *   <li>{@code AnalyticsController#emailReport} resolves the <em>caller's own</em> scope via its
 *       existing {@code resolveScope()} and asks for exactly one digest, sent to the caller — the
 *       manual "Email me this report" button, answered synchronously so a click either confirms
 *       delivery or surfaces the failure, matching {@code CustomerController#emailInvoice}.</li>
 *   <li>{@code SchedulingConfig}'s weekly cron job asks for one digest per active organization
 *       (sent to that organization's {@code ROLE_ORGANIZATION_ADMIN}s) plus one system-wide digest
 *       (sent to {@code ROLE_ADMIN}/{@code ROLE_APPLICATION_ADMIN} and any configured extra
 *       recipients), and owns the iteration itself so a failure emailing one organization cannot
 *       prevent the rest from being notified.</li>
 * </ul>
 * Both routes build the digest through the exact same code — there is no second, scheduled-only
 * implementation to drift from the manual one.
 */
public interface ReportDigestService {

    /**
     * Builds the digest for the caller's own resolved scope and emails it to the caller — the
     * manual "Email me this report" action.
     *
     * @param caller the authenticated administrator triggering the send; also the recipient
     * @param scope  the caller's resolved scope, exactly as returned by
     *               {@code AnalyticsController#resolveScope}: {@code null} for an unscoped
     *               administrator (system-wide figures), or their active organization ids
     *               (possibly empty, which renders as zeros rather than falling back system-wide)
     */
    void emailReportForCaller(UserDTO caller, Collection<Long> scope);

    /**
     * Builds the org-scoped digest for one organization and emails it to every one of that
     * organization's {@code ROLE_ORGANIZATION_ADMIN} recipients.
     *
     * <p>A organization with no such administrator is skipped without error — there is no
     * recipient to fail to reach, and an empty membership is a normal, unremarkable state for a
     * newly created organization.
     *
     * @param organization the organization to report on
     */
    void sendOrganizationDigest(OrganizationSummary organization);

    /**
     * Builds the system-wide digest and emails it to every system administrator
     * ({@code ROLE_ADMIN}/{@code ROLE_APPLICATION_ADMIN}) plus the given extra recipients.
     *
     * @param extraRecipients additional addresses to notify, resolved by the caller from
     *                        {@code REPORT_ADMIN_EXTRA_RECIPIENTS}; may be empty
     */
    void sendSystemWideDigest(Collection<String> extraRecipients);
}
