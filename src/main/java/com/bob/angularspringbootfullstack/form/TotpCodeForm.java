package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * Request body for the authenticated TOTP lifecycle endpoints
 * ({@code POST /user/totp/enable} and {@code POST /user/totp/disable}).
 * <p>
 * Carries the single proof-of-possession value those operations demand: a current
 * 6-digit authenticator code (or, for disable, alternatively an unused recovery code).
 * {@code TotpServiceImpl} decides which forms of code are acceptable per operation —
 * this form only guarantees something was submitted.
 */
@Data
public class TotpCodeForm {

    /**
     * A current authenticator code, or an unused recovery code where the endpoint
     * accepts one. Never logged or persisted.
     */
    @NotEmpty(message = "Verification code cannot be empty")
    private String code;
}
