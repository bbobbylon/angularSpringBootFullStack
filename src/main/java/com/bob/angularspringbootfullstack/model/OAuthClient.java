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
 * A machine credential pair for one service account, authenticated over RFC 6749 §4.4
 * (client-credentials grant) instead of the {@code X-API-Key} header
 * (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option B).
 * <p>
 * Maps to the {@code oauthclients} table ({@code schema.sql}). Unlike {@link ApiKey}'s
 * {@code keyHash} (SHA-256, checked on every request), {@link #clientSecretHash} is bcrypt — this
 * credential is verified once per token mint (at most every {@code ACCESS_TOKEN_EXPIRE_TIME}),
 * not on every request, so bcrypt's per-row salt is worth its cost here. {@link #clientId} is not
 * secret — it is the public half of the pair, shown permanently in the admin UI, unlike
 * {@link ApiKey#getKeyPrefix()} which is only ever a truncated hint. {@code @JsonIgnore} on
 * {@link #clientSecretHash} is redundant defense, same reasoning as {@code ApiKey#keyHash}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class OAuthClient {
    private Long id;
    private Long userId;
    private String clientId;
    @JsonIgnore
    private String clientSecretHash;
    private String name;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime lastUsedAt;
    private boolean revoked;
}
