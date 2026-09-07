package com.bob.angularspringbootfullstack.maintenance;

import com.bob.angularspringbootfullstack.utils.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.SELECT_ALL_OIDC_CIPHERTEXTS_FOR_ROTATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.UPDATE_OIDC_CIPHERTEXT_QUERY;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Rotates the AES-256-GCM key protecting every organization's stored OIDC client secret
 * (FUTURE-ENHANCEMENTS.md §3.1, {@link EncryptionUtil}). Invoked only by
 * {@code OrgIdpKeyRotationRunner}, a {@code key-rotation}-profile-gated {@code CommandLineRunner} —
 * this class holds the actual database work as a separate, ordinarily-testable Spring bean so
 * {@link #rotate}'s {@code @Transactional} boundary is a real external call through this bean's
 * proxy rather than a same-class self-invocation, which Spring's proxy-based AOP would silently not
 * intercept.
 * <p>
 * Simply swapping {@code ORG_IDP_SECRET_ENCRYPTION_KEY} to a new value is not a rotation: every
 * already-stored ciphertext was produced under the old key, and {@link EncryptionUtil}'s GCM
 * authentication tag fails decryption outright — rather than silently returning garbage — the moment
 * a different key is used. Every stored secret must be decrypted under the old key and re-encrypted
 * under the new one in the same operation before the running application can be pointed at the new
 * key. See {@code aws/RUNBOOK.md} → "Rotating the org SSO encryption key" for the full procedure.
 */
@Component
@RequiredArgsConstructor
public class OrgIdpKeyRotationService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Re-encrypts every organization's stored OIDC client secret from {@code currentKey} to
     * {@code newKey}.
     *
     * <p>Every row is decrypted and re-encrypted in memory <em>before</em> any database write is
     * issued, so a wrong {@code currentKey}, a malformed {@code newKey}, or a corrupted stored
     * ciphertext aborts the whole run with zero rows changed — never a half-rotated table. The
     * method is additionally wrapped in one database transaction as a second, independent safety
     * net for the case a write itself fails partway through the (already fully computed) batch.
     *
     * @param currentKey the base64-encoded key every stored ciphertext is currently encrypted under
     * @param newKey     the base64-encoded key to re-encrypt every stored ciphertext under
     * @return the number of rows rotated
     * @throws IllegalStateException if either key is blank or the two keys are identical
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if any stored ciphertext
     *                                                                    fails to decrypt under
     *                                                                    {@code currentKey}
     */
    @Transactional
    public int rotate(String currentKey, String newKey) {
        if (isBlank(currentKey) || isBlank(newKey)) {
            throw new IllegalStateException("Both the current key (ORG_IDP_SECRET_ENCRYPTION_KEY) "
                    + "and the new key (ORG_IDP_KEY_ROTATION_NEW_KEY) must be set to rotate.");
        }
        if (currentKey.equals(newKey)) {
            throw new IllegalStateException("The new key is identical to the current key — nothing to rotate.");
        }

        List<Row> rows = jdbcTemplate.query(SELECT_ALL_OIDC_CIPHERTEXTS_FOR_ROTATION_QUERY,
                (rs, rowNum) -> new Row(rs.getLong("id"), rs.getString("oidc_client_secret_ciphertext")));
        if (rows.isEmpty()) {
            return 0;
        }

        List<Row> reencrypted = rows.stream()
                .map(row -> new Row(row.id(), EncryptionUtil.encryptWithKey(newKey,
                        EncryptionUtil.decryptWithKey(currentKey, row.ciphertext()))))
                .toList();

        reencrypted.forEach(row -> jdbcTemplate.update(UPDATE_OIDC_CIPHERTEXT_QUERY,
                Map.of("id", row.id(), "ciphertext", row.ciphertext())));
        return reencrypted.size();
    }

    /**
     * Package-private, not {@code private} — {@code OrgIdpKeyRotationServiceTest} constructs rows
     * directly to stub {@code jdbcTemplate.query(...)}'s return value, the same reasoning
     * {@code AuditIntegrityServiceImpl.ChainedRow} already documents for the identical need.
     */
    record Row(long id, String ciphertext) {
    }
}
