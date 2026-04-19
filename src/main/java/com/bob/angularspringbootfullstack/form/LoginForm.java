package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * LoginForm is a data transfer object for user login requests.
 * <p>
 * This form captures the credentials submitted by users during login.
 * Both fields are required for authentication to proceed.
 * <p>
 * Fields:
 * - email: User's email address (used as username)
 * - password: User's password (will be validated against BCrypted version)
 */
@Data
public class LoginForm {
    /**
     * User's email address (required, non-empty)
     */
    @NotEmpty(message = "Email is required and can't be empty!")
    @Email(message = "Email is not valid!")
    private String email;
    /**
     * User's password (required, non-empty)
     */
    @NotEmpty(message = "Password is required and can't be empty!")
    private String password;
}
