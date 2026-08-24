package com.bob.angularspringbootfullstack.form;

import lombok.Data;

/**
 * Request body for {@code POST /admin/organization/{id}/invites} — creates a pending, single-use
 * invite (self-service member onboarding, 2026-08-22). Both fields are optional:
 * {@code OrganizationController} defaults {@code roleName} to {@code ROLE_USER} and
 * {@code ttlHours} to one week when omitted, and bounds any explicitly requested {@code roleName}
 * by {@code RoleType#canAssign} against the creating administrator's own tier — the same guard
 * {@code AdminUserController#requireAssignableTier} applies to a direct role reassignment, so an
 * invite can never mint a role its creator could not otherwise assign.
 */
@Data
public class OrganizationInviteForm {

    /** The role granted on redemption; defaults to {@code ROLE_USER} when omitted or blank. */
    private String roleName;

    /** How many hours the invite remains redeemable; defaults to one week (168) when omitted or non-positive. */
    private Long ttlHours;
}
