import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { OrganizationService } from './organization.service';
import { environment } from '../../environments/environment';

/**
 * Specs for {@link OrganizationService} — the HTTP boundary for
 * {@code OrganizationController}'s Organization CRUD + membership management endpoints
 * (FUTURE-ENHANCEMENTS.md §3.2).
 *
 * <p>Driven through the real {@code HttpClient} against {@link HttpTestingController}, mirroring
 * {@code FavoriteService}'s spec shape — this class *is* the HTTP boundary, so there is no lower
 * layer worth mocking out. Each spec asserts the request method/URL this service builds and that
 * the response envelope's {@code data} is passed through unchanged, plus the shared
 * {@code handleError} contract every admin service in this app follows.
 */
describe('OrganizationService', () => {
  let service: OrganizationService;
  let httpMock: HttpTestingController;

  const orgUrl = `${environment.apiUrl}/admin/organization`;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(OrganizationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('organizations$', () => {
    it('fetches the in-scope catalog', () => {
      let result: unknown;
      service.organizations$().subscribe((response) => (result = response.data?.organizations));

      const request = httpMock.expectOne(orgUrl);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { organizations: [{ id: 1, name: 'Acme', status: 'ACTIVE' }] } });

      expect(result).toEqual([{ id: 1, name: 'Acme', status: 'ACTIVE' }]);
    });
  });

  describe('createOrganization$', () => {
    it('posts the name and returns the created organization', () => {
      let result: unknown;
      service.createOrganization$('Acme Partners').subscribe((response) => (result = response.data?.organization));

      const request = httpMock.expectOne(orgUrl);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ name: 'Acme Partners' });
      request.flush({ data: { organization: { id: 1, name: 'Acme Partners', status: 'ACTIVE' } } });

      expect(result).toEqual({ id: 1, name: 'Acme Partners', status: 'ACTIVE' });
    });

    it('surfaces the server-provided reason on failure (e.g. a duplicate name)', () => {
      let error: Error | undefined;
      service.createOrganization$('Acme').subscribe({ error: (err: Error) => (error = err) });

      httpMock
        .expectOne(orgUrl)
        .flush({ reason: "An organization named 'Acme' already exists." }, { status: 400, statusText: 'Bad Request' });

      expect(error?.message).toBe("An organization named 'Acme' already exists.");
    });
  });

  describe('renameOrganization$', () => {
    it('patches the new name at the organization-scoped URL', () => {
      service.renameOrganization$(1, 'New Name').subscribe();

      const request = httpMock.expectOne(`${orgUrl}/1/name`);
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ name: 'New Name' });
      request.flush({ data: { organizations: [] } });
    });
  });

  describe('setOrganizationStatus$', () => {
    it('patches the status at the organization-scoped URL', () => {
      service.setOrganizationStatus$(1, 'INACTIVE').subscribe();

      const request = httpMock.expectOne(`${orgUrl}/1/status`);
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ status: 'INACTIVE' });
      request.flush({ data: { organizations: [] } });
    });
  });

  describe('members$', () => {
    it('fetches the active members of one organization', () => {
      let result: unknown;
      service.members$(9).subscribe((response) => (result = response.data?.members));

      const request = httpMock.expectOne(`${orgUrl}/9/members`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { members: [{ id: 42, email: 'member@example.com' }] } });

      expect(result).toEqual([{ id: 42, email: 'member@example.com' }]);
    });
  });

  describe('addMember$', () => {
    it('posts to the member-scoped URL with no body', () => {
      service.addMember$(9, 42).subscribe();

      const request = httpMock.expectOne(`${orgUrl}/9/members/42`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({});
      request.flush({ message: 'Member added successfully.' });
    });
  });

  describe('removeMember$', () => {
    it('deletes at the member-scoped URL', () => {
      service.removeMember$(9, 42).subscribe();

      const request = httpMock.expectOne(`${orgUrl}/9/members/42`);
      expect(request.request.method).toBe('DELETE');
      request.flush({ message: 'Member removed successfully.' });
    });
  });
});
