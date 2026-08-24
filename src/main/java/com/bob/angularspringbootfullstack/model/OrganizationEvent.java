package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * Represents a single audit entry from the {@code organizationevents} table, joined with the
 * shared {@code events} reference table for the human-readable type and description — the
 * organization-scoped counterpart to {@link UserEvent}.
 *
 * <p>{@code organizationevents} and {@code userevents} deliberately share one {@code events}
 * catalog rather than each maintaining its own type system (see the {@code ORG_*}
 * {@link com.bob.angularspringbootfullstack.enumeration.EventType} constants and
 * {@code schema.sql}'s {@code CK_Events_Type}), so a single audit-entry shape reads naturally for
 * both: this class differs from {@link UserEvent} only in carrying {@link #actorEmail} (who acted
 * on the organization) rather than being scoped to one user's own actions.
 *
 * <p>This is a read model — rows are written via
 * {@link com.bob.angularspringbootfullstack.service.OrganizationService#recordOrganizationEvent}
 * and read back via
 * {@link com.bob.angularspringbootfullstack.service.OrganizationService#listOrganizationEvents}.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class OrganizationEvent {
    /** Primary key of the {@code organizationevents} row. */
    private Long id;
    /** The category of action that occurred, e.g. {@code "ORG_MEMBER_ADDED"}. */
    private String type;
    /** The human-readable explanation shown in the organization's Activity panel. */
    private String description;
    /**
     * The email of the administrator who performed the action, or {@code null} when the actor's
     * account has since been deleted ({@code organizationevents.actor_user_id} is
     * {@code ON DELETE SET NULL} — the historical fact that the event happened is kept even when
     * the identity of who did it is not).
     */
    private String actorEmail;
    /** Optional free-form context for this audit row (e.g. the member's email on a membership event). */
    private String detail;
    /** When the event was recorded. */
    private LocalDateTime createdAt;
}
