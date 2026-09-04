package com.bob.angularspringbootfullstack.utils;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.RoleType;
import com.bob.angularspringbootfullstack.service.OrganizationService;

import java.util.Collection;

/**
 * The single definition of <em>which tenants a caller may see</em> — the one rule the shared-schema
 * multi-tenancy model documented in {@code documentation/FUTURE-ENHANCEMENTS.md} §6.1 rests on.
 *
 * <p><b>Why this class exists.</b> This rule was previously copy-pasted as a private
 * {@code resolveScope} method into {@link com.bob.angularspringbootfullstack.controller.CustomerController},
 * {@link com.bob.angularspringbootfullstack.controller.AnalyticsController},
 * {@link com.bob.angularspringbootfullstack.controller.OrganizationController}, and
 * {@link com.bob.angularspringbootfullstack.controller.SecurityDashboardController}. That is four
 * copies of a security boundary, and the duplication has already caused a real cross-tenant leak:
 * the copies compared the caller's role against the literal string {@code "ROLE_ORGANIZATION_ADMIN"},
 * the analytics copy was corrected on 2026-08-13, and the customer and security-dashboard copies
 * kept showing every organization's data to a plain {@code ROLE_USER} until 2026-08-21. Nothing
 * about that failure was visible at the call sites — each controller looked individually correct.
 * Centralising the rule means the next correction cannot land on three of four surfaces.
 *
 * <p><b>Why a static helper rather than a Spring bean.</b> The four controllers' scope suites
 * ({@code CustomerControllerOrgScopeTest} and siblings) are plain Mockito tests using
 * {@code @InjectMocks}, and they assert on the collaborator this rule delegates to — including
 * {@code verify(organizationService, never()).findActiveOrganizationIds(...)} to prove an unscoped
 * caller never even asks for a scope. Injecting a new collaborator would have forced those
 * assertions to be rewritten against a mock of <em>this</em> class, which would have replaced a test
 * of real behaviour with a test of a stub. Taking {@link OrganizationService} as a parameter keeps
 * every existing assertion meaningful and exercises the real rule.
 *
 * @see RoleType#isOrganizationScoped(String)
 * @see OrganizationService#findActiveOrganizationIds(Long)
 */
public final class OrganizationScope {

    private OrganizationScope() {
        // Utility holder for a single rule; never instantiated.
    }

    /**
     * Resolves the organization scope a caller's reads must be restricted to.
     *
     * <p>The return value has three distinct meanings, and conflating any two of them is a tenancy
     * bug rather than a cosmetic one:
     *
     * <ul>
     *   <li>{@code null} — the caller is an <em>unscoped platform operator</em>
     *       ({@code ROLE_ADMIN} / {@code ROLE_APPLICATION_ADMIN}). No tenant predicate applies and
     *       callers should take their unscoped query path. This is the only case where seeing
     *       everything is correct.</li>
     *   <li>a non-empty collection — the caller is a tenant user, and every query made on their
     *       behalf must carry this set as an {@code IN} predicate <em>inside the SQL</em>. Filtering
     *       the result set instead is not equivalent: an aggregate has already discarded its
     *       attribution by the time it is a number, and post-filtering a page corrupts
     *       {@code totalElements}.</li>
     *   <li>an <em>empty</em> collection — the caller is a tenant user belonging to no active
     *       organization, and must therefore see <em>nothing</em>. It emphatically does not mean
     *       "no restriction". Callers are expected to reject this before it reaches the database
     *       (see {@code CustomerServiceImpl#requireScope}), because an empty {@code IN ()} is not a
     *       reliably-defined query.</li>
     * </ul>
     *
     * <p>The role test is {@link RoleType#isOrganizationScoped(String)}, which is fail-closed: an
     * unrecognized or renamed role resolves to <em>scoped</em>, never to platform-wide access. A
     * typo in a role name therefore costs a tenant admin some visibility rather than handing a
     * stranger the whole database.
     *
     * @param caller              the authenticated principal, as resolved by {@code CustomAuthFilter}
     * @param organizationService used to look up the caller's active memberships; only consulted
     *                            when the caller is actually scoped, so an unscoped caller costs no
     *                            query
     * @return {@code null} for an unscoped platform operator, otherwise the caller's active
     *         organization ids (possibly empty, meaning "nothing")
     */
    public static Collection<Long> resolve(UserDTO caller, OrganizationService organizationService) {
        if (!RoleType.isOrganizationScoped(caller.getRoleName())) {
            return null;
        }
        return organizationService.findActiveOrganizationIds(caller.getId());
    }
}
