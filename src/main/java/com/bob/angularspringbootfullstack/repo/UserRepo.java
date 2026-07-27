package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.form.UpdateForm;
import com.bob.angularspringbootfullstack.model.User;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;

/**
 * UserRepo defines the data access contract for User entities.
 * <p>
 * This generic repository interface extends to any type T that extends User,
 * providing a flexible CRUD (Create, Read, Update, Delete) contract.
 * Implementations handle direct database access via SQL queries.
 * <p>
 * Generic CRUD operations include standard database operations,
 * while custom methods handle user-specific queries and operations.
 *
 * @param <T> the type parameter representing User or User subtypes
 */
public interface UserRepo<T extends User> {

    /**
     * Creates a new user in the database.
     *
     * @param data the user entity to create
     * @return the created user with ID populated
     */
    T create(T data);

    /**
     * Retrieves a paginated list of users.
     * Supports pagination for large datasets.
     *
     * @param page     the page number (0-indexed)
     * @param pageSize the number of users per page
     * @return a collection of users on the specified page
     */
    Collection<T> list(int page, int pageSize);

    /**
     * Pages through the user directory for the administrative dashboard (FR-ADMIN-1),
     * filtered by a free-text term matched against first name, last name, and email.
     * A blank term returns the unfiltered directory, so this also backs {@link #list}.
     *
     * @param searchTerm free-text filter; blank or null means "no filter"
     * @param page       the page number (0-indexed)
     * @param pageSize   the number of users per page
     * @return the matching users on the requested page, newest accounts first
     */
    Collection<T> searchUsers(String searchTerm, int page, int pageSize);

    /**
     * Counts the users the same filter in {@link #searchUsers} would match, so callers
     * can compute total pages for the directory view.
     *
     * @param searchTerm free-text filter; blank or null means "no filter"
     * @return the total number of matching users
     */
    long countUsers(String searchTerm);

    /**
     * Retrieves a single user by ID.
     *
     * @param id the user's unique identifier
     * @return the user if found, null otherwise
     */
    T get(Long id);

    /**
     * Updates an existing user in the database.
     *
     * @param id   the ID of the user to update
     * @param data the updated user data
     * @return the updated user
     */
    T update(Long id, T data);

    /**
     * Deletes a user from the database.
     *
     * @param id the ID of the user to delete
     */
    void delete(Long id);

    /**
     * Retrieves a user by their email address.
     *
     * @param email the user's email address
     * @return the user if found
     * throws ApiException if user is not found
     */
    User getUserByEmail(String email);

    /**
     * Sends a 2FA verification code to the specified user.
     *
     * @param userDTO the user who will receive the verification code
     */
    void sendVerificationCode(UserDTO userDTO);

    /**
     * Mints and persists a single-use verification code for the given user, replacing any code
     * already outstanding, and returns it <em>without</em> dispatching it anywhere.
     *
     * <p>Separating minting from delivery is what lets two different flows share one code store
     * (the {@code twofactorverifications} table) and one redemption endpoint
     * ({@code GET /user/verify/code/{email}/{code}}) while delivering over different channels:
     * {@link #sendVerificationCode} sends over SMS for enrolled 2FA users, and the FR-TPF-1
     * step-up path emails it to accounts with no second factor. Returning the code rather than
     * sending it also keeps the choice of channel — a business decision — in the service layer
     * where it belongs.
     *
     * @param userDTO the user the code is issued to
     * @return the freshly generated code, already persisted with its expiration
     */
    String issueVerificationCode(UserDTO userDTO);

    /**
     * Verifies a user's 2FA code.
     *
     * @param email user's email
     * @param code  verification code
     * @return the user if the code is valid and not expired
     */
    User verifyCode(String email, String code);

    /**
     * Initiates password reset for the given email.
     *
     * @param email email address of the user
     */
    void resetPassword(String email);

    /**
     * Verifies a password reset URL key and returns the associated user.
     *
     * @param key URL key portion (UUID) from the reset link
     * @return the user associated with the key
     */
    T verifyPasswordKey(String key);

    /**
     * Completes the forgot-password reset flow for the user identified by {@code userID}.
     * <p>
     * Called after {@link #verifyPasswordKey(String)} has already validated the
     * reset link in the previous step of the flow and returned the user (whose
     * ID the caller now holds). Persists the new password by ID — no URL key,
     * no password in the query string — and deletes the single-use reset row.
     *
     * @param userID          the user whose password is being reset
     * @param newPassword     new password (encoded by the implementation)
     * @param confirmPassword must match {@code newPassword}
     */
    void setNewPassword(Long userID, String newPassword, String confirmPassword);

    /**
     * Verifies an account verification key and enables the account.
     *
     * @param key URL key portion (UUID) from the account verification link
     * @return the verified user
     */
    T verifyAccountKey(String key);

    /**
     * Updates profile fields for an existing user.
     *
     * @param user the form data containing the fields to update
     * @return the updated user entity
     */
    T updateUserDetails(UpdateForm user);

    /**
     * Verifies {@code currentPassword}, then updates the user's password and
     * stamps {@code password_changed_at} to invalidate pre-change tokens.
     */
    void updatePassword(Long userID, String currentPassword, String newPassword, String confirmPassword);

    /**
     * Persists the {@code enabled} and {@code notLocked} flags for the given user.
     * Both control whether the account is usable: {@code enabled = false} blocks login
     * entirely; {@code notLocked = false} locks the account (e.g., after suspicious activity).
     *
     * @param userID    the ID of the user to update
     * @param enabled   {@code true} to allow login, {@code false} to block it
     * @param notLocked {@code true} to unlock the account, {@code false} to lock it
     */
    void updateAccountSettings(Long userID, Boolean enabled, Boolean notLocked);

    /**
     * Flips the user's MFA (two-factor authentication) flag on or off.
     * A phone number must be present on the account — MFA codes are delivered via SMS.
     *
     * @param email the email address of the user toggling MFA
     * @return the updated user entity with the new MFA state reflected
     */
    T toggleMFA(String email);

    /**
     * Saves a new profile image to disk and updates the {@code image_url} column
     * in the database with the URL where the image can be fetched.
     *
     * @param userDTO the authenticated user whose image is being changed
     * @param image   the uploaded image file from the multipart request
     */
    void updateProfileImage(UserDTO userDTO, MultipartFile image);
}

