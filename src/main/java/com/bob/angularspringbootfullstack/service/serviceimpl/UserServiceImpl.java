package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepo<User> userRepo;


    @Override
    public UserDTO createUser(User user) {
        // here is where we start connecting the layers - We are going to use the fromUser to be able to map the user to the userDTO. Call the repository > Take the user > runs the .create() method, and we pass it to fromUser, which creates a UserDTO, and returns that back to the controller
        return UserDTOMapper.fromUser(userRepo.create(user));

    }
}
