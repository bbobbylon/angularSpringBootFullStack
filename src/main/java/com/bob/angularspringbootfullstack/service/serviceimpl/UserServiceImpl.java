package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.fromUser;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepo<User> userRepo;
    private final RoleRepo<Role> roleRepo;

    /**
     * Creates a new user in the system through the repository layer.
     * Delegates user creation to the UserRepo, then converts the resulting User
     * entity to a UserDTO for exposure to the presentation layer.
     * <p>
     * The returned UserDTO includes the user's role name and permissions as simple fields
     * (not as a Role object), reflecting the current DTO structure for API responses.
     *
     * @param user the user entity containing registration information
     * @return a UserDTO representing the newly created user (with roleName and permissions fields)
     */
    @Override
    public UserDTO createUser(User user) {
        return mapToUserDTO(userRepo.create(user));
    }

    /**
     * Retrieves a user by their email address from the repository.
     * Converts the User entity to a UserDTO for the presentation layer.
     * <p>
     * The returned UserDTO includes the user's role name and permissions as simple fields.
     *
     * @param email the email address to search for
     * @return a UserDTO if user is found, otherwise throws an exception
     * @throws ApiException if user is not found in the database
     */
    @Override
    public UserDTO getUserByEmail(String email) {
        return mapToUserDTO(userRepo.getUserByEmail(email));
    }

    /**
     * Sends a 2FA verification code to the user via their registered contact method.
     * Delegates to the repository layer which handles SMS/Email sending logic.
     *
     * @param userDTO the user who will receive the verification code
     */
    @Override
    public void sendVerificationCode(UserDTO userDTO) {
        userRepo.sendVerificationCode(userDTO);
    }

    /**
     * Verifies a 2FA code for the given user email.
     * <p>
     * If verification succeeds, returns a UserDTO with roleName and permissions fields.
     * If verification fails (invalid/expired code or user not found), throws an authentication exception
     * that is mapped to a 401 Unauthorized response by the global exception handler.
     *
     * @param email the user's email address
     * @param code  the 2FA code to verify
     * @return a UserDTO if verification is successful
     * @throws org.springframework.security.authentication.BadCredentialsException or
     *                                                                             org.springframework.security.core.userdetails.UsernameNotFoundException if verification fails
     */
    @Override
    public UserDTO verifyCode(String email, String code) {
        return mapToUserDTO(userRepo.verifyCode(email, code));
    }

    @Override
    public void resetPassword(String email) {
        userRepo.resetPassword(email);
    }

    /**
     * Verifies a password reset link/key and returns the associated user.
     * <p>
     * <b>Purpose:</b> When a user clicks a password reset link, the key/token in the URL is sent to this method.
     * This method verifies the link is still valid (not expired) and returns the user associated with it.
     * <p>
     * <b>Flow:</b>
     * <ol>
     *   <li>Extract the key from the reset link (e.g., /user/verify/password/{key})</li>
     *   <li>Call userRepo.verifyPasswordKey(key)</li>
     *   <li>Repository checks if link exists and is not expired</li>
     *   <li>Deletes the link row (so it can't be reused)</li>
     *   <li>Returns User entity associated with the link</li>
     *   <li>This method converts User to UserDTO</li>
     * </ol>
     * <p>
     * <b>Server Logging (NEW - May 2026):</b><br/>
     * When a password reset link is verified successfully, the repository logs:
     * <pre>
     * INFO: Password reset verification url http://localhost:8080/user/verify/password/550e8400-...
     *       has been verified for user with email: bob@example.com
     * </pre>
     * <p>
     * <b>Security Notes:</b>
     * <ul>
     *   <li>Link expires after 24 hours (set in UserRepoImpl.resetPassword())</li>
     *   <li>Link is deleted after verification (can't be reused)</li>
     *   <li>Link contains UUID key that's unique to each reset request</li>
     * </ul>
     *
     * @param key the password reset token/key from the URL
     * @return a UserDTO if the link is valid, otherwise throws ApiException
     * @throws ApiException if link is not valid or has expired
     */
    @Override
    public UserDTO verifyPasswordKey(String key) {
        return mapToUserDTO(userRepo.verifyPasswordKey(key));
    }

    /**
     * Sets a new password for a user using a valid password reset key.
     * <p>
     * <b>Purpose:</b> After user submits a new password via the reset form, this method updates the password.
     * <p>
     * <b>Flow:</b>
     * <ol>
     *   <li>Receive reset key, new password, and confirmation password</li>
     *   <li>Validate passwords match (throws ApiException if not)</li>
     *   <li>Validate reset link is not expired (throws ApiException if expired)</li>
     *   <li>Lookup user by reset link key</li>
     *   <li>Encode new password with BCrypt</li>
     *   <li>Update user's password in database</li>
     *   <li>Delete reset link (so it can't be used again)</li>
     * </ol>
     * <p>
     * <b>Server Logging (NEW - May 2026):</b><br/>
     * When password is successfully reset, the repository logs:
     * <pre>
     * INFO: Password successfully reset for user with email: bob@example.com
     * </pre>
     * <p>
     * <b>Security Measures:</b>
     * <ul>
     *   <li>Password is encoded with BCrypt (strength 12) before storing</li>
     *   <li>Reset link is deleted after use (prevents replay attacks)</li>
     *   <li>Confirmation password must match (prevents typos)</li>
     *   <li>Link expiration checked (prevents old links from being used)</li>
     * </ul>
     *
     * @param key               the password reset token from the URL
     * @param newPassword       the new password to set
     * @param confirmPassword   the confirmation password (must match newPassword)
     * @throws ApiException if passwords don't match, link is expired, or other validation fails
     */
    @Override
    public void setNewPassword(String key, String newPassword, String confirmPassword) {
        userRepo.setNewPassword(key, newPassword, confirmPassword);
    }

    /**
     * Verifies an account using the verification link key and enables the account.
     * <p>
     * <b>Purpose:</b> When a new user registers, they receive an account verification email with a link.
     * Clicking the link calls this method to verify ownership of the email address and enable the account.
     * <p>
     * <b>Flow:</b>
     * <ol>
     *   <li>Extract the key from the verification link</li>
     *   <li>Lookup the verificationurl record in the database</li>
     *   <li>Check if the link is still valid (not expired)</li>
     *   <li>Update the user's enabled flag to true</li>
     *   <li>Delete the verification link (so it can't be used again)</li>
     *   <li>Return the verified user</li>
     * </ol>
     * <p>
     * <b>Server Logging (NEW - May 2026):</b><br/>
     * When account is successfully verified, the repository logs:
     * <pre>
     * INFO: Account successfully verified for user with email: bob@example.com
     * </pre>
     * <p>
     * <b>Timeline:</b>
     * <pre>
     * 1. POST /user/register (new user)
     *    → Server generates verification URL with UUID key
     *    → Stores URL in accountverifications table
     *    → Logs: "Account verification url http://localhost:8080/user/verify/account/{key}
     *             sent to user with email: bob@example.com"
     *
     * 2. User receives email with link
     *    → User clicks link OR calls GET /user/verify/account/{key}
     *
     * 3. GET /user/verify/account/{key}
     *    → Calls this method (verifyAccount)
     *    → Calls userRepo.verifyAccountKey(key)
     *    → Repository verifies key, enables user, deletes link
     *    → Logs: "Account successfully verified for user with email: bob@example.com"
     *    → Returns 200 OK
     *
     * 4. User can now log in
     * </pre>
     *
     * @param key the account verification token from the URL
     * @return a UserDTO of the verified user, otherwise throws exception
     * @throws ApiException if link is not valid or has expired
     * @throws UsernameNotFoundException if user cannot be found
     */
    @Override
    public UserDTO verifyAccount(String key) {
        return mapToUserDTO(userRepo.verifyAccountKey(key));
    }

    /**
     * Maps a User entity to a UserDTO, including the user's role name and permissions.
     * <p>
     * This method uses the UserDTOMapper to copy properties and set roleName/permissions fields
     * from the user's Role object, matching the current UserDTO structure.
     *
     * @param user the User entity
     * @return a UserDTO with roleName and permissions fields populated
     */
    private UserDTO mapToUserDTO(User user) {
        return fromUser(user, roleRepo.getRoleByUserId(user.getId()));
    }
}
