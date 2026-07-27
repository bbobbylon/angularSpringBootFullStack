import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { SecurityDashboardService } from '../../../service/security-dashboard.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import {
  LoginOutcomeTrendPointInterface,
  MfaAdoptionInterface,
  RestrictedAccountInterface,
  SecurityOverviewDataInterface,
  SuspiciousLoginInterface,
} from '../../../interface/security-overview.interface';
import { UserInterface } from '../../../interface/user.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/** One plotted day of the login-outcome trend, with SVG coordinates pre-computed. */
interface TrendColumn {
  /** Short axis label (e.g. {@code "07-24"}). */
  label: string;
  /** Full date, for the hover title. */
  fullLabel: string;
  successful: number;
  failed: number;
  suspicious: number;
  /** Bar heights as a share of the tallest day, 0–100. */
  successHeight: number;
  failedHeight: number;
  suspiciousHeight: number;
}

/**
 * Administrative security dashboard — {@code /security-overview} (SRS FR-TPF-2).
 *
 * <h3>What this screen is for</h3>
 * FR-TPF-1 gave the platform the ability to notice a sign-in that does not match an account's
 * history and escalate it. Everything it noticed went into the audit log, where nobody could see
 * it. A detection control whose output is never reviewed cannot be tuned, cannot be shown to work,
 * and cannot tell an administrator that one account has been flagged eleven times this week — so
 * this page is not a nicety on top of FR-TPF-1, it is the half that makes the other half
 * meaningful.
 *
 * <h3>Data flow</h3>
 * One request to {@code GET /admin/security/overview} returns every panel, because six requests
 * would give six different instants of the same database and no way to tell which panel was
 * stale. The response is held in a single state signal and every visual is derived from it with
 * {@code computed}, so a re-fetch (the user changing the window) updates the whole screen
 * atomically.
 *
 * <h3>Reading the numbers honestly</h3>
 * Two presentation decisions exist to stop the screen from lying:
 * <ul>
 *   <li>The <b>scope banner</b>. When the response is org-scoped (FR-ORG-2) the page says so. A
 *       dashboard that renders identically whether it covers the whole platform or one
 *       organization's slice invites its worst misreading — an org admin concluding all is quiet
 *       when they can only see their own corner.</li>
 *   <li>The <b>shared trend scale</b>. All three series are normalised against one maximum rather
 *       than each against its own. Independently-scaled series would draw four failures the same
 *       height as four hundred successes, which is exactly the comparison the chart exists to
 *       support.</li>
 * </ul>
 *
 * <p>Access: the route carries {@code adminGuard}, and the API sits under {@code /admin/**} where
 * SecurityConfig enforces {@code UPDATE:USER}/{@code UPDATE:ROLE} server-side. The guard is the
 * usability half only (NFR-SEC-4).
 */
@Component({
  selector: 'app-security-overview',
  standalone: true,
  imports: [NavbarComponent, RouterLink, DecimalPipe, DatePipe, TranslocoDirective],
  templateUrl: './security-overview.component.html',
  styleUrl: './security-overview.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecurityOverviewComponent implements OnInit {
  readonly DataState = DataState;

  private readonly securityDashboard = inject(SecurityDashboardService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  /** The windows offered in the selector — a week, a month, a quarter. */
  protected readonly windowOptions = [7, 30, 90];

  /** The currently requested window, in days. Drives re-fetches. */
  protected readonly selectedWindow = signal(7);

  protected readonly pageState = signal<GlobalStateInterface<CustomHttpResponseInterface<SecurityOverviewDataInterface>>>({
    dataState: DataState.LOADING,
  });

  protected readonly user = computed<UserInterface | undefined>(() => this.pageState().appData?.data?.user);

  private readonly overview = computed(() => this.pageState().appData?.data?.overview);

  /** True when these figures cover only the caller's organizations (FR-ORG-2). */
  protected readonly isScoped = computed(() => this.overview()?.scoped ?? false);

  protected readonly windowDays = computed(() => this.overview()?.windowDays ?? this.selectedWindow());

  protected readonly suspiciousLogins = computed<SuspiciousLoginInterface[]>(() => this.overview()?.suspiciousLogins ?? []);

  protected readonly restrictedAccounts = computed<RestrictedAccountInterface[]>(() => this.overview()?.restrictedAccounts ?? []);

  protected readonly mfa = computed<MfaAdoptionInterface>(
    () => this.overview()?.mfaAdoption ?? { totalUsers: 0, totpUsers: 0, smsUsers: 0, singleFactorUsers: 0, mfaCoveragePercent: 0 },
  );

  protected readonly activeSessions = computed(() => this.overview()?.activeSessions ?? 0);
  protected readonly accountsWithSessions = computed(() => this.overview()?.accountsWithSessions ?? 0);

  /** Headline counters, zero-filled by the server so every tile always has a number. */
  protected readonly counts = computed<Record<string, number>>(() => this.overview()?.eventCounts ?? {});

  protected readonly suspiciousCount = computed(() => this.counts()['SUSPICIOUS_LOGIN'] ?? 0);
  protected readonly failedCount = computed(() => this.counts()['LOGIN_ATTEMPT_FAILURE'] ?? 0);
  protected readonly successCount = computed(() => this.counts()['LOGIN_ATTEMPT_SUCCESS'] ?? 0);
  protected readonly tokenReuseCount = computed(() => this.counts()['TOKEN_REUSE_DETECTED'] ?? 0);
  protected readonly federatedCount = computed(() => this.counts()['FEDERATED_LOGIN'] ?? 0);
  protected readonly recoveryCodeCount = computed(() => this.counts()['RECOVERY_CODE_USED'] ?? 0);

  /**
   * Share of sign-in attempts that failed, over the window.
   *
   * <p>Reported as a rate rather than as the raw failure count because the raw number is
   * uninterpretable on its own — forty failures is alarming against fifty successes and routine
   * against forty thousand. Returns 0 for a window with no attempts at all rather than dividing by
   * zero, which is a real case on a quiet weekend or a fresh deployment.
   */
  protected readonly failureRate = computed(() => {
    const attempts = this.successCount() + this.failedCount();
    return attempts === 0 ? 0 : Math.round((this.failedCount() / attempts) * 1000) / 10;
  });

  /**
   * Average live sessions per signed-in account.
   *
   * <p>The ratio is the signal, not either number alone: eighty sessions across seventy-five
   * accounts is ordinary multi-device use, eighty across four is worth a second look.
   */
  protected readonly sessionsPerAccount = computed(() => {
    const accounts = this.accountsWithSessions();
    return accounts === 0 ? 0 : Math.round((this.activeSessions() / accounts) * 10) / 10;
  });

  /**
   * The trend chart's columns, with bar heights normalised against a single shared maximum.
   *
   * <p>One scale for all three series is the whole point. Normalising each series against its own
   * maximum would make the tallest failure bar and the tallest success bar the same height, which
   * reverses the comparison the chart exists to support — a reader would see "as many failures as
   * successes" in a window where failures were a rounding error.
   *
   * <p>The floor of 1 on the divisor keeps a completely quiet window rendering a flat baseline
   * instead of producing NaN heights that collapse the SVG.
   */
  protected readonly trend = computed<TrendColumn[]>(() => {
    const points: LoginOutcomeTrendPointInterface[] = this.overview()?.trend ?? [];
    if (points.length === 0) return [];

    const peak = Math.max(1, ...points.map((point) => Math.max(point.successful, point.failed, point.suspicious)));

    return points.map((point) => ({
      label: point.day.slice(5),
      fullLabel: point.day,
      successful: point.successful,
      failed: point.failed,
      suspicious: point.suspicious,
      successHeight: (point.successful / peak) * 100,
      failedHeight: (point.failed / peak) * 100,
      suspiciousHeight: (point.suspicious / peak) * 100,
    }));
  });

  /**
   * Accounts appearing more than once in the flagged sign-in list, worst first.
   *
   * <p>A single flagged sign-in is usually a person on a new laptop; the same account flagged
   * repeatedly is a pattern, and patterns are what a reviewer is actually scanning for. Surfacing
   * them separately means that signal is not buried in a chronological list where the repeats sit
   * rows apart.
   */
  protected readonly repeatOffenders = computed(() => {
    const byAccount = new Map<string, { email: string; name: string; userId: number; count: number }>();
    for (const entry of this.suspiciousLogins()) {
      const existing = byAccount.get(entry.email);
      if (existing) {
        existing.count += 1;
      } else {
        byAccount.set(entry.email, {
          email: entry.email,
          name: `${entry.firstName ?? ''} ${entry.lastName ?? ''}`.trim(),
          userId: entry.userId,
          count: 1,
        });
      }
    }
    return [...byAccount.values()].filter((account) => account.count > 1).sort((a, b) => b.count - a.count);
  });

  /** Locked accounts only — the subset an administrator can resolve with an unlock. */
  protected readonly lockedCount = computed(() => this.restrictedAccounts().filter((account) => !account.nonLocked).length);

  /** Accounts held back by verification rather than by a lockout. */
  protected readonly disabledCount = computed(() => this.restrictedAccounts().filter((account) => !account.enabled).length);

  ngOnInit(): void {
    this.load(this.selectedWindow());
  }

  /**
   * Switches the reporting window and re-fetches.
   *
   * <p>Ignores a click on the window already selected — a re-fetch that cannot change the answer
   * only costs a loading flash.
   *
   * @param days - the window to load, one of {@link windowOptions}
   */
  protected selectWindow(days: number): void {
    if (days === this.selectedWindow()) return;
    this.selectedWindow.set(days);
    this.load(days);
  }

  /**
   * Fetches the overview for a window and folds the result into {@link pageState}.
   *
   * @param days - how many days of history to summarise
   */
  private load(days: number): void {
    this.securityDashboard
      .overview$(days)
      .pipe(
        map((response) => ({ dataState: DataState.LOADED, appData: response })),
        startWith({ dataState: DataState.LOADING }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.pageState.set(state));
  }

  /**
   * Splits the audit row's {@code detail} into something readable.
   *
   * <p>FR-TPF-1 writes strings like {@code "a new device → step-up: EMAIL_CODE"}. Rendering that
   * raw is acceptable but wastes the structure it already has; the two halves answer different
   * questions ("what was noticed" and "what did we do about it") and belong in different columns.
   *
   * @param detail - the raw detail column, possibly null on older rows
   * @returns the signal half, or a placeholder when there is nothing recorded
   */
  protected signalOf(detail: string | null): string {
    if (!detail) return 'Not recorded';
    // Named signalPart, not signal — `signal` is imported from @angular/core at module scope, and
    // shadowing it inside a method is legal but reads as a bug to anyone skimming the file.
    const [signalPart] = detail.split('→');
    return signalPart.trim() || 'Not recorded';
  }

  /**
   * The step-up half of an audit row's {@code detail}.
   *
   * @param detail - the raw detail column, possibly null
   * @returns the step-up that was applied, or a placeholder
   */
  protected stepUpOf(detail: string | null): string {
    if (!detail || !detail.includes('→')) return '—';
    return detail.split('→').slice(1).join('→').replace('step-up:', '').trim() || '—';
  }
}
