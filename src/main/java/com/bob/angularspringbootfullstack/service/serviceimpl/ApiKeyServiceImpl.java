package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.ApiKeyDTO;
import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.model.ApiKey;
import com.bob.angularspringbootfullstack.repo.ApiKeyRepo;
import com.bob.angularspringbootfullstack.service.ApiKeyService;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.enumeration.EventType.API_KEY_ISSUED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.API_KEY_REVOKED;
import static com.bob.angularspringbootfullstack.utils.TotpUtils.sha256Hex;

/**
 * Issues, resolves, revokes, and lists API keys (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 * <p>
 * Raw-key generation and hashing live here rather than in {@link ApiKeyRepo} — what the key looks
 * like and how it is protected at rest are business rules, not data-access concerns. {@link #resolve}
 * is the one method on the hot path of every M2M request; it is called exclusively by
 * {@code ApiKeyAuthFilter}, which is why it also checks {@code enabled} — API keys have no separate
 * "login" step the way password auth does, so this method IS the login step, and must reject a
 * deactivated service account exactly as {@code UserController#authenticate} would.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyServiceImpl implements ApiKeyService {
    /** {@code tsk_} + this many secure-random bytes, base64url-encoded, is the raw key shape. */
    private static final int RAW_KEY_BYTES = 32;
    private static final String RAW_KEY_PREFIX = "tsk_";
    /** Non-secret prefix shown in admin UIs so a key can be recognized without ever re-displaying it. */
    private static final int KEY_PREFIX_DISPLAY_LENGTH = 10;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiKeyRepo apiKeyRepo;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     */
    @Override
    public String issue(Long serviceAccountUserId, String name, LocalDateTime expiresAt, Long issuedByUserId) {
        String rawKey = generateRawKey();
        ApiKey apiKey = ApiKey.builder()
                .userId(serviceAccountUserId)
                .name(name)
                .keyPrefix(rawKey.substring(0, KEY_PREFIX_DISPLAY_LENGTH))
                .keyHash(sha256Hex(rawKey))
                .createdBy(issuedByUserId)
                .expiresAt(expiresAt)
                .build();
        apiKeyRepo.create(apiKey);
        UserDTO serviceAccount = userService.getUserById(serviceAccountUserId);
        eventPublisher.publishEvent(new NewUserEvent(serviceAccount.getEmail(), API_KEY_ISSUED));
        log.info("Issued API key '{}' (prefix {}) for service account id {}", name, apiKey.getKeyPrefix(), serviceAccountUserId);
        return rawKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<UserDTO> resolve(String rawKey) {
        Optional<ApiKey> apiKey = apiKeyRepo.findActiveByHash(sha256Hex(rawKey));
        if (apiKey.isEmpty()) {
            return Optional.empty();
        }
        UserDTO serviceAccount = userService.getUserById(apiKey.get().getUserId());
        if (!serviceAccount.isEnabled()) {
            return Optional.empty();
        }
        apiKeyRepo.touchLastUsed(apiKey.get().getId());
        return Optional.of(serviceAccount);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ApiKeyDTO> findById(Long keyId) {
        return apiKeyRepo.findById(keyId).map(this::toDTO);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revoke(Long keyId) {
        apiKeyRepo.revoke(keyId);
        log.info("Revoked API key id {}", keyId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ApiKeyDTO> listForUser(Long serviceAccountUserId) {
        return apiKeyRepo.findByUserId(serviceAccountUserId).stream().map(this::toDTO).toList();
    }

    private ApiKeyDTO toDTO(ApiKey apiKey) {
        return ApiKeyDTO.builder()
                .id(apiKey.getId())
                .userId(apiKey.getUserId())
                .name(apiKey.getName())
                .keyPrefix(apiKey.getKeyPrefix())
                .createdAt(apiKey.getCreatedAt())
                .createdBy(apiKey.getCreatedBy())
                .lastUsedAt(apiKey.getLastUsedAt())
                .expiresAt(apiKey.getExpiresAt())
                .revoked(apiKey.isRevoked())
                .build();
    }

    private String generateRawKey() {
        byte[] bytes = new byte[RAW_KEY_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return RAW_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
