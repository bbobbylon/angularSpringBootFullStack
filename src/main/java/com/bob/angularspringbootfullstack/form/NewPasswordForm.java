package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class NewPasswordForm {
    @NotNull(message = "The user ID is required")
    private Long userID;
    @NotEmpty(message = "The new password is required")
    private String newPassword;
    @NotEmpty(message = "Confirmation password cannot be empty")
    private String confirmPassword;
}
