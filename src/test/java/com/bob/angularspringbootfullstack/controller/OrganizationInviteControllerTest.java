package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewOrganizationEvent;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.model.Organization;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural guard for {@link OrganizationInviteController} — the self-service invite-redemption
 * counterpart to {@link OrganizationController}'s admin-facing invite management (dashboard
 * revamp, 2026-08-22). Unlike {@link OrganizationControllerTest}, there is no authorization rule
 * of this controller's own to exercise: both endpoints require only {@code .authenticated()} at
 * the {@code SecurityConfig} URL level (not re-tested here, matching
 * {@link OrganizationControllerTest}'s scope for the {@code UPDATE:ORGANIZATION} URL gate), so
 * these tests focus on the "not found" collapsing behaviour (NFR-SEC-7) and the happy path.
 */
class OrganizationInviteControllerTest {

    private OrganizationService organizationService;
    private ApplicationEventPublisher eventPublisher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        organizationService = mock(OrganizationService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        OrganizationInviteController controller = new OrganizationInviteController(organizationService, eventPublisher);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Authentication authAs(long id) {
        UserDTO caller = new UserDTO();
        caller.setId(id);
        caller.setEmail("caller@example.com");
        caller.setRoleName("ROLE_USER");
        return new UsernamePasswordAuthenticationToken(caller, null, AuthorityUtils.NO_AUTHORITIES);
    }

    @Test
    @DisplayName("previewInvite resolves a live code to its organization's name")
    void previewInviteResolvesName() throws Exception {
        when(organizationService.previewInvite("live")).thenReturn(Optional.of("Acme"));

        mockMvc.perform(get("/user/organization/invite/{code}", "live").principal(authAs(1L)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("previewInvite resolves an unknown or expired code to a 400, never a raw 404/500")
    void previewInviteUnknownCodeIsHandled() throws Exception {
        when(organizationService.previewInvite("bogus")).thenReturn(Optional.empty());

        mockMvc.perform(get("/user/organization/invite/{code}", "bogus").principal(authAs(1L)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("redeemInvite joins the caller to the organization and publishes an audit event")
    void redeemInviteJoinsAndPublishes() throws Exception {
        Organization organization = Organization.builder().id(9L).name("Acme").build();
        when(organizationService.redeemInvite("live", 1L)).thenReturn(organization);

        mockMvc.perform(post("/user/organization/invite/{code}/redeem", "live").principal(authAs(1L)))
                .andExpect(status().isOk());

        verify(organizationService).redeemInvite("live", 1L);
        verify(eventPublisher).publishEvent(any(NewOrganizationEvent.class));
    }
}
