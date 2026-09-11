package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.ApiKeyDTO;
import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.model.ApiKey;
import com.bob.angularspringbootfullstack.repo.ApiKeyRepo;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.enumeration.EventType.API_KEY_ISSUED;
import static com.bob.angularspringbootfullstack.utils.TotpUtils.sha256Hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApiKeyServiceImpl} (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 * {@link ApiKeyRepo} and {@link UserService} are mocked, so no database is involved and no real
 * key material is ever persisted.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceImplTest {

    private static final long SERVICE_ACCOUNT_ID = 42L;

    @Mock
    private ApiKeyRepo apiKeyRepo;
    @Mock
    private UserService userService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ApiKeyServiceImpl apiKeyService;

    private UserDTO serviceAccount(boolean enabled) {
        UserDTO dto = new UserDTO();
        dto.setId(SERVICE_ACCOUNT_ID);
        dto.setEmail("svc-bot@service.tessera.internal");
        dto.setEnabled(enabled);
        dto.setPermissions("READ:USER, UPDATE:USER");
        return dto;
    }

    @Test
    @DisplayName("issue() mints a tsk_-prefixed key, persists only its hash, and audits against the service account's email")
    void issueMintsAndPersistsHashedKeyOnly() {
        when(userService.getUserById(SERVICE_ACCOUNT_ID)).thenReturn(serviceAccount(true));
        when(apiKeyRepo.create(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String rawKey = apiKeyService.issue(SERVICE_ACCOUNT_ID, "CI pipeline", null, 1L);

        assertTrue(rawKey.startsWith("tsk_"), "raw key must carry the tsk_ prefix: " + rawKey);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepo).create(captor.capture());
        ApiKey persisted = captor.getValue();
        assertEquals(SERVICE_ACCOUNT_ID, persisted.getUserId());
        assertEquals("CI pipeline", persisted.getName());
        assertEquals(1L, persisted.getCreatedBy());
        // Only the hash is ever persisted — never the raw key itself.
        assertEquals(sha256Hex(rawKey), persisted.getKeyHash());
        assertEquals(rawKey.substring(0, 10), persisted.getKeyPrefix());

        ArgumentCaptor<NewUserEvent> eventCaptor = ArgumentCaptor.forClass(NewUserEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(API_KEY_ISSUED, eventCaptor.getValue().getEventType());
        assertEquals("svc-bot@service.tessera.internal", eventCaptor.getValue().getEmail());
    }

    @Test
    @DisplayName("issue() never mints the same raw key twice")
    void issueGeneratesDistinctKeysEachTime() {
        when(userService.getUserById(SERVICE_ACCOUNT_ID)).thenReturn(serviceAccount(true));
        when(apiKeyRepo.create(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String first = apiKeyService.issue(SERVICE_ACCOUNT_ID, "Key A", null, 1L);
        String second = apiKeyService.issue(SERVICE_ACCOUNT_ID, "Key B", null, 1L);

        assertFalse(first.equals(second), "two independently issued keys must not collide");
    }

    @Test
    @DisplayName("resolve() returns the service account and touches last_used_at on an active key")
    void resolveReturnsAccountAndTouchesLastUsedOnHit() {
        ApiKey stored = ApiKey.builder().id(5L).userId(SERVICE_ACCOUNT_ID).keyHash(sha256Hex("tsk_rawvalue")).build();
        when(apiKeyRepo.findActiveByHash(sha256Hex("tsk_rawvalue"))).thenReturn(Optional.of(stored));
        when(userService.getUserById(SERVICE_ACCOUNT_ID)).thenReturn(serviceAccount(true));

        Optional<UserDTO> resolved = apiKeyService.resolve("tsk_rawvalue");

        assertTrue(resolved.isPresent());
        assertEquals(SERVICE_ACCOUNT_ID, resolved.get().getId());
        verify(apiKeyRepo).touchLastUsed(5L);
    }

    @Test
    @DisplayName("resolve() rejects a deactivated service account even if the key itself is still active")
    void resolveRejectsDisabledServiceAccount() {
        ApiKey stored = ApiKey.builder().id(5L).userId(SERVICE_ACCOUNT_ID).keyHash(sha256Hex("tsk_rawvalue")).build();
        when(apiKeyRepo.findActiveByHash(sha256Hex("tsk_rawvalue"))).thenReturn(Optional.of(stored));
        when(userService.getUserById(SERVICE_ACCOUNT_ID)).thenReturn(serviceAccount(false));

        Optional<UserDTO> resolved = apiKeyService.resolve("tsk_rawvalue");

        assertTrue(resolved.isEmpty());
        // Deactivated accounts must not have their key's last-used bookkeeping updated either.
        verify(apiKeyRepo, never()).touchLastUsed(anyLong());
    }

    @Test
    @DisplayName("resolve() treats an unknown, revoked, or expired key as a plain miss")
    void resolveMissesForUnknownRevokedOrExpiredKey() {
        when(apiKeyRepo.findActiveByHash(any())).thenReturn(Optional.empty());

        Optional<UserDTO> resolved = apiKeyService.resolve("tsk_does-not-exist");

        assertTrue(resolved.isEmpty());
        verify(userService, never()).getUserById(any());
        verify(apiKeyRepo, never()).touchLastUsed(anyLong());
    }

    @Test
    @DisplayName("listForUser() maps every key to a DTO that never carries the hash")
    void listForUserMapsToDtoWithoutHash() {
        ApiKey active = ApiKey.builder().id(1L).userId(SERVICE_ACCOUNT_ID).name("Active")
                .keyPrefix("tsk_abcd12").keyHash("secret-hash").revoked(false).build();
        when(apiKeyRepo.findByUserId(SERVICE_ACCOUNT_ID)).thenReturn(List.of(active));

        List<ApiKeyDTO> keys = apiKeyService.listForUser(SERVICE_ACCOUNT_ID);

        assertEquals(1, keys.size());
        assertEquals("Active", keys.getFirst().getName());
        assertEquals("tsk_abcd12", keys.getFirst().getKeyPrefix());
        // ApiKeyDTO has no keyHash field at all — this is a compile-time guarantee, exercised here
        // by confirming the DTO that reaches an admin response only carries what ApiKeyDTO declares.
    }

    @Test
    @DisplayName("revoke() delegates straight to the repo")
    void revokeDelegatesToRepo() {
        apiKeyService.revoke(9L);

        verify(apiKeyRepo).revoke(9L);
    }

    @Test
    @DisplayName("findById() maps a found key to its DTO and stays empty for an unknown id")
    void findByIdMapsOrStaysEmpty() {
        ApiKey stored = ApiKey.builder().id(9L).userId(SERVICE_ACCOUNT_ID).name("Key").build();
        when(apiKeyRepo.findById(9L)).thenReturn(Optional.of(stored));
        when(apiKeyRepo.findById(404L)).thenReturn(Optional.empty());

        assertEquals(SERVICE_ACCOUNT_ID, apiKeyService.findById(9L).orElseThrow().getUserId());
        assertTrue(apiKeyService.findById(404L).isEmpty());
    }
}
