import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { OrganizationSsoService } from './organization-sso.service';
import { environment } from '../../environments/environment';

/**
 * Specs for {@link OrganizationSsoService} — the HTTP boundary for
 * {@code OrganizationIdentityProviderController}'s per-organization SSO configuration and domain
 * endpoints (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external IdP", Stage 1).
 *
 * <p>Driven through the real {@code HttpClient} against {@link HttpTestingController}, mirroring
 * {@code OrganizationService}'s spec shape — this class *is* the HTTP boundary, so there is no
 * lower layer worth mocking out.
 */
describe('OrganizationSsoService', () => {
  let service: OrganizationSsoService;
  let httpMock: HttpTestingController;

  const ssoUrl = `${environment.apiUrl}/admin/organization/9/sso`;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(OrganizationSsoService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('getConfig$', () => {
    it('fetches the configuration and its claimed domains together', () => {
      let config: unknown;
      let domains: unknown;
      service.getConfig$(9).subscribe((response) => {
        config = response.data?.config;
        domains = response.data?.domains;
      });

      const request = httpMock.expectOne(ssoUrl);
      expect(request.request.method).toBe('GET');
      request.flush({
        data: {
          config: { id: 1, organizationId: 9, protocol: 'OIDC', status: 'ACTIVE', secretConfigured: true },
          domains: [{ id: 1, organizationId: 9, domain: 'acme.com' }],
        },
      });

      expect(config).toEqual({ id: 1, organizationId: 9, protocol: 'OIDC', status: 'ACTIVE', secretConfigured: true });
      expect(domains).toEqual([{ id: 1, organizationId: 9, domain: 'acme.com' }]);
    });

    it('resolves an undefined config for an organization that has never configured SSO', () => {
      let config: unknown = 'unset';
      service.getConfig$(9).subscribe((response) => (config = response.data?.config));

      httpMock.expectOne(ssoUrl).flush({ data: { config: null, domains: [] } });

      expect(config).toBeNull();
    });
  });

  describe('upsertConfig$', () => {
    it('puts the full OIDC form including a new client secret', () => {
      let result: unknown;
      service
        .upsertConfig$(9, 'OIDC', 'Acme Okta', 'https://acme.okta.com', 'client-id', 's3cret')
        .subscribe((response) => (result = response.data?.config));

      const request = httpMock.expectOne(ssoUrl);
      expect(request.request.method).toBe('PUT');
      expect(request.request.body).toEqual({
        protocol: 'OIDC',
        displayName: 'Acme Okta',
        issuerUri: 'https://acme.okta.com',
        clientId: 'client-id',
        clientSecret: 's3cret',
        metadataUri: undefined,
      });
      request.flush({ data: { config: { id: 1, displayName: 'Acme Okta', secretConfigured: true } } });

      expect(result).toEqual({ id: 1, displayName: 'Acme Okta', secretConfigured: true });
    });

    it('omits the client secret when editing an OIDC configuration without replacing it', () => {
      service.upsertConfig$(9, 'OIDC', 'Acme Okta', 'https://acme.okta.com', 'client-id').subscribe();

      const request = httpMock.expectOne(ssoUrl);
      expect(request.request.body).toEqual({
        protocol: 'OIDC',
        displayName: 'Acme Okta',
        issuerUri: 'https://acme.okta.com',
        clientId: 'client-id',
        clientSecret: undefined,
        metadataUri: undefined,
      });
      request.flush({ data: { config: { id: 1 } } });
    });

    it('puts a SAML configuration with the metadata URI, sending no OIDC fields', () => {
      let result: unknown;
      service
        .upsertConfig$(9, 'SAML', 'Acme Okta', undefined, undefined, undefined, 'https://acme.okta.com/metadata')
        .subscribe((response) => (result = response.data?.config));

      const request = httpMock.expectOne(ssoUrl);
      expect(request.request.method).toBe('PUT');
      expect(request.request.body).toEqual({
        protocol: 'SAML',
        displayName: 'Acme Okta',
        issuerUri: undefined,
        clientId: undefined,
        clientSecret: undefined,
        metadataUri: 'https://acme.okta.com/metadata',
      });
      request.flush({ data: { config: { id: 1, displayName: 'Acme Okta', protocol: 'SAML' } } });

      expect(result).toEqual({ id: 1, displayName: 'Acme Okta', protocol: 'SAML' });
    });

    it('surfaces the server-provided reason on refusal (e.g. missing secret on first configuration)', () => {
      let error: Error | undefined;
      service
        .upsertConfig$(9, 'OIDC', 'Acme Okta', 'https://acme.okta.com', 'client-id')
        .subscribe({ error: (err: Error) => (error = err) });

      httpMock
        .expectOne(ssoUrl)
        .flush({ reason: 'A client secret is required the first time this organization is configured.' }, { status: 400, statusText: 'Bad Request' });

      expect(error?.message).toBe('A client secret is required the first time this organization is configured.');
    });
  });

  describe('setStatus$', () => {
    it('patches the status-scoped URL', () => {
      let result: unknown;
      service.setStatus$(9, 'INACTIVE').subscribe((response) => (result = response.data?.config));

      const request = httpMock.expectOne(`${ssoUrl}/status`);
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ status: 'INACTIVE' });
      request.flush({ data: { config: { id: 1, status: 'INACTIVE' } } });

      expect(result).toEqual({ id: 1, status: 'INACTIVE' });
    });
  });

  describe('deleteConfig$', () => {
    it('deletes at the configuration-scoped URL', () => {
      service.deleteConfig$(9).subscribe();

      const request = httpMock.expectOne(ssoUrl);
      expect(request.request.method).toBe('DELETE');
      request.flush({ message: 'Identity provider configuration removed successfully.' });
    });
  });

  describe('addDomain$', () => {
    it('posts the domain and returns the created row', () => {
      let result: unknown;
      service.addDomain$(9, 'acme.com').subscribe((response) => (result = response.data?.domain));

      const request = httpMock.expectOne(`${ssoUrl}/domains`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ domain: 'acme.com' });
      request.flush({ data: { domain: { id: 1, organizationId: 9, domain: 'acme.com' } } });

      expect(result).toEqual({ id: 1, organizationId: 9, domain: 'acme.com' });
    });

    it('surfaces the server-provided reason when the domain is already claimed elsewhere', () => {
      let error: Error | undefined;
      service.addDomain$(9, 'acme.com').subscribe({ error: (err: Error) => (error = err) });

      httpMock
        .expectOne(`${ssoUrl}/domains`)
        .flush({ reason: 'This domain is already claimed by another organization.' }, { status: 400, statusText: 'Bad Request' });

      expect(error?.message).toBe('This domain is already claimed by another organization.');
    });
  });

  describe('removeDomain$', () => {
    it('deletes at the domain-scoped URL', () => {
      service.removeDomain$(9, 1).subscribe();

      const request = httpMock.expectOne(`${ssoUrl}/domains/1`);
      expect(request.request.method).toBe('DELETE');
      request.flush({ message: 'Domain removed successfully.' });
    });
  });
});
