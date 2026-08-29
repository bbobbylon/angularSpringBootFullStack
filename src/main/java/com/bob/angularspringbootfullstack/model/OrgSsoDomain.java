package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * OrgSsoDomain — one {@code organizationssodomains} row: an email domain routed to a specific
 * organization's SSO login (FUTURE-ENHANCEMENTS.md §3.1). Consumed by the public
 * {@code GET /oauth2/org-sso-lookup} endpoint so the login page can send a signing-in user straight
 * to their organization's IdP from nothing but their email address.
 *
 * <p>{@code domain} is globally unique ({@code UQ_OrgSsoDomains_Domain}) — a domain can never be
 * claimed by more than one organization — which is what makes the lookup always unambiguous, and
 * why this is a separate table from {@link OrganizationIdentityProvider} rather than a single CSV
 * column on it: an organization may claim several domains (e.g. an acquisition's old domain
 * alongside its own).
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class OrgSsoDomain {
    private Long id;
    private Long organizationId;
    private String domain;
    private LocalDateTime createdAt;
}
