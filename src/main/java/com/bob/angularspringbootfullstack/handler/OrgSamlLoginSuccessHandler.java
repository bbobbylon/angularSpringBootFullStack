package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.service.FederatedIdentityService;
import com.bob.angularspringbootfullstack.service.FederatedLoginCompletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * The SAML sibling of {@link OAuth2LoginSuccessHandler} (FUTURE-ENHANCEMENTS.md §3.1, Stage 3): the
 * token-exchange point for a per-organization SAML 2.0 login.
 *
 * <p>Spring Security's SAML2 login has already validated the IdP's signed assertion by the time this
 * handler runs — signature verification, audience/recipient checks, and condition (NotBefore/
 * NotOnOrAfter) enforcement all happened upstream, against the {@code RelyingPartyRegistration}
 * {@link com.bob.angularspringbootfullstack.configuration.OrgAwareRelyingPartyRegistrationRepository}
 * resolved from the organization's stored metadata. What remains, and what this class owns, mirrors
 * {@link OAuth2LoginSuccessHandler} exactly except for the extraction step:
 *
 * <ol>
 *   <li>read the verified {@code NameID} and whatever profile attributes the IdP included in the
 *       assertion — unlike OIDC, SAML has no single standard claim schema, so common attribute name
 *       variants are tried in order and the {@code NameID} itself is the email fallback (many IdPs are
 *       configured with the email NameID format);</li>
 *   <li>find-or-create the local user via {@link FederatedIdentityService}, identically to the OIDC
 *       path;</li>
 *   <li>hand off to the same {@link FederatedLoginCompletionService} the OIDC handler uses for
 *       auto-join, account-state/MFA policy, token issuance, and the SPA redirect — there is nothing
 *       protocol-specific left to do once a verified local user exists.</li>
 * </ol>
 *
 * <p>The account-link handshake ({@code FederatedAuthController#startLink}) is intentionally
 * OIDC/OAuth2-only and is not replicated here: linking an *additional* identity to an already
 * signed-in account is a consumer-provider feature (connect your Google account from Security
 * Center), not something a per-organization enterprise IdP needs — an org's SAML login is the
 * account, not an add-on to one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrgSamlLoginSuccessHandler implements AuthenticationSuccessHandler {

    /**
     * Candidate SAML attribute names for the user's email, tried in order. Covers the informal
     * {@code email}/{@code emailAddress} names several IdPs use directly, Microsoft ADFS's claims URI,
     * and the formal X.500 OID SAML attribute profile ({@code urn:oid:0.9.2342.19200300.100.1.3} =
     * {@code mail}) that Okta and Azure AD emit by default.
     */
    private static final String[] EMAIL_ATTRIBUTES = {
            "email", "emailAddress",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
            "urn:oid:0.9.2342.19200300.100.1.3"
    };

    /** Candidate SAML attribute names for the given (first) name, same multi-IdP-shape reasoning. */
    private static final String[] GIVEN_NAME_ATTRIBUTES = {"givenName", "given_name", "urn:oid:2.5.4.42"};

    /** Candidate SAML attribute names for the surname (last name). */
    private static final String[] SURNAME_ATTRIBUTES = {"surname", "sn", "family_name", "urn:oid:2.5.4.4"};

    private final FederatedIdentityService federatedIdentityService;
    private final FederatedLoginCompletionService federatedLoginCompletionService;

    /** The SPA origin (env {@code UI_APP_URL}), used only for the failure-path redirect. */
    @Value("${ui.app.url:http://localhost:4200}")
    private String uiAppUrl;

    /**
     * Completes a SAML login per the class contract: resolve the local user from the verified
     * assertion, then delegate to {@link FederatedLoginCompletionService} for account policy, MFA, and
     * token issuance.
     *
     * @param request        the ACS callback request
     * @param response       the response used for the redirect to the SPA
     * @param authentication the {@link Saml2Authentication} built by Spring Security from the
     *                       verified assertion
     */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            Saml2Authentication samlAuthentication = (Saml2Authentication) authentication;
            Saml2AuthenticatedPrincipal principal = (Saml2AuthenticatedPrincipal) samlAuthentication.getPrincipal();
            String provider = principal.getRelyingPartyRegistrationId();
            FederatedProfile profile = extractProfile(principal);

            UserDTO userDTO = federatedIdentityService.findOrCreateFederatedUser(
                    provider, profile.subject(), profile.email(), profile.firstName(), profile.lastName(), null);

            federatedLoginCompletionService.completeLogin(provider, userDTO, request, response);
        } catch (Exception exception) {
            // Coarse error code only, matching OAuth2LoginSuccessHandler's NFR-SEC-7 discipline —
            // nothing in the redirect discloses whether an account or organization exists.
            log.error("SAML federated login post-processing failed: {}", exception.getMessage(), exception);
            response.sendRedirect(uiAppUrl + "/login?error=federated");
        }
    }

    /**
     * Builds the provider-neutral profile from the verified assertion. The durable identity key is
     * always (registration id, {@code NameID}) — never the email — mirroring
     * {@link OAuth2LoginSuccessHandler}'s subject-not-email discipline; the email is only used to
     * pre-fill a newly created local account.
     */
    private FederatedProfile extractProfile(Saml2AuthenticatedPrincipal principal) {
        String nameId = principal.getName();
        String email = firstAttribute(principal, EMAIL_ATTRIBUTES);
        if (isBlank(email)) {
            // Many IdPs are configured with the email NameID format, making the NameID itself
            // the best available fallback rather than leaving the local account without an email.
            email = nameId;
        }
        String firstName = firstAttribute(principal, GIVEN_NAME_ATTRIBUTES);
        String lastName = firstAttribute(principal, SURNAME_ATTRIBUTES);
        return new FederatedProfile(
                nameId,
                email,
                isBlank(firstName) ? "Organization" : firstName,
                isBlank(lastName) ? "Member" : lastName);
    }

    /** Returns the first non-blank single-valued attribute among the given candidate names, or null. */
    private static String firstAttribute(Saml2AuthenticatedPrincipal principal, String... candidateNames) {
        for (String name : candidateNames) {
            List<Object> values = principal.getAttribute(name);
            if (values != null && !values.isEmpty() && values.get(0) != null) {
                String value = String.valueOf(values.get(0));
                if (!isBlank(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private record FederatedProfile(String subject, String email, String firstName, String lastName) {
    }
}
