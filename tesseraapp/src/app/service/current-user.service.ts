import { inject, Injectable, signal } from '@angular/core';
import { catchError, Observable, of, shareReplay, tap } from 'rxjs';
import { UserService } from './user.service';
import { UserInterface } from '../interface/user.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { ProfileInterface } from '../interface/appstates.interface';

/**
 * The authenticated user's identity, fetched once and shared by everything that needs it.
 *
 * <h3>The problem this replaces</h3>
 * The navbar used to receive the user through an {@code @Input} from whichever feature component
 * happened to be hosting it, and that user came out of the {@code GET /customer/list} response.
 * Identity was therefore a side effect of asking for a page of customers: the navbar could not
 * render a name until the customer list had loaded, screens with no customer data had to fetch
 * some anyway, and a response about customers carried a field about the caller for no reason
 * other than that it was already there.
 *
 * <h3>Why a shared service rather than a fetch in the navbar</h3>
 * The obvious fix — have {@code NavbarComponent} call {@code profile$()} in its own
 * {@code ngOnInit} — trades one problem for another. The navbar is not a singleton: every feature
 * template instantiates its own, so a per-instance fetch means one {@code /user/profile} request
 * per navigation. Backend HTTP caching (POST-SUBMISSION-UPGRADES.md #3) does not fix this the way
 * the old client-side {@code cacheInterceptor} briefly appeared to: {@code Cache-Control:
 * private, no-cache} means the browser always revalidates over the network before reusing a
 * response, so N navbar instances still cost N real round trips (each cheaply answered with a
 * {@code 304} if nothing changed, but a round trip all the same) — deduping the fetch still has
 * to happen here, in application code, not for free at the HTTP layer.
 *
 * <p>Holding the user in a root-provided signal fetches it once for the application's lifetime and
 * hands the same value to every reader. It also gives the profile screen somewhere to publish an
 * edit ({@link refresh}), instead of the navbar quietly showing a stale name until the next full
 * page load.
 *
 * <h3>This is not an authorization source</h3>
 * The user object here drives *display* — avatar, name. Authority checks must continue to go
 * through {@code UserService.hasAnyAuthority}, which reads the current token, because a cached
 * profile would keep granting a capability after a role change. Nothing on this class should ever
 * be consulted to decide whether an action is allowed.
 *
 * @see UserService for token handling, and for the authority checks that must not come from here
 */
@Injectable({ providedIn: 'root' })
export class CurrentUserService {
  private readonly userService = inject(UserService);

  /** Backing state. `undefined` means "not loaded yet", never "no user". */
  private readonly _user = signal<UserInterface | undefined>(undefined);

  /**
   * The in-flight request, kept so concurrent callers share one round trip.
   *
   * <p>Several components can call {@link load} in the same change-detection pass — the navbar and
   * the page hosting it, for instance. Without this they would each issue a request, because none
   * of them has been handed a result yet to short-circuit on.
   */
  private inFlight?: Observable<CustomHttpResponseInterface<ProfileInterface>>;

  /** The authenticated user, or `undefined` until the first load resolves. */
  readonly user = this._user.asReadonly();

  /**
   * Ensures the user is loaded, fetching only if it has not been already.
   *
   * <p>Safe to call from every component that needs identity — the second and subsequent calls are
   * free once a value has arrived, and concurrent first calls share a single request.
   */
  load(): void {
    if (this._user() !== undefined || this.inFlight) return;
    this.fetch();
  }

  /**
   * Re-fetches the user unconditionally.
   *
   * <p>Call this after anything that changes the displayed identity — a profile edit or an avatar
   * upload — so the navbar reflects it without a page reload.
   */
  refresh(): void {
    this.inFlight = undefined;
    this.fetch();
  }

  /**
   * Publishes a user the caller already has, skipping a round trip.
   *
   * <p>Screens that update the profile receive the updated user in their own response; handing it
   * over directly is both faster and more correct than re-requesting it, because it cannot race
   * with the write that produced it.
   *
   * @param user - the user to publish
   */
  set(user: UserInterface | undefined): void {
    if (user) this._user.set(user);
  }

  /**
   * Drops the cached user.
   *
   * <p>Must run on sign-out. Without it, the next account to sign in on the same tab sees the
   * previous user's name and avatar in the navbar until something refetches — the same class of
   * leak that made {@code UserService.logOut()} evict the HTTP cache.
   */
  clear(): void {
    this._user.set(undefined);
    this.inFlight = undefined;
  }

  /**
   * Issues the request and publishes the result.
   *
   * <p>Failures are swallowed deliberately. This is decoration: if the profile call fails, the
   * navbar should fall back to its initials block, not surface a toast on every screen. A genuine
   * auth failure is already handled where it matters — {@code tokenInterceptor} refreshes on 401
   * and routes to `/login` when that fails.
   */
  private fetch(): void {
    this.inFlight = this.userService.profile$().pipe(
      tap((response) => this._user.set(response?.data?.user)),
      catchError(() => of({} as CustomHttpResponseInterface<ProfileInterface>)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.inFlight.subscribe();
  }
}
