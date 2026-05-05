package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * User entity representing a registered user.
 *
 * <p>This model maps to the {@code users} table and contains profile fields as well as
 * authentication-related flags (enabled/locked/2FA).
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class User {
    private Long id;
    @NotEmpty(message = "First name is required")
    private String firstName;
    @NotEmpty(message = "Last name is required")
    private String lastName;
    @Email(message = "Email is required")
    private String email;
    @NotEmpty(message = "Password is required")
    private String password;
    private String imageUrl;
    private String address;
    private String phoneNumber;
    private String bio;
    private String title;
    private boolean enabled;
    private boolean isNotLocked;
    private boolean isUsing2FA;
    private LocalDateTime createdAt;
}
