package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    private String firstName;
    private String lastName;
    private String email;
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
