import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { catchError, Observable, of, shareReplay, tap, throwError } from 'rxjs';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { environment } from '../../environments/environment';

/** The {@code data} payload every {@code /user/favorites/**} endpoint returns. */
export interface FavoritesDataInterface {
  favorites: string[];
}

/**
 * The caller's pinned destination ids, fetched once and shared by every reader — the same
 * fetch-once-and-share-via-signal shape as {@link CurrentUserService}, applied to
 * {@code /user/favorites}.
 *
 * <p>A "destination id" is opaque here: this service does not know or care what
 * {@code 'customers'} or {@code 'analytics'} means, only that the server accepted or rejected a
 * pin for it. Resolving an id to a route/icon/label is {@code FavoritesBarComponent}'s job, via
 * {@code findDestination} in {@code navigable-destinations.ts} — the same registry
 * {@code CommandPaletteComponent} builds its entries from.
 *
 * @see CurrentUserService for the identical caching shape this mirrors
 */
@Injectable({ providedIn: 'root' })
export class FavoriteService {
  private readonly http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /** Backing state. `undefined` means "not loaded yet", never "no favorites". */
  private readonly _favorites = signal<string[] | undefined>(undefined);

  /** The in-flight load request, kept so concurrent callers (e.g. navbar + favorites bar) share one round trip. */
  private inFlight?: Observable<CustomHttpResponseInterface<FavoritesDataInterface>>;

  /** The caller's pinned destination ids, or `undefined` until the first load resolves. */
  readonly favorites = this._favorites.asReadonly();

  /**
   * Ensures favorites are loaded, fetching only if they have not been already. Safe to call from
   * every component that needs the list — second and later calls are free.
   */
  load(): void {
    if (this._favorites() !== undefined || this.inFlight) return;
    this.fetch();
  }

  /** Drops the cached list. Must run on sign-out, mirroring {@code CurrentUserService.clear}. */
  clear(): void {
    this._favorites.set(undefined);
    this.inFlight = undefined;
  }

  /**
   * Pins a destination ({@code POST /user/favorites/{destinationId}}). Idempotent, and refused
   * once the caller is already at the 8-favorite cap — {@code FavoriteServiceImpl} raises an
   * {@code ApiException} whose message surfaces through {@link handleError} as the rejected
   * Observable's error message, ready for a toast.
   *
   * @param destinationId - the destination id to pin
   * @returns Observable of the envelope; also publishes the refreshed list into {@link favorites} on success
   */
  add$(destinationId: string): Observable<CustomHttpResponseInterface<FavoritesDataInterface>> {
    return this.http
      .post<CustomHttpResponseInterface<FavoritesDataInterface>>(`${this.server}/user/favorites/${destinationId}`, {})
      .pipe(
        tap((response) => this._favorites.set(response?.data?.favorites ?? [])),
        catchError(this.handleError),
      );
  }

  /**
   * Unpins a destination ({@code DELETE /user/favorites/{destinationId}}). Idempotent — unpinning
   * something never pinned still succeeds.
   *
   * @param destinationId - the destination id to unpin
   * @returns Observable of the envelope; also publishes the refreshed list into {@link favorites} on success
   */
  remove$(destinationId: string): Observable<CustomHttpResponseInterface<FavoritesDataInterface>> {
    return this.http
      .delete<CustomHttpResponseInterface<FavoritesDataInterface>>(`${this.server}/user/favorites/${destinationId}`)
      .pipe(
        tap((response) => this._favorites.set(response?.data?.favorites ?? [])),
        catchError(this.handleError),
      );
  }

  /**
   * Issues the list request and publishes the result. Failures are swallowed deliberately, same
   * rationale as {@code CurrentUserService#fetch}: a favorites bar that fails to load should
   * quietly render nothing, not surface a toast on every authenticated screen.
   */
  private fetch(): void {
    this.inFlight = this.http
      .get<CustomHttpResponseInterface<FavoritesDataInterface>>(`${this.server}/user/favorites`)
      .pipe(
        // catchError before tap: a fallback emitted after tap would never reach it, and the
        // signal would stay undefined forever on failure instead of settling to [].
        catchError(() => of({} as CustomHttpResponseInterface<FavoritesDataInterface>)),
        tap((response) => this._favorites.set(response?.data?.favorites ?? [])),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    this.inFlight.subscribe();
  }

  /**
   * Normalizes HTTP errors into a single Observable<never>, mirroring
   * {@code ContactService#handleError} — {@code error.error.reason} carries an
   * {@code ApiException}'s message per {@code GlobalExceptionHandler#handleApiException}.
   */
  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage: string;

    if (error.error instanceof ErrorEvent) {
      errorMessage = `An error occurred: ${error.error.message}`;
    } else if (error.error?.reason) {
      errorMessage = error.error.reason as string;
    } else {
      errorMessage = `Server returned code: ${error.status}, error message is: ${error.message}`;
    }
    console.error(errorMessage);

    return throwError(() => new Error(errorMessage));
  }
}
