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
    private final RoleRepo<Role> roleRepo;

    /**
     * Creates a new user.
     * This method takes a User object, typically from a registration form, and passes it
     * to the repository to be persisted. It returns a DTO of the created user.
     *
     * @param user the registration data
     * @return the created user as a DTO
     */
    @Override
    public UserDTO createUser(User user) {
        return mapToUserDTO(userRepo.create(user));
    }

    /**
     * Retrieves a user by their email address.
     * This method is used to fetch a user's details based on their email.
     *
     * @param email the email of the user to retrieve
     * @return a UserDTO representing the user
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
     * @param userDTO the DTO of the user to send the code to
     */
    @Override
    public void sendVerificationCode(UserDTO userDTO) {
        userRepo.sendVerificationCode(userDTO);
    }

    /**
     * Verifies a two-factor authentication code provided by a user.
     * This method checks the code against the one stored in the database.
     *
     * @param email the user's email
     * @param code  the verification code
     * @return a UserDTO if the code is valid
     */
    @Override
    public UserDTO verifyCode(String email, String code) {
        return mapToUserDTO(userRepo.verifyCode(email, code));
    }

    /**
     * Initiates the password reset process for a user.
     * This method calls the repository to generate and send a password reset link.
     *
     * @param email the email of the user requesting a password reset
     */
    @Override
    public void resetPassword(String email) {
        userRepo.resetPassword(email);
    }

    /**
     * Verifies a password reset key and returns the associated user.
     *
     * @param key the UUID portion of the reset URL
     * @return a UserDTO if the key is valid
     */
    @Override
    public UserDTO verifyPasswordKey(String key) {
        return mapToUserDTO(userRepo.verifyPasswordKey(key));
    }

    /**
     * Sets a new password for a user after a successful password reset verification.
     *
     * @param key             the UUID portion of the reset URL
     * @param newPassword     the new password
     * @param confirmPassword must match {@code newPassword}
     */
    @Override
    public void setNewPassword(String key, String newPassword, String confirmPassword) {
        userRepo.setNewPassword(key, newPassword, confirmPassword);
    }

    /**
     * Verifies a user's account using a verification key.
     *
     * @param key the UUID portion of the activation URL
     * @return the now-enabled user as a DTO
     */
    @Override
    public UserDTO verifyAccount(String key) {
        return mapToUserDTO(userRepo.verifyAccountKey(key));
    }

    /**
     * Updates a user's profile details from the supplied form data.
     *
     * @param user the validated form containing the fields to update
     * @return the updated user as a DTO
     */
    @Override
    public UserDTO updateUserDTO(UpdateForm user) {
        return mapToUserDTO(userRepo.updateUserDetails(user));
    }

    /**
     * Retrieves a user by their ID.
     *
     * @param id the user's primary key
     * @return a UserDTO representing the user
     */
    @Override
    public UserDTO getUserById(Long id) {
        return mapToUserDTO(userRepo.get(id));
    }

    /**
     * Updates a user's password.
     *
     * @param id              the ID of the user
     * @param currentPassword the user's current password
     * @param newPassword     the new password
     * @param confirmPassword the confirmation of the new password
     */
    @Override
    public void updatePassword(Long id, String currentPassword, String newPassword, String confirmPassword) {
        userRepo.updatePassword(id, currentPassword, newPassword, confirmPassword);
    }

    /**
     * Updates the role of a user.
     *
     * @param id       the ID of the user
     * @param roleName the name of the new role
     */
    @Override
    public void updateUserRole(Long id, String roleName) {
        roleRepo.updateUserRole(id, roleName);
    }

    /**
     * Updates a user's account settings.
     *
     * @param id        the ID of the user
     * @param enabled   the new enabled status
     * @param notLocked the new locked status
     */
    @Override
    public void updateAccountSettings(Long id, Boolean enabled, Boolean notLocked) {
        userRepo.updateAccountSettings(id, enabled, notLocked);
    }

    /**
     * Toggles a user's multi-factor authentication status.
     *
     * @param email the email of the user
     * @return the updated UserDTO
     */
    @Override
    public UserDTO toggleMFA(String email) {
        return mapToUserDTO(userRepo.toggleMFA(email));
    }

    /**
     * Delegates saving the uploaded profile image to disk and recording its URL
     * in the database to the repository layer.
     *
     * @param userDTO the authenticated user whose image is being changed
     * @param image   the uploaded image file from the multipart request
     */
    @Override
    public void updateProfileImage(UserDTO userDTO, MultipartFile image) {
        userRepo.updateProfileImage(userDTO, image);
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
