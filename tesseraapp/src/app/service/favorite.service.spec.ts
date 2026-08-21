import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { FavoriteService } from './favorite.service';
import { environment } from '../../environments/environment';

/**
 * Specs for {@link FavoriteService} — the fetch-once-and-share cache over
 * {@code /user/favorites}, mirroring {@code CurrentUserService}'s caching contract.
 *
 * <p>Driven through the real {@code HttpClient} against {@link HttpTestingController} rather than
 * a stubbed service, since this class *is* the HTTP boundary being tested — unlike
 * {@code tokenInterceptor}'s specs, there is no lower layer worth mocking out here.
 */
describe('FavoriteService', () => {
  let service: FavoriteService;
  let httpMock: HttpTestingController;

  const favoritesUrl = `${environment.apiUrl}/user/favorites`;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(FavoriteService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('load', () => {
    it('is undefined until the first load resolves', () => {
      expect(service.favorites()).toBeUndefined();
    });

    it('fetches and publishes the list', () => {
      service.load();

      httpMock.expectOne(favoritesUrl).flush({
        statusCode: 200,
        status: 'OK',
        message: 'ok',
        timestamp: new Date(),
        data: { favorites: ['customers', 'billing'] },
      });

      expect(service.favorites()).toEqual(['customers', 'billing']);
    });

    it('does not issue a second request while the first is still in flight', () => {
      service.load();
      service.load();

      httpMock.expectOne(favoritesUrl);
    });

    it('does not re-fetch once a value has already loaded', () => {
      service.load();
      httpMock.expectOne(favoritesUrl).flush({ data: { favorites: [] } });

      service.load();

      httpMock.expectNone(favoritesUrl);
    });

    it('publishes an empty list rather than throwing when the request fails', () => {
      // Decoration, not a critical path — a favorites bar that cannot load should quietly render
      // nothing, not surface a toast on every authenticated screen (mirrors CurrentUserService).
      service.load();

      httpMock.expectOne(favoritesUrl).flush('server error', { status: 500, statusText: 'Server Error' });

      expect(service.favorites()).toEqual([]);
    });
  });

  describe('clear', () => {
    it('drops the cached list and allows a fresh load', () => {
      service.load();
      httpMock.expectOne(favoritesUrl).flush({ data: { favorites: ['customers'] } });

      service.clear();

      expect(service.favorites()).toBeUndefined();
      service.load();
      httpMock.expectOne(favoritesUrl).flush({ data: { favorites: [] } });
    });
  });

  describe('add$', () => {
    it('pins a destination and publishes the refreshed list', () => {
      let result: string[] | undefined;
      service.add$('security').subscribe((response) => (result = response.data?.favorites));

      httpMock.expectOne(`${favoritesUrl}/security`).flush({ data: { favorites: ['security'] } });

      expect(result).toEqual(['security']);
      expect(service.favorites()).toEqual(['security']);
    });

    it('surfaces the server-provided message when the cap is exceeded', () => {
      let error: Error | undefined;
      service.add$('roles').subscribe({ error: (err: Error) => (error = err) });

      httpMock
        .expectOne(`${favoritesUrl}/roles`)
        .flush(
          { reason: 'You can only pin up to 8 destinations — unpin one first.' },
          { status: 400, statusText: 'Bad Request' },
        );

      expect(error?.message).toBe('You can only pin up to 8 destinations — unpin one first.');
    });
  });

  describe('remove$', () => {
    it('unpins a destination and publishes the refreshed list', () => {
      let result: string[] | undefined;
      service.remove$('billing').subscribe((response) => (result = response.data?.favorites));

      const request = httpMock.expectOne(`${favoritesUrl}/billing`);
      expect(request.request.method).toBe('DELETE');
      request.flush({ data: { favorites: [] } });

      expect(result).toEqual([]);
      expect(service.favorites()).toEqual([]);
    });
  });
});
