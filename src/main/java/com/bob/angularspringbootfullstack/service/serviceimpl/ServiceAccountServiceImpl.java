package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.rowmapper.UserRowMapper;
import com.bob.angularspringbootfullstack.service.ServiceAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.bob.angularspringbootfullstack.constants.Constants.SERVICE_ACCOUNT_EMAIL_DOMAIN;
import static com.bob.angularspringbootfullstack.constants.Constants.SERVICE_ACCOUNT_ORIGIN;
import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.fromUser;
import static com.bob.angularspringbootfullstack.enumeration.EventType.SERVICE_ACCOUNT_CREATED;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.canAssign;
import static com.bob.angularspringbootfullstack.query.UserQuery.INSERT_SERVICE_ACCOUNT_QUERY;
import static com.bob.angularspringbootfullstack.query.UserQuery.SELECT_USERS_BY_ORIGIN_QUERY;
import static java.util.Objects.requireNonNull;

/**
 * Creates, lists, and deactivates service accounts (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 * <p>
 * A service account is an ordinary {@code users} row stamped with
 * {@code origin = Constants.SERVICE_ACCOUNT_ORIGIN} — it is created via direct JDBC rather than
 * {@code UserRepo#create}, mirroring {@code FederatedIdentityServiceImpl#insertFederatedUser},
 * because it needs neither the registration flow's validation nor its verification email: it is
 * enabled at birth, like a federated account, but unlike one it DOES get a password (see
 * {@code UserQuery#INSERT_SERVICE_ACCOUNT_QUERY}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceAccountServiceImpl implements ServiceAccountService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** Hex chars of random suffix appended to the email slug, keeping two same-named accounts unique. */
    private static final int EMAIL_SUFFIX_BYTES = 4;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final UserRepo<User> userRepo;
    private final RoleRepo<Role> roleRepo;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public UserDTO create(String name, String roleName, Long createdByUserId) {
        String callerRoleName = roleRepo.getRoleByUserId(createdByUserId).getName();
        if (!canAssign(callerRoleName, roleName)) {
            throw new ApiException("You cannot grant a role above your own.");
        }
        String email = buildSyntheticEmail(name);
        try {
            Long newUserId = insertServiceAccount(name, email);
            roleRepo.addRoleToUser(newUserId, roleName);
            log.info("Created service account id {} ('{}') with role {}", newUserId, name, roleName);
            UserDTO serviceAccount = mapToUserDTO(userRepo.get(newUserId));
            eventPublisher.publishEvent(new NewUserEvent(serviceAccount.getEmail(), SERVICE_ACCOUNT_CREATED));
            return serviceAccount;
        } catch (DuplicateKeyException e) {
            // Practically unreachable — a collision between two independently generated random
            // email suffixes — but fails loudly rather than aliasing two service accounts.
            throw new ApiException("Could not generate a unique service account. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<UserDTO> list() {
        List<User> serviceAccounts = jdbcTemplate.query(
                SELECT_USERS_BY_ORIGIN_QUERY, Map.of("origin", SERVICE_ACCOUNT_ORIGIN), new UserRowMapper());
        return serviceAccounts.stream().map(this::mapToUserDTO).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deactivate(Long id) {
        userRepo.updateAccountSettings(id, false, true);
        log.info("Deactivated service account id {}", id);
    }

    /**
     * Builds a synthetic, collision-resistant email: {@code slug(name)-<randomSuffix>@<domain>}, so
     * {@code UQ_Users_Email} never rejects two service accounts sharing a display name.
     */
    private String buildSyntheticEmail(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = "service-account";
        }
        byte[] suffixBytes = new byte[EMAIL_SUFFIX_BYTES];
        SECURE_RANDOM.nextBytes(suffixBytes);
        String suffix = HexFormat.of().formatHex(suffixBytes);
        return slug + "-" + suffix + SERVICE_ACCOUNT_EMAIL_DOMAIN;
    }

    private Long insertServiceAccount(String name, String email) {
        String[] nameParts = name.trim().split("\\s+", 2);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(INSERT_SERVICE_ACCOUNT_QUERY,
                new MapSqlParameterSource()
                        .addValue("firstName", nameParts[0])
                        .addValue("lastName", nameParts.length > 1 ? nameParts[1] : "")
                        .addValue("email", email)
                        .addValue("password", passwordEncoder.encode(UUID.randomUUID().toString()))
                        .addValue("origin", SERVICE_ACCOUNT_ORIGIN),
                keyHolder);
        return requireNonNull(keyHolder.getKey()).longValue();
    }

    private UserDTO mapToUserDTO(User user) {
        return fromUser(user, roleRepo.getRoleByUserId(user.getId()));
    }
}
