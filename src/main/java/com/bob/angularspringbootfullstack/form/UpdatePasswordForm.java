package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotEmpty;
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
    @NotEmpty(message = "The new password is required")
    private String newPassword;
    @NotEmpty(message = "Confirmation password cannot be empty")
    private String confirmPassword;
}
