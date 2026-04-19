package com.bob.angularspringbootfullstack.dtomapper;


import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import org.springframework.beans.BeanUtils;

/**
 * UserDTOMapper is a utility class for mapping between User and UserDTO objects.
 * This mapper uses Spring's BeanUtils to copy properties between objects, providing
 * a clean separation between the domain model (User) and the API contract (UserDTO).
 * This is a common pattern in layered architecture.
 * <p>
 * Why use a mapper?
 * - Decoupling: Changes to the User entity don't necessarily affect the API contract
 * - Security: Can control which fields are exposed (e.g., password is not copied)
 * - Flexibility: Can add complex transformation logic if needed
 */
public class UserDTOMapper {
    /**
     * Converts a User entity to a UserDTO object.
     * <p>
     * Uses Spring's BeanUtils.copyProperties which performs a shallow copy
     * of all matching property names from User to UserDTO. Since UserDTO
     * doesn't have a password field, the password is automatically excluded.
     *
     * @param user the User entity to convert
     * @return a UserDTO with user's data (excluding password)
     */
    public static UserDTO fromUser(User user) {
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        return userDTO;
    }

    // method overloading taking place here. This is for taking the user's role information along with the user.
    public static UserDTO fromUser(User user, Role role) {
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        userDTO.setRoleName(role.getName());
        userDTO.setPermissions(role.getPermission());
        return userDTO;
    }

    /**
     * Converts a UserDTO object to a User entity.
     * <p>
     * Uses Spring's BeanUtils.copyProperties to transfer all matching properties
     * from UserDTO back to User. Since UserDTO doesn't have password data,
     * the user's password field remains unset (null) in the resulting User entity.
     *
     * @param userDTO the UserDTO to convert
     * @return a User entity with DTO's data
     */
    public static User toUser(UserDTO userDTO) {
        User user = new User();
        BeanUtils.copyProperties(userDTO, user);
        return user;
    }

}
