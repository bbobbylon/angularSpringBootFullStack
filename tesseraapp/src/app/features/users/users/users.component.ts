import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { NgClass, NgOptimizedImage } from '@angular/common';
import { RouterModule } from '@angular/router';
import { debounceTime, map, of, startWith, Subject, switchMap } from 'rxjs';
import { catchError, filter } from 'rxjs/operators';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { AdminUserListInterface } from '../../../interface/admin.interface';
import { AdminUserService } from '../../../service/admin-user.service';
import { ExtractArrayValuePipe } from '../../../pipe/extract-array-value.pipe';
import { NotificationsService } from '../../../service/notifications-service';
import { PageSizeSelectComponent } from '../../../shared/page-size-select/page-size-select.component';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Administrative user directory — the list half of the Users dashboard
 * (SRS FR-ADMIN-1, plan.md M3).
 *
 * Fetches {@code GET /admin/user/list} with the current page and search term.
 * Pagination and search are driven by Signals bridged to Observables via
 * {@code toObservable}, so any change re-fetches through
 * {@code combineLatest + switchMap} — the same reactive pattern as
 * {@code CustomersComponent}, which this view deliberately mirrors so the two
 * list screens stay maintainable as one idiom.
 *
 * The route is protected by {@code adminGuard} (FR-ADMIN-5); the backend
 * independently enforces the UPDATE:USER / UPDATE:ROLE authorities on every call.
 */
@Component({
  selector: 'app-users',
  imports: [NgClass, RouterModule, NavbarComponent, ExtractArrayValuePipe, NgOptimizedImage, TranslocoDirective, PageSizeSelectComponent],
  templateUrl: './users.component.html',
  styleUrl: './users.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UsersComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;

  /**
   * Drives the entire template — emits a new {@link GlobalStateInterface} snapshot
   * whenever the page index or search term changes.
   */
  usersState = signal<GlobalStateInterface<CustomHttpResponseInterface<AdminUserListInterface>>>({ dataState: DataState.LOADING });

  /** Tracks the current 0-based page index; emitting a new value triggers a re-fetch. */
  private currentPage = signal(0);

  /** Public read-only view of the page index, used by pagination controls. */
  currentPage$ = this.currentPage.asReadonly();

  /** The active (debounced) search term; empty string lists the full directory. */
  private currentSearchTerm = signal('');

  /**
   * Rows fetched per page. Changing it resets {@link currentPage} — see {@link changePageSize}.
   *
   * <p>Ten matches {@code AdminUserController.DEFAULT_PAGE_SIZE} and NFR-PERF-3's stated default.
   * The backend clamps whatever arrives to 1–100 ({@code UserRepoImpl}), so the largest offered
   * option is exactly the server's ceiling rather than a number it would silently reduce.
   */
  private pageSize = signal(10);

  /** Read-only view of the row count, for the template's size selector. */
  pageSize$ = this.pageSize.asReadonly();

  /**
   * Page, size and search term as one derived value — the single input to the fetch pipeline.
   *
   * <p>Replaces a {@code combineLatest} over two {@code toObservable} bridges. Both writers that
   * touch two signals at once — {@link changePageSize} and the debounced search subscription, each
   * of which also resets the page index — used to make it emit twice in one flush and issue two
   * requests for a single user action. {@code switchMap} hid that by cancelling one; the directory
   * endpoint still served both.
   */
  private readonly query = computed(() => ({
    page: this.currentPage(),
    size: this.pageSize(),
    term: this.currentSearchTerm(),
  }));
  private readonly _query$ = toObservable(this.query);

  private readonly adminUserService = inject(AdminUserService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);

  /**
   * Raw keystrokes from the search input. The debounced subscription in
   * {@link ngOnInit} gates what reaches {@link currentSearchTerm}.
   */
  private readonly searchInput$ = new Subject<string>();

  /**
   * Deterministic fallback avatar colors — the same palette as the customers list,
   * indexed by {@code id % length} so a user keeps their color across renders.
   */
  private readonly avatarColors = ['0D8ABC', '2ECC71', 'E74C3C', '9B59B6', 'F39C12', '1ABC9C', 'E67E22'];

  /**
   * Wires the state signal to react to page, row-count and search-term changes.
   *
   * All three ride in {@link query}, so one fetch fires per change however many of them moved;
   * {@code switchMap} cancels in-flight requests so stale responses never overwrite newer results;
   * {@code startWith(LOADING)} flips the template to its loading branch on each fetch.
   */
  ngOnInit(): void {
    this.searchInput$
      .pipe(
        debounceTime(300),
        filter((term) => term.length === 0 || term.length >= 3),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((term) => {
        this.currentSearchTerm.set(term);
        this.currentPage.set(0);
      });

    const users$ = this._query$.pipe(
      switchMap(({ page, size, term }) =>
        this.adminUserService.users$(page, term, size).pipe(
          map((response) => ({ dataState: DataState.LOADED, appData: response })),
          startWith({ dataState: DataState.LOADING }),
          catchError((error: string) => {
            this.notification.onError(error);
            return of({ dataState: DataState.ERROR, error });
          }),
        ),
      ),
    );
    users$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((state) => this.usersState.set(state));
  }

  /**
   * Pushes each keystroke from the search input into the debounced pipeline.
   *
   * @param term - the current value of the search input
   */
  onSearchInput(term: string): void {
    this.searchInput$.next(term);
  }

  /**
   * Advances or retreats one page; the active search term is preserved because
   * the fetch pipeline reads both signals via {@code combineLatest}.
   *
   * @param direction - {@code 'forward'} to increment, anything else to decrement
   */
  goToNextOrPreviousPage(direction: string): void {
    const step = direction === 'forward' ? 1 : -1;
    this.currentPage.update((current) => current + step);
  }

  /**
   * Jumps directly to a specific 0-based page index.
   *
   * @param pageIndex - the target page
   */
  goToPage(pageIndex: number): void {
    this.currentPage.set(pageIndex);
  }

  /**
   * Changes how many users are listed per page and returns to the first page.
   *
   * <p>The reset keeps the request answerable: page 6 of a 10-row directory is past the end of a
   * 100-row one, and the pager would offer no route back from the empty table that results. Any
   * active search term is preserved, since {@link query} carries it separately.
   *
   * @param size - the new row count, one of the selector's offered values
   */
  changePageSize(size: number): void {
    this.pageSize.set(size);
    this.currentPage.set(0);
  }

  /**
   * Returns a deterministic fallback avatar color for the given user ID.
   *
   * @param id - the user's numeric ID used to index into the color pool
   * @returns a hex color string usable in {@code [style.background-color]}
   */
  protected getAvatarColor(id: number): string {
    return '#' + this.avatarColors[id % this.avatarColors.length];
  }
}
