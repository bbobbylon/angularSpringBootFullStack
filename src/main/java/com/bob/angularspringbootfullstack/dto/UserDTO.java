package com.bob.angularspringbootfullstack.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * UserDTO (Data Transfer Object) is used to expose user information to the API clients.
 * <p>
 * This DTO is a "view" of the User entity that excludes sensitive information
 * like passwords. It serves as the contract between the backend API and frontend client,
 * ensuring that only appropriate data is serialized and sent over the network.
 * <p>
 * Unlike the User entity, this DTO:
 * - Does NOT include the password field (never sent to clients)
 * - Includes only data safe for public exposure
 * - Is used when returning user data in API responses
 * <p>
 * Fields match the User entity except for password.
 */
@Getter
@Setter
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
    private String roleName;
    private String permissions;
}
