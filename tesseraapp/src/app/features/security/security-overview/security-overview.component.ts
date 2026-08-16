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
  PageInfoInterface,
  RestrictedAccountInterface,
  SecurityOverviewDataInterface,
  SecuritySettingsInterface,
  SuspiciousLoginInterface,
} from '../../../interface/security-overview.interface';
import { UserInterface } from '../../../interface/user.interface';
import { PAGE_SIZE_OPTIONS, PageSizeSelectComponent } from '../../../shared/page-size-select/page-size-select.component';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';

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
  imports: [NavbarComponent, RouterLink, DecimalPipe, DatePipe, TranslocoDirective, PageSizeSelectComponent],
  templateUrl: './security-overview.component.html',
  styleUrl: './security-overview.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecurityOverviewComponent implements OnInit {
  readonly DataState = DataState;

  private readonly securityDashboard = inject(SecurityDashboardService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly transloco = inject(TranslocoService);

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

  // ── Pagination ────────────────────────────────────────────────────────────────────────────
  // The two tables page INDEPENDENTLY. A single shared index would mean stepping through flagged
  // sign-ins silently reset the restricted-accounts list an administrator was working down — the
  // two panels answer unrelated questions and are read at unrelated rates.

  /** 0-based page of the flagged sign-ins table. */
  private readonly suspiciousPage = signal(0);

  /** 0-based page of the locked/disabled accounts table. */
  private readonly restrictedPage = signal(0);

  // Row counts are per-table for the same reason the page indexes are. Fifty matches the server's
  // DEFAULT_LIST_SIZE, so the first load is identical to what this screen has always shown.

  /** Rows per page of the flagged sign-ins table; the server clamps this to 1–100. */
  protected readonly suspiciousSize = signal(50);

  /** Rows per page of the locked/disabled accounts table; the server clamps this to 1–100. */
  protected readonly restrictedSize = signal(50);

  /** Server-reported metadata for the flagged sign-ins table; zeroed until the first response. */
  protected readonly suspiciousPageInfo = computed<PageInfoInterface>(
    () => this.overview()?.suspiciousLoginsPage ?? { page: 0, size: 0, totalElements: 0, totalPages: 0 },
  );

  /** Server-reported metadata for the restricted accounts table. */
  protected readonly restrictedPageInfo = computed<PageInfoInterface>(
    () => this.overview()?.restrictedAccountsPage ?? { page: 0, size: 0, totalElements: 0, totalPages: 0 },
  );

  /**
   * Whether each pager is worth rendering at all.
   *
   * <p>Hidden below two pages on purpose. A pager showing a lone "1" is visually indistinguishable
   * from a table that has no pagination, which leaves the reader unsure whether they are seeing
   * everything — the exact ambiguity this work exists to remove. Absent controls plus a visible
   * total says "this is all of it" unambiguously.
   */
  protected readonly showSuspiciousPager = computed(() => this.suspiciousPageInfo().totalPages > 1);
  protected readonly showRestrictedPager = computed(() => this.restrictedPageInfo().totalPages > 1);

  /**
   * Whether each table's footer — position readout, size selector, prev/next — should render.
   *
   * <p>Keyed to the row total rather than the page count, which is what keeps the size selector
   * from deleting itself. Gating the footer on {@link showSuspiciousPager} would mean that choosing
   * 100 rows for a 60-row table collapses it to one page, hides the footer, and takes away the only
   * control that could restore a smaller size. Asking instead whether any offered size could
   * produce a second page means the footer outlives its own effect. The prev/next nav inside is
   * still gated on the page count, so the "lone 1" it was protecting against never appears.
   */
  protected readonly showSuspiciousFoot = computed(() => this.suspiciousPageInfo().totalElements > PAGE_SIZE_OPTIONS[0]);
  protected readonly showRestrictedFoot = computed(() => this.restrictedPageInfo().totalElements > PAGE_SIZE_OPTIONS[0]);

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

  // ── Anomaly signal tuning (FUTURE-ENHANCEMENTS "Anomaly signal tuning UI") ─────────────────
  // A separate request and a separate state signal from the overview above: the settings panel
  // is admin configuration, not a reporting figure, and must not be re-fetched (or accidentally
  // reset) every time the reporting window or a table page changes.

  /** The settings row as last confirmed by the server — the baseline {@link isDirty} compares against. */
  protected readonly savedSettings = signal<SecuritySettingsInterface | null>(null);

  /**
   * The enabled-override the admin is currently editing: {@code null} means "use the server
   * default", matching the API's own null-means-no-override contract so there is nothing to
   * translate between the draft and the request body.
   */
  protected readonly enabledDraft = signal<boolean | null>(null);

  /** The history-limit override the admin is currently editing; {@code null} means "use the default". */
  protected readonly historyLimitDraft = signal<number | null>(null);

  protected readonly settingsSaving = signal(false);

  /**
   * Whether the draft differs from what the server last confirmed — gates the Save button so a
   * click with nothing changed cannot fire a pointless request, and lets the template show an
   * "unsaved changes" hint.
   */
  protected readonly settingsDirty = computed(() => {
    const saved = this.savedSettings();
    if (!saved) return false;
    return saved.anomalyEnabled !== this.enabledDraft() || saved.anomalyHistoryLimit !== this.historyLimitDraft();
  });

  ngOnInit(): void {
    this.load(this.selectedWindow());
    this.loadAnomalySettings();
  }

  /** Fetches the current anomaly detection overrides and resets the draft to match. */
  private loadAnomalySettings(): void {
    this.securityDashboard
      .anomalySettings$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          const settings = response.data?.settings;
          if (!settings) return;
          this.savedSettings.set(settings);
          this.enabledDraft.set(settings.anomalyEnabled);
          this.historyLimitDraft.set(settings.anomalyHistoryLimit);
        },
        error: (error: string) => this.notification.onError(error),
      });
  }

  /**
   * Sets the enabled-override draft. Called from a three-way button group (default / enabled /
   * disabled) rather than a checkbox, since "unset" is a genuine third state here, not the
   * absence of a boolean.
   *
   * @param value - null for "use the server default", otherwise the override to stage
   */
  protected selectEnabledDraft(value: boolean | null): void {
    this.enabledDraft.set(value);
  }

  /**
   * Reads the history-limit number input and stages it as the draft override; an empty field
   * clears the override back to null ("use the default") rather than coercing to 0, which would
   * be a real (and nonsensical) override value.
   *
   * @param raw - the input element's string value
   */
  protected onHistoryLimitInput(raw: string): void {
    const trimmed = raw.trim();
    this.historyLimitDraft.set(trimmed === '' ? null : Number(trimmed));
  }

  /** Clears the history-limit draft back to "use the server default". */
  protected clearHistoryLimitDraft(): void {
    this.historyLimitDraft.set(null);
  }

  /** Persists the draft and refreshes {@link savedSettings} so {@link settingsDirty} clears. */
  protected saveAnomalySettings(): void {
    if (!this.settingsDirty() || this.settingsSaving()) return;
    this.settingsSaving.set(true);
    this.securityDashboard
      .updateAnomalySettings$(this.enabledDraft(), this.historyLimitDraft())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          const settings = response.data?.settings;
          this.settingsSaving.set(false);
          if (!settings) return;
          this.savedSettings.set(settings);
          this.enabledDraft.set(settings.anomalyEnabled);
          this.historyLimitDraft.set(settings.anomalyHistoryLimit);
          this.notification.onSuccess(this.transloco.translate('toasts.settingsUpdated'));
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.settingsSaving.set(false);
        },
      });
  }

  /**
   * Moves the flagged sign-ins table to a page and re-fetches.
   *
   * <p>Clamped to the reported range here rather than relying on the server, so a disabled control
   * that is somehow activated cannot fire a pointless request.
   *
   * @param page - the target 0-based page index
   */
  protected goToSuspiciousPage(page: number): void {
    const last = Math.max(this.suspiciousPageInfo().totalPages - 1, 0);
    const target = Math.min(Math.max(page, 0), last);
    if (target === this.suspiciousPage()) return;
    this.suspiciousPage.set(target);
    this.load(this.selectedWindow());
  }

  /**
   * Moves the restricted accounts table to a page and re-fetches.
   *
   * @param page - the target 0-based page index
   */
  protected goToRestrictedPage(page: number): void {
    const last = Math.max(this.restrictedPageInfo().totalPages - 1, 0);
    const target = Math.min(Math.max(page, 0), last);
    if (target === this.restrictedPage()) return;
    this.restrictedPage.set(target);
    this.load(this.selectedWindow());
  }

  /**
   * Resizes the flagged sign-ins table and re-reads it from the first page.
   *
   * <p>Only this table is touched. The restricted-accounts list keeps both its size and its place,
   * which is the same independence the two page indexes already have — an administrator scanning
   * flagged sign-ins in hundreds should not thereby resize the lockout list a colleague's ticket is
   * about.
   *
   * @param size - the new row count; the server clamps it to 1–100 and reports back what it used
   */
  protected changeSuspiciousSize(size: number): void {
    if (size === this.suspiciousSize()) return;
    this.suspiciousSize.set(size);
    // Page 3 of a 10-row listing is past the end of a 100-row one. Unlike the client-side tables,
    // this one would fetch that page from the server before discovering it is empty.
    this.suspiciousPage.set(0);
    this.load(this.selectedWindow());
  }

  /**
   * Resizes the locked/disabled accounts table and re-reads it from the first page.
   *
   * @param size - the new row count; the server clamps it to 1–100 and reports back what it used
   */
  protected changeRestrictedSize(size: number): void {
    if (size === this.restrictedSize()) return;
    this.restrictedSize.set(size);
    this.restrictedPage.set(0);
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
    // Both pagers reset: a different window is a different result set, so page 3 of the old window
    // has no meaningful counterpart in the new one and would likely land past the end.
    this.suspiciousPage.set(0);
    this.restrictedPage.set(0);
    this.load(days);
  }

  /**
   * Fetches the overview for a window and folds the result into {@link pageState}.
   *
   * @param days - how many days of history to summarise
   */
  private load(days: number): void {
    this.securityDashboard
      .overview$(days, this.suspiciousPage(), this.suspiciousSize(), this.restrictedPage(), this.restrictedSize())
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
