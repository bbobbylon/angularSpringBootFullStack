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
     * Verifies a 2FA code for a user.
     *
     * @param email user's email
     * @param code  verification code supplied by the user
     * @return the verified user as a DTO
     */
    UserDTO verifyCode(String email, String code);

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
     * @return the matching users on the requested page, newest accounts first
     */
    Collection<UserDTO> searchUsers(String searchTerm, int page, int pageSize);

    /**
     * Counts the users {@link #searchUsers} would match for the same term, so the
     * admin UI can compute total pages.
     *
     * @param searchTerm free-text filter; blank or null counts everyone
     * @return the total number of matching users
     */
    long countUsers(String searchTerm);
}


