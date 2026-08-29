package com.bob.angularspringbootfullstack.event;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Signals that one organization's SSO/IdP configuration was just created, edited, its status
 * toggled, or deleted (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external IdP", Stage 2).
 *
 * <p>Published by {@code OrganizationIdentityProviderServiceImpl} after every write, and consumed by
 * {@link com.bob.angularspringbootfullstack.configuration.OrgAwareClientRegistrationRepository},
 * which evicts that organization's cached {@code ClientRegistration} so the change takes effect on
 * the very next login attempt rather than waiting out the cache's TTL.
 *
 * <p>An event, not a direct method call, deliberately: {@code OrgAwareClientRegistrationRepository}
 * already depends on {@code OrganizationIdentityProviderService} to resolve credentials, so a direct
 * call in the other direction (service → repository) would be a circular bean dependency. Publishing
 * this event — the same mechanism {@link NewOrganizationEvent} already uses for audit logging — keeps
 * the two beans decoupled.
 */
@EqualsAndHashCode(callSuper = true)
@Getter
public class OrgSsoConfigChangedEvent extends ApplicationEvent {
    private final Long organizationId;

    /**
     * @param organizationId the organization whose SSO configuration just changed
     */
    public OrgSsoConfigChangedEvent(Long organizationId) {
        super(organizationId);
        this.organizationId = organizationId;
    }
}
