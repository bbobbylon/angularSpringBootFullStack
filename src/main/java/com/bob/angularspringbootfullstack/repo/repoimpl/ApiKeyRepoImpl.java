package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.ApiKey;
import com.bob.angularspringbootfullstack.repo.ApiKeyRepo;
import com.bob.angularspringbootfullstack.rowmapper.ApiKeyRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.query.ApiKeyQuery.*;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;

/**
 * JDBC-based {@link ApiKeyRepo} implementation, following the same
 * {@code MapSqlParameterSource}/{@code GeneratedKeyHolder}/try-catch-and-translate convention as
 * {@code RoleRepoImpl}.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class ApiKeyRepoImpl implements ApiKeyRepo {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     */
    @Override
    public ApiKey create(ApiKey data) {
        log.info("Issuing API key '{}' for user id {}", data.getName(), data.getUserId());
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("userId", data.getUserId())
                    .addValue("name", data.getName())
                    .addValue("keyPrefix", data.getKeyPrefix())
                    .addValue("keyHash", data.getKeyHash())
                    .addValue("createdBy", data.getCreatedBy())
                    .addValue("expiresAt", data.getExpiresAt());
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(INSERT_API_KEY_QUERY, params, keyHolder);
            data.setId(requireNonNull(keyHolder.getKey()).longValue());
            return data;
        } catch (DuplicateKeyException e) {
            // Practically unreachable — a SHA-256 collision between two independently generated
            // 256-bit random keys — but the unique constraint exists precisely so this fails loudly
            // instead of aliasing two service accounts' credentials.
            throw new ApiException("Could not generate a unique API key. Please try again.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ApiKey> findActiveByHash(String keyHash) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    SELECT_ACTIVE_API_KEY_BY_HASH_QUERY, of("keyHash", keyHash), new ApiKeyRowMapper()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ApiKey> findById(Long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    SELECT_API_KEY_BY_ID_QUERY, of("id", id), new ApiKeyRowMapper()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ApiKey> findByUserId(Long userId) {
        try {
            return jdbcTemplate.query(SELECT_API_KEYS_BY_USER_ID_QUERY, of("userId", userId), new ApiKeyRowMapper());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Checks existence via {@link #findById} first rather than branching on the UPDATE's
     * affected-row count — see {@code ApiKeyQuery#REVOKE_API_KEY_QUERY}'s Javadoc for why that
     * count cannot distinguish "no such key" from "already revoked" under MySQL Connector/J's
     * default {@code useAffectedRows=true}.
     */
    @Override
    public void revoke(Long id) {
        log.info("Revoking API key id {}", id);
        if (findById(id).isEmpty()) {
            throw new ApiException("API key not found.");
        }
        try {
            jdbcTemplate.update(REVOKE_API_KEY_QUERY, of("id", id));
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Failures are logged, not thrown — see {@link ApiKeyRepo#touchLastUsed}'s Javadoc for why a
     * write here must never fail the authenticated request it rides on.
     */
    @Override
    public void touchLastUsed(Long id) {
        try {
            jdbcTemplate.update(TOUCH_API_KEY_LAST_USED_QUERY, of("id", id));
        } catch (Exception e) {
            log.warn("Could not update last_used_at for API key id {}: {}", id, e.getMessage());
        }
    }
}
