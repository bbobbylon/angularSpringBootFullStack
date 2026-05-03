package com.bob.angularspringbootfullstack.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * UserDTO (Data Transfer Object) is used to expose user information to the API clients.
 * <p>
 * This DTO is a "view" of the User entity that excludes sensitive information
 * like passwords. It serves as the contract between the backend API and frontend client,
 * ensuring that only appropriate data is serialized and sent over the network.
 * <p>
 * <b>Key Purpose:</b> Prevent password (and other sensitive fields) from being serialized
 * to JSON and sent to clients, while providing all user profile information the frontend needs.
 * <p>
 * <b>Lombok @Data Annotation:</b> Automatically generates:
 * <ul>
 *   <li>@Getter: Getters for all fields (public String getEmail() { return email; })</li>
 *   <li>@Setter: Setters for all fields</li>
 *   <li>@ToString: toString() method that includes all fields (safe here since no password)</li>
 *   <li>@EqualsAndHashCode: equals() and hashCode() based on all fields</li>
 * </ul>
 * <p>
 * Unlike the User entity, this DTO:
 * - Does NOT include the password field (never sent to clients)
 * - Includes only data safe for public exposure
 * - Is used when returning user data in API responses
 * <p>
 * <b>Usage:</b>
 * <pre>
 * // Backend: Create DTO from User entity
 * User user = userRepo.getUserByEmail(email);
 * UserDTO dto = userDTOMapper.fromUser(user, role);
 *
 * // Return to client (JSON serialized):
 * {
 *   "id": 1,
 *   "firstName": "John",
 *   "lastName": "Doe",
 *   "email": "john@example.com",
 *   "roleName": "ROLE_USER",
 *   "permissions": "READ:USER,UPDATE:USER",
 *   // ... other fields
 *   // NOTE: password is NOT here!
 * }
 * </pre>
 * <p>
 * <b>toString() Safety:</b> Since this DTO has no password field, the auto-generated @ToString
 * is safe to use. Logging userDTO will show all fields without exposing passwords.
 * Example: UserDTO logs to server console as readable output with all user properties visible.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserDTO {
    /**
     * User's unique identifier
     */
    private Long id;
    /**
     * User's first name
     */
    private String firstName;
    /**
     * User's last name
     */
    private String lastName;
    /**
     * User's email address (used as username)
     */
    private String email;
    /**
     * User's profile image URL
     */
    private String imageUrl;
    /**
     * User's physical address
     */
    private String address;
    /**
     * User's phone number for 2FA
     */
    private String phoneNumber;
    /**
     * User's biography/description
     */
    private String bio;
    /**
     * User's professional title or designation
     */
    private String title;
    /**
     * Flag indicating if user account is enabled
     */
    private boolean enabled;
    /**
     * Flag indicating if user account is not locked
     */
    private boolean isNotLocked;
    /**
     * Flag indicating if user has 2-factor authentication enabled
     */
    private boolean isUsing2FA;
    /**
     * Timestamp of when the account was created
     */
    private LocalDateTime createdAt;
    /**
     * Role name assigned to the user (flattened from {@link com.bob.angularspringbootfullstack.model.Role}).
     */
    private String roleName;
    /**
     * Comma-separated permission string for the user's role (flattened from {@link com.bob.angularspringbootfullstack.model.Role}).
     */
    private String permissions;
}
