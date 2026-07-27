package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.model.DailyEventCount;
import com.bob.angularspringbootfullstack.model.LoginOutcomeTrendPoint;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.model.SessionActivity;
import com.bob.angularspringbootfullstack.repo.SecurityDashboardRepo;
import com.bob.angularspringbootfullstack.service.SecurityDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the administrative security dashboard (SRS FR-TPF-2).
 *
 * <p>Read-only and transactional so all six panels observe one consistent snapshot of the
 * database. Without that, an administrator could see a suspicious sign-in listed in the activity
 * table while the counter above it still read zero — two panels describing different instants, and
 * no way to tell which one to believe.
 *
 * <h3>What this class decides</h3>
 * <ul>
 *   <li><b>Window clamping.</b> {@code windowDays} arrives from a query parameter, so it is
 *       untrusted input to a set of aggregate queries. Clamping it keeps a mistyped or hostile
 *       {@code ?days=100000} from turning the dashboard into a full-table scan — a denial of
 *       service that needs no vulnerability, just a large number.</li>
 *   <li><b>Empty scope means nothing.</b> An organization admin with no active memberships gets
 *       zeros, never the system-wide view. Enforced here, before any query runs, so it cannot be
 *       reached by a repository method that would happily answer the unscoped question.</li>
 *   <li><b>Gap filling.</b> The database cannot return a row for a day on which nothing happened;
 *       the chart must show that day anyway.</li>
 *   <li><b>Zero-filling the counters.</b> Every tracked event type appears in the response even at
 *       zero, so the UI never has to distinguish "this did not happen" from "this was not
 *       reported".</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class SecurityDashboardServiceImpl implements SecurityDashboardService {

    private final SecurityDashboardRepo securityDashboardRepo;

    /** Shortest window worth charting — a single day still renders one bar. */
    private static final int MIN_WINDOW_DAYS = 1;

    /**
     * Longest window the dashboard will summarise.
     *
     * <p>Ninety days is chosen to bound query cost, not because older events stop mattering. They
     * remain in {@code userevents} and remain readable per-account through the activity log; what
     * is bounded is how much of that a single unindexed-by-date aggregate will sweep on demand.
     */
    private static final int MAX_WINDOW_DAYS = 90;

    /** Default window when the caller expresses no preference — a week reads as "recently". */
    public static final int DEFAULT_WINDOW_DAYS = 7;

    /**
     * How many flagged sign-ins and restricted accounts to list.
     *
     * <p>A cap rather than pagination because these are triage lists, not records to browse: fifty
     * unreviewed anomalies already means the reader's job is to find a pattern, and page fifty-one
     * would not help them do it. The counters above the table report the true totals, so a capped
     * list never masks the scale of what happened.
     */
    private static final int LIST_LIMIT = 50;

    /** The event types the counters always report, in the order the dashboard reads best. */
    private static final List<EventType> REPORTED_EVENT_TYPES = List.of(
            EventType.SUSPICIOUS_LOGIN,
            EventType.LOGIN_ATTEMPT_FAILURE,
            EventType.LOGIN_ATTEMPT_SUCCESS,
            EventType.TOKEN_REUSE_DETECTED,
            EventType.FEDERATED_LOGIN,
            EventType.RECOVERY_CODE_USED,
            EventType.SESSION_REVOKED);

    /**
     * {@inheritDoc}
     */
    @Override
    public SecurityOverview getOverview(Collection<Long> organizationIds, int windowDays) {
        int window = Math.clamp(windowDays, MIN_WINDOW_DAYS, MAX_WINDOW_DAYS);
        boolean scoped = organizationIds != null;

        // Fail closed: an org admin with no active membership sees zeros, not the whole platform.
        // Checked before any repository call so there is no path on which the unscoped query runs
        // for a caller who was supposed to be restricted.
        if (scoped && organizationIds.isEmpty()) {
            log.debug("[SECURITY-DASHBOARD] Caller has no active organization memberships — returning an empty overview.");
            return SecurityOverview.empty(window);
        }

        LocalDate today = LocalDate.now();
        LocalDate firstDay = today.minusDays(window - 1L);
        // Anchored to the start of the first day rather than to "now minus N × 24h" so the trend's
        // first bar is a whole day like every other one. A window that began mid-morning would make
        // the oldest bar a partial day and look like a dip that never happened.
        LocalDateTime since = firstDay.atStartOfDay();

        SessionActivity sessions = securityDashboardRepo.findSessionActivity(organizationIds);

        return new SecurityOverview(
                window,
                scoped,
                zeroFilled(securityDashboardRepo.countSecurityEvents(since, organizationIds)),
                securityDashboardRepo.findRecentSuspiciousLogins(since, LIST_LIMIT, organizationIds),
                buildTrend(securityDashboardRepo.findDailyLoginOutcomes(since, organizationIds), firstDay, today),
                securityDashboardRepo.findRestrictedAccounts(LIST_LIMIT, organizationIds),
                securityDashboardRepo.findMfaAdoption(organizationIds),
                sessions.activeSessions(),
                sessions.accountsWithSessions());
    }

    /**
     * Ensures every reported event type is present, inserting zeros for those that did not occur.
     *
     * <p>An absent key and a zero look the same to a careless template but mean different things to
     * a careful reader, and the difference matters most for exactly the type you most want to see:
     * "0 suspicious logins" is a reassuring statement, while a missing tile is an unanswered
     * question. Ordering follows {@link #REPORTED_EVENT_TYPES} rather than the database's grouping
     * order, so the tiles do not rearrange themselves between refreshes.
     *
     * @param actual the counts the query returned, containing only types that occurred
     * @return every reported type in display order, with zeros filled in
     */
    private static Map<String, Long> zeroFilled(Map<String, Long> actual) {
        Map<String, Long> counts = new LinkedHashMap<>();
        REPORTED_EVENT_TYPES.forEach(type -> counts.put(type.name(), actual.getOrDefault(type.name(), 0L)));
        return counts;
    }

    /**
     * Pivots the sparse {@code (day, type, count)} rows into one dense point per day.
     *
     * <p>Two transformations happen here, and both are things SQL cannot do for us. The pivot turns
     * three rows per day into one, keeping the set of tracked series out of the query. The
     * gap-filling inserts days the database had nothing to say about — without it a quiet weekend
     * simply vanishes from the axis, so a Monday spike renders as a gentle slope and the chart
     * misleads precisely when it matters.
     *
     * @param rows     the long-format counts, in any order
     * @param firstDay the first day of the window, inclusive
     * @param lastDay  the last day of the window, inclusive
     * @return one point per day from {@code firstDay} to {@code lastDay}, oldest first
     */
    private static List<LoginOutcomeTrendPoint> buildTrend(List<DailyEventCount> rows, LocalDate firstDay, LocalDate lastDay) {
        Map<LocalDate, Map<String, Long>> byDay = new LinkedHashMap<>();
        for (DailyEventCount row : rows) {
            if (row.day() == null) continue;
            byDay.computeIfAbsent(row.day(), day -> new LinkedHashMap<>()).put(row.eventType(), row.total());
        }

        List<LoginOutcomeTrendPoint> trend = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            Map<String, Long> counts = byDay.getOrDefault(day, Map.of());
            trend.add(new LoginOutcomeTrendPoint(
                    day,
                    counts.getOrDefault(EventType.LOGIN_ATTEMPT_SUCCESS.name(), 0L),
                    counts.getOrDefault(EventType.LOGIN_ATTEMPT_FAILURE.name(), 0L),
                    counts.getOrDefault(EventType.SUSPICIOUS_LOGIN.name(), 0L)));
        }
        return trend;
    }
}
