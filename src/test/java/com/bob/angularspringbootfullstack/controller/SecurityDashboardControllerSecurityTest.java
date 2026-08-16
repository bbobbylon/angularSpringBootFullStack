package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.form.AnomalySettingsForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.model.SecuritySettings;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.SecurityDashboardService;
import com.bob.angularspringbootfullstack.service.SecuritySettingsService;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Authorization guard for the admin-only security dashboard surface, {@link SecurityDashboardController}
 * ({@code /admin/security/**}), including the anomaly-settings endpoints added for
 * FUTURE-ENHANCEMENTS "Anomaly signal tuning UI".
 *
 * <p>Same shape as {@link AnalyticsControllerSecurityTest} — a method-security slice with the
 * controller proxied and its collaborators mocked, so {@link PreAuthorize} is genuinely enforced
 * rather than bypassed the way {@code standaloneSetup} would bypass it. No web layer, no database:
 * this asserts the method-level authority gate, independent of the URL-level {@code /admin/**}
 * matcher in {@code SecurityConfig}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SecurityDashboardControllerSecurityTest.Config.class)
class SecurityDashboardControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        SecurityDashboardService securityDashboardService() {
            return mock(SecurityDashboardService.class);
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        OrganizationService organizationService() {
            return mock(OrganizationService.class);
        }

        @Bean
        SecuritySettingsService securitySettingsService() {
            return mock(SecuritySettingsService.class);
        }

        @Bean
        SecurityDashboardController securityDashboardController(SecurityDashboardService securityDashboardService,
                                                                 UserService userService,
                                                                 OrganizationService organizationService,
                                                                 SecuritySettingsService securitySettingsService) {
            return new SecurityDashboardController(securityDashboardService, userService, organizationService, securitySettingsService);
        }
    }

    @Autowired
    private SecurityDashboardController controller;
    @Autowired
    private SecurityDashboardService securityDashboardService;
    @Autowired
    private UserService userService;
    @Autowired
    private SecuritySettingsService securitySettingsService;

    private static UserDTO principal() {
        UserDTO user = new UserDTO();
        user.setId(9L);
        user.setEmail("caller@example.com");
        return user;
    }

    @BeforeEach
    void resetMocks() {
        reset(securityDashboardService, userService, securitySettingsService);
    }

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
    @DisplayName("a non-admin (READ:USER, READ:CUSTOMER) is refused every endpoint with 403")
    void nonAdminIsForbidden() {
        authenticateWith("READ:USER", "READ:CUSTOMER");
        UserDTO caller = principal();

        assertThatThrownBy(() -> controller.getOverview(caller, Optional.empty(), 0, 50, 0, 50))
                .as("overview must require an admin authority")
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getAnomalySettings(caller))
                .as("reading anomaly settings must require an admin authority")
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.updateAnomalySettings(caller, new AnomalySettingsForm()))
                .as("writing anomaly settings must require an admin authority")
                .isInstanceOf(AccessDeniedException.class);

        // The @PreAuthorize denies BEFORE the method body, so no data is ever loaded or written.
        verifyNoInteractions(securityDashboardService, securitySettingsService);
    }

    @Test
    @DisplayName("an admin with UPDATE:USER can read the anomaly settings")
    void updateUserAuthorityCanReadSettings() {
        authenticateWith("UPDATE:USER");
        when(userService.getUserByEmail(any())).thenReturn(new UserDTO());
        when(securitySettingsService.getSettings()).thenReturn(SecuritySettings.builder().build());

        ResponseEntity<HttpResponse> response = controller.getAnomalySettings(principal());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("UPDATE:ROLE also satisfies the authority requirement for writing anomaly settings")
    void updateRoleAuthorityCanWriteSettings() {
        authenticateWith("UPDATE:ROLE");
        UserDTO caller = principal();
        when(userService.getUserByEmail(any())).thenReturn(new UserDTO());
        when(securitySettingsService.updateSettings(any(), any(), eq(caller.getId())))
                .thenReturn(SecuritySettings.builder().build());

        AnomalySettingsForm form = new AnomalySettingsForm();
        form.setEnabled(false);
        ResponseEntity<HttpResponse> response = controller.updateAnomalySettings(caller, form);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("an admin with UPDATE:ROLE can load the overview")
    void updateRoleAuthorityCanReadOverview() {
        authenticateWith("UPDATE:ROLE");
        when(userService.getUserByEmail(any())).thenReturn(new UserDTO());
        when(securityDashboardService.getOverview(isNull(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(SecurityOverview.empty(7));

        ResponseEntity<HttpResponse> response =
                controller.getOverview(principal(), Optional.empty(), 0, 50, 0, 50);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
