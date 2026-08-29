package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * OrganizationIdentityProvider — the {@code organizationidentityproviders} row describing an
 * organization's own external IdP for single sign-on (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization
 * external IdP"). One row per organization ({@code UQ_OrgIdP_Organization}); an org replaces its row
 * to switch providers rather than layering several.
 *
 * <p>Deliberately never carries the decrypted client secret — {@code oidc_client_secret_ciphertext}
 * (the AES-256-GCM blob {@link com.bob.angularspringbootfullstack.utils.EncryptionUtil} produces)
 * stays out of this model entirely; {@code OrganizationIdentityProviderServiceImpl} decrypts it only
 * at the point of use (building a {@code ClientRegistration} for login), and every read-facing
 * method exposes {@link #secretConfigured} instead, matching the rest of the codebase's discipline
 * around never re-exposing a stored secret (see how passwords and TOTP recovery codes are handled).
 *
 * <p>{@code samlMetadataUri} is reserved for the SAML follow-up (Stage 3) and unused while
 * {@link #protocol} is {@code "OIDC"}.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class OrganizationIdentityProvider {
    private Long id;
    private Long organizationId;
    /** {@code "OIDC"} or {@code "SAML"} (schema's {@code CK_OrgIdP_Protocol}). Only OIDC is wired today. */
    private String protocol;
    /** Shown on the login page's SSO redirect affordance. */
    private String displayName;
    /** {@code "ACTIVE"} or {@code "INACTIVE"} (schema's {@code CK_OrgIdP_Status}). */
    private String status;
    private String oidcIssuerUri;
    private String oidcClientId;
    /** True when a client secret is stored, without ever revealing it. */
    private boolean secretConfigured;
    private String samlMetadataUri;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
