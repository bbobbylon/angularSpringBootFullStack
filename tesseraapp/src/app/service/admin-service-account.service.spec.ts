import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { AdminServiceAccountService } from './admin-service-account.service';
import { environment } from '../../environments/environment';

/**
 * Specs for {@link AdminServiceAccountService} — the HTTP boundary for
 * {@code AdminServiceAccountController}'s service-account and API-key endpoints
 * (FUTURE-ENHANCEMENTS.md §3.1 "P2-3 — Machine-to-machine API access", Option A).
 *
 * <p>Driven through the real {@code HttpClient} against {@link HttpTestingController}, mirroring
 * {@code OrganizationService}'s spec shape — this class *is* the HTTP boundary, so there is no
 * lower layer worth mocking out. Each spec asserts the request method/URL this service builds and
 * that the response envelope's {@code data} is passed through unchanged, plus the shared
 * {@code handleError} contract every admin service in this app follows.
 */
describe('AdminServiceAccountService', () => {
  let service: AdminServiceAccountService;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.apiUrl}/admin/serviceaccounts`;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AdminServiceAccountService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('list$', () => {
    it('fetches every service account', () => {
      let result: unknown;
      service.list$().subscribe((response) => (result = response.data?.serviceAccounts));

      const request = httpMock.expectOne(baseUrl);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { serviceAccounts: [{ id: 34, firstName: 'CI', lastName: 'pipeline' }] } });

      expect(result).toEqual([{ id: 34, firstName: 'CI', lastName: 'pipeline' }]);
    });
  });

  describe('create$', () => {
    it('posts the name and role and returns the created account', () => {
      let result: unknown;
      service.create$('CI pipeline', 'ROLE_MODERATOR').subscribe((response) => (result = response.data?.serviceAccount));

      const request = httpMock.expectOne(baseUrl);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ name: 'CI pipeline', role: 'ROLE_MODERATOR' });
      request.flush({ data: { serviceAccount: { id: 34, firstName: 'CI', lastName: 'pipeline' } } });

      expect(result).toEqual({ id: 34, firstName: 'CI', lastName: 'pipeline' });
    });

    it('surfaces the server-provided reason on refusal (e.g. a role above the caller\'s tier)', () => {
      let error: Error | undefined;
      service.create$('CI pipeline', 'ROLE_ADMIN').subscribe({ error: (err: Error) => (error = err) });

      httpMock
        .expectOne(baseUrl)
        .flush({ reason: 'You cannot assign a role above your own.' }, { status: 400, statusText: 'Bad Request' });

      expect(error?.message).toBe('You cannot assign a role above your own.');
    });
  });

  describe('deactivate$', () => {
    it('deletes at the account-scoped URL and returns the refreshed list', () => {
      let result: unknown;
      service.deactivate$(34).subscribe((response) => (result = response.data?.serviceAccounts));

      const request = httpMock.expectOne(`${baseUrl}/34`);
      expect(request.request.method).toBe('DELETE');
      request.flush({ data: { serviceAccounts: [{ id: 34, enabled: false }] } });

      expect(result).toEqual([{ id: 34, enabled: false }]);
    });
  });

  describe('listApiKeys$', () => {
    it('fetches the account-scoped key list', () => {
      let result: unknown;
      service.listApiKeys$(34).subscribe((response) => (result = response.data?.apiKeys));

      const request = httpMock.expectOne(`${baseUrl}/34/apikeys`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { apiKeys: [{ id: 1, name: 'GitHub Actions', revoked: false }] } });

      expect(result).toEqual([{ id: 1, name: 'GitHub Actions', revoked: false }]);
    });
  });

  describe('issueApiKey$', () => {
    it('posts the name and returns the raw key alongside the refreshed key list', () => {
      let rawKey: unknown;
      let apiKeys: unknown;
      service.issueApiKey$(34, 'GitHub Actions').subscribe((response) => {
        rawKey = response.data?.rawKey;
        apiKeys = response.data?.apiKeys;
      });

      const request = httpMock.expectOne(`${baseUrl}/34/apikeys`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ name: 'GitHub Actions', expiresAt: undefined });
      request.flush({
        data: { rawKey: 'tsk_raw', apiKeys: [{ id: 1, name: 'GitHub Actions', revoked: false }] },
      });

      expect(rawKey).toBe('tsk_raw');
      expect(apiKeys).toEqual([{ id: 1, name: 'GitHub Actions', revoked: false }]);
    });

    it('includes an explicit expiresAt when supplied', () => {
      service.issueApiKey$(34, 'GitHub Actions', '2026-12-31T23:59:59').subscribe();

      const request = httpMock.expectOne(`${baseUrl}/34/apikeys`);
      expect(request.request.body).toEqual({ name: 'GitHub Actions', expiresAt: '2026-12-31T23:59:59' });
      request.flush({ data: { apiKeys: [] } });
    });
  });

  describe('revokeApiKey$', () => {
    it('deletes at the key-scoped URL and returns the refreshed key list', () => {
      let result: unknown;
      service.revokeApiKey$(34, 1).subscribe((response) => (result = response.data?.apiKeys));

      const request = httpMock.expectOne(`${baseUrl}/34/apikeys/1`);
      expect(request.request.method).toBe('DELETE');
      request.flush({ data: { apiKeys: [{ id: 1, revoked: true }] } });

      expect(result).toEqual([{ id: 1, revoked: true }]);
    });

    it('surfaces the generic error message when the server sends no reason', () => {
      let error: Error | undefined;
      service.revokeApiKey$(34, 999).subscribe({ error: (err: Error) => (error = err) });

      httpMock.expectOne(`${baseUrl}/34/apikeys/999`).flush(null, { status: 404, statusText: 'Not Found' });

      expect(error?.message).toContain('404');
    });
  });
});
