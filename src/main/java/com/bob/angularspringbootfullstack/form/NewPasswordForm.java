package com.bob.angularspringbootfullstack.form;

import com.bob.angularspringbootfullstack.constants.PasswordPolicy;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Request body for {@code PUT /user/new/password}, the last step of the forgot-password reset
 * flow ({@link com.bob.angularspringbootfullstack.controller.UserController#setNewPassword}).
 * <p>
 * By the time this form is submitted, the reset link has already been validated by
 * {@link com.bob.angularspringbootfullstack.service.serviceimpl.UserServiceImpl#verifyPasswordKey},
 * whose response is where the client obtained {@link #userID} — the key itself and the new
 * password are deliberately kept out of the URL. The controller passes {@link #userID} and
 * {@link #newPassword} straight through to
 * {@link com.bob.angularspringbootfullstack.repo.UserRepo#setNewPassword(Long, String, String)}.
 * <p>
 * Field names must match the Angular {@code NewPasswordFormInterface}
 * ({@code interface/appstates.interface.ts}) exactly, since Spring's {@code @RequestBody @Valid}
 * binding uses JSON property names as binding keys. This is also the weakest of the three
 * "set a password" doors in this codebase — the other two (authenticated password change,
 * admin-forced reset) require a starting credential or staff authority, while this one only
 * requires possession of a still-valid reset link — which is why {@link #newPassword} is the only
 * field here also checked against {@link PasswordPolicy}, not just {@code @NotEmpty}.
 */
@Data
public class NewPasswordForm {
    @NotNull(message = "The user ID is required")
    private Long userID;
    @NotEmpty(message = "The new password is required")
    @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE)
    private String newPassword;
    @NotEmpty(message = "Confirmation password cannot be empty")
    private String confirmPassword;
}
