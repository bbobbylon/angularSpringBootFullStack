package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.User;
import jakarta.validation.constraints.NotEmpty;

// when the controller/resources calls on the UserService, we will give it the UserDTO instead of the User object since it won't have the password.
public interface UserService {
    UserDTO createUser(User user);

    UserDTO getUserByEmail(@NotEmpty String email);

    void sendVerificationCode(UserDTO userDTO);
}
