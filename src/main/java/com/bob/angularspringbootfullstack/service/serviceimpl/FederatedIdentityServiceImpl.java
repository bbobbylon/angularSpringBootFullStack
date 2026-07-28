package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.service.FederatedIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.sql.Timestamp;
import java.util.Map;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.fromUser;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_USER;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.INSERT_FEDERATED_USER_QUERY;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.COUNT_PASSWORD_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.COUNT_PROVIDER_LINKS_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.DELETE_PROVIDER_LINK_QUERY;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.INSERT_PROVIDER_LINK_QUERY;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.SELECT_PROVIDER_LINKS_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.SELECT_USER_ID_BY_PROVIDER_SUBJECT_QUERY;
import static com.bob.angularspringbootfullstack.query.UserQuery.COUNT_USER_EMAIL_QUERY;
import static java.util.Objects.requireNonNull;

/**
 * JDBC-backed implementation of the federated find-or-create flow (SRS FR-FED-3/6).
 *
 * <p>Runs inside a single transaction so a first federated login is atomic across its
 * three writes (user row, role assignment, provider link) — a partial failure cannot
 * strand an account without a role or a link (NFR-REL-3).
 *
 * <p>Account-linking note: step 2 links a federated identity to a pre-existing local
 * account purely by email match. That is safe for the supported providers because each
 * asserts verified emails on the scopes requested (Google/Microsoft OIDC {@code email}
 * claim; GitHub primary email), but any future provider that does not verify emails must
 * NOT be wired into this path without adding an explicit verification step — unverified
 * email linking is an account-takeover vector.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FederatedIdentityServiceImpl implements FederatedIdentityService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final UserRepo<User> userRepo;
    private final RoleRepo<Role> roleRepo;

    /**
     * Resolves the federated identity per the contract's three-step order. See
     * {@link FederatedIdentityService#findOrCreateFederatedUser} for the resolution
     * semantics and parameter meanings.
     */
    @Override
    @Transactional
    public UserDTO findOrCreateFederatedUser(String provider, String subject, String email,
                                             String firstName, String lastName, String imageUrl) {
        try {
            // Step 1: returning federated user — the (provider, subject) link is the durable key.
            List<Long> linkedIds = jdbcTemplate.queryForList(SELECT_USER_ID_BY_PROVIDER_SUBJECT_QUERY,
                    Map.of("provider", provider, "subject", subject), Long.class);
            if (!linkedIds.isEmpty()) {
                return mapToUserDTO(userRepo.get(linkedIds.getFirst()));
            }

            // Step 2: same verified email already has a local account — link rather than duplicate,
            // so in-house and federated sign-ins converge on one identity (SRS §1.4).
            Integer emailCount = jdbcTemplate.queryForObject(COUNT_USER_EMAIL_QUERY, Map.of("email", email), Integer.class);
            if (emailCount != null && emailCount > 0) {
                User existing = userRepo.getUserByEmail(email);
                insertProviderLink(existing.getId(), provider, subject);
                log.info("Linked {} identity to existing local account id {}", provider, existing.getId());
                return mapToUserDTO(existing);
            }

            // Step 3: first contact — create an enabled, passwordless account with ROLE_USER.
            Long newUserId = insertFederatedUser(email, firstName, lastName, imageUrl);
            roleRepo.addRoleToUser(newUserId, ROLE_USER.name());
            insertProviderLink(newUserId, provider, subject);
            log.info("Created new federated user id {} via {}", newUserId, provider);
            return mapToUserDTO(userRepo.get(newUserId));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Federated find-or-create failed for provider '{}': {}", provider, exception.getMessage(), exception);
            throw new ApiException("An error occurred completing your federated sign-in. Please try again.");
        }
    }

    /**
     * Inserts the local account row for a first-time federated user and returns the
     * generated primary key. Enabled at birth and passwordless by design — see
     * {@link com.bob.angularspringbootfullstack.query.OAuthQuery#INSERT_FEDERATED_USER_QUERY}.
     */
    private Long insertFederatedUser(String email, String firstName, String lastName, String imageUrl) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(INSERT_FEDERATED_USER_QUERY,
                new MapSqlParameterSource()
                        .addValue("firstName", firstName)
                        .addValue("lastName", lastName)
                        .addValue("email", email)
                        .addValue("imageUrl", imageUrl),
                keyHolder);
        return requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * Persists the (provider, subject) → user link that makes future logins from this
     * federated identity resolve in step 1 (FR-FED-6: only these two identity fields
     * are stored — never provider credentials).
     */
    private void insertProviderLink(Long userId, String provider, String subject) {
        jdbcTemplate.update(INSERT_PROVIDER_LINK_QUERY,
                Map.of("userId", userId, "provider", provider, "subject", subject));
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public List<ProviderLink> listLinks(Long userId) {
        return jdbcTemplate.query(SELECT_PROVIDER_LINKS_BY_USER_ID_QUERY,
                Map.of("userId", userId),
                (resultSet, rowNum) -> {
                    Timestamp linkedAt = resultSet.getTimestamp("created_at");
                    return new ProviderLink(
                            resultSet.getString("provider"),
                            linkedAt == null ? null : linkedAt.toLocalDateTime());
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>The order of the two counts is deliberate. The password check runs first and short-circuits:
     * an account that can sign in with a password may unlink freely, and asking how many providers
     * it has would be a question whose answer could not change the outcome.
     */
    @Override
    @Transactional
    public void unlinkProvider(Long userId, String provider) {
        boolean hasPassword = count(COUNT_PASSWORD_BY_USER_ID_QUERY, Map.of("userId", userId)) > 0;
        if (!hasPassword) {
            long linkCount = count(COUNT_PROVIDER_LINKS_BY_USER_ID_QUERY, Map.of("userId", userId));
            if (linkCount <= 1) {
                // Refusing here is the whole feature. A federated-only account that removes its
                // last provider has no credential left and no self-service way back in — the user
                // would have to ask an administrator to repair an account they broke with a button
                // the UI offered them.
                throw new ApiException(
                        "This is the only way you can sign in. Set a password first, or connect another provider.");
            }
        }

        int removed = jdbcTemplate.update(DELETE_PROVIDER_LINK_QUERY,
                Map.of("userId", userId, "provider", provider));
        if (removed == 0) {
            // Already not linked. Reported as success: the caller asked for a state that is
            // already true, and there is nothing for them to do about it.
            log.debug("[FEDERATION] No '{}' link to remove for userId={}", provider, userId);
            return;
        }
        log.info("[FEDERATION] Disconnected provider '{}' from userId={}", provider, userId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public boolean linkProviderToUser(Long userId, String provider, String subject) {
        List<Long> owners = jdbcTemplate.queryForList(SELECT_USER_ID_BY_PROVIDER_SUBJECT_QUERY,
                Map.of("provider", provider, "subject", subject), Long.class);

        if (!owners.isEmpty()) {
            Long owner = owners.getFirst();
            if (owner.equals(userId)) {
                // Already connected to this account — the requested end state already holds.
                return false;
            }
            // The takeover guard. Deliberately does not say WHICH account holds the identity: that
            // would turn the link endpoint into a probe for "does an account exist with this
            // provider identity?", which is the same enumeration channel the login path works hard
            // to close.
            log.warn("[FEDERATION] Refused link: provider '{}' identity already belongs to userId {} (requested by userId {})",
                    provider, owner, userId);
            throw new ApiException("That account is already connected to a different user.");
        }

        insertProviderLink(userId, provider, subject);
        log.info("[FEDERATION] Linked provider '{}' to userId {}", provider, userId);
        return true;
    }

    /**
     * Runs a scalar COUNT, treating a null result as zero.
     *
     * @param query      the counting query
     * @param parameters its named parameters
     * @return the count, never null
     */
    private long count(String query, Map<String, ?> parameters) {
        Long value = jdbcTemplate.queryForObject(query, parameters, Long.class);
        return value == null ? 0 : value;
    }

    /**
     * Flattens the user's role onto the DTO, mirroring
     * {@code UserServiceImpl#mapToUserDTO} so federated and in-house code paths hand
     * identical DTO shapes to the token provider.
     */
    private UserDTO mapToUserDTO(User user) {
        return fromUser(user, roleRepo.getRoleByUserId(user.getId()));
    }
}
