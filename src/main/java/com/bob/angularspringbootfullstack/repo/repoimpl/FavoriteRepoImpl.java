package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.repo.FavoriteRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

import static com.bob.angularspringbootfullstack.query.FavoriteQuery.*;
import static java.util.Map.of;

/**
 * JDBC-based {@link FavoriteRepo} implementation for the favorites / pinned-destinations bar
 * (FUTURE-ENHANCEMENTS.md §3.3). No {@code RowMapper} is needed: every query here resolves to a
 * single {@code destination_id} column or a count, so {@link NamedParameterJdbcTemplate}'s own
 * scalar/list helpers are enough — introducing a one-field {@code Favorite} model purely to route
 * through a {@code RowMapper} would be ceremony this domain does not need.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class FavoriteRepoImpl implements FavoriteRepo {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public List<String> listByUserId(Long userId) {
        try {
            return jdbcTemplate.queryForList(SELECT_FAVORITES_BY_USER_ID_QUERY, of("userId", userId), String.class);
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    @Override
    public int countByUserId(Long userId) {
        try {
            return Objects.requireNonNullElse(
                    jdbcTemplate.queryForObject(COUNT_FAVORITES_BY_USER_ID_QUERY, of("userId", userId), Integer.class), 0);
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    @Override
    public void add(Long userId, String destinationId) {
        try {
            jdbcTemplate.update(INSERT_FAVORITE_QUERY, of("userId", userId, "destinationId", destinationId));
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    @Override
    public void remove(Long userId, String destinationId) {
        try {
            jdbcTemplate.update(DELETE_FAVORITE_QUERY, of("userId", userId, "destinationId", destinationId));
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }
}
