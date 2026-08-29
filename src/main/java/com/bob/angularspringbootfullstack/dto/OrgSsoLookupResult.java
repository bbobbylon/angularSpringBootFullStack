package com.bob.angularspringbootfullstack.dto;

/**
 * The public, non-enumerating answer to "does this email's domain belong to an organization with
 * SSO configured?" (FUTURE-ENHANCEMENTS.md §3.1's email-domain discovery UX,
 * {@code GET /oauth2/org-sso-lookup}).
 *
 * <p>Deliberately minimal: {@code organizationName}/{@code displayName} are shown on the login
 * page's redirect prompt, and {@code loginUrl} is where the browser is sent next
 * (Spring Security's {@code /oauth2/authorization/{registrationId}} entry point). Nothing here
 * reveals whether any particular <em>account</em> exists — only whether the domain itself is
 * SSO-enabled, the same non-enumeration discipline
 * {@code FederatedAuthController#getFederatedProviders} already applies to the three consumer OAuth
 * providers.
 *
 * @param organizationName the organization's display name
 * @param displayName      the IdP's own display name, as configured by the organization's admin
 * @param loginUrl         the URL the browser should navigate to in order to start the SSO flow
 */
public record OrgSsoLookupResult(String organizationName, String displayName, String loginUrl) {
}
