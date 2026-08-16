package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.OrganizationSummary;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.model.Stats;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.EmailService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.SecurityDashboardService;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ReportDigestServiceImpl} — plain Mockito, no Spring context, matching
 * {@code AnalyticsControllerOrgScopeTest}'s style for the same three-way scope branch this class
 * shares with that controller.
 *
 * <p>Asserts three things per method: the right {@link CustomerService}/{@link
 * SecurityDashboardService} variant is called for the given scope (never both the scoped and
 * unscoped one — that would be the org-leak bug {@code AnalyticsControllerOrgScopeTest} guards
 * against, reused here since this class makes the identical decision), the digest reaches every
 * intended recipient, and one recipient's delivery failure does not stop the others from being
 * attempted.
 */
@ExtendWith(MockitoExtension.class)
class ReportDigestServiceImplTest {

    private static final List<Long> ORG_IDS = List.of(3L);

    @Mock
    private CustomerService customerService;
    @Mock
    private SecurityDashboardService securityDashboardService;
    @Mock
    private OrganizationService organizationService;
    @Mock
    private UserService userService;
    @Mock
    private EmailService emailService;

    @InjectMocks
    private ReportDigestServiceImpl reportDigestService;

    private static UserDTO caller(String email) {
        UserDTO user = new UserDTO();
        user.setId(9L);
        user.setEmail(email);
        return user;
    }

    // ── emailReportForCaller (manual "Email me this report") ───────────────────────────────

    @Test
    @DisplayName("an unscoped caller (null scope) receives the system-wide digest")
    void emailReportForCaller_unscoped_sendsSystemWideFigures() {
        when(customerService.getStats()).thenReturn(new Stats());
        when(securityDashboardService.getOverview(isNull(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(SecurityOverview.empty(7));

        reportDigestService.emailReportForCaller(caller("admin@example.com"), null);

        verify(emailService).sendReportDigestEmail(eq("admin@example.com"), eq("System-wide"), any(), any());
        verify(customerService, never()).getStatsForOrganizations(any());
    }

    @Test
    @DisplayName("a scoped caller with active organizations receives their org-restricted figures")
    void emailReportForCaller_scoped_sendsOrgRestrictedFigures() {
        when(customerService.getStatsForOrganizations(ORG_IDS)).thenReturn(new Stats());
        when(securityDashboardService.getOverview(eq(ORG_IDS), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(SecurityOverview.empty(7));

        reportDigestService.emailReportForCaller(caller("orgadmin@example.com"), ORG_IDS);

        verify(emailService).sendReportDigestEmail(eq("orgadmin@example.com"), eq("Your organizations"), any(), any());
        verify(customerService, never()).getStats();
    }

    @Test
    @DisplayName("a scoped caller with NO active organizations gets zeros, not a system-wide fallback")
    void emailReportForCaller_emptyScope_sendsZeroesWithoutConsultingEitherService() {
        reportDigestService.emailReportForCaller(caller("lonelyadmin@example.com"), List.of());

        verify(emailService).sendReportDigestEmail(eq("lonelyadmin@example.com"), eq("Your organizations"), any(), any());
        verify(customerService, never()).getStats();
        verify(customerService, never()).getStatsForOrganizations(any());
        verify(securityDashboardService, never()).getOverview(any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    // ── sendOrganizationDigest (scheduled, per organization) ───────────────────────────────

    @Test
    @DisplayName("an organization with admins gets its digest emailed to every one of them")
    void sendOrganizationDigest_emailsEveryOrgAdmin() {
        OrganizationSummary org = new OrganizationSummary(3L, "Acme Org");
        when(organizationService.findOrganizationAdminEmails(3L)).thenReturn(List.of("a@acme.test", "b@acme.test"));
        when(customerService.getStatsForOrganizations(ORG_IDS)).thenReturn(new Stats());
        when(securityDashboardService.getOverview(eq(ORG_IDS), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(SecurityOverview.empty(7));

        reportDigestService.sendOrganizationDigest(org);

        verify(emailService).sendReportDigestEmail(eq("a@acme.test"), eq("Acme Org"), any(), any());
        verify(emailService).sendReportDigestEmail(eq("b@acme.test"), eq("Acme Org"), any(), any());
    }

    @Test
    @DisplayName("an organization with no admins is skipped without building or sending a digest")
    void sendOrganizationDigest_noAdmins_skipsEntirely() {
        OrganizationSummary org = new OrganizationSummary(3L, "Empty Org");
        when(organizationService.findOrganizationAdminEmails(3L)).thenReturn(List.of());

        reportDigestService.sendOrganizationDigest(org);

        verify(customerService, never()).getStatsForOrganizations(any());
        verify(emailService, never()).sendReportDigestEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("one org admin's delivery failure does not stop the digest reaching the rest")
    void sendOrganizationDigest_oneRecipientFails_othersStillReceiveIt() {
        OrganizationSummary org = new OrganizationSummary(3L, "Acme Org");
        when(organizationService.findOrganizationAdminEmails(3L)).thenReturn(List.of("bad@acme.test", "good@acme.test"));
        when(customerService.getStatsForOrganizations(ORG_IDS)).thenReturn(new Stats());
        when(securityDashboardService.getOverview(eq(ORG_IDS), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(SecurityOverview.empty(7));
        doThrow(new MailSendException("boom")).when(emailService)
                .sendReportDigestEmail(eq("bad@acme.test"), any(), any(), any());

        reportDigestService.sendOrganizationDigest(org);

        verify(emailService).sendReportDigestEmail(eq("good@acme.test"), eq("Acme Org"), any(), any());
    }

    // ── sendSystemWideDigest (scheduled, once per run) ──────────────────────────────────────

    @Test
    @DisplayName("system administrators and configured extra recipients both receive the system-wide digest")
    void sendSystemWideDigest_emailsAdminsAndExtraRecipients() {
        when(userService.findSystemAdminEmails()).thenReturn(List.of("root@example.com"));
        when(customerService.getStats()).thenReturn(new Stats());
        when(securityDashboardService.getOverview(isNull(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(SecurityOverview.empty(7));

        reportDigestService.sendSystemWideDigest(List.of("ops@example.com"));

        verify(emailService).sendReportDigestEmail(eq("root@example.com"), eq("System-wide"), any(), any());
        verify(emailService).sendReportDigestEmail(eq("ops@example.com"), eq("System-wide"), any(), any());
    }

    @Test
    @DisplayName("no system administrators and no extra recipients skips the send entirely")
    void sendSystemWideDigest_noRecipients_skipsEntirely() {
        when(userService.findSystemAdminEmails()).thenReturn(List.of());

        reportDigestService.sendSystemWideDigest(List.of());

        verify(customerService, never()).getStats();
        verify(emailService, never()).sendReportDigestEmail(any(), any(), any(), any());
    }
}
