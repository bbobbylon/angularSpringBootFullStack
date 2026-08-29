package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for {@code PUT /admin/organization/{id}/sso} — configuring or replacing an
 * organization's identity provider, OIDC or SAML (FUTURE-ENHANCEMENTS.md §3.1).
 * <p>
 * {@link #clientSecret} is intentionally optional: leaving it blank on an update keeps the
 * previously stored secret unchanged (see {@code OrganizationIdentityProviderService#upsertOidcConfig}),
 * so an admin can correct the display name or issuer URI without re-entering a secret the UI never
 * shows back to them in the first place.
 * <p>
 * {@link #issuerUri}/{@link #clientId}/{@link #clientSecret} and {@link #metadataUri} are mutually
 * exclusive depending on {@link #protocol} — neither pair can be marked {@code @NotBlank} here, since
 * which one is required depends on a sibling field's value, something bean validation cannot express
 * across fields on its own. {@code OrganizationIdentityProviderController} routes to
 * {@code upsertOidcConfig} or {@code upsertSamlConfig} based on {@link #protocol}, and each of those
 * methods enforces its own protocol's required fields (mirroring how the first-time-secret-required
 * rule already lived in the service layer, not this form, before SAML existed).
 */
@Data
public class OrganizationIdentityProviderForm {

    @NotBlank(message = "Display name is required")
    @Size(max = 100, message = "Display name must be 100 characters or fewer")
    private String displayName;

    /** {@code "OIDC"} or {@code "SAML"}; blank/omitted defaults to {@code "OIDC"} for backward compatibility. */
    @Size(max = 10, message = "Protocol must be 10 characters or fewer")
    private String protocol;

    @Size(max = 500, message = "Issuer URI must be 500 characters or fewer")
    private String issuerUri;

    @Size(max = 255, message = "Client ID must be 255 characters or fewer")
    private String clientId;

    /** Blank keeps the existing stored secret unchanged; required the first time OIDC SSO is configured. */
    @Size(max = 500, message = "Client secret must be 500 characters or fewer")
    private String clientSecret;

    /** The IdP's SAML metadata document location; required when {@link #protocol} is {@code "SAML"}. */
    @Size(max = 500, message = "Metadata URI must be 500 characters or fewer")
    private String metadataUri;
}
