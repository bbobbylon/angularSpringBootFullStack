package com.bob.angularspringbootfullstack.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Admin-facing view of an {@link com.bob.angularspringbootfullstack.model.OAuthClient} — every
 * field except the secret itself. Never carries {@code clientSecretHash}, and the raw secret is
 * returned only on the one response that mints it
 * ({@code AdminServiceAccountController#registerOAuthClient}'s {@code rawClientSecret} field lives
 * beside this DTO, not on it), same convention as {@link ApiKeyDTO}. Unlike {@code ApiKeyDTO},
 * {@link #clientId} is carried in full rather than as a truncated prefix — it is the public half
 * of the credential pair, not a secret.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthClientDTO {
    private Long id;
    private Long userId;
    private String clientId;
    private String name;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime lastUsedAt;
    private boolean revoked;
}
