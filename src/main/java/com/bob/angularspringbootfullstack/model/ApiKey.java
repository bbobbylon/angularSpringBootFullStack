package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * A machine credential for one service account (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 * <p>
 * Maps to the {@code apikeys} table ({@code schema.sql}). {@link #keyHash} is a SHA-256 digest
 * ({@code TotpUtils#sha256Hex}) of the raw {@code tsk_...} key — never the raw key itself, which
 * exists only for the instant {@code ApiKeyServiceImpl#issue} mints it and returns it to the
 * caller. {@link #keyPrefix} is the non-secret leading segment shown in the admin UI so an
 * operator can tell keys apart without ever re-deriving the secret. {@code @JsonIgnore} on
 * {@link #keyHash} is redundant defense — no code path currently serializes this model directly
 * to a client, {@code ApiKeyServiceImpl#listForUser} maps to a DTO first — but a hash is still not
 * something a future accidental serialization should leak.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class ApiKey {
    private Long id;
    private Long userId;
    private String name;
    private String keyPrefix;
    @JsonIgnore
    private String keyHash;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime lastUsedAt;
    private LocalDateTime expiresAt;
    private boolean revoked;
}
