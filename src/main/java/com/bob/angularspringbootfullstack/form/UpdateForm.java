package com.bob.angularspringbootfullstack.form;

import com.bob.angularspringbootfullstack.constants.PhonePolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * UpdateForm is a data transfer object for user update requests.
 * <p>
 * This form will be used to capture the updated profile information submitted by users when they want to update their account details.
 * It includes validation annotations to ensure that the required fields are provided and that the email and phone number formats are correct.
 * <p>
 * Fields:
 * - email: User's email address (used as username)
 * - firstName: User's first name (required, non-empty)
 * - lastName: User's last name (required, non-empty)
 * - imageUrl: URL to the user's profile image (optional)
 * - address: User's physical address (optional)
 * - phoneNumber: User's phone number (optional, must match valid phone number pattern)
 * - bio: User's biography or personal description (optional)
 * <p>
 * Note: The 'id' field is always set server-side from the authenticated JWT principal — any value the client sends is ignored. Do not include it in request payloads.
 */
@Data
public class UpdateForm {
    private Long id;
    @NotEmpty(message = "First name is required")
    private String firstName;
    @NotEmpty(message = "Last name is required")
    private String lastName;
    @Email(message = "Email is required")
    private String email;
    private String imageUrl;
    private String address;
    @Pattern(regexp = PhonePolicy.PATTERN, message = PhonePolicy.MESSAGE)
    private String phoneNumber;
    private String bio;
    private String title;
}
