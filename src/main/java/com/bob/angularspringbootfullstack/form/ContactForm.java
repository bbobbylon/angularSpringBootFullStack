package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for the public {@code POST /contact} endpoint (the Contact Us page).
 * <p>
 * Unlike every other form in this package, the submitter is never authenticated and the address
 * on the form is never verified — it is only ever used as an email {@code Reply-To}, so nothing
 * here trusts it as an identity claim.
 */
@Data
public class ContactForm {

    /** The visitor's supplied name, used in the email greeting/attribution. */
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be 100 characters or fewer")
    private String name;

    /** The visitor's supplied reply-to address. Never verified — see class Javadoc. */
    @NotBlank(message = "Email is required")
    @Email(message = "A valid email address is required")
    private String email;

    /** The visitor's supplied subject line. */
    @NotBlank(message = "Subject is required")
    @Size(max = 150, message = "Subject must be 150 characters or fewer")
    private String subject;

    /** The visitor's supplied message body. */
    @NotBlank(message = "Message is required")
    @Size(max = 5000, message = "Message must be 5000 characters or fewer")
    private String message;
}
