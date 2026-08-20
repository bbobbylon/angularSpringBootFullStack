package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * Request body for the public login-completion endpoint {@code POST /user/verify/webauthn}: the
 * browser's {@code PublicKeyCredential.toJSON()} authentication response, forwarded to
 * {@code PasskeyService} as-is. Unlike {@link TotpVerifyForm}, there is no separate challenge
 * field — the assertion's own {@code clientDataJSON} carries the challenge the server minted, and
 * the credential id inside {@code credential} is what resolves the account (usernameless login).
 *
 * <p>{@code JsonNode} is {@code tools.jackson.databind} (Jackson 3), not the classic
 * {@code com.fasterxml.jackson.databind} — see {@link PasskeyRegisterVerifyForm}'s Javadoc for why
 * that distinction is load-bearing here and not just a style preference.
 */
@Data
public class PasskeyLoginVerifyForm {

    /** The browser's authentication response, verbatim. */
    @NotNull(message = "Passkey credential response is required")
    private JsonNode credential;
}
