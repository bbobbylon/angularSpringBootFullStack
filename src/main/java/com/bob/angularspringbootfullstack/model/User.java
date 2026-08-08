package com.bob.angularspringbootfullstack.model;

import com.bob.angularspringbootfullstack.constants.PasswordPolicy;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
    // Bean-validation constraints below run ONLY on the registration path
    // (POST /user/register binds the body with @Valid User). Every other flow
    // constructs User server-side without @Valid, so these never block internal
    // updates. Validation is intentionally kept at the controller boundary —
    // jakarta.persistence.validation.mode is set to none for the JPA entities.
    @NotBlank(message = "First name is required")
    private String firstName;
    @NotBlank(message = "Last name is required")
    private String lastName;
    @NotBlank(message = "Email is required")
    @Email(message = "A valid email address is required")
    private String email;
    @NotBlank(message = "Password is required")
    @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE)
    private String password;
    private String imageUrl;
    private String address;
    private String phoneNumber;
    private String bio;
    private String title;
    private boolean enabled;
    private boolean isNotLocked;
    private boolean isUsing2FA;
    /**
     * Whether a CONFIRMED authenticator-app (TOTP) credential exists for this user
     * (SRS FR-MFA-4). Denormalized from the {@code totpcredentials} table onto
     * {@code users.using_totp} — kept in lockstep by {@code TotpServiceImpl} — so row
     * mapping and DTO exposure need no join. When true, login challenges use the
     * authenticator instead of the SMS code path ({@code isUsing2FA}).
     */
    private boolean usingTotp;
    /**
     * Whether the user has at least one registered passkey (WebAuthn credential). Denormalized
     * from the {@code passkeycredentials} table onto {@code users.using_passkey} — kept in
     * lockstep by {@code PasskeyServiceImpl} — so row mapping and DTO exposure need no join.
     * Unlike {@link #usingTotp}, this is not consulted during login (passkey sign-in is
     * usernameless/discoverable, so the server never checks it per-account before offering the
     * option); it exists purely for the Security Center and admin user-detail displays.
     */
    private boolean usingPasskey;
    private LocalDateTime createdAt;
    /**
     * Timestamp of the most recent password change.
     * <p>
     * Set to {@code NOW()} by the database on every password update. Any JWT whose
     * {@code issuedAt} is not after this value is rejected by
     * {@link com.bob.angularspringbootfullstack.tokenprovider.TokenProvider#isTokenValid},
     * preventing stolen pre-change tokens from remaining usable.
     * Null for users who have never changed their password.
     */
    private LocalDateTime passwordChangedAt;
}
