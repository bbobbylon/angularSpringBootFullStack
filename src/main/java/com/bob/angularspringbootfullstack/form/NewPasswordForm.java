package com.bob.angularspringbootfullstack.form;

import com.bob.angularspringbootfullstack.constants.PasswordPolicy;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class NewPasswordForm {
    @NotNull(message = "The user ID is required")
    private Long userID;
    // The forgot-password path enforced only @NotEmpty, making it the weakest of the three doors
    // into setting a password — and the one an attacker who has compromised an inbox would use.
    @NotEmpty(message = "The new password is required")
    @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE)
    private String newPassword;
    @NotEmpty(message = "Confirmation password cannot be empty")
    private String confirmPassword;
}
