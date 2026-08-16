package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.form.UpdateForm;
import com.bob.angularspringbootfullstack.model.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;

/**
 * UserService defines the business logic contract for user-related operations.
 * <p>
 * This service layer interface acts as a facade between the controller layer
 * and repository layer. It defines the operations available for user management,
 * ensuring consistent business logic and allowing for easy testing via mocking.
 * <p>
 * The service returns UserDTO objects instead of User entities to prevent
 * sensitive data (like passwords) from being exposed to the API clients.
 */
public interface UserService {
    /**
     * Creates a new user in the system with the provided credentials.
     *
     * @param user the user entity containing registration information
     * @return a UserDTO representing the created user (without a password)
     */
    UserDTO createUser(User user);

    /**
     * Retrieves a user by their email address.
     *
     * @param email the email address to search for (must not be empty)
     * @return a UserDTO if a user is found
     */
    UserDTO getUserByEmail(@NotEmpty String email);

    /**
     * Sends a 2FA verification code to the user via their registered contact method.
     *
     * @param userDTO the user who will receive the verification code
     */
    void sendVerificationCode(UserDTO userDTO);

    /**
     * Issues and emails a one-time step-up code for a sign-in flagged as anomalous (SRS FR-TPF-1).
     *
     * <p>Called only for accounts with no enrolled second factor — an account with TOTP or SMS 2FA
     * is already being challenged. The code is stored in the same {@code twofactorverifications}
     * table as the SMS flow and is redeemed at the same
     * {@code GET /user/verify/code/{email}/{code}} endpoint, so the step-up reuses a proven,
     * already-public redemption path instead of introducing a second one to secure.
     *
     * @param userDTO       the user awaiting step-up verification
     * @param reasonSummary human-readable description of what looked unusual, for the email body
     */
    void sendStepUpCode(UserDTO userDTO, String reasonSummary);

    /**
     * Verifies a 2FA code for a user.
     *
     * @param email user's email
     * @param code  verification code supplied by the user
     * @return the verified user as a DTO
     */
    UserDTO verifyCode(String email, String code);

    /**
     * Redelivers an already-outstanding 2FA/step-up code, without ever minting one from an email
     * alone (SRS FR-AUTH-4 / anti-enumeration).
     *
     * <p>A no-op — same as a hit — for an unknown email, a TOTP-enrolled account (that challenge
     * has no server-issued code to resend), or an account with no pending
     * {@code twofactorverifications} row. Only when {@code UserRepo#hasPendingVerificationCode}
     * is true does this redeliver, over whichever channel that account's original challenge used:
     * SMS/Twilio Verify via {@link #sendVerificationCode} for a 2FA-enrolled account, or email via
     * {@link #sendStepUpCode} for an FR-TPF-1 step-up challenge. Both mint a fresh code (see
     * {@code UserRepo#issueVerificationCode}'s delete-then-insert), which invalidates whatever the
     * caller was just resending — intentional, since the old one is what they are asking to
     * replace.
     *
     * @param email the account email a code may be outstanding for; never confirmed to exist
     */
    void resendVerificationCode(String email);

    /**
     * Starts the password reset flow for the given email address.
     *
     * <p>The implementation generates a one-time verification URL/key and persists it with an
     * expiration time.
     *
     * @param email email address of the user requesting a password reset
     */
    void resetPassword(String email);

    /**
     * Verifies a password reset key/link and returns the associated user.
     *
     * @param key password reset key from the verification URL
     * @return the user associated with the reset key
     */
    UserDTO verifyPasswordKey(String key);

    /**
     * Completes the forgot-password reset flow for the user identified by {@code userID}.
     * <p>
     * Invoked by {@code PUT /user/new/password} after {@link #verifyPasswordKey(String)}
     * has already validated the reset link and returned the user (whose ID the
     * frontend now holds). Distinct from
     * {@link #updatePassword(Long, String, String, String)}, which is the
     * authenticated change-password flow and requires the current password.
     *
     * @param userID          the user whose password is being reset
     * @param newPassword     new password
     * @param confirmPassword must match {@code newPassword}
     */
    void setNewPassword(Long userID, String newPassword, String confirmPassword);

    /**
     * Verifies an account verification key and enables the user account.
     *
     * @param key account verification key from the verification URL
     * @return the verified user
     */
    UserDTO verifyAccount(String key);

    /**
     * Updates an existing user's profile details from the supplied form data.
     *
     * @param user the validated form containing the fields to update
     * @return the updated user as a DTO
     */
    UserDTO updateUserDTO(@Valid UpdateForm user);

    /**
     * Retrieves a user by their ID.
     *
     * @param id The ID of the user to retrieve.
     * @return A UserDTO representing the user.
     */
    UserDTO getUserById(Long id);

    /**
     * Updates a user's password.
     *
     * @param id              The ID of the user.
     * @param currentPassword The user's current password.
     * @param newPassword     The new password.
     * @param confirmPassword The confirmation of the new password.
     */
    void updatePassword(Long id, String currentPassword, String newPassword, String confirmPassword);

    /**
     * Updates the role of a user.
     *
     * @param id       The ID of the user.
     * @param roleName The name of the new role.
     */
    void updateUserRole(Long id, String roleName);

    /**
     * Updates a user's account settings.
     *
     * @param id        The ID of the user.
     * @param enabled   The new-enabled status.
     * @param notLocked The new locked status.
     */
    void updateAccountSettings(Long id, Boolean enabled, Boolean notLocked);

    /**
     * Toggles a user's multifactor authentication status.
     *
     * @param email The email of the user.
     * @return The updated UserDTO.
     */
    UserDTO toggleMFA(String email);

    /**
     * Saves a new profile image for the user and records the updated image URL in the database.
     *
     * @param userDTO the authenticated user whose image is being changed
     * @param image   the uploaded image file from the multipart request
     */
    void updateProfileImage(UserDTO userDTO, MultipartFile image);

    /**
     * Pages through the user directory for the administrative dashboard (FR-ADMIN-1),
     * filtered by a free-text term matched against name and email. Each returned DTO
     * carries its role name and permission string so the admin UI can render and
     * reassign roles without extra requests.
     *
     * @param searchTerm free-text filter; blank or null lists everyone
     * @param page       0-indexed page number
     * @param pageSize   rows per page
     * @param orderBy    a validated {@code "column ASC|DESC"} SQL fragment (see
     *                   {@code SortUtils#resolveSqlOrderBy}), e.g. {@code "created_at DESC, id DESC"}
     * @return the matching users on the requested page, in the requested order
     */
    Collection<UserDTO> searchUsers(String searchTerm, int page, int pageSize, String orderBy);

    /**
     * Counts the users {@link #searchUsers} would match for the same term, so the
     * admin UI can compute total pages.
     *
     * @param searchTerm free-text filter; blank or null counts everyone
     * @return the total number of matching users
     */
    long countUsers(String searchTerm);
}


