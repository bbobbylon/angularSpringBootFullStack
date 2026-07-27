package com.bob.angularspringbootfullstack.form;

import com.bob.angularspringbootfullstack.constants.PasswordPolicy;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Request body for the {@code PATCH /user/update/password} endpoint.
 * <p>
 * Field names must match exactly for Jackson to map the incoming JSON to these
 * properties via the {@code @Data}-generated setters. Spring Boot validates each
 * field with {@code @NotEmpty} before the controller method is invoked, so a
 * missing or blank field returns a 400 before any business logic runs.
 */
@Data
public class UpdatePasswordForm {
    @NotEmpty(message = "The current password is required")
    private String currentPassword;
    // Strength is enforced on the CHANGE path too, not just at registration: without it an
    // account created under the policy could be downgraded to a one-character password the
    // moment it was changed, which makes the registration check decorative.
    @NotEmpty(message = "The new password is required")
    @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE)
    private String newPassword;
    @NotEmpty(message = "Confirmation password cannot be empty")
    private String confirmPassword;
}
