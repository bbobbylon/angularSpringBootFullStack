package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.OAuthClientCredentials;
import com.bob.angularspringbootfullstack.dto.OAuthClientDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.model.OAuthClient;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.repo.OAuthClientRepo;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.tokenprovider.TokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.enumeration.EventType.OAUTH_CLIENT_ISSUED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OAuthClientServiceImpl} (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option B).
 * Every collaborator is mocked, including a real {@link BCryptPasswordEncoder} instance (cheap
 * enough to run for real rather than mock, and mocking a hash/matches pair would just re-assert
 * the mock's own stubbing instead of proving the secret round-trips correctly).
 */
@ExtendWith(MockitoExtension.class)
class OAuthClientServiceImplTest {

    private static final long SERVICE_ACCOUNT_ID = 42L;
    private static final long CLIENT_ROW_ID = 7L;

    @Mock
    private OAuthClientRepo oAuthClientRepo;
    @Mock
    private UserRepo<User> userRepo;
    @Mock
    private RoleRepo<Role> roleRepo;
    @Mock
    private TokenProvider tokenProvider;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    // @Spy (not a plain field) so Mockito's constructor-injection candidate pool for @InjectMocks
    // actually includes it — a plain field is invisible to @InjectMocks, which only wires
    // @Mock/@Spy-annotated fields into the constructor it calls.
    @Spy
    private BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private OAuthClientServiceImpl oAuthClientService;

    private User serviceAccountUser(boolean enabled) {
        User user = new User();
        user.setId(SERVICE_ACCOUNT_ID);
        user.setEmail("svc-bot@service.tessera.internal");
        user.setEnabled(enabled);
        return user;
    }

    private OAuthClient storedClient(String rawSecret, boolean revoked) {
        return OAuthClient.builder()
                .id(CLIENT_ROW_ID)
                .userId(SERVICE_ACCOUNT_ID)
                .clientId("tsc_abc123")
                .clientSecretHash(passwordEncoder.encode(rawSecret))
                .revoked(revoked)
                .build();
    }

    @Test
    @DisplayName("register() mints a tsc_/tss_ pair, persists only the secret's bcrypt hash, and audits against the service account's email")
    void registerMintsAndPersistsHashedSecretOnly() {
        when(oAuthClientRepo.create(any(OAuthClient.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepo.get(SERVICE_ACCOUNT_ID)).thenReturn(serviceAccountUser(true));

        OAuthClientCredentials credentials = oAuthClientService.register(SERVICE_ACCOUNT_ID, "CI pipeline", 1L);

        assertTrue(credentials.getClientId().startsWith("tsc_"), "client_id must carry the tsc_ prefix: " + credentials.getClientId());
        assertTrue(credentials.getClientSecret().startsWith("tss_"), "client_secret must carry the tss_ prefix: " + credentials.getClientSecret());

        ArgumentCaptor<OAuthClient> captor = ArgumentCaptor.forClass(OAuthClient.class);
        verify(oAuthClientRepo).create(captor.capture());
        OAuthClient persisted = captor.getValue();
        assertEquals(SERVICE_ACCOUNT_ID, persisted.getUserId());
        assertEquals("CI pipeline", persisted.getName());
        assertEquals(1L, persisted.getCreatedBy());
        assertEquals(credentials.getClientId(), persisted.getClientId());
        // Only the bcrypt hash is ever persisted — never the raw secret.
        assertTrue(passwordEncoder.matches(credentials.getClientSecret(), persisted.getClientSecretHash()));

        ArgumentCaptor<NewUserEvent> eventCaptor = ArgumentCaptor.forClass(NewUserEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(OAUTH_CLIENT_ISSUED, eventCaptor.getValue().getEventType());
        assertEquals("svc-bot@service.tessera.internal", eventCaptor.getValue().getEmail());
    }

    @Test
    @DisplayName("register() never mints the same client_id or client_secret twice")
    void registerGeneratesDistinctCredentialsEachTime() {
        when(oAuthClientRepo.create(any(OAuthClient.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepo.get(SERVICE_ACCOUNT_ID)).thenReturn(serviceAccountUser(true));

        OAuthClientCredentials first = oAuthClientService.register(SERVICE_ACCOUNT_ID, "Client A", 1L);
        OAuthClientCredentials second = oAuthClientService.register(SERVICE_ACCOUNT_ID, "Client B", 1L);

        assertFalse(first.getClientId().equals(second.getClientId()));
        assertFalse(first.getClientSecret().equals(second.getClientSecret()));
    }

    @Test
    @DisplayName("authenticate() mints an access token, touches last_used_at, and never re-derives a session family")
    void authenticateMintsTokenOnValidCredentials() {
        OAuthClient client = storedClient("tss_correct-secret", false);
        when(oAuthClientRepo.findByClientId("tsc_abc123")).thenReturn(Optional.of(client));
        when(userRepo.get(SERVICE_ACCOUNT_ID)).thenReturn(serviceAccountUser(true));
        when(roleRepo.getRoleByUserId(SERVICE_ACCOUNT_ID)).thenReturn(new Role());
        when(tokenProvider.createAccessToken(any(UserPrincipal.class), isNull())).thenReturn("signed.jwt.token");

        Optional<String> token = oAuthClientService.authenticate("tsc_abc123", "tss_correct-secret");

        assertTrue(token.isPresent());
        assertEquals("signed.jwt.token", token.get());
        verify(oAuthClientRepo).touchLastUsed(CLIENT_ROW_ID);
        // The null second argument IS the assertion: client-credentials tokens carry no session
        // family, so TokenProvider treats them exactly like a legacy pre-session token.
        verify(tokenProvider).createAccessToken(any(UserPrincipal.class), isNull());
    }

    @Test
    @DisplayName("authenticate() rejects a wrong secret without touching last_used_at")
    void authenticateRejectsWrongSecret() {
        OAuthClient client = storedClient("tss_correct-secret", false);
        when(oAuthClientRepo.findByClientId("tsc_abc123")).thenReturn(Optional.of(client));

        Optional<String> token = oAuthClientService.authenticate("tsc_abc123", "tss_wrong-secret");

        assertTrue(token.isEmpty());
        verify(oAuthClientRepo, never()).touchLastUsed(anyLong());
        verify(tokenProvider, never()).createAccessToken(any(), any());
    }

    @Test
    @DisplayName("authenticate() short-circuits a revoked client before ever running the bcrypt compare")
    void authenticateShortCircuitsRevokedClient() {
        OAuthClient client = storedClient("tss_correct-secret", true);
        when(oAuthClientRepo.findByClientId("tsc_abc123")).thenReturn(Optional.of(client));

        Optional<String> token = oAuthClientService.authenticate("tsc_abc123", "tss_correct-secret");

        assertTrue(token.isEmpty());
        verify(userRepo, never()).get(anyLong());
        verify(oAuthClientRepo, never()).touchLastUsed(anyLong());
    }

    @Test
    @DisplayName("authenticate() rejects a deactivated service account even with the correct secret")
    void authenticateRejectsDisabledServiceAccount() {
        OAuthClient client = storedClient("tss_correct-secret", false);
        when(oAuthClientRepo.findByClientId("tsc_abc123")).thenReturn(Optional.of(client));
        when(userRepo.get(SERVICE_ACCOUNT_ID)).thenReturn(serviceAccountUser(false));

        Optional<String> token = oAuthClientService.authenticate("tsc_abc123", "tss_correct-secret");

        assertTrue(token.isEmpty());
        verify(oAuthClientRepo, never()).touchLastUsed(anyLong());
    }

    @Test
    @DisplayName("authenticate() treats an unknown client_id as a plain miss")
    void authenticateMissesForUnknownClientId() {
        when(oAuthClientRepo.findByClientId("tsc_does-not-exist")).thenReturn(Optional.empty());

        Optional<String> token = oAuthClientService.authenticate("tsc_does-not-exist", "anything");

        assertTrue(token.isEmpty());
        verify(userRepo, never()).get(anyLong());
    }

    @Test
    @DisplayName("listForUser() maps every client to a DTO that never carries the secret hash")
    void listForUserMapsToDtoWithoutSecretHash() {
        OAuthClient active = OAuthClient.builder().id(1L).userId(SERVICE_ACCOUNT_ID).name("Active")
                .clientId("tsc_abc123").clientSecretHash("bcrypt-hash").revoked(false).build();
        when(oAuthClientRepo.findByUserId(SERVICE_ACCOUNT_ID)).thenReturn(List.of(active));

        List<OAuthClientDTO> clients = oAuthClientService.listForUser(SERVICE_ACCOUNT_ID);

        assertEquals(1, clients.size());
        assertEquals("Active", clients.getFirst().getName());
        assertEquals("tsc_abc123", clients.getFirst().getClientId());
        // OAuthClientDTO has no clientSecretHash field at all — a compile-time guarantee.
    }

    @Test
    @DisplayName("revoke() delegates straight to the repo")
    void revokeDelegatesToRepo() {
        oAuthClientService.revoke(CLIENT_ROW_ID);

        verify(oAuthClientRepo).revoke(CLIENT_ROW_ID);
    }

    @Test
    @DisplayName("findById() maps a found client to its DTO and stays empty for an unknown id")
    void findByIdMapsOrStaysEmpty() {
        OAuthClient stored = OAuthClient.builder().id(9L).userId(SERVICE_ACCOUNT_ID).name("Client").clientId("tsc_x").build();
        when(oAuthClientRepo.findById(9L)).thenReturn(Optional.of(stored));
        when(oAuthClientRepo.findById(404L)).thenReturn(Optional.empty());

        assertEquals(SERVICE_ACCOUNT_ID, oAuthClientService.findById(9L).orElseThrow().getUserId());
        assertTrue(oAuthClientService.findById(404L).isEmpty());
    }
}
