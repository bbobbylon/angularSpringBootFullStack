package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.OrganizationIdentityProvider;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * OrganizationIdentityProviderRowMapper converts {@code organizationidentityproviders} rows into
 * {@link OrganizationIdentityProvider} objects for {@code OrganizationIdentityProviderServiceImpl}.
 * <p>
 * Deliberately never reads {@code oidc_client_secret_ciphertext} into the model — see
 * {@link OrganizationIdentityProvider}'s Javadoc for why the model only ever carries whether a
 * secret is configured, never the secret material itself. {@code secretConfigured} is derived here
 * from column nullness rather than stored as its own column.
 */
public class OrganizationIdentityProviderRowMapper implements RowMapper<OrganizationIdentityProvider> {
    @Override
    public OrganizationIdentityProvider mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return OrganizationIdentityProvider.builder()
                .id(resultSet.getLong("id"))
                .organizationId(resultSet.getLong("organization_id"))
                .protocol(resultSet.getString("protocol"))
                .displayName(resultSet.getString("display_name"))
                .status(resultSet.getString("status"))
                .oidcIssuerUri(resultSet.getString("oidc_issuer_uri"))
                .oidcClientId(resultSet.getString("oidc_client_id"))
                .secretConfigured(resultSet.getString("oidc_client_secret_ciphertext") != null)
                .samlMetadataUri(resultSet.getString("saml_metadata_uri"))
                .createdAt(createdAt == null ? null : createdAt.toLocalDateTime())
                .updatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime())
                .build();
    }
}
