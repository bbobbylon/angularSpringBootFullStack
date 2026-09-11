package com.bob.angularspringbootfullstack.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Admin-facing view of an {@link com.bob.angularspringbootfullstack.model.ApiKey} — every field
 * except the secret itself. Never carries {@code keyHash}, and carries the raw key only on the
 * one response that mints it ({@code AdminServiceAccountController#issueApiKey}'s
 * {@code rawKey} field lives beside this DTO, not on it, so a list/get response can never
 * accidentally replay a secret that already left the server once).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyDTO {
    private Long id;
    private Long userId;
    private String name;
    private String keyPrefix;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime lastUsedAt;
    private LocalDateTime expiresAt;
    private boolean revoked;
}
