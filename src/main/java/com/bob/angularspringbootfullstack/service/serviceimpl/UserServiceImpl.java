package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.form.UpdateForm;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.fromUser;

/**
 * Service implementation for User-related business logic.
 * This class orchestrates operations on users by interacting with the UserRepository.
 * It provides a layer of abstraction between the controller and the data access layer,
 * encapsulating the business rules for user management, such as creating users,
 * handling password updates, and managing account verification processes.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepo<User> userRepo;
    private final RoleRepo<Role> roleRepository;

    /**
     * Creates a new user.
     * This method takes a User object, typically from a registration form, and passes it
     * to the repository to be persisted. It returns a DTO of the created user.
     *
     * @param user the registration data
     * @return the created user as a DTO
     * @param user The User object to be created.
     * @return A UserDTO representing the newly created user.
     */
    @Override
    public UserDTO createUser(User user) {
        return mapToUserDTO(userRepo.create(user));
    }

    /**
     * Retrieves a user by their email address.
     * This method is used to fetch a user's details based on their email.
     *
     * @param email The email of the user to retrieve.
     * @return A UserDTO representing the user.
     */
    @Override
    public UserDTO getUserByEmail(String email) {
        return mapToUserDTO(userRepo.getUserByEmail(email));
    }

    /**
     * Sends a verification code to a user for two-factor authentication.
     * This method generates a verification code, saves it to the database with an expiration time,
     * and then (in a real application) would send it to the user via SMS or another method.
     *
     * @param userDTO The DTO of the user to send the code to.
     */
    @Override
    public void sendVerificationCode(UserDTO userDTO) {
        userRepo.sendVerificationCode(userDTO);
    }

    /**
     * Verifies a two-factor authentication code provided by a user.
     * This method checks the code against the one stored in the database.
     *
     * @param email The user's email.
     * @param code The verification code.
     * @return A UserDTO if the code is valid.
     */
    @Override
    public UserDTO verifyCode(String email, String code) {
        return mapToUserDTO(userRepo.verifyCode(email, code));
    }

    /**
     * Initiates the password reset process for a user.
     * This method calls the repository to generate and send a password reset link.
     *
     * @param email The email of the user requesting a password reset.
     */
    @Override
    public void resetPassword(String email) {
        userRepo.resetPassword(email);
    }

    /**
     * Verifies a password reset URL.
     *
     * @param url The password reset URL.
     * @return A UserDTO if the URL is valid.
     */
    @Override
    public UserDTO verifyPasswordUrl(String url) {
        return mapToUserDTO(userRepo.verifyPasswordUrl(url));
    }

    /**
     * Sets a new password for a user after a password reset.
     *
     * @param key             the UUID portion of the reset URL
     * @param newPassword     the new password (encoded with BCrypt before storage)
     * @param confirmPassword must equal newPassword
     * @param newPassword The new password.
     * @param confirmPassword The confirmation of the new password.
     * @param url The password reset URL.
     */
    @Override
    public void newPassword(Long userId, String newPassword, String confirmPassword, String url) {
        userRepo.newPassword(userId, newPassword, confirmPassword, url);
    }

    /**
     * Verifies a user's account using a verification key.
     *
     * @param key the UUID portion of the activation URL
     * @return the now-enabled user as a DTO
     * @return A UserDTO if the key is valid.
     */
    @Override
    public UserDTO verifyAccount(String key) {
        return mapToUserDTO(userRepo.verifyAccountKey(key));
    }

    /**
     * Updates a user's profile details.
     *
     * @param userDTO The DTO containing the updated user information.
     * @return The updated UserDTO.
     */
    @Override
    public UserDTO updateUserDetails(UserDTO userDTO) {
        return mapToUserDTO(userRepo.updateUserDetails(userDTO));

    }

    /**
     * Retrieves a user by their ID.
     *
     * @param userID the user's primary key
     * @param id The ID of the user to retrieve.
     * @return A UserDTO representing the user.
     */
    @Override
    public UserDTO getUserById(Long id) {
        return mapToUserDTO(userRepo.get(id));
    }

    /**
     * Updates a user's password.
     *
     * @param id The ID of the user.
     * @param currentPassword The user's current password.
     * @param newPassword The new password.
     * @param confirmPassword The confirmation of the new password.
     */
    @Override
    public void updatePassword(Long id, String currentPassword, String newPassword, String confirmPassword) {
        userRepo.updatePassword(id, currentPassword, newPassword, confirmPassword);
    }

    /**
     * Updates the role of a user.
     *
     * @param id The ID of the user.
     * @param roleName The name of the new role.
     */
    @Override
    public void updateUserRole(Long id, String roleName) {
        roleRepository.updateUserRole(id, roleName);
    }

    /**
     * Updates a user's account settings.
     *
     * @param id The ID of the user.
     * @param enabled The new enabled status.
     * @param notLocked The new locked status.
     */
    @Override
    public void updateAccountSettings(Long id, Boolean enabled, Boolean notLocked) {
        userRepo.updateAccountSettings(id, enabled, notLocked);
    }

    /**
     * Toggles a user's multi-factor authentication status.
     *
     * @param email The email of the user.
     * @return The updated UserDTO.
     */
    @Override
    public UserDTO toggleMFA(String email) {
        return mapToUserDTO(userRepo.toggleMFA(email));
    }

    /**
     * Updates a user's profile image.
     *
     * @param id The ID of the user.
     * @param image The new profile image.
     */
    @Override
    public void updateProfileImage(Long id, MultipartFile image) {
        userRepo.updateProfileImage(id, image);
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
