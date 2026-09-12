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
import com.bob.angularspringbootfullstack.service.OAuthClientService;
import com.bob.angularspringbootfullstack.tokenprovider.TokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.enumeration.EventType.OAUTH_CLIENT_ISSUED;

/**
 * Registers, authenticates, revokes, and lists OAuth2 client-credentials pairs
 * (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option B — RFC 6749 §4.4).
 * <p>
 * Raw credential generation and hashing live here rather than in {@link OAuthClientRepo}, same
 * convention as {@link ApiKeyServiceImpl}. {@link #authenticate} is the one method on the hot path
 * of every token request; it deliberately mints the access token via
 * {@link TokenProvider#createAccessToken} with a {@code null} session family rather than going
 * through {@code SessionServiceImpl#issueTokenPair} — client-credentials is a stateless,
 * sessionless grant per RFC 6749 §4.4 (no refresh token, no "session" a human would recognize in
 * the Security Center), so minting one would misuse infrastructure built for browser logins and
 * would count against a service account's (nonexistent, but still shared-table) concurrent-session
 * cap. {@link TokenProvider#isTokenValid} already treats a token with no {@code sid} claim exactly
 * like a legacy pre-session token, so this needs zero changes to {@code TokenProvider} itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthClientServiceImpl implements OAuthClientService {
    /** {@code tsc_} + this many secure-random bytes, base64url-encoded, is the client_id shape. */
    private static final int CLIENT_ID_BYTES = 16;
    private static final String CLIENT_ID_PREFIX = "tsc_";
    /** {@code tss_} + this many secure-random bytes, base64url-encoded, is the client_secret shape. */
    private static final int CLIENT_SECRET_BYTES = 32;
    private static final String CLIENT_SECRET_PREFIX = "tss_";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OAuthClientRepo oAuthClientRepo;
    private final UserRepo<User> userRepo;
    private final RoleRepo<Role> roleRepo;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     */
    @Override
    public OAuthClientCredentials register(Long serviceAccountUserId, String name, Long issuedByUserId) {
        String clientId = generateWithPrefix(CLIENT_ID_PREFIX, CLIENT_ID_BYTES);
        String clientSecret = generateWithPrefix(CLIENT_SECRET_PREFIX, CLIENT_SECRET_BYTES);
        OAuthClient client = OAuthClient.builder()
                .userId(serviceAccountUserId)
                .clientId(clientId)
                .clientSecretHash(passwordEncoder.encode(clientSecret))
                .name(name)
                .createdBy(issuedByUserId)
                .build();
        oAuthClientRepo.create(client);
        User serviceAccount = userRepo.get(serviceAccountUserId);
        eventPublisher.publishEvent(new NewUserEvent(serviceAccount.getEmail(), OAUTH_CLIENT_ISSUED));
        log.info("Registered OAuth client '{}' (client_id {}) for service account id {}", name, clientId, serviceAccountUserId);
        return OAuthClientCredentials.builder().clientId(clientId).clientSecret(clientSecret).build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<String> authenticate(String clientId, String clientSecret) {
        Optional<OAuthClient> client = oAuthClientRepo.findByClientId(clientId);
        if (client.isEmpty() || client.get().isRevoked()) {
            // Revoked is checked before the bcrypt compare below — cheap short-circuit, since a
            // revoked client can never authenticate regardless of whether the secret still matches.
            return Optional.empty();
        }
        if (!passwordEncoder.matches(clientSecret, client.get().getClientSecretHash())) {
            return Optional.empty();
        }
        User serviceAccount = userRepo.get(client.get().getUserId());
        if (!serviceAccount.isEnabled()) {
            return Optional.empty();
        }
        oAuthClientRepo.touchLastUsed(client.get().getId());
        Role role = roleRepo.getRoleByUserId(serviceAccount.getId());
        UserPrincipal principal = new UserPrincipal(serviceAccount, role);
        return Optional.of(tokenProvider.createAccessToken(principal, null));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<OAuthClientDTO> findById(Long id) {
        return oAuthClientRepo.findById(id).map(this::toDTO);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revoke(Long id) {
        oAuthClientRepo.revoke(id);
        log.info("Revoked OAuth client id {}", id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<OAuthClientDTO> listForUser(Long serviceAccountUserId) {
        return oAuthClientRepo.findByUserId(serviceAccountUserId).stream().map(this::toDTO).toList();
    }

    private OAuthClientDTO toDTO(OAuthClient client) {
        return OAuthClientDTO.builder()
                .id(client.getId())
                .userId(client.getUserId())
                .clientId(client.getClientId())
                .name(client.getName())
                .createdAt(client.getCreatedAt())
                .createdBy(client.getCreatedBy())
                .lastUsedAt(client.getLastUsedAt())
                .revoked(client.isRevoked())
                .build();
    }

    private String generateWithPrefix(String prefix, int randomBytes) {
        byte[] bytes = new byte[randomBytes];
        SECURE_RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
