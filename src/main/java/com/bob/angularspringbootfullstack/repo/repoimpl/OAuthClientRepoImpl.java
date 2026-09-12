package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.OAuthClient;
import com.bob.angularspringbootfullstack.repo.OAuthClientRepo;
import com.bob.angularspringbootfullstack.rowmapper.OAuthClientRowMapper;
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

import static com.bob.angularspringbootfullstack.query.OAuthClientQuery.*;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;

/**
 * JDBC-based {@link OAuthClientRepo} implementation, following the same
 * {@code MapSqlParameterSource}/{@code GeneratedKeyHolder}/try-catch-and-translate convention as
 * {@code ApiKeyRepoImpl}.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class OAuthClientRepoImpl implements OAuthClientRepo {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     */
    @Override
    public OAuthClient create(OAuthClient data) {
        log.info("Registering OAuth client '{}' for user id {}", data.getName(), data.getUserId());
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("userId", data.getUserId())
                    .addValue("clientId", data.getClientId())
                    .addValue("clientSecretHash", data.getClientSecretHash())
                    .addValue("name", data.getName())
                    .addValue("createdBy", data.getCreatedBy());
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(INSERT_OAUTH_CLIENT_QUERY, params, keyHolder);
            data.setId(requireNonNull(keyHolder.getKey()).longValue());
            return data;
        } catch (DuplicateKeyException e) {
            // Practically unreachable — a collision between two independently generated random
            // client ids — but the unique constraint exists precisely so this fails loudly instead
            // of aliasing two service accounts' credentials.
            throw new ApiException("Could not generate a unique OAuth client. Please try again.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<OAuthClient> findByClientId(String clientId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    SELECT_OAUTH_CLIENT_BY_CLIENT_ID_QUERY, of("clientId", clientId), new OAuthClientRowMapper()));
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
    public Optional<OAuthClient> findById(Long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    SELECT_OAUTH_CLIENT_BY_ID_QUERY, of("id", id), new OAuthClientRowMapper()));
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
    public List<OAuthClient> findByUserId(Long userId) {
        try {
            return jdbcTemplate.query(SELECT_OAUTH_CLIENTS_BY_USER_ID_QUERY, of("userId", userId), new OAuthClientRowMapper());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Checks existence via {@link #findById} first rather than branching on the UPDATE's
     * affected-row count — see {@code OAuthClientQuery#REVOKE_OAUTH_CLIENT_QUERY}'s Javadoc for why.
     */
    @Override
    public void revoke(Long id) {
        log.info("Revoking OAuth client id {}", id);
        if (findById(id).isEmpty()) {
            throw new ApiException("OAuth client not found.");
        }
        try {
            jdbcTemplate.update(REVOKE_OAUTH_CLIENT_QUERY, of("id", id));
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Failures are logged, not thrown — see {@link OAuthClientRepo#touchLastUsed}'s Javadoc for why
     * a write here must never fail the token issuance it rides on.
     */
    @Override
    public void touchLastUsed(Long id) {
        try {
            jdbcTemplate.update(TOUCH_OAUTH_CLIENT_LAST_USED_QUERY, of("id", id));
        } catch (Exception e) {
            log.warn("Could not update last_used_at for OAuth client id {}: {}", id, e.getMessage());
        }
    }
}
