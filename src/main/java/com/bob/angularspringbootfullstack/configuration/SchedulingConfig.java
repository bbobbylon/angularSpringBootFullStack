package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.model.OrganizationSummary;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.ReportDigestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Wires up the weekly report digest cron job (POST-SUBMISSION-UPGRADES.md "Scheduled/on-demand
 * report emails").
 *
 * <p>{@code @EnableScheduling} is declared here rather than on the application class because
 * nothing else in this codebase runs on a timer — every other background dispatch
 * ({@code NotificationServiceImpl}) is a fire-and-forget {@link java.util.concurrent.CompletableFuture},
 * not a cron trigger, so this is genuinely new infrastructure and keeping its enablement next to
 * its only consumer avoids a stray annotation on {@code AngularSpringBootFullStackApplication}
 * that nothing nearby explains.
 *
 * <p>This class owns <em>only</em> the schedule and the iteration over organizations; it holds no
 * report-building logic of its own. Building one digest for one resolved scope is
 * {@link ReportDigestService}'s job — the same interface {@code AnalyticsController#emailReport}
 * calls for the manual "Email me this report" button — so the scheduled and manual routes are
 * guaranteed to produce identical digests rather than risking two implementations drifting apart.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class SchedulingConfig {

    private final OrganizationService organizationService;
    private final ReportDigestService reportDigestService;

    /**
     * Comma-separated extra addresses to CC on the system-wide digest (env
     * {@code REPORT_ADMIN_EXTRA_RECIPIENTS}) — see {@code application.yml}'s
     * {@code report.digest.admin-extra-recipients} for the default and rationale. Blank/unset
     * means no extra recipients, mirroring {@code UserTypeResolver}'s
     * {@code internal-domains} CSV-or-blank convention.
     */
    @Value("${report.digest.admin-extra-recipients:}")
    private String adminExtraRecipientsCsv;

    /**
     * Sends one full round of report digests: every active organization's
     * {@code ROLE_ORGANIZATION_ADMIN}s receive their organization's digest, then every system
     * administrator (plus any configured extra recipients) receives the system-wide digest.
     *
     * <p>Runs on {@code report.digest.cron} (default Monday 06:00 server time — see
     * {@code application.yml}). Iterates every organization unconditionally rather than only
     * those with pending changes: a digest is a periodic snapshot, not a change notification, so
     * "nothing changed this week" is itself a legitimate thing to report.
     *
     * <p>Per-recipient delivery failures are caught and logged inside
     * {@link ReportDigestService}, not here — one bad address must not stop the remaining
     * organizations, or the system-wide digest that follows, from being sent. This method itself
     * therefore has nothing to catch; a failure that does escape (e.g. the database being
     * unreachable) is left to Spring's default {@code @Scheduled} error logging, since at that
     * point no further progress in this run is possible anyway.
     */
    @Scheduled(cron = "${report.digest.cron:0 0 6 * * MON}")
    public void sendScheduledReportDigests() {
        Collection<OrganizationSummary> organizations = organizationService.findActiveOrganizations();
        log.info("Starting scheduled report digest run for {} active organization(s).", organizations.size());
        organizations.forEach(reportDigestService::sendOrganizationDigest);
        reportDigestService.sendSystemWideDigest(parseExtraRecipients());
        log.info("Scheduled report digest run complete.");
    }

    /**
     * Splits {@link #adminExtraRecipientsCsv} the same way {@code SecurityConfig} splits its
     * allowed-origin-patterns CSV: trimmed, blank entries dropped, blank/unset input yielding an
     * empty (not null) list so {@link ReportDigestService#sendSystemWideDigest} never has to
     * null-check it.
     *
     * @return the configured extra recipients, possibly empty, never {@code null}
     */
    private List<String> parseExtraRecipients() {
        return Arrays.stream(adminExtraRecipientsCsv.split(","))
                .map(String::trim)
                .filter(recipient -> !recipient.isEmpty())
                .toList();
    }
}
