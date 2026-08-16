package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for the public {@code POST /user/verify/resend} endpoint.
 * <p>
 * Deliberately email-only, mirroring every other pre-authentication verification form in this
 * package: the caller holds no token at this point in the login/step-up flow, so the email is the
 * only identifier available, and {@code UserService#resendVerificationCode} treats it as
 * unauthenticated input — never confirming or denying that it belongs to a real account (FR-AUTH-4).
 */
@Data
public class ResendCodeForm {

    /** The account email a 2FA/step-up code may be outstanding for. Never verified as an identity. */
    @NotBlank(message = "Email is required")
    @Email(message = "A valid email address is required")
    private String email;
}
