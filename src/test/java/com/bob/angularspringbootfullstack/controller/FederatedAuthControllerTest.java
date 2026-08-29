package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.configuration.FederatedProviderCatalog;
import com.bob.angularspringbootfullstack.dto.OrgSsoLookupResult;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService;
import com.bob.angularspringbootfullstack.service.serviceimpl.ProviderLinkTicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for {@link FederatedAuthController#orgSsoLookup}, the public email-domain
 * discovery endpoint (FUTURE-ENHANCEMENTS.md §3.1's email-domain discovery UX). The property under
 * test is the anti-enumeration contract documented on that method: a claimed-and-active domain
 * reports the full redirect shape, and every other outcome — unclaimed domain, inactive IdP, inactive
 * organization — collapses to the identical {@code found: false}, indistinguishable from outside.
 */
@ExtendWith(MockitoExtension.class)
class FederatedAuthControllerTest {

    @Mock
    private FederatedProviderCatalog catalog;
    @Mock
    private ProviderLinkTicketService linkTicketService;
    @Mock
    private OrganizationIdentityProviderService organizationIdentityProviderService;

    @Test
    @DisplayName("a domain claimed by an active organization SSO config reports found:true with the redirect shape")
    void reportsFoundForAClaimedDomain() {
        FederatedAuthController controller = new FederatedAuthController(catalog, linkTicketService, organizationIdentityProviderService);
        when(organizationIdentityProviderService.resolveByEmailDomain("user@acme.com"))
                .thenReturn(Optional.of(new OrgSsoLookupResult("Acme Partners", "Acme Okta", "/oauth2/authorization/org-oidc-42")));

        ResponseEntity<HttpResponse> response = controller.orgSsoLookup("user@acme.com");

        assertThat(data(response))
                .containsEntry("found", true)
                .containsEntry("organizationName", "Acme Partners")
                .containsEntry("displayName", "Acme Okta")
                .containsEntry("loginUrl", "/oauth2/authorization/org-oidc-42");
    }

    @Test
    @DisplayName("an unclaimed domain reports found:false and nothing else")
    void reportsNotFoundForAnUnclaimedDomain() {
        FederatedAuthController controller = new FederatedAuthController(catalog, linkTicketService, organizationIdentityProviderService);
        when(organizationIdentityProviderService.resolveByEmailDomain("user@unknown.com")).thenReturn(Optional.empty());

        ResponseEntity<HttpResponse> response = controller.orgSsoLookup("user@unknown.com");

        assertThat(data(response))
                .containsEntry("found", false)
                .doesNotContainKeys("organizationName", "displayName", "loginUrl");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<HttpResponse> response) {
        return (Map<String, Object>) response.getBody().getData();
    }
}
