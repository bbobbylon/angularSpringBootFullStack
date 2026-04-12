package com.bob.angularspringbootfullstack.dtomapper;


import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.User;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

// this will map a UserDTO into a User and vice versa. We will also mark it as @Component so that it gets picked up by Spring.
@Component
public class UserDTOMapper {
    // from User object TO UserDTO object
    public static UserDTO fromUser(User user) {
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        return userDTO;
    }

    public static User toUser(UserDTO userDTO) {
        User user = new User();
        BeanUtils.copyProperties(userDTO, user);
        return user;
    }

}
