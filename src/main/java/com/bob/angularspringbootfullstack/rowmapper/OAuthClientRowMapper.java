package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.OAuthClient;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Converts an {@code oauthclients} row into an {@link OAuthClient}. {@code last_used_at} and
 * {@code created_by} are nullable columns, so each is null-checked before conversion — same
 * reasoning as {@link ApiKeyRowMapper}.
 */
public class OAuthClientRowMapper implements RowMapper<OAuthClient> {
    @Override
    public OAuthClient mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Timestamp lastUsedAt = resultSet.getTimestamp("last_used_at");
        long createdBy = resultSet.getLong("created_by");
        // wasNull() reflects only the MOST RECENT column read, so it must be captured right here —
        // any getXXX call made later while building the object below would silently overwrite it.
        Long createdByOrNull = resultSet.wasNull() ? null : createdBy;
        return OAuthClient.builder()
                .id(resultSet.getLong("id"))
                .userId(resultSet.getLong("user_id"))
                .clientId(resultSet.getString("client_id"))
                .clientSecretHash(resultSet.getString("client_secret_hash"))
                .name(resultSet.getString("name"))
                .createdAt(createdAt == null ? null : createdAt.toLocalDateTime())
                .createdBy(createdByOrNull)
                .lastUsedAt(lastUsedAt == null ? null : lastUsedAt.toLocalDateTime())
                .revoked(resultSet.getBoolean("revoked"))
                .build();
    }
}
