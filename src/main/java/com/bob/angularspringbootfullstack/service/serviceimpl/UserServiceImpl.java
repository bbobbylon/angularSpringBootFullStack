package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.form.UpdateForm;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.service.NotificationService;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;

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
     * Delivery c./shannel for the FR-TPF-1 step-up code (email, not SMS — see {@link #sendStepUpCode}).
     */
    private final NotificationService notificationService;

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
     * {@inheritDoc}
     *
     * <p>The two halves live in different layers on purpose: the repository mints and persists the
     * code (data), while choosing to deliver it over <em>email</em> rather than SMS is a business
     * decision and therefore belongs here. That is also why this method — unlike
     * {@link #sendVerificationCode} — does not simply delegate to a single repository call.
     */
    @Override
    public void sendStepUpCode(UserDTO userDTO, String reasonSummary) {
        String code = userRepo.issueVerificationCode(userDTO);
        notificationService.sendStepUpCode(userDTO.getFirstName(), userDTO.getEmail(), code, reasonSummary);
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
     * Completes the forgot-password reset flow.
     * <p>
     * Thin delegation to {@link com.bob.angularspringbootfullstack.repo.UserRepo#setNewPassword(Long, String, String)}.
     * The userID is supplied by the controller from the {@link com.bob.angularspringbootfullstack.form.NewPasswordForm}
     * request body, which was populated on the client from the prior
     * {@link #verifyPasswordKey(String)} response.
     *
     * @param userID          the user whose password is being reset
     * @param newPassword     the new password
     * @param confirmPassword must match {@code newPassword}
     */
    @Override
    public void setNewPassword(Long userID, String newPassword, String confirmPassword) {
        userRepo.setNewPassword(userID, newPassword, confirmPassword);
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
     * Toggles a user's multifactor authentication status.
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
     * Pages through the user directory for the administrative dashboard (FR-ADMIN-1).
     * <p>
     * Delegates filtering/pagination to the repository, then enriches each row with its
     * role via {@link #mapToUserDTO(User)}. That role lookup is one extra query per row,
     * which is acceptable at the bounded page size (default 10, hard cap 100 in the
     * repository); switching to a single JOIN query is a known optimization if directory
     * pages ever grow.
     *
     * @param searchTerm free-text filter; blank or null lists everyone
     * @param page       0-indexed page number
     * @param pageSize   rows per page
     * @return the matching users on the requested page, newest accounts first
     */
    @Override
    public Collection<UserDTO> searchUsers(String searchTerm, int page, int pageSize) {
        return userRepo.searchUsers(searchTerm, page, pageSize).stream()
                .map(this::mapToUserDTO)
                .toList();
    }

    /**
     * Counts the users matching the same directory filter as {@link #searchUsers},
     * for total-pages metadata in the admin UI.
     *
     * @param searchTerm free-text filter; blank or null counts everyone
     * @return the total number of matching users
     */
    @Override
    public long countUsers(String searchTerm) {
        return userRepo.countUsers(searchTerm);
    }

    /**
     * Looks up the user's role and converts the User entity into a UserDTO
     * with roleName and permissions populated.
     *
     * @param user the persisted user entity
     * @return the DTO view of the user including a role and permissions
     */
    private UserDTO mapToUserDTO(User user) {
        return fromUser(user, roleRepo.getRoleByUserId(user.getId()));
    }
}
