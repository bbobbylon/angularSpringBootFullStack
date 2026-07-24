package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Authorization guard for the admin-only analytics surface, {@link AnalyticsController}
 * ({@code GET /admin/analytics/**}).
 *
 * <p><b>What this locks in.</b> The billing and analytics dashboards visualise aggregate
 * financial data that must be admin-only, but they reuse the same underlying
 * customer/invoice/stats data the application serves to <em>every</em> authenticated user
 * through {@code /customer/**} (the home dashboard needs it). The fix was to re-expose that
 * data under {@code /admin/analytics/**} behind a real server-side authority check, so a
 * plain {@code ROLE_USER} who bypasses the SPA's {@code adminGuard} and calls the API
 * directly is refused. This test proves the check actually bites.
 *
 * <p><b>Why a method-security slice, not {@code standaloneSetup}.</b>
 * {@link org.springframework.test.web.servlet.setup.MockMvcBuilders#standaloneSetup} (used
 * by {@link AdminUserControllerTest}) does <em>not</em> activate {@link PreAuthorize} — the
 * annotation is enforced by the Spring Security AOP interceptor that {@link
 * EnableMethodSecurity} installs. So a standalone test would let an unauthorised call run
 * straight through and prove nothing. Here a minimal context proxies just the controller
 * (services mocked) with method security enabled, and the {@link SecurityContextHolder} is
 * populated directly — no {@code spring-security-test} dependency, consistent with the rest
 * of the suite. No web layer, no database: the URL-level {@code /admin/**} matcher in
 * {@code SecurityConfig} is the redundant first layer; this asserts the method-level second
 * layer independently (defence in depth, FR-RBAC-2).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AnalyticsControllerSecurityTest.Config.class)
class AnalyticsControllerSecurityTest {

    /**
     * Minimal context: method security enabled, the controller proxied, its collaborators
     * mocked. Autowiring {@link AnalyticsController} therefore yields the security-advised
     * proxy, so calls run through the {@link PreAuthorize} interceptor.
     */
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        CustomerService customerService() {
            return mock(CustomerService.class);
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        AnalyticsController analyticsController(CustomerService customerService, UserService userService) {
            return new AnalyticsController(customerService, userService);
        }
    }

    @Autowired
    private AnalyticsController controller;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private UserService userService;

    /** The principal the endpoints embed in their envelope; identity is irrelevant to the authority check. */
    private static UserDTO principal() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setEmail("caller@example.com");
        return user;
    }

    /**
     * The mocked services are context-managed singletons shared across every test method,
     * so their Mockito invocation history must be reset between tests — otherwise the
     * allowed-path tests' calls leak into {@link #nonAdminIsForbidden}'s
     * {@code verifyNoInteractions} cross-check.
     */
    @BeforeEach
    void resetMocks() {
        reset(customerService, userService);
    }

    /** Installs an authenticated context carrying exactly the supplied authorities. */
    private static void authenticateWith(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal(), null, AuthorityUtils.createAuthorityList(authorities)));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a non-admin (READ:USER, READ:CUSTOMER) is refused every analytics endpoint with 403")
    void nonAdminIsForbidden() {
        // These are exactly the authorities ROLE_USER holds — enough to reach the shared
        // /customer/** GETs, but they must NOT unlock the aggregate analytics surface.
        authenticateWith("READ:USER", "READ:CUSTOMER");
        UserDTO caller = principal();

        assertThatThrownBy(() -> controller.getSummary(caller))
                .as("summary must require an admin authority")
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getCustomers(caller, Optional.empty(), Optional.empty()))
                .as("customers must require an admin authority")
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getInvoices(caller, Optional.empty(), Optional.empty()))
                .as("invoices must require an admin authority")
                .isInstanceOf(AccessDeniedException.class);

        // The @PreAuthorize denies BEFORE the method body, so no data is ever loaded.
        verifyNoInteractions(customerService);
    }

    @Test
    @DisplayName("an admin with UPDATE:USER is allowed through to the data")
    void updateUserAuthorityIsAllowed() {
        authenticateWith("UPDATE:USER");
        when(userService.getUserByEmail(any())).thenReturn(new UserDTO());
        when(customerService.getInvoices(anyInt(), anyInt())).thenReturn(Page.<Invoice>empty());

        ResponseEntity<HttpResponse> response =
                controller.getInvoices(principal(), Optional.of(0), Optional.of(20));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("UPDATE:ROLE also satisfies the analytics authority requirement (the OR branch)")
    void updateRoleAuthorityIsAllowed() {
        authenticateWith("UPDATE:ROLE");
        when(userService.getUserByEmail(any())).thenReturn(new UserDTO());
        when(customerService.getInvoices(anyInt(), anyInt())).thenReturn(Page.<Invoice>empty());

        ResponseEntity<HttpResponse> response =
                controller.getInvoices(principal(), Optional.of(0), Optional.of(20));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
