package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.OrganizationSummary;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.model.Stats;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.EmailService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.ReportDigestService;
import com.bob.angularspringbootfullstack.service.SecurityDashboardService;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

import static com.bob.angularspringbootfullstack.service.serviceimpl.SecurityDashboardServiceImpl.DEFAULT_LIST_SIZE;
import static com.bob.angularspringbootfullstack.service.serviceimpl.SecurityDashboardServiceImpl.DEFAULT_WINDOW_DAYS;

/**
 * Default {@link ReportDigestService}. See that interface's Javadoc for the split of
 * responsibility between this class (build one digest for one resolved scope) and its two
 * callers (decide who receives one and when).
 *
 * <p>Every method here follows the same three-way branch every other org-scoped reporting path in
 * this codebase uses ({@code AnalyticsController#resolveScope} / {@code SecurityDashboardServiceImpl#getOverview}):
 * a {@code null} scope means system-wide, a non-empty scope restricts to those organizations, and
 * an explicitly empty scope means the caller belongs to no active organization and must see
 * (and here, receive) zeros rather than the system-wide view.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportDigestServiceImpl implements ReportDigestService {

    private final CustomerService customerService;
    private final SecurityDashboardService securityDashboardService;
    private final OrganizationService organizationService;
    private final UserService userService;
    private final EmailService emailService;

    /**
     * {@inheritDoc}
     *
     * <p>Sent synchronously, like {@code CustomerController#emailInvoice}: a manual button click is
     * exactly the case where the caller needs to know whether the send actually succeeded, so any
     * {@link org.springframework.mail.MailException} from {@link EmailService} propagates to the
     * controller rather than being swallowed here.
     */
    @Override
    public void emailReportForCaller(UserDTO caller, Collection<Long> scope) {
        String scopeLabel = scope == null ? "System-wide" : "Your organizations";
        Digest digest = buildDigest(scope);
        emailService.sendReportDigestEmail(caller.getEmail(), scopeLabel, digest.stats(), digest.overview());
        log.info("Manual report digest ({}) emailed to {}", scopeLabel, caller.getEmail());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Failures are caught and logged per recipient rather than propagated: this runs from
     * {@code SchedulingConfig}'s unattended cron job, where one bad address must not stop the
     * remaining organizations' administrators — or the system-wide digest that follows — from
     * being notified.
     */
    @Override
    public void sendOrganizationDigest(OrganizationSummary organization) {
        Collection<String> recipients = organizationService.findOrganizationAdminEmails(organization.id());
        if (recipients.isEmpty()) {
            log.debug("No organization administrators to notify for organization {} ({}); skipping its report digest.",
                    organization.id(), organization.name());
            return;
        }
        Digest digest = buildDigest(List.of(organization.id()));
        for (String recipient : recipients) {
            try {
                emailService.sendReportDigestEmail(recipient, organization.name(), digest.stats(), digest.overview());
            } catch (Exception exception) {
                log.error("Failed to send the {} report digest to organization administrator {}: {}",
                        organization.name(), recipient, exception.getMessage(), exception);
            }
        }
        log.info("Organization report digest ({}) emailed to {} administrator(s)", organization.name(), recipients.size());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Same per-recipient failure isolation as {@link #sendOrganizationDigest}, for the same
     * unattended-cron reason.
     */
    @Override
    public void sendSystemWideDigest(Collection<String> extraRecipients) {
        Collection<String> recipients = userService.findSystemAdminEmails();
        if (recipients.isEmpty() && extraRecipients.isEmpty()) {
            log.debug("No system administrators or configured extra recipients to notify; skipping the system-wide report digest.");
            return;
        }
        Digest digest = buildDigest(null);
        for (String recipient : recipients) {
            try {
                emailService.sendReportDigestEmail(recipient, "System-wide", digest.stats(), digest.overview());
            } catch (Exception exception) {
                log.error("Failed to send the system-wide report digest to administrator {}: {}",
                        recipient, exception.getMessage(), exception);
            }
        }
        for (String recipient : extraRecipients) {
            try {
                emailService.sendReportDigestEmail(recipient, "System-wide", digest.stats(), digest.overview());
            } catch (Exception exception) {
                log.error("Failed to send the system-wide report digest to configured extra recipient {}: {}",
                        recipient, exception.getMessage(), exception);
            }
        }
        log.info("System-wide report digest emailed to {} administrator(s) and {} extra recipient(s)",
                recipients.size(), extraRecipients.size());
    }

    /**
     * Resolves the {@code Stats} + {@code SecurityOverview} pair for one scope, applying the same
     * empty-scope-means-nothing rule every other org-scoped reporting path enforces (see this
     * class's Javadoc). Centralizing it here is what makes the manual and scheduled routes share
     * one code path rather than two independently-maintained copies of this branch.
     *
     * @param scope {@code null} for system-wide, a non-empty collection to restrict to those
     *              organizations, or an empty collection for "no active memberships — zeros"
     * @return the resolved business and security figures for that scope
     */
    private Digest buildDigest(Collection<Long> scope) {
        if (scope != null && scope.isEmpty()) {
            return new Digest(new Stats(), SecurityOverview.empty(DEFAULT_WINDOW_DAYS));
        }
        Stats stats = scope == null ? customerService.getStats() : customerService.getStatsForOrganizations(scope);
        SecurityOverview overview = securityDashboardService.getOverview(
                scope, DEFAULT_WINDOW_DAYS, 0, DEFAULT_LIST_SIZE, 0, DEFAULT_LIST_SIZE);
        return new Digest(stats, overview);
    }

    /**
     * The resolved figures for one scope, paired so {@link #buildDigest} has a single return value
     * instead of two out-parameters.
     */
    private record Digest(Stats stats, SecurityOverview overview) {
    }
}
