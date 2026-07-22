package com.bob.angularspringbootfullstack.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Inserts representative demo users across all seven SRS roles on startup (OTH-1),
 * so every role and the administrative dashboard can be demonstrated on a fresh
 * database without manual data setup.
 *
 * <p><b>OTH-2 compliance:</b> annotated {@code @Profile("dev")} — the default active
 * profile per {@code application.yml}. Setting {@code SPRING_ACTIVE_PROFILES=prod}
 * in any production environment prevents this bean from being instantiated entirely.
 *
 * <p><b>Idempotency:</b> each call checks whether the demo email already exists
 * before inserting, so application re-starts on an existing database produce no
 * duplicate rows and log no errors.
 *
 * <p><b>Demo credentials:</b> all seed accounts share the password
 * {@code TesseraDemo@1}, hashed by the application's own {@link BCryptPasswordEncoder}
 * bean so the hash is guaranteed compatible with the login path.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private final NamedParameterJdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder;

    private static final String DEMO_PASSWORD = "TesseraDemo@1";

    /**
     * {@inheritDoc}
     *
     * <p>Seeds one representative user per SRS role. The order matters only for log
     * readability — each call is fully independent.
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("[DemoDataSeeder] Checking seed users …");
        seedIfMissing("Alice",  "Guest",    "alice.guest@tessera.dev",  "ROLE_GUEST",              "Chrome on macOS",   "10.0.1.10");
        seedIfMissing("Bob",    "Mod",      "bob.mod@tessera.dev",      "ROLE_MODERATOR",           "Firefox on Ubuntu", "10.0.1.11");
        seedIfMissing("Carol",  "Help",     "carol.help@tessera.dev",   "ROLE_HELP_DESK_ADMIN",    "Edge on Windows",   "10.0.1.12");
        seedIfMissing("Dave",   "OrgAdmin", "dave.org@tessera.dev",     "ROLE_ORGANIZATION_ADMIN", "Safari on macOS",   "10.0.1.13");
        seedIfMissing("Eve",    "Admin",    "eve.admin@tessera.dev",    "ROLE_ADMIN",              "Chrome on Windows", "10.0.1.14");
        seedIfMissing("Frank",  "AppAdmin", "frank.app@tessera.dev",    "ROLE_APPLICATION_ADMIN",  "Chrome on Linux",   "10.0.1.15");
        log.info("[DemoDataSeeder] Seed check complete.");
    }

    /**
     * Creates a fully-enabled demo user with the given role, org membership, and
     * a small sample of audit events, but only when the email is not already present.
     *
     * @param firstName user's given name
     * @param lastName  user's family name
     * @param email     unique identifier — also used as the idempotency key
     * @param roleName  SRS role name (e.g. {@code "ROLE_ADMIN"})
     * @param device    device description for sample audit events
     * @param ip        IP address for sample audit events
     */
    private void seedIfMissing(String firstName, String lastName, String email,
                                String roleName, String device, String ip) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE email = :email",
                    Map.of("email", email), Integer.class);
            if (count != null && count > 0) {
                return; // already seeded
            }

            // Resolve the role BEFORE inserting the user. The previous code inserted the user
            // first and then called queryForObject for the role id — which throws
            // EmptyResultDataAccessException ("expected 1, actual 0") on a missing role. Because
            // this runs inside an ApplicationRunner, that exception aborts the WHOLE application
            // at startup, and the auto-committed user INSERT leaves behind an orphaned, role-less
            // account that makes the seeder skip that email forever. Looking the role up first
            // with a null-tolerant extractor means a missing SRS role (i.e. schema.sql not yet
            // applied) simply skips this demo user with a clear warning — startup is never harmed.
            Long roleId = jdbc.query(
                    "SELECT id FROM roles WHERE name = :name",
                    Map.of("name", roleName),
                    rs -> rs.next() ? rs.getLong("id") : null);
            if (roleId == null) {
                log.warn("[DemoDataSeeder] Skipping {} — role '{}' not found. " +
                         "Has schema.sql (the SRS role catalog) been applied?", email, roleName);
                return;
            }

            // Insert user — enabled immediately; seed accounts skip the email-verification flow.
            MapSqlParameterSource userParams = new MapSqlParameterSource()
                    .addValue("firstName", firstName)
                    .addValue("lastName",  lastName)
                    .addValue("email",     email)
                    .addValue("password",  passwordEncoder.encode(DEMO_PASSWORD));

            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(
                    "INSERT INTO users (first_name, last_name, email, password, enabled, non_locked) " +
                    "VALUES (:firstName, :lastName, :email, :password, TRUE, TRUE)",
                    userParams, keyHolder);

            Long userId = keyHolder.getKey().longValue();

            jdbc.update(
                    "INSERT INTO userroles (user_id, role_id) VALUES (:userId, :roleId)",
                    Map.of("userId", userId, "roleId", roleId));

            // Enroll in the Tessera organization so org-scoped admin views work out of the box.
            // Wrapped in try/catch: the organizations table may not exist on older schemas (pre-organizations).
            try {
                Long orgId = jdbc.query(
                        "SELECT id FROM organizations WHERE name = 'Tessera' LIMIT 1",
                        Map.of(),
                        rs -> rs.next() ? rs.getLong("id") : null);
                if (orgId != null) {
                    jdbc.update(
                            "INSERT INTO userorganizations (user_id, organization_id, active) " +
                            "VALUES (:userId, :orgId, TRUE) ON DUPLICATE KEY UPDATE active = TRUE",
                            Map.of("userId", userId, "orgId", orgId));
                }
            } catch (Exception e) {
                log.debug("[DemoDataSeeder] Skipping org enrollment for {}: {}", email, e.getMessage());
            }

            // Seed a small activity history so the audit dashboard is non-empty.
            insertEvent(userId, "LOGIN_ATTEMPT_SUCCESS", device, ip);
            insertEvent(userId, "PROFILE_UPDATE",        device, ip);
            insertEvent(userId, "LOGIN_ATTEMPT_SUCCESS", device, ip);

            log.info("[DemoDataSeeder] Created {} {} ({}) as {}", firstName, lastName, email, roleName);
        } catch (Exception e) {
            // OTH-2 safety net: a demo-data seeder must NEVER prevent the application from
            // starting. Any unexpected failure for one user is logged and that user skipped.
            log.warn("[DemoDataSeeder] Skipped seeding {}: {}", email, e.getMessage());
        }
    }

    /**
     * Inserts a single audit event row for the given user, looked up by event type name
     * so the seeder does not depend on specific auto-increment IDs.
     *
     * @param userId    target user
     * @param eventType one of the {@code events.type} values seeded by schema.sql
     * @param device    parsed device description
     * @param ip        originating IP address
     */
    private void insertEvent(Long userId, String eventType, String device, String ip) {
        try {
            Long eventId = jdbc.query(
                    "SELECT id FROM events WHERE type = :type",
                    Map.of("type", eventType),
                    rs -> rs.next() ? rs.getLong("id") : null);
            if (eventId != null) {
                jdbc.update(
                        "INSERT INTO userevents (user_id, event_id, device, ip_address) " +
                        "VALUES (:userId, :eventId, :device, :ip)",
                        Map.of("userId", userId, "eventId", eventId, "device", device, "ip", ip));
            }
        } catch (Exception e) {
            log.warn("[DemoDataSeeder] Could not insert event {} for user {}: {}", eventType, userId, e.getMessage());
        }
    }
}
