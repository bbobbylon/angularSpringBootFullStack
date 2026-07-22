package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * Request body for the public login-completion endpoint {@code POST /user/verify/totp}.
 * <p>
 * Pairs the opaque {@code challenge} minted server-side when the first authentication
 * factor succeeded (see {@code TotpService#createLoginChallenge}) with the user's
 * authenticator or recovery {@code code}. The challenge — not an email — identifies the
 * account, so the endpoint exposes no user-enumeration surface (NFR-SEC-7) and a TOTP
 * code alone can never complete a login whose password step did not happen.
 * <p>
 * Sent as a POST body (unlike the SMS flow's path-variable GET) so neither value ever
 * lands in URL or proxy logs.
 */
@Data
public class TotpVerifyForm {

    /** The opaque first-factor proof returned by the login (or federated) flow. */
    @NotEmpty(message = "Challenge cannot be empty")
    private String challenge;

    /** A current authenticator code or an unused recovery code. */
    @NotEmpty(message = "Verification code cannot be empty")
    private String code;
}
