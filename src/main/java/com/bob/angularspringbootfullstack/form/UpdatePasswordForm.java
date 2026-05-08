package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/** Request body for PATCH /user/update/password. Field names must match exactly for Jackson to deserialize. */
@Data
public class UpdatePasswordForm {
    @NotEmpty(message = "The current password is required")
    private String currentPassword;
    @NotEmpty(message = "The new password is required")
    private String newPassword;
    @NotEmpty(message = "Confirmation password cannot be empty")
    private String confirmPassword;
}
