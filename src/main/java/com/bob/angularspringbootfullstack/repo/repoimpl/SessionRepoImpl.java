package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.repo.SessionRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.bob.angularspringbootfullstack.query.SessionQuery.IS_FAMILY_REVOKED_QUERY;
import static java.util.Map.of;

/**
 * JDBC implementation of {@link SessionRepo}.
 */
@Repository
@RequiredArgsConstructor
public class SessionRepoImpl implements SessionRepo {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFamilyRevoked(String family) {
        Boolean revoked = jdbcTemplate.queryForObject(IS_FAMILY_REVOKED_QUERY, of("family", family), Boolean.class);
        return Boolean.TRUE.equals(revoked);
    }
}
