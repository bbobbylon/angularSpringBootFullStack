package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * Represents a single audit entry from the {@code userevents} table, joined
 * with the {@code events} reference table to include the human-readable type
 * and description.
 *
 * <p>This is a read model — rows are never written through this class directly.
 * New entries are inserted via
 * {@link com.bob.angularspringbootfullstack.repo.EventRepo#addUserEvent} and
 * read back by
 * {@link com.bob.angularspringbootfullstack.repo.EventRepo#getEventsByUserId}.
 *
 * <p>{@code @JsonInclude(NON_DEFAULT)} suppresses null fields so the JSON
 * payload stays small when optional columns are absent.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class UserEvent {
    /** Primary key of the {@code userevents} row. */
    private Long id;
    /** The category of action that occurred, e.g. {@code "LOGIN_ATTEMPT_SUCCESS"}. */
    private String type;
    /** OS, browser, and device name parsed from the User-Agent header at the time of the event. */
    private String device;
    /** The human-readable explanation shown in the Activity Logs UI. */
    private String description;
    /** The IP address from which the request originated. */
    private String ipAddress;
    /**
     * Optional free-form context for this audit row (FR-FED-5): the federated provider name
     * ({@code google} | {@code github} | {@code microsoft}) on a {@code FEDERATED_LOGIN}, and
     * {@code null} for events with no extra detail. Suppressed from JSON when null by
     * {@code @JsonInclude(NON_DEFAULT)}.
     */
    private String detail;
    /** When the event was recorded. Used as the default sort column in the Activity Logs table. */
    private LocalDateTime createdAt;
}
