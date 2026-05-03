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

    @Override
    public UserDTO verifyPasswordKey(String key) {
        return mapToUserDTO(userRepo.verifyPasswordKey(key));
    }

    @Override
    public void setNewPassword(String key, String newPassword, String confirmPassword) {
        userRepo.setNewPassword(key, newPassword, confirmPassword);
    }

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
