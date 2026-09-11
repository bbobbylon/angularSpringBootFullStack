package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Request body for {@code POST /admin/serviceaccounts/{id}/apikeys} (FUTURE-ENHANCEMENTS.md
 * §3.1, P2-3 Option A) — issuing a new API key for a service account.
 */
@Data
public class IssueApiKeyForm {

    /**
     * A caller-chosen label for this key, e.g. {@code "CI pipeline"}, shown alongside its
     * {@code keyPrefix} in the admin panel so a key can be recognized without ever re-displaying
     * the raw secret.
     */
    @NotBlank(message = "Name is required")
    private String name;

    /**
     * Optional expiry. {@code null} means the key never expires.
     */
    private LocalDateTime expiresAt;
}
