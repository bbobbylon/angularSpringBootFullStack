package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.fromUser;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepo<User> userRepo;

    /**
     * Creates a new user in the system through the repository layer.
     * Delegates user creation to the UserRepo, then converts the resulting User
     * entity to a UserDTO for exposure to the presentation layer.
     * <p>
     * This method bridges the service layer and repository layer, ensuring
     * that domain models (User) are not exposed to the controller layer.
     *
     * @param user the user entity containing registration information
     * @return a UserDTO representing the newly created user
     */
    @Override
    public UserDTO createUser(User user) {
        return fromUser(userRepo.create(user));
    }

    /**
     * Retrieves a user by their email address from the repository.
     * Converts the User entity to a UserDTO for the presentation layer.
     *
     * @param email the email address to search for
     * @return a UserDTO if user is found, otherwise throws an exception
     * @throws ApiException if user is not found in the database
     */
    @Override
    public UserDTO getUserByEmail(String email) {
        return fromUser(userRepo.getUserByEmail(email));
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

    @Override
    public User getUser(String email) {
        return userRepo.getUserByEmail(email);
    }

    @Override
    public UserDTO verifyCode(String email, String code) {
        return fromUser(userRepo.verifyCode(email, code));
    }
}
