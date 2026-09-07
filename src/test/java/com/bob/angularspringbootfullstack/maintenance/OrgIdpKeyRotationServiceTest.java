package com.bob.angularspringbootfullstack.maintenance;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.utils.EncryptionUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.SELECT_ALL_OIDC_CIPHERTEXTS_FOR_ROTATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.UPDATE_OIDC_CIPHERTEXT_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrgIdpKeyRotationService#rotate}. Deliberately uses the real
 * {@link EncryptionUtil} statics rather than mocking them — stubbing encryption would only prove
 * the method calls something named "encrypt", not that a rotated row is actually readable under
 * the new key afterward, which is the entire point of the feature.
 */
@ExtendWith(MockitoExtension.class)
class OrgIdpKeyRotationServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private OrgIdpKeyRotationService service;

    private static final String CURRENT_KEY = Base64.getEncoder().encodeToString("a".repeat(32).getBytes());
    private static final String NEW_KEY = Base64.getEncoder().encodeToString("b".repeat(32).getBytes());

    @Test
    @DisplayName("rejects a blank current key without touching the database")
    void blankCurrentKeyRejected() {
        assertThatThrownBy(() -> service.rotate("", NEW_KEY))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("rejects a blank new key without touching the database")
    void blankNewKeyRejected() {
        assertThatThrownBy(() -> service.rotate(CURRENT_KEY, " "))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("rejects rotating a key onto itself without touching the database")
    void identicalKeysRejected() {
        assertThatThrownBy(() -> service.rotate(CURRENT_KEY, CURRENT_KEY))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("no stored secrets means zero rows rotated and no update issued")
    void noRowsToRotate() {
        when(jdbcTemplate.query(eq(SELECT_ALL_OIDC_CIPHERTEXTS_FOR_ROTATION_QUERY), any(RowMapper.class)))
                .thenReturn(List.of());

        int rotated = service.rotate(CURRENT_KEY, NEW_KEY);

        assertThat(rotated).isZero();
        verify(jdbcTemplate, never()).update(eq(UPDATE_OIDC_CIPHERTEXT_QUERY), anyMap());
    }

    @Test
    @DisplayName("every stored secret is re-encrypted so it decrypts correctly under the new key")
    void rotatesEveryRowToTheNewKey() {
        String cipher1 = EncryptionUtil.encryptWithKey(CURRENT_KEY, "org-1-secret");
        String cipher2 = EncryptionUtil.encryptWithKey(CURRENT_KEY, "org-2-secret");
        when(jdbcTemplate.query(eq(SELECT_ALL_OIDC_CIPHERTEXTS_FOR_ROTATION_QUERY), any(RowMapper.class)))
                .thenReturn(List.of(
                        new OrgIdpKeyRotationService.Row(1L, cipher1),
                        new OrgIdpKeyRotationService.Row(2L, cipher2)));

        int rotated = service.rotate(CURRENT_KEY, NEW_KEY);

        assertThat(rotated).isEqualTo(2);
        ArgumentCaptor<Map> params = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .update(eq(UPDATE_OIDC_CIPHERTEXT_QUERY), params.capture());

        Map<Long, String> rotatedById = new java.util.HashMap<>();
        for (Map<?, ?> call : params.getAllValues()) {
            rotatedById.put((Long) call.get("id"), (String) call.get("ciphertext"));
        }
        assertThat(EncryptionUtil.decryptWithKey(NEW_KEY, rotatedById.get(1L))).isEqualTo("org-1-secret");
        assertThat(EncryptionUtil.decryptWithKey(NEW_KEY, rotatedById.get(2L))).isEqualTo("org-2-secret");
    }

    @Test
    @DisplayName("a ciphertext that does not decrypt under the supplied current key aborts with zero writes")
    void wrongCurrentKeyAbortsBeforeAnyWrite() {
        String cipherUnderAnotherKey = EncryptionUtil.encryptWithKey(NEW_KEY, "org-1-secret");
        when(jdbcTemplate.query(eq(SELECT_ALL_OIDC_CIPHERTEXTS_FOR_ROTATION_QUERY), any(RowMapper.class)))
                .thenReturn(List.of(new OrgIdpKeyRotationService.Row(1L, cipherUnderAnotherKey)));

        assertThatThrownBy(() -> service.rotate(CURRENT_KEY, NEW_KEY))
                .isInstanceOf(ApiException.class);

        verify(jdbcTemplate, never()).update(eq(UPDATE_OIDC_CIPHERTEXT_QUERY), anyMap());
    }
}
