package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.DailyEventCount;
import com.bob.angularspringbootfullstack.model.LoginOutcomeTrendPoint;
import com.bob.angularspringbootfullstack.model.MfaAdoption;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.model.SessionActivity;
import com.bob.angularspringbootfullstack.repo.SecurityDashboardRepo;
import com.bob.angularspringbootfullstack.service.serviceimpl.SecurityDashboardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.bob.angularspringbootfullstack.service.serviceimpl.SecurityDashboardServiceImpl.DEFAULT_LIST_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural specs for {@link SecurityDashboardServiceImpl} — the assembly layer of the
 * administrative security dashboard (SRS FR-TPF-2).
 *
 * <p>The repository is mocked throughout, which is the point: everything worth testing here is a
 * <em>policy</em> decision the SQL deliberately does not make — how far back to look, what an
 * empty organization scope means, how sparse per-day counts become a dense trend, and whether a
 * type that never occurred is reported as zero or omitted. Each of those has a plausible-looking
 * wrong answer, and three of them fail silently:
 *
 * <ul>
 *   <li>An unclamped window turns {@code ?days=100000} into a full scan — a denial of service that
 *       needs no vulnerability, only a large number.</li>
 *   <li>Treating an empty scope as "unscoped" hands the platform-wide security picture to the
 *       account with the least established membership. This is the one genuine access-control
 *       decision in the class, so it is asserted twice: that zeros come back, and that the
 *       repository is never reached at all.</li>
 *   <li>Skipping days with no activity compresses the time axis, so a quiet weekend followed by a
 *       Monday burst renders as a gentle slope — the chart misleads exactly when it matters.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecurityDashboardServiceImplTest {

    private static final List<Long> ORG_IDS = List.of(1L, 4L);

    @Mock
    private SecurityDashboardRepo securityDashboardRepo;

    @InjectMocks
    private SecurityDashboardServiceImpl service;

    /**
     * Neutral, empty answers for every panel. Individual tests override only the one they are
     * about, so each test reads as a statement about a single behaviour.
     */
    @BeforeEach
    void stubEmptyPanels() {
        when(securityDashboardRepo.countSecurityEvents(any(), any())).thenReturn(Map.of());
        when(securityDashboardRepo.findRecentSuspiciousLogins(any(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(securityDashboardRepo.findDailyLoginOutcomes(any(), any())).thenReturn(List.of());
        when(securityDashboardRepo.findRestrictedAccounts(anyInt(), anyInt(), any())).thenReturn(List.of());
        when(securityDashboardRepo.findMfaAdoption(any())).thenReturn(new MfaAdoption(0, 0, 0, 0));
        when(securityDashboardRepo.findSessionActivity(any())).thenReturn(new SessionActivity(0, 0));
    }

    @Test
    @DisplayName("an organization admin with no active memberships sees nothing, not everything")
    void emptyScopeYieldsEmptyOverviewWithoutQuerying() {
        SecurityOverview overview = overviewOf(List.of(), 7);

        assertTrue(overview.scoped(), "the response must still declare itself scoped");
        assertTrue(overview.suspiciousLogins().isEmpty());
        assertTrue(overview.restrictedAccounts().isEmpty());
        assertEquals(0, overview.activeSessions());
        assertEquals(0, overview.mfaAdoption().totalUsers());
        // Asserting zeros alone would still pass if the unscoped query had run and simply returned
        // nothing. The decision must be made before any query, so that it cannot depend on data.
        verify(securityDashboardRepo, never()).countSecurityEvents(any(), any());
        verify(securityDashboardRepo, never()).findRecentSuspiciousLogins(any(), anyInt(), anyInt(), any());
        verify(securityDashboardRepo, never()).findDailyLoginOutcomes(any(), any());
        verify(securityDashboardRepo, never()).findRestrictedAccounts(anyInt(), anyInt(), any());
        verify(securityDashboardRepo, never()).findMfaAdoption(any());
        verify(securityDashboardRepo, never()).findSessionActivity(any());
    }

    @Test
    @DisplayName("an unscoped administrator passes a null scope through to every panel")
    void unscopedCallerQueriesEverything() {
        overviewOf(null, 7);

        verify(securityDashboardRepo).countSecurityEvents(any(), isNull());
        verify(securityDashboardRepo).findRecentSuspiciousLogins(any(), anyInt(), anyInt(), isNull());
        verify(securityDashboardRepo).findMfaAdoption(isNull());
        verify(securityDashboardRepo).findSessionActivity(isNull());
    }

    @Test
    @DisplayName("a scoped administrator's organization ids reach every panel, so none can leak across tenants")
    void scopedCallerPassesOrganizationIdsToEveryPanel() {
        overviewOf(ORG_IDS, 7);

        // Every panel, not just the obvious ones: a single unscoped query would leak a different
        // organization's security posture just as effectively as six would.
        verify(securityDashboardRepo).countSecurityEvents(any(), eqScope());
        verify(securityDashboardRepo).findRecentSuspiciousLogins(any(), anyInt(), anyInt(), eqScope());
        verify(securityDashboardRepo).findDailyLoginOutcomes(any(), eqScope());
        verify(securityDashboardRepo).findRestrictedAccounts(anyInt(), anyInt(), eqScope());
        verify(securityDashboardRepo).findMfaAdoption(eqScope());
        verify(securityDashboardRepo).findSessionActivity(eqScope());
        verify(securityDashboardRepo, never()).findMfaAdoption(isNull());
    }

    @Test
    @DisplayName("an absurd window is clamped rather than executed")
    void windowIsClampedToTheMaximum() {
        SecurityOverview overview = overviewOf(null, 100_000);

        assertEquals(90, overview.windowDays());
        assertEquals(90, overview.trend().size(), "the trend must cover exactly the clamped window");
    }

    @Test
    @DisplayName("a zero or negative window is clamped up to one day rather than producing an empty chart")
    void windowIsClampedToTheMinimum() {
        SecurityOverview overview = overviewOf(null, 0);

        assertEquals(1, overview.windowDays());
        assertEquals(1, overview.trend().size());
        assertEquals(LocalDate.now(), overview.trend().getFirst().day());
    }

    @Test
    @DisplayName("an absurd page size is clamped rather than executed, and the response admits it")
    void listSizeIsClampedToTheMaximum() {
        SecurityOverview overview = service.getOverview(null, 7, 0, 5_000, 0, 5_000);

        // Bounding the query itself, not merely the rows rendered: an unclamped size makes
        // ?suspiciousSize=5000000 a full read of the audit table for anyone who can reach the
        // endpoint — the same denial of service the window clamp exists to prevent.
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(securityDashboardRepo).findRecentSuspiciousLogins(any(), anyInt(), size.capture(), isNull());
        assertEquals(100, size.getValue());

        // The metadata must report the size actually used. If it echoed the requested 5000 instead,
        // the pager would divide the total by a size the server never applied and offer pages that
        // do not exist.
        assertEquals(100, overview.suspiciousLoginsPage().size());
        assertEquals(100, overview.restrictedAccountsPage().size());
    }

    @Test
    @DisplayName("a zero or negative page size is clamped up to one rather than returning nothing at all")
    void listSizeIsClampedToTheMinimum() {
        SecurityOverview overview = service.getOverview(null, 7, 0, 0, 0, -3);

        assertEquals(1, overview.suspiciousLoginsPage().size());
        assertEquals(1, overview.restrictedAccountsPage().size());
    }

    @Test
    @DisplayName("the two tables size independently, so resizing one does not disturb the other")
    void eachTableCarriesItsOwnPageSize() {
        SecurityOverview overview = service.getOverview(null, 7, 0, 10, 2, 50);

        ArgumentCaptor<Integer> suspiciousSize = ArgumentCaptor.forClass(Integer.class);
        verify(securityDashboardRepo).findRecentSuspiciousLogins(any(), anyInt(), suspiciousSize.capture(), isNull());
        assertEquals(10, suspiciousSize.getValue());

        ArgumentCaptor<Integer> restrictedPage = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> restrictedSize = ArgumentCaptor.forClass(Integer.class);
        verify(securityDashboardRepo).findRestrictedAccounts(restrictedPage.capture(), restrictedSize.capture(), isNull());
        assertEquals(2, restrictedPage.getValue(), "the restricted table's own page index must survive");
        assertEquals(50, restrictedSize.getValue());

        // An admin working down a lockout list in tens must not have it resized because they widened
        // the flagged-sign-ins table above it.
        assertEquals(10, overview.suspiciousLoginsPage().size());
        assertEquals(50, overview.restrictedAccountsPage().size());
    }

    @Test
    @DisplayName("the window start is anchored to midnight, so the oldest bar is a whole day")
    void windowStartsAtMidnightOfTheFirstDay() {
        overviewOf(null, 7);

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(securityDashboardRepo).countSecurityEvents(since.capture(), isNull());

        // "now minus 7×24h" would make the first bar a partial day, which reads as a dip that
        // never happened.
        assertEquals(LocalDate.now().minusDays(6).atStartOfDay(), since.getValue());
    }

    @Test
    @DisplayName("every tracked event type is reported, at zero when it never happened")
    void countersAreZeroFilledSoAbsenceIsStatedNotImplied() {
        when(securityDashboardRepo.countSecurityEvents(any(), any()))
                .thenReturn(Map.of("LOGIN_ATTEMPT_FAILURE", 12L));

        Map<String, Long> counts = overviewOf(null, 7).eventCounts();

        assertEquals(12L, counts.get("LOGIN_ATTEMPT_FAILURE"));
        // "0 suspicious logins" reassures; a missing tile is an unanswered question.
        assertEquals(0L, counts.get("SUSPICIOUS_LOGIN"));
        assertEquals(0L, counts.get("TOKEN_REUSE_DETECTED"));
        assertTrue(counts.containsKey("RECOVERY_CODE_USED"));
    }

    @Test
    @DisplayName("quiet days appear in the trend as zeros rather than vanishing from the axis")
    void trendIsGapFilledAcrossTheWholeWindow() {
        LocalDate today = LocalDate.now();
        when(securityDashboardRepo.findDailyLoginOutcomes(any(), any())).thenReturn(List.of(
                new DailyEventCount(today, "LOGIN_ATTEMPT_SUCCESS", 9L),
                new DailyEventCount(today, "LOGIN_ATTEMPT_FAILURE", 4L),
                new DailyEventCount(today, "SUSPICIOUS_LOGIN", 1L)));

        List<LoginOutcomeTrendPoint> trend = overviewOf(null, 5).trend();

        assertEquals(5, trend.size(), "one point per day, including the four with no events");
        assertEquals(today.minusDays(4), trend.getFirst().day(), "oldest first");
        assertEquals(0, trend.getFirst().successful());
        assertEquals(0, trend.getFirst().failed());

        LoginOutcomeTrendPoint latest = trend.getLast();
        assertEquals(today, latest.day());
        assertEquals(9, latest.successful());
        assertEquals(4, latest.failed());
        assertEquals(1, latest.suspicious());
    }

    @Test
    @DisplayName("a row with a null day is skipped instead of crashing the whole dashboard")
    void malformedTrendRowIsIgnored() {
        when(securityDashboardRepo.findDailyLoginOutcomes(any(), any())).thenReturn(List.of(
                new DailyEventCount(null, "LOGIN_ATTEMPT_FAILURE", 3L)));

        List<LoginOutcomeTrendPoint> trend = overviewOf(null, 3).trend();

        // Six other panels are riding on this call. One unusable row must cost one data point,
        // not the screen.
        assertEquals(3, trend.size());
        assertTrue(trend.stream().allMatch(point -> point.failed() == 0));
    }

    @Test
    @DisplayName("MFA coverage is reported as a percentage that cannot exceed 100 or divide by zero")
    void mfaCoverageIsSafeAtBothExtremes() {
        assertEquals(0.0, new MfaAdoption(0, 0, 0, 0).mfaCoveragePercent(),
                "an empty population must render zeros, not fail");
        assertEquals(75.0, new MfaAdoption(4, 2, 1, 1).mfaCoveragePercent());
        assertEquals(100.0, new MfaAdoption(2, 2, 0, 0).mfaCoveragePercent());
    }

    @Test
    @DisplayName("the response declares whether it is scoped, so the UI cannot present a slice as the whole")
    void overviewDeclaresItsScope() {
        assertFalse(overviewOf(null, 7).scoped());
        assertTrue(overviewOf(ORG_IDS, 7).scoped());
    }

    /**
     * Requests an overview at the default paging, which is what almost every test here wants.
     *
     * <p>Both tables' page and size are supplied explicitly rather than defaulted by the service,
     * because the service has no defaults — the controller owns them. Routing the common case
     * through one helper keeps the paging arguments from crowding out the window argument, which is
     * the one most of these tests are actually about.
     *
     * @param scope      the caller's organization restriction, or {@code null} for unscoped
     * @param windowDays the requested window, deliberately unclamped so clamping tests can pass junk
     * @return the assembled overview
     */
    private SecurityOverview overviewOf(Collection<Long> scope, int windowDays) {
        return service.getOverview(scope, windowDays, 0, DEFAULT_LIST_SIZE, 0, DEFAULT_LIST_SIZE);
    }

    /** Matcher for "the caller's organization ids", kept in one place for readability. */
    private static Collection<Long> eqScope() {
        return org.mockito.ArgumentMatchers.eq(ORG_IDS);
    }
}
