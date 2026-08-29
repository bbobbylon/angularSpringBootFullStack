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

    it('includes the org-setup fields when options are supplied', () => {
      service
        .createOrganization$('Acme Partners', {
          tenantUuid: '3fa85f64-5717-4562-b3fc-2c963f66afa6',
          mfaAllowedMethods: ['TOTP', 'PASSKEY'],
          featureFlags: ['beta'],
          customerIds: [11, 12],
          sendConfirmationEmail: true,
        })
        .subscribe();

      const request = httpMock.expectOne(orgUrl);
      expect(request.request.body).toEqual({
        name: 'Acme Partners',
        tenantUuid: '3fa85f64-5717-4562-b3fc-2c963f66afa6',
        mfaAllowedMethods: ['TOTP', 'PASSKEY'],
        featureFlags: ['beta'],
        customerIds: [11, 12],
        sendConfirmationEmail: true,
      });
      request.flush({ data: { organization: { id: 1, name: 'Acme Partners' } } });
    });
  });

  describe('setTenantUuid$', () => {
    it('patches the tenant-uuid-scoped URL', () => {
      let result: unknown;
      service.setTenantUuid$(1, '3fa85f64-5717-4562-b3fc-2c963f66afa6').subscribe((response) => (result = response.data?.organization));

      const request = httpMock.expectOne(`${orgUrl}/1/tenant-uuid`);
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ tenantUuid: '3fa85f64-5717-4562-b3fc-2c963f66afa6' });
      request.flush({ data: { organization: { id: 1, tenantUuid: '3fa85f64-5717-4562-b3fc-2c963f66afa6' } } });

      expect(result).toEqual({ id: 1, tenantUuid: '3fa85f64-5717-4562-b3fc-2c963f66afa6' });
    });
  });

  describe('updateOrganizationSettings$', () => {
    it('patches the settings-scoped URL with the full replacement policy and flags', () => {
      service.updateOrganizationSettings$(1, ['SMS'], ['beta']).subscribe();

      const request = httpMock.expectOne(`${orgUrl}/1/settings`);
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ mfaAllowedMethods: ['SMS'], featureFlags: ['beta'] });
      request.flush({ data: { organization: { id: 1 } } });
    });
  });

  describe('orgCustomers$', () => {
    it('fetches the organization-scoped customer list', () => {
      let result: unknown;
      service.orgCustomers$(9).subscribe((response) => (result = response.data?.customers));

      const request = httpMock.expectOne(`${orgUrl}/9/customers`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { customers: [{ id: 11, customerName: 'Jane Co' }] } });

      expect(result).toEqual([{ id: 11, customerName: 'Jane Co' }]);
    });
  });

  describe('orgInvoices$', () => {
    it('fetches the organization-scoped invoice list', () => {
      let result: unknown;
      service.orgInvoices$(9).subscribe((response) => (result = response.data?.invoices));

      const request = httpMock.expectOne(`${orgUrl}/9/invoices`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { invoices: [{ id: 21, invoiceNumber: 'A3F9KQ2B' }] } });

      expect(result).toEqual([{ id: 21, invoiceNumber: 'A3F9KQ2B' }]);
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
    it('fetches the active members of one organization, alongside their org roles', () => {
      let members: unknown;
      let orgRoles: unknown;
      service.members$(9).subscribe((response) => {
        members = response.data?.members;
        orgRoles = response.data?.orgRoles;
      });

      const request = httpMock.expectOne(`${orgUrl}/9/members`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { members: [{ id: 42, email: 'member@example.com' }], orgRoles: { 42: 'ORG_ADMIN' } } });

      expect(members).toEqual([{ id: 42, email: 'member@example.com' }]);
      expect(orgRoles).toEqual({ 42: 'ORG_ADMIN' });
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

    it('includes the orgRole query param when a capacity is supplied', () => {
      service.addMember$(9, 42, 'ORG_VIEWER').subscribe();

      const request = httpMock.expectOne(`${orgUrl}/9/members/42?orgRole=ORG_VIEWER`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({});
      request.flush({ message: 'Member added successfully.' });
    });
  });

  describe('setMemberOrgRole$', () => {
    it('patches the member-scoped role URL with the orgRole query param', () => {
      service.setMemberOrgRole$(9, 42, 'ORG_ADMIN').subscribe();

      const request = httpMock.expectOne(`${orgUrl}/9/members/42/role?orgRole=ORG_ADMIN`);
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({});
      request.flush({ message: 'Member role updated successfully.' });
    });

    it('surfaces the server-provided reason on refusal (e.g. the last-admin guard)', () => {
      let error: Error | undefined;
      service.setMemberOrgRole$(9, 42, 'ORG_MEMBER').subscribe({ error: (err: Error) => (error = err) });

      httpMock
        .expectOne(`${orgUrl}/9/members/42/role?orgRole=ORG_MEMBER`)
        .flush({ reason: 'An organization must keep at least one active administrator.' }, { status: 400, statusText: 'Bad Request' });

      expect(error?.message).toBe('An organization must keep at least one active administrator.');
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

  describe('updateOrganizationProfile$', () => {
    it('patches the profile fields at the organization-scoped URL', () => {
      let result: unknown;
      service
        .updateOrganizationProfile$(1, 'A description', 'contact@acme.test', 'https://acme.test')
        .subscribe((response) => (result = response.data?.organization));

      const request = httpMock.expectOne(`${orgUrl}/1/profile`);
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({
        description: 'A description',
        contactEmail: 'contact@acme.test',
        website: 'https://acme.test',
      });
      request.flush({ data: { organization: { id: 1, name: 'Acme', description: 'A description' } } });

      expect(result).toEqual({ id: 1, name: 'Acme', description: 'A description' });
    });
  });

  describe('orgStats$', () => {
    it('fetches one organization\'s KPI tiles', () => {
      let result: unknown;
      service.orgStats$(9).subscribe((response) => (result = response.data?.stats));

      const request = httpMock.expectOne(`${orgUrl}/9/stats`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { stats: { memberCount: 3, stats: { totalCustomers: 5, totalInvoices: 8, totalBilled: 100 }, statusBreakdown: {} } } });

      expect(result).toEqual({ memberCount: 3, stats: { totalCustomers: 5, totalInvoices: 8, totalBilled: 100 }, statusBreakdown: {} });
    });
  });

  describe('orgEvents$', () => {
    it('fetches a page of the audit trail with default paging', () => {
      let result: unknown;
      service.orgEvents$(9).subscribe((response) => (result = response.data?.events));

      const request = httpMock.expectOne(`${orgUrl}/9/events?page=0&size=20`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { events: [{ id: 1, type: 'ORG_CREATED' }], totalEvents: 1 } });

      expect(result).toEqual([{ id: 1, type: 'ORG_CREATED' }]);
    });

    it('honors an explicit page and size', () => {
      service.orgEvents$(9, 2, 5).subscribe();

      const request = httpMock.expectOne(`${orgUrl}/9/events?page=2&size=5`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { events: [], totalEvents: 0 } });
    });
  });

  describe('createInvite$', () => {
    it('posts the role and ttl and returns the refreshed invite list', () => {
      let result: unknown;
      service.createInvite$(9, 'ROLE_USER', 72).subscribe((response) => (result = response.data?.invites));

      const request = httpMock.expectOne(`${orgUrl}/9/invites`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ roleName: 'ROLE_USER', ttlHours: 72 });
      request.flush({ data: { invite: { id: 1, code: 'abc' }, invites: [{ id: 1, code: 'abc' }] } });

      expect(result).toEqual([{ id: 1, code: 'abc' }]);
    });

    it('omits roleName/ttlHours when not supplied, letting the backend default them', () => {
      service.createInvite$(9).subscribe();

      const request = httpMock.expectOne(`${orgUrl}/9/invites`);
      expect(request.request.body).toEqual({ roleName: undefined, ttlHours: undefined });
      request.flush({ data: { invites: [] } });
    });
  });

  describe('activeInvites$', () => {
    it('fetches the organization\'s outstanding invites', () => {
      let result: unknown;
      service.activeInvites$(9).subscribe((response) => (result = response.data?.invites));

      const request = httpMock.expectOne(`${orgUrl}/9/invites`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { invites: [{ id: 1, code: 'abc' }] } });

      expect(result).toEqual([{ id: 1, code: 'abc' }]);
    });
  });

  describe('revokeInvite$', () => {
    it('deletes at the invite-scoped URL and returns the refreshed invite list', () => {
      let result: unknown;
      service.revokeInvite$(9, 1).subscribe((response) => (result = response.data?.invites));

      const request = httpMock.expectOne(`${orgUrl}/9/invites/1`);
      expect(request.request.method).toBe('DELETE');
      request.flush({ data: { invites: [] } });

      expect(result).toEqual([]);
    });
  });

  describe('previewInvite$', () => {
    it('resolves a live code to its organization name via the /user/organization path', () => {
      let result: unknown;
      service.previewInvite$('live').subscribe((response) => (result = response.data?.organizationName));

      const request = httpMock.expectOne(`${environment.apiUrl}/user/organization/invite/live`);
      expect(request.request.method).toBe('GET');
      request.flush({ data: { organizationName: 'Acme' } });

      expect(result).toBe('Acme');
    });

    it('surfaces the generic not-found reason for an unknown or expired code', () => {
      let error: Error | undefined;
      service.previewInvite$('bogus').subscribe({ error: (err: Error) => (error = err) });

      httpMock
        .expectOne(`${environment.apiUrl}/user/organization/invite/bogus`)
        .flush({ reason: 'This invite link is invalid or has expired.' }, { status: 400, statusText: 'Bad Request' });

      expect(error?.message).toBe('This invite link is invalid or has expired.');
    });
  });

  describe('redeemInvite$', () => {
    it('posts to the redeem URL with no body and returns the joined organization', () => {
      let result: unknown;
      service.redeemInvite$('live').subscribe((response) => (result = response.data?.organization));

      const request = httpMock.expectOne(`${environment.apiUrl}/user/organization/invite/live/redeem`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({});
      request.flush({ data: { organization: { id: 9, name: 'Acme' } } });

      expect(result).toEqual({ id: 9, name: 'Acme' });
    });
  });
});
