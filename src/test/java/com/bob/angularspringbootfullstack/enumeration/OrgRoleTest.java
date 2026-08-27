package com.bob.angularspringbootfullstack.enumeration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@link OrgRole}'s fail-closed contract — the in-organization mirror of the guarantees
 * {@link RoleType#canAssign} and {@link RoleType#isOrganizationScoped(String)} make on the global
 * ladder.
 *
 * <p>Every assertion here is about a security boundary. An org role that resolves generously
 * (an unknown name treated as a grant, a ceiling that lets a member appoint an admin) hands out
 * authority the caller does not hold, and the failure is silent — which is why these are pinned
 * rather than left to review.
 */
class OrgRoleTest {

    @Test
    @DisplayName("the ladder is ORG_VIEWER < ORG_MEMBER < ORG_ADMIN")
    void tiersAreOrdered() {
        assertThat(OrgRole.ORG_VIEWER.getTier()).isLessThan(OrgRole.ORG_MEMBER.getTier());
        assertThat(OrgRole.ORG_MEMBER.getTier()).isLessThan(OrgRole.ORG_ADMIN.getTier());
    }

    @Test
    @DisplayName("the default capacity is ORG_MEMBER, matching schema.sql's column default")
    void defaultIsOrdinaryMembership() {
        assertThat(OrgRole.DEFAULT).isEqualTo(OrgRole.ORG_MEMBER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORG_ADMIN", "org_admin", "  Org_Admin  "})
    @DisplayName("from resolves case-insensitively and trims surrounding whitespace")
    void fromIsLenientAboutCasing(String name) {
        assertThat(OrgRole.from(name)).contains(OrgRole.ORG_ADMIN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "ORG_OVERLORD", "ROLE_ADMIN", "ADMIN"})
    @DisplayName("from returns empty for anything it does not recognize, including a global role name")
    void fromFailsClosed(String name) {
        assertThat(OrgRole.from(name)).isEmpty();
    }

    @Test
    @DisplayName("isAtLeast compares by tier, and is inclusive of equality")
    void isAtLeastIsInclusive() {
        assertThat(OrgRole.ORG_ADMIN.isAtLeast(OrgRole.ORG_ADMIN)).isTrue();
        assertThat(OrgRole.ORG_ADMIN.isAtLeast(OrgRole.ORG_VIEWER)).isTrue();
        assertThat(OrgRole.ORG_VIEWER.isAtLeast(OrgRole.ORG_ADMIN)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ORG_OVERLORD", "ROLE_ORGANIZATION_ADMIN"})
    @DisplayName("satisfies denies an unrecognized stored capacity rather than assuming one")
    void satisfiesFailsClosed(String stored) {
        assertThat(OrgRole.satisfies(stored, OrgRole.ORG_VIEWER)).isFalse();
        assertThat(OrgRole.satisfies(stored, OrgRole.ORG_ADMIN)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "ORG_ADMIN,  ORG_ADMIN,  true",
            "ORG_ADMIN,  ORG_MEMBER, true",
            "ORG_ADMIN,  ORG_VIEWER, true",
            "ORG_MEMBER, ORG_MEMBER, true",
            "ORG_MEMBER, ORG_ADMIN,  false",
            "ORG_VIEWER, ORG_MEMBER, false"
    })
    @DisplayName("canAssign permits equal-or-lower capacity and refuses elevation beyond the caller's own")
    void canAssignRefusesElevation(String caller, String target, boolean expected) {
        assertThat(OrgRole.canAssign(caller, target)).isEqualTo(expected);
    }

    @Test
    @DisplayName("canAssign fails closed when either side is unrecognized — including a non-member caller")
    void canAssignFailsClosedOnUnknownNames() {
        // A null caller role is what findOrgRole yields for somebody with no membership at all;
        // it must never be able to grant anything.
        assertThat(OrgRole.canAssign(null, "ORG_VIEWER")).isFalse();
        assertThat(OrgRole.canAssign("ORG_ADMIN", "ORG_OVERLORD")).isFalse();
        assertThat(OrgRole.canAssign("ORG_OVERLORD", "ORG_VIEWER")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ORGANIZATION_ADMIN", "ROLE_ADMIN", "ROLE_APPLICATION_ADMIN"})
    @DisplayName("an invite carrying an org-administering global role grants ORG_ADMIN in that org only")
    void invitesAtOrAboveOrgAdminTierGrantOrgAdmin(String invitedGlobalRole) {
        assertThat(OrgRole.fromInvitedGlobalRole(invitedGlobalRole)).isEqualTo(OrgRole.ORG_ADMIN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ROLE_USER", "ROLE_GUEST", "ROLE_MODERATOR", "ROLE_HELP_DESK_ADMIN", "NONSENSE"})
    @DisplayName("every lower or unrecognized invite role grants ordinary membership, never ORG_ADMIN")
    void invitesBelowOrgAdminTierGrantOrdinaryMembership(String invitedGlobalRole) {
        assertThat(OrgRole.fromInvitedGlobalRole(invitedGlobalRole)).isEqualTo(OrgRole.ORG_MEMBER);
    }
}
