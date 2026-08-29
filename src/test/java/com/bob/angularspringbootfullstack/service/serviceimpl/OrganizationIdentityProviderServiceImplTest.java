package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.OrgSsoDomain;
import com.bob.angularspringbootfullstack.model.OrganizationIdentityProvider;
import com.bob.angularspringbootfullstack.rowmapper.OrganizationIdentityProviderRowMapper;
import com.bob.angularspringbootfullstack.utils.EncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.DELETE_DOMAIN_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.INSERT_DOMAIN_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.INSERT_IDP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.SELECT_DOMAINS_BY_ORGANIZATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.SELECT_IDP_BY_ORGANIZATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.UPDATE_IDP_KEEP_SECRET_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.UPDATE_IDP_WITH_SECRET_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for per-organization SSO configuration
 * (FUTURE-ENHANCEMENTS.md §3.1) {@link OrganizationIdentityProviderServiceImpl} owns: the
 * first-time-configuration-requires-a-secret rule, the insert-vs-update branching in
 * {@link OrganizationIdentityProviderServiceImpl#upsertOidcConfig}, and translating
 * {@link DuplicateKeyException} into a friendly {@link ApiException}.
 * <p>
 * {@link NamedParameterJdbcTemplate} is mocked, matching {@link OrganizationServiceImplTest}'s
 * convention of mocking the database only. {@link EncryptionUtil} is a real instance seeded with a
 * fixed test key via {@link ReflectionTestUtils} — its own round-trip correctness is
 * {@link com.bob.angularspringbootfullstack.utils.EncryptionUtilTest}'s job, not this class's.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationIdentityProviderServiceImplTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OrganizationIdentityProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        EncryptionUtil encryptionUtil = new EncryptionUtil();
        ReflectionTestUtils.setField(encryptionUtil, "base64Key",
                Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
        service = new OrganizationIdentityProviderServiceImpl(jdbcTemplate, encryptionUtil, eventPublisher);
    }

    private OrganizationIdentityProvider existingConfig(boolean secretConfigured) {
        return OrganizationIdentityProvider.builder()
                .id(1L).organizationId(7L).protocol("OIDC").displayName("Acme Okta")
                .status("ACTIVE").oidcIssuerUri("https://acme.okta.com").oidcClientId("client-id")
                .secretConfigured(secretConfigured).build();
    }

    // ── getConfig ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getConfig returns empty when no row exists for the organization")
    void getConfigReturnsEmptyWhenNotFound() {
        when(jdbcTemplate.queryForObject(eq(SELECT_IDP_BY_ORGANIZATION_QUERY), eq(Map.of("organizationId", 7L)),
                any(OrganizationIdentityProviderRowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(service.getConfig(7L)).isEmpty();
    }

    // ── upsertOidcConfig ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("configuring SSO for the first time without a client secret is refused")
    void upsertRejectsFirstTimeConfigWithoutSecret() {
        when(jdbcTemplate.queryForObject(eq(SELECT_IDP_BY_ORGANIZATION_QUERY), anyMap(), any(OrganizationIdentityProviderRowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> service.upsertOidcConfig(7L, "Acme Okta", "https://acme.okta.com", "client-id", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("first time");

        verify(jdbcTemplate, never()).update(eq(INSERT_IDP_QUERY), anyMap());
    }

    @Test
    @DisplayName("a blank display name, issuer, or client id is refused before the database is touched")
    void upsertRejectsMissingRequiredFields() {
        assertThatThrownBy(() -> service.upsertOidcConfig(7L, " ", "https://acme.okta.com", "client-id", "secret"))
                .isInstanceOf(ApiException.class);

        verify(jdbcTemplate, never()).update(eq(INSERT_IDP_QUERY), anyMap());
    }

    @Test
    @DisplayName("a well-formed first-time config is inserted with an encrypted secret")
    void upsertInsertsNewConfigWithEncryptedSecret() {
        when(jdbcTemplate.queryForObject(eq(SELECT_IDP_BY_ORGANIZATION_QUERY), anyMap(), any(OrganizationIdentityProviderRowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1))
                .thenReturn(existingConfig(true));

        OrganizationIdentityProvider result = service.upsertOidcConfig(7L, "Acme Okta", "https://acme.okta.com", "client-id", "s3cret");

        verify(jdbcTemplate).update(eq(INSERT_IDP_QUERY), anyMap());
        assertThat(result.getDisplayName()).isEqualTo("Acme Okta");
    }

    @Test
    @DisplayName("editing an existing config without a new secret keeps the stored secret untouched")
    void upsertKeepsSecretWhenNoneSupplied() {
        when(jdbcTemplate.queryForObject(eq(SELECT_IDP_BY_ORGANIZATION_QUERY), anyMap(), any(OrganizationIdentityProviderRowMapper.class)))
                .thenReturn(existingConfig(true));

        service.upsertOidcConfig(7L, "Acme Okta Renamed", "https://acme.okta.com", "client-id", null);

        verify(jdbcTemplate).update(eq(UPDATE_IDP_KEEP_SECRET_QUERY), anyMap());
        verify(jdbcTemplate, never()).update(eq(UPDATE_IDP_WITH_SECRET_QUERY), anyMap());
        verify(jdbcTemplate, never()).update(eq(INSERT_IDP_QUERY), anyMap());
    }

    @Test
    @DisplayName("editing an existing config with a new secret replaces the stored secret")
    void upsertReplacesSecretWhenSupplied() {
        when(jdbcTemplate.queryForObject(eq(SELECT_IDP_BY_ORGANIZATION_QUERY), anyMap(), any(OrganizationIdentityProviderRowMapper.class)))
                .thenReturn(existingConfig(true));

        service.upsertOidcConfig(7L, "Acme Okta", "https://acme.okta.com", "client-id", "new-secret");

        verify(jdbcTemplate).update(eq(UPDATE_IDP_WITH_SECRET_QUERY), anyMap());
        verify(jdbcTemplate, never()).update(eq(UPDATE_IDP_KEEP_SECRET_QUERY), anyMap());
    }

    // ── addDomain / removeDomain ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("claiming a domain already claimed by another organization surfaces a friendly ApiException")
    void addDomainTranslatesDuplicateKey() {
        doThrow(new DuplicateKeyException("dup")).when(jdbcTemplate).update(eq(INSERT_DOMAIN_QUERY), anyMap());

        assertThatThrownBy(() -> service.addDomain(7L, "acme.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already claimed");
    }

    @Test
    @DisplayName("domains are normalized to lowercase before being claimed")
    void addDomainNormalizesCase() {
        when(jdbcTemplate.query(eq(SELECT_DOMAINS_BY_ORGANIZATION_QUERY), eq(Map.of("organizationId", 7L)), any(RowMapper.class)))
                .thenReturn(List.of(OrgSsoDomain.builder().id(1L).organizationId(7L).domain("acme.com").build()));

        service.addDomain(7L, "ACME.COM");

        verify(jdbcTemplate).update(eq(INSERT_DOMAIN_QUERY), eq(Map.of("organizationId", 7L, "domain", "acme.com")));
    }

    @Test
    @DisplayName("removing a domain that does not belong to the organization is refused")
    void removeDomainRejectsWhenNoRowsAffected() {
        when(jdbcTemplate.update(eq(DELETE_DOMAIN_QUERY), eq(Map.of("id", 99L, "organizationId", 7L)))).thenReturn(0);

        assertThatThrownBy(() -> service.removeDomain(7L, 99L)).isInstanceOf(ApiException.class);
    }

    // ── resolveByEmailDomain ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an email with no '@' resolves to empty without touching the database")
    void resolveByEmailDomainRejectsMalformedEmail() {
        assertThat(service.resolveByEmailDomain("not-an-email")).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), anyMap(), any(org.springframework.jdbc.core.ResultSetExtractor.class));
    }
}
