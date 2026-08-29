package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.RoleType;
import com.bob.angularspringbootfullstack.event.NewOrganizationEvent;
import com.bob.angularspringbootfullstack.form.OrgSsoDomainForm;
import com.bob.angularspringbootfullstack.form.OrganizationIdentityProviderForm;
import com.bob.angularspringbootfullstack.form.OrganizationStatusForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.OrgSsoDomain;
import com.bob.angularspringbootfullstack.model.OrganizationIdentityProvider;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_SSO_CONFIGURED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_SSO_DOMAIN_ADDED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_SSO_DOMAIN_REMOVED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_SSO_REMOVED;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.springframework.http.HttpStatus.OK;

/**
 * Manages one organization's external IdP configuration for single sign-on and the email domains
 * routed to it (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external IdP", Stage 1).
 * <p>
 * Sibling to {@link OrganizationController}, split into its own controller because this is
 * meaningfully more sensitive than the rest of organization administration: a misconfigured or
 * maliciously configured IdP can sign an attacker into the organization as an auto-joined member
 * (see {@code OrganizationIdentityProviderService}'s Javadoc and Stage 2's auto-join flow). Gating
 * therefore requires organization-admin authority specifically, not bare membership — the same
 * {@link OrganizationService#isOrgAdminOf} check {@link OrganizationController} uses for membership
 * mutations, applied here to every operation rather than just some.
 * <p>
 * Every endpoint returns the standard {@code ResponseEntity<HttpResponse>} envelope and publishes a
 * {@link NewOrganizationEvent} on mutation, so SSO configuration changes show up in the
 * organization's own audit trail ({@code organizationevents}) exactly like every other
 * administrative action on the organization.
 */
@RestController
@RequestMapping(path = "/admin/organization/{organizationId}/sso")
@RequiredArgsConstructor
@Slf4j
public class OrganizationIdentityProviderController {

    private final OrganizationIdentityProviderService ssoService;
    private final OrganizationService organizationService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Fetches an organization's SSO configuration and its claimed domains together, so the admin
     * frontend's SSO tab can render its full state from a single request.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization to inspect
     * @return 200 OK with {@code config} (nullable) and {@code domains}
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @GetMapping
    public ResponseEntity<HttpResponse> getConfig(Authentication authentication, @PathVariable Long organizationId) {
        requireOrgAdmin(authentication, organizationId);
        // Map.of rejects null values, and a not-yet-configured organization legitimately has no
        // config row — a LinkedHashMap keeps that state representable in the response body.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("config", ssoService.getConfig(organizationId).orElse(null));
        data.put("domains", ssoService.listDomains(organizationId));
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(data)
                        .message("Identity provider configuration retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Creates or replaces an organization's identity provider configuration — OIDC or SAML,
     * depending on {@link OrganizationIdentityProviderForm#getProtocol()} (FUTURE-ENHANCEMENTS.md
     * §3.1, Stage 3). Blank/omitted protocol defaults to {@code "OIDC"}, matching this endpoint's
     * behavior before SAML support existed, so existing callers are unaffected.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization being configured
     * @param form           the protocol and its corresponding credentials/metadata, plus display name
     * @return 200 OK with the saved configuration
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PutMapping
    public ResponseEntity<HttpResponse> upsertConfig(Authentication authentication, @PathVariable Long organizationId,
                                                       @RequestBody @Valid OrganizationIdentityProviderForm form) {
        requireOrgAdmin(authentication, organizationId);
        String protocol = isBlank(form.getProtocol()) ? "OIDC" : form.getProtocol().trim().toUpperCase();
        OrganizationIdentityProvider saved = "SAML".equals(protocol)
                ? ssoService.upsertSamlConfig(organizationId, form.getDisplayName(), form.getMetadataUri())
                : ssoService.upsertOidcConfig(
                        organizationId, form.getDisplayName(), form.getIssuerUri(), form.getClientId(), form.getClientSecret());
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_SSO_CONFIGURED, saved.getDisplayName()));
        log.info("'{}' configured SSO for organization id {}", caller.getEmail(), organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("config", saved))
                        .message("Identity provider configuration saved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Activates or deactivates an organization's SSO configuration without deleting it.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization whose configuration is being toggled
     * @param form           the new status ({@code "ACTIVE"} or {@code "INACTIVE"})
     * @return 200 OK with the updated configuration
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PatchMapping("/status")
    public ResponseEntity<HttpResponse> setStatus(Authentication authentication, @PathVariable Long organizationId,
                                                    @RequestBody @Valid OrganizationStatusForm form) {
        requireOrgAdmin(authentication, organizationId);
        OrganizationIdentityProvider updated = ssoService.setStatus(organizationId, form.getStatus());
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_SSO_CONFIGURED,
                "status set to " + updated.getStatus()));
        log.info("'{}' set organization id {} SSO status to '{}'", caller.getEmail(), organizationId, updated.getStatus());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("config", updated))
                        .message("Identity provider status updated successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Removes an organization's SSO configuration entirely. Its members fall back to ordinary
     * password or consumer-OAuth login on their next sign-in.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization whose configuration is being removed
     * @return 200 OK
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @DeleteMapping
    public ResponseEntity<HttpResponse> deleteConfig(Authentication authentication, @PathVariable Long organizationId) {
        requireOrgAdmin(authentication, organizationId);
        ssoService.deleteConfig(organizationId);
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_SSO_REMOVED, null));
        log.info("'{}' removed SSO configuration for organization id {}", caller.getEmail(), organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("Identity provider configuration removed successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Claims an email domain for this organization's SSO routing.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization claiming the domain
     * @param form           the domain to claim
     * @return 200 OK with the created domain row
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @PostMapping("/domains")
    public ResponseEntity<HttpResponse> addDomain(Authentication authentication, @PathVariable Long organizationId,
                                                    @RequestBody @Valid OrgSsoDomainForm form) {
        requireOrgAdmin(authentication, organizationId);
        OrgSsoDomain created = ssoService.addDomain(organizationId, form.getDomain());
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_SSO_DOMAIN_ADDED, created.getDomain()));
        log.info("'{}' added SSO domain '{}' to organization id {}", caller.getEmail(), created.getDomain(), organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("domain", created))
                        .message("Domain added successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Releases a domain from this organization's SSO routing.
     *
     * @param authentication the calling administrator's authentication
     * @param organizationId the organization the domain must currently belong to
     * @param domainId       the domain row to remove
     * @return 200 OK
     */
    @PreAuthorize("hasAuthority('UPDATE:ORGANIZATION')")
    @DeleteMapping("/domains/{domainId}")
    public ResponseEntity<HttpResponse> removeDomain(Authentication authentication, @PathVariable Long organizationId,
                                                       @PathVariable Long domainId) {
        requireOrgAdmin(authentication, organizationId);
        ssoService.removeDomain(organizationId, domainId);
        UserDTO caller = getAuthenticatedUser(authentication);
        eventPublisher.publishEvent(new NewOrganizationEvent(organizationId, caller.getId(), ORG_SSO_DOMAIN_REMOVED, null));
        log.info("'{}' removed SSO domain id {} from organization id {}", caller.getEmail(), domainId, organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("Domain removed successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Refuses every operation on this controller unless the caller is an unscoped tier, or a
     * {@code ROLE_ORGANIZATION_ADMIN} who actively administers {@code organizationId} themselves —
     * mirrors {@code OrganizationController#requireMembershipAuthority}, but applied unconditionally
     * (that sibling method allows plain membership for some read endpoints; SSO configuration is
     * sensitive enough that even read access requires admin authority here).
     */
    private void requireOrgAdmin(Authentication authentication, Long organizationId) {
        UserDTO caller = getAuthenticatedUser(authentication);
        if (!RoleType.isOrganizationScoped(caller.getRoleName())) {
            return;
        }
        if (organizationService.isOrgAdminOf(caller.getId(), organizationId)) {
            return;
        }
        log.warn("Caller '{}' (role {}) denied an SSO operation on organization {} — not an ORG_ADMIN there",
                caller.getEmail(), caller.getRoleName(), organizationId);
        throw new AccessDeniedException("You may only manage single sign-on for organizations you administer.");
    }
}
