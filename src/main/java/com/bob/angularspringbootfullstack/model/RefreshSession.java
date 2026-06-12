package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One row of the {@code refreshsessions} table (Flyway V6) — the stateful half of the
 * hybrid token model (plan.md M5, SRS FR-JWT-5).
 *
 * <p>A <b>session</b> from the user's point of view is a {@code family}: one login on one
 * device. Within a family, every refresh rotates to a new concrete token identified by
 * {@code jti}; the old row is flagged {@code superseded} and retained, because recognizing
 * an already-rotated token later is exactly how reuse (token theft) is detected.
 *
 * <p>Serialized to the SPA by the Account Security Center's sessions list. The
 * {@code jti} is excluded from JSON: it identifies the live refresh token, and while it
 * cannot forge one (the HMAC signature still rules), there is no reason to hand session
 * internals to the browser.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshSession {
    private Long id;
    @JsonIgnore
    private Long userId;
    /** Stable session identity across rotations; the unit of display and revocation. */
    private String family;
    /** The current refresh token's JWT ID within this family. Never serialized. */
    @JsonIgnore
    private String jti;
    /** Parsed "OS - Browser - Device" string captured when the session was opened/rotated. */
    private String device;
    private String ipAddress;
    private LocalDateTime createdAt;
    /** Stamped on every rotation — effectively "last seen". */
    private LocalDateTime lastUsedAt;
    private LocalDateTime expiresAt;
    @JsonIgnore
    private boolean revoked;
    @JsonIgnore
    private boolean superseded;
}
