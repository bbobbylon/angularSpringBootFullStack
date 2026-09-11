package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.ApiKey;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Converts an {@code apikeys} row into an {@link ApiKey}. {@code last_used_at}, {@code created_by}
 * and {@code expires_at} are all nullable columns, so each is null-checked before conversion —
 * {@link Timestamp#toLocalDateTime()} throws on a null {@link Timestamp} reference, and
 * {@link ResultSet#getLong(String)} silently returns 0 for a SQL NULL rather than null, which
 * would misrepresent "nobody recorded" as "created by user 0".
 */
public class ApiKeyRowMapper implements RowMapper<ApiKey> {
    @Override
    public ApiKey mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Timestamp lastUsedAt = resultSet.getTimestamp("last_used_at");
        Timestamp expiresAt = resultSet.getTimestamp("expires_at");
        long createdBy = resultSet.getLong("created_by");
        // wasNull() reflects only the MOST RECENT column read, so it must be captured right here —
        // any getXXX call made later while building the object below would silently overwrite it.
        Long createdByOrNull = resultSet.wasNull() ? null : createdBy;
        return ApiKey.builder()
                .id(resultSet.getLong("id"))
                .userId(resultSet.getLong("user_id"))
                .name(resultSet.getString("name"))
                .keyPrefix(resultSet.getString("key_prefix"))
                .keyHash(resultSet.getString("key_hash"))
                .createdAt(createdAt == null ? null : createdAt.toLocalDateTime())
                .createdBy(createdByOrNull)
                .lastUsedAt(lastUsedAt == null ? null : lastUsedAt.toLocalDateTime())
                .expiresAt(expiresAt == null ? null : expiresAt.toLocalDateTime())
                .revoked(resultSet.getBoolean("revoked"))
                .build();
    }
}
