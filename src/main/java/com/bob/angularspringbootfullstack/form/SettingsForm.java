package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for the {@code PATCH /user/update/settings} endpoint.
 * <p>
 * Carries the two account-level flags that an admin (or the user themselves,
 * depending on permission) can toggle from the Authorization tab:
 * <ul>
 *   <li>{@code enabled} — whether the account is active and can log in</li>
 *   <li>{@code notLocked} — whether the account is currently unlocked</li>
 * </ul>
 * Both fields are mandatory; a {@code null} value fails Bean Validation before
 * the controller method is entered.
 */
@Data
public class SettingsForm {

    /**
     * Whether the user account should be active.
     * {@code true} means the account can log in; {@code false} disables it.
     */
    @NotNull(message = "Enabled checkbox cannot be empty")
    private Boolean enabled;

    /**
     * Whether the user account should be unlocked.
     * {@code true} means the account is accessible; {@code false} locks it out.
     */
    @NotNull(message = "notLocked checkbox cannot be empty")
    private Boolean notLocked;
}
