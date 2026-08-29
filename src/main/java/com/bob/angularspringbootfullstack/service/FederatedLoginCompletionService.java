package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * The protocol-agnostic tail of every federated login, shared by
 * {@code OAuth2LoginSuccessHandler} (consumer OAuth2/OIDC) and {@code OrgSamlLoginSuccessHandler}
 * (per-organization SAML, FUTURE-ENHANCEMENTS.md §3.1 Stage 3).
 *
 * <p>Extracted rather than duplicated once a second protocol needed the exact same
 * resolve-user-outcome → ensure-org-membership → mint-tokens-or-challenge-MFA → redirect sequence:
 * the two handlers differ only in how they verify the assertion and extract a profile from it
 * (JSON claims vs. XML attributes) — everything after "here is a verified local {@link UserDTO}" is
 * identical regardless of which protocol produced it. A caller supplies the resolved registration id
 * (so this class can recognize an {@code org-oidc-*}/{@code org-saml-*} login for auto-join) and the
 * already-resolved user; this class owns everything from there.
 */
public interface FederatedLoginCompletionService {

    /**
     * Completes a federated login: auto-joins the organization for a first-time per-organization SSO
     * login, enforces the same account-state and MFA policy as in-house login, and either redirects
     * to an MFA challenge screen or issues tokens and redirects to the SPA callback.
     *
     * <p>Every failure path degrades to a redirect onto the SPA login screen with a coarse
     * {@code error} code — see the calling handler's own class Javadoc for why (NFR-SEC-7).
     *
     * @param provider the resolved registration id, e.g. {@code "google"}, {@code "org-oidc-42"}, or
     *                 {@code "org-saml-42"}
     * @param userDTO  the local user the login already resolved to
     * @param request  the callback request, needed to open a tracked session on success
     * @param response the response used for every redirect this method issues
     */
    void completeLogin(String provider, UserDTO userDTO, HttpServletRequest request, HttpServletResponse response)
            throws IOException;
}
