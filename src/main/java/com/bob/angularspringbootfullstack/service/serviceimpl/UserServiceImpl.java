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

/**
 * Default UserService implementation that delegates persistence to UserRepo
 * and enriches each returned User with its Role via RoleRepo before mapping
 * it to a UserDTO for the controller layer.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepo<User> userRepo;
    private final RoleRepo<Role> roleRepo;

    /**
     * Persists a new user and returns it as a DTO with role/permission fields
     * filled in. The repository assigns the default role and the email
     * verification URL.
     *
     * @param user the registration data
     * @return the created user as a DTO
     */
    @Override
    public UserDTO createUser(User user) {
        return mapToUserDTO(userRepo.create(user));
    }

    /**
     * Looks up a user by email and returns it as a DTO with role/permission
     * fields filled in.
     *
     * @param email the user's email
     * @return the user as a DTO
     */
    @Override
    public UserDTO getUserByEmail(String email) {
        return mapToUserDTO(userRepo.getUserByEmail(email));
    }

    /**
     * Generates and stores a fresh 2FA code for the user and (when SMS is
     * enabled) sends it to their phone number.
     *
     * @param userDTO the user to send the code to
     */
    @Override
    public void sendVerificationCode(UserDTO userDTO) {
        userRepo.sendVerificationCode(userDTO);
    }

    /**
     * Validates a 2FA code for the given email and returns the verified user
     * as a DTO. The repository deletes the code on success so it can't be
     * reused.
     *
     * @param email the user's email
     * @param code  the 2FA code submitted by the client
     * @return the user as a DTO
     */
    @Override
    public UserDTO verifyCode(String email, String code) {
        return mapToUserDTO(userRepo.verifyCode(email, code));
    }

    /**
     * Starts the password reset flow by generating a one-time verification
     * URL with a 24-hour expiration and persisting it for the user.
     *
     * @param email the email of the user requesting a reset
     */
    @Override
    public void resetPassword(String email) {
        userRepo.resetPassword(email);
    }

    /**
     * Resolves a password reset key to the user it belongs to, throwing if
     * the link is missing or expired.
     *
     * @param key the UUID portion of the reset URL
     * @return the user as a DTO
     */
    @Override
    public UserDTO verifyPasswordKey(String key) {
        return mapToUserDTO(userRepo.verifyPasswordKey(key));
    }

    /**
     * Updates the user's password after confirming the two passwords match
     * and the reset link is still valid; the link is deleted on success.
     *
     * @param key             the UUID portion of the reset URL
     * @param newPassword     the new password (encoded with BCrypt before storage)
     * @param confirmPassword must equal newPassword
     */
    @Override
    public void setNewPassword(String key, String newPassword, String confirmPassword) {
        userRepo.setNewPassword(key, newPassword, confirmPassword);
    }

    /**
     * Verifies an account activation link, enables the user, and returns
     * them as a DTO.
     *
     * @param key the UUID portion of the activation URL
     * @return the now-enabled user as a DTO
     */
    @Override
    public UserDTO verifyAccount(String key) {
        return mapToUserDTO(userRepo.verifyAccountKey(key));
    }

    /**
     * Looks up the user's role and converts the User entity into a UserDTO
     * with roleName and permissions populated.
     *
     * @param user the persisted user entity
     * @return the DTO view of the user including role and permissions
     */
    private UserDTO mapToUserDTO(User user) {
        return fromUser(user, roleRepo.getRoleByUserId(user.getId()));
    }
}
