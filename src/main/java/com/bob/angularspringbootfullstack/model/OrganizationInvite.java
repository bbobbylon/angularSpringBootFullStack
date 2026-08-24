package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * A pending, single-use organization invite — the {@code organizationinvites} row.
 *
 * <p>Follows the same DB-backed, expiring-single-use-token convention as
 * {@code resetpasswordverifications} rather than an in-memory ticket
 * ({@code ProviderLinkTicketService}'s shape, documented as a per-instance scaling limitation new
 * features should not repeat): {@link #code} is redeemed exactly once, and redemption deletes the
 * row (see {@code OrganizationService#redeemInvite}), so an expired-or-already-redeemed code both
 * resolve identically to "not found."
 *
 * <p>{@link #roleName} is the role granted on redemption, bounded at creation time by
 * {@code RoleType#canAssign} against the inviting administrator's own tier
 * ({@code OrganizationController#createInvite}) — an invite can never be used to mint a role more
 * privileged than its creator could otherwise assign directly.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class OrganizationInvite {
    private Long id;
    private Long organizationId;
    /** The administrator who created this invite, surfaced to the UI as {@link #invitedByEmail}. */
    private Long invitedByUserId;
    private String invitedByEmail;
    /** Opaque single-use token; the redeemable part of the invite link. */
    private String code;
    /** The role granted to whoever redeems this invite. */
    private String roleName;
    private LocalDateTime expirationDate;
    private LocalDateTime createdAt;
}
