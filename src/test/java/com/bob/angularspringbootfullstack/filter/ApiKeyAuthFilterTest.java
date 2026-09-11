package com.bob.angularspringbootfullstack.filter;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link ApiKeyAuthFilter}'s precedence rule and authentication shape
 * (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A). {@link ApiKeyService} is mocked, so no database is
 * involved.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    @Mock
    private ApiKeyService apiKeyService;

    /** Counts how many requests reached the application, mirroring {@code RateLimitFilterTest}. */
    private static final class CountingFilterChain implements FilterChain {
        private int invocations;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            invocations++;
        }
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private UserDTO serviceAccount() {
        UserDTO dto = new UserDTO();
        dto.setId(42L);
        dto.setEmail("svc-bot@service.tessera.internal");
        dto.setPermissions("READ:USER, UPDATE:USER");
        return dto;
    }

    @Test
    @DisplayName("a valid API key authenticates the request with its permissions as authorities")
    void validKeyAuthenticates() throws Exception {
        when(apiKeyService.resolve("tsk_valid")).thenReturn(Optional.of(serviceAccount()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/list");
        request.addHeader("X-API-Key", "tsk_valid");
        CountingFilterChain chain = new CountingFilterChain();

        new ApiKeyAuthFilter(apiKeyService).doFilter(request, new MockHttpServletResponse(), chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication != null && authentication.isAuthenticated());
        assertEquals(42L, ((UserDTO) authentication.getPrincipal()).getId());
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("READ:USER")));
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("UPDATE:USER")));
        assertEquals(1, chain.invocations, "the chain must always continue");
    }

    @Test
    @DisplayName("an unknown, revoked, or expired key leaves the security context clear")
    void invalidKeyLeavesContextClear() throws Exception {
        when(apiKeyService.resolve("tsk_bad")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/list");
        request.addHeader("X-API-Key", "tsk_bad");
        CountingFilterChain chain = new CountingFilterChain();

        new ApiKeyAuthFilter(apiKeyService).doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(1, chain.invocations, "the chain must always continue so the 401 entry point can respond");
    }

    @Test
    @DisplayName("X-API-Key is ignored when an Authorization: Bearer header is also present")
    void bearerHeaderTakesPrecedenceOverApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/list");
        request.addHeader("X-API-Key", "tsk_valid");
        request.addHeader("Authorization", "Bearer some.jwt.value");
        CountingFilterChain chain = new CountingFilterChain();

        new ApiKeyAuthFilter(apiKeyService).doFilter(request, new MockHttpServletResponse(), chain);

        verify(apiKeyService, never()).resolve(org.mockito.ArgumentMatchers.any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(1, chain.invocations);
    }

    @Test
    @DisplayName("a request with no X-API-Key header is skipped entirely")
    void noApiKeyHeaderIsSkipped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/list");
        CountingFilterChain chain = new CountingFilterChain();

        new ApiKeyAuthFilter(apiKeyService).doFilter(request, new MockHttpServletResponse(), chain);

        verify(apiKeyService, never()).resolve(org.mockito.ArgumentMatchers.any());
        assertEquals(1, chain.invocations);
    }
}
