package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.VerificationType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.form.UpdateForm;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.rowmapper.UserRowMapper;
import com.bob.angularspringbootfullstack.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.bob.angularspringbootfullstack.constants.Constants.DATE_FORMAT;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_USER;
import static com.bob.angularspringbootfullstack.enumeration.VerificationType.ACCOUNT;
import static com.bob.angularspringbootfullstack.enumeration.VerificationType.PASSWORD;
import static com.bob.angularspringbootfullstack.query.UserQuery.*;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.time.DateFormatUtils.format;
import static org.apache.commons.lang3.time.DateUtils.addDays;

/**
 * JDBC-based {@link UserRepo} implementation.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>User creation (including default role assignment)</li>
 *   <li>Authentication support via {@link UserDetailsService#loadUserByUsername(String)}</li>
 *   <li>2FA code generation/verification</li>
 *   <li>Password reset and account verification URL workflows</li>
 * </ul>
 *
 * <p>-----------------------------------------------------------------------
 * TODO(refactor-architecture): This class violates the Single Responsibility
 * Principle. The repository layer should only be responsible for data
 * persistence (CRUD SQL operations). All business logic currently living here
 * should be extracted to {@link com.bob.angularspringbootfullstack.service.serviceimpl.UserServiceImpl}.
 * -----------------------------------------------------------------------
 *
 * <p><b>Business logic to move to UserServiceImpl:</b>
 * <ul>
 *   <li>Email uniqueness check ({@code getEmailCount}) — service should call repo,
 *       then throw if count > 0, not the other way around.</li>
 *   <li>Password encoding ({@code BCryptPasswordEncoder}) — encoding is a
 *       business rule, not a persistence concern. The repo should receive an
 *       already-encoded password.</li>
 *   <li>Verification URL generation ({@code getVerificationURL}, UUID creation)
 *       — URL construction and UUID minting are application logic, not SQL.</li>
 *   <li>2FA code generation ({@code randomAlphanumeric}, expiry calculation)
 *       — should be generated in the service and passed to the repo to persist.</li>
 *   <li>Password match validation ({@code updatePassword} new/confirm check)
 *       — field-level validation belongs in the service or form layer.</li>
 *   <li>Phone number presence guard in {@code toggleMFA} — a business rule,
 *       not a data access concern.</li>
 * </ul>
 *
 * <p><b>What should remain here after refactor:</b>
 * <ul>
 *   <li>All {@code jdbcTemplate} calls (INSERT, UPDATE, SELECT, DELETE)</li>
 *   <li>Row mapping via {@link com.bob.angularspringbootfullstack.rowmapper.UserRowMapper}</li>
 *   <li>{@link UserDetailsService#loadUserByUsername} (Spring Security contract)</li>
 * </ul>
 * -----------------------------------------------------------------------
 */
@NullMarked
@Repository
@RequiredArgsConstructor
@Slf4j
public class UserRepoImpl implements UserRepo<User>, UserDetailsService {

    //HERE WE ARE ADDING SOME BEANZ

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RoleRepo<Role> roleRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final NotificationService notificationService;

    /**
     * Frontend (Angular SPA) origin used when building user-facing verification links.
     * <p>
     * Injected from {@code ui.app.url} (env var {@code UI_APP_URL}, defaulting to
     * {@code http://localhost:4200}). Account-activation and password-reset emails must
     * link to the SPA — which serves the verify screen at {@code /user/verify/{type}/{key}}
     * (see the frontend's {@code app.routes.ts}) — rather than to this backend. Pointing the
     * link at the backend instead lands the user on the raw REST endpoint and dumps the JSON
     * {@code HttpResponse} in their browser, bypassing the verify UI entirely.
     * <p>
     * Field injection (rather than a constructor arg) mirrors {@code TokenProvider}'s
     * {@code @Value} usage and keeps this value out of the Lombok
     * {@code @RequiredArgsConstructor}, which is reserved for the {@code final} bean
     * dependencies above.
     *
     * @see #getVerificationURL(String, String)
     */
    @Value("${ui.app.url:http://localhost:4200}")
    private String uiAppUrl;

    /**
     * Filesystem directory where uploaded profile images are written.
     * <p>
     * Injected from {@code app.image.storage-path} (env {@code IMAGE_STORAGE_PATH}) so the
     * location is portable — local dev defaults under the user's home directory, while
     * Docker/cloud point it at a mounted volume. Replaces the former hardcoded
     * {@code ~/Downloads/images} path that only worked on the original developer's machine.
     *
     * @see #saveImage(String, MultipartFile)
     */
    @Value("${app.image.storage-path}")
    private String imageStoragePath;

    /**
     * Registers a new user under a transactional boundary.
     * <p>
     * Steps, in order:
     * <ol>
     *   <li>Reject the request if {@link #getEmailCount(String)} finds an existing
     *       row with the (normalized, lowercased) email.</li>
     *   <li>Insert the user via {@code INSERT_USER_QUERY}, capturing the
     *       generated primary key from the {@link GeneratedKeyHolder}.</li>
     *   <li>Grant the default {@code ROLE_USER} via
     *       {@link RoleRepo#addRoleToUser(Long, String)}.</li>
     *   <li>Mint a bare UUID verification key, persist it through
     *       {@code INSERT_ACCOUNT_VERIFICATION_URL_QUERY}, and build the clickable
     *       link from it via {@link #getVerificationURL(String, String)} so the
     *       recipient's click later resolves back to this user.</li>
     *   <li>Hand the recipient + link off to
     *       {@link NotificationService#sendAccountVerification(String, String, String)}
     *       which dispatches the email asynchronously on a {@code ForkJoinPool}
     *       worker so this request doesn't block on SMTP.</li>
     *   <li>Return the in-memory {@link User} with its generated id populated.
     *       The DB row's {@code enabled} column remains {@code false}
     *       (schema-level default) until the recipient hits the verification
     *       link, at which point {@link #verifyAccountKey(String)} flips it.</li>
     * </ol>
     * The {@code @Transactional} boundary ensures that if the role grant or
     * verification-URL insert fails, the user insert is rolled back — leaving
     * no orphan {@code users} rows lacking a role or activation link.
     *
     * @param user the registration payload (firstName, lastName, email, raw password)
     * @return the persisted {@link User} with its database-generated id populated
     * @throws ApiException if the email already exists or any persistence step fails
     */
    @Override
    @Transactional
    public User create(User user) {
        if (getEmailCount(user.getEmail().trim().toLowerCase()) > 0)
            throw new ApiException("Email already exists, please use a different email address and try again");

        log.info("Creating new user with email: {}", user.getEmail());
        try {
            KeyHolder holder = new GeneratedKeyHolder();
            SqlParameterSource parameterSource = getSqlParameterSource(user);
            jdbcTemplate.update(INSERT_USER_QUERY, parameterSource, holder);
            user.setId(requireNonNull(holder.getKey()).longValue());

            roleRepository.addRoleToUser(user.getId(), ROLE_USER.name());

            // Persist the bare UUID verification key — NOT the full URL. The host belongs only in the
            // emailed link (built below); keeping it out of the stored key makes verification lookups
            // host-independent, so changing ui.app.url never invalidates a pending link.
            // TODO: the `url` column now holds a bare key, not a URL — consider renaming it to
            //  `verification_key` (needs a DB migration on local + Aiven; see schema.sql / psqlschema.sql / aivendatabase.sql).
            String key = UUID.randomUUID().toString();
            String verificationURL = getVerificationURL(key, ACCOUNT.getType());
            jdbcTemplate.update(INSERT_ACCOUNT_VERIFICATION_URL_QUERY, of("userId", user.getId(), "url", key));
            notificationService.sendAccountVerification(user.getFirstName(), user.getEmail(), verificationURL);
            log.info("Account verification url {} sent to user with email: {}", verificationURL, user.getEmail());

            ///user.setEnabled(true);
            //user.setNotLocked(true);
            return user;
        } catch (Exception exception) {
            log.error("Error creating user: {}", exception.getMessage(), exception);
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * Counts the number of users with a given email in the database.
     * Used for validation during user registration to ensure email uniqueness.
     *
     * @param email the email address to check
     * @return the count of users with the specified email (0 if unique, >0 if duplicate)
     */
    private int getEmailCount(String email) {
        Integer count = jdbcTemplate.queryForObject(COUNT_USER_EMAIL_QUERY, of("email", email), Integer.class);
        return count != null ? count : 0;
    }

    /**
     * Maps a User entity to an SQL parameter source for database insert operations.
     * Handles password encoding using BCryptPasswordEncoder and email normalization.
     *
     * @param user the user entity to be mapped
     * @return SqlParameterSource containing mapped parameters (firstName, lastName, email, encoded password)
     */
    private SqlParameterSource getSqlParameterSource(User user) {
        return new MapSqlParameterSource()
                .addValue("firstName", user.getFirstName())
                .addValue("lastName", user.getLastName())
                .addValue("email", user.getEmail().trim().toLowerCase())
                .addValue("password", passwordEncoder.encode(user.getPassword()));
    }

    /**
     * Builds the user-facing verification link embedded in an activation or password-reset email.
     * <p>
     * This is now the <em>only</em> consumer of the verification key's host. The bare UUID
     * {@code key} is what gets persisted (in the {@code url} column) and later matched on, while
     * this method merely wraps that key in the configured frontend origin ({@link #uiAppUrl}) to
     * produce something clickable. The link resolves to the Angular {@code VerifyComponent}
     * (route {@code /user/verify/{type}/{key}}), which reads the {@code key} back off the path and
     * calls the backend to verify it.
     * <p>
     * Because the persisted key no longer contains the host, changing {@link #uiAppUrl} re-points
     * future emails <em>without</em> invalidating any pending verification rows — the lookup matches
     * on the UUID alone. (This replaced {@code ServletUriComponentsBuilder.fromCurrentContextPath()},
     * which derived the host from the inbound request and so emitted this backend's own origin.)
     *
     * @param key  the bare UUID identifying this verification instance (also the DB lookup key)
     * @param type the verification type segment ({@code account} or {@code password})
     * @return the absolute frontend verification URL, e.g.
     *         {@code http://localhost:4200/user/verify/account/<uuid>}
     */
    private String getVerificationURL(String key, String type) {
        // Tolerate a trailing slash on the configured origin so we never emit "//user/verify".
        String base = uiAppUrl.endsWith("/") ? uiAppUrl.substring(0, uiAppUrl.length() - 1) : uiAppUrl;
        return base + "/user/verify/" + type + "/" + key;
    }

    /**
     * Retrieves one page of the user directory with no filter applied.
     * Delegates to {@link #searchUsers(String, int, int)} with a blank term so the
     * pagination/ordering logic lives in exactly one place.
     *
     * @param page     0-indexed page number
     * @param pageSize page size
     * @return the users on the requested page, newest accounts first
     */
    @Override
    public Collection<User> list(int page, int pageSize) {
        return searchUsers("", page, pageSize);
    }

    /**
     * Pages through the user directory for the administrative dashboard (FR-ADMIN-1).
     * <p>
     * The free-text term is wrapped in SQL wildcards here (not by callers) and matched
     * against first name, last name, and email via {@code LIKE}. Page inputs are clamped
     * defensively — negative pages become page 0 and the page size is bounded to 100 —
     * so a hand-crafted request cannot turn the directory into an unbounded full-table
     * dump (NFR-PERF-3).
     *
     * @param searchTerm free-text filter; blank or null lists everyone
     * @param page       0-indexed page number (negative values are treated as 0)
     * @param pageSize   rows per page (bounded to 1..100)
     * @return the matching users on the requested page, newest accounts first
     */
    @Override
    public Collection<User> searchUsers(String searchTerm, int page, int pageSize) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        try {
            return jdbcTemplate.query(SELECT_USERS_PAGED_QUERY,
                    of("searchTerm", toLikePattern(searchTerm),
                            "pageSize", safeSize,
                            "offset", safePage * safeSize),
                    new UserRowMapper());
        } catch (Exception exception) {
            log.error("Error listing users (page {}, size {}, term '{}'): {}", safePage, safeSize, searchTerm, exception.getMessage(), exception);
            throw new ApiException("An error occurred while retrieving the user directory. Please try again.");
        }
    }

    /**
     * Counts the users matching the same directory filter as
     * {@link #searchUsers(String, int, int)}, for total-pages metadata in the admin UI.
     *
     * @param searchTerm free-text filter; blank or null counts everyone
     * @return the total number of matching users
     */
    @Override
    public long countUsers(String searchTerm) {
        try {
            Long count = jdbcTemplate.queryForObject(COUNT_USERS_QUERY,
                    of("searchTerm", toLikePattern(searchTerm)), Long.class);
            return count == null ? 0 : count;
        } catch (Exception exception) {
            log.error("Error counting users for term '{}': {}", searchTerm, exception.getMessage(), exception);
            throw new ApiException("An error occurred while retrieving the user directory. Please try again.");
        }
    }

    /**
     * Normalizes a raw directory search term into the {@code LIKE} pattern both
     * directory queries expect: trimmed and wrapped in {@code %} wildcards, with a
     * blank/null term collapsing to {@code %%} (match everything). Centralized so the
     * SELECT and COUNT queries can never disagree about how filtering works.
     */
    private static String toLikePattern(String searchTerm) {
        return "%" + (isBlank(searchTerm) ? "" : searchTerm.trim()) + "%";
    }

    /**
     * Retrieves a user by their database ID.
     *
     * @param id the user's primary key
     * @return the matching {@link User}
     * @throws UsernameNotFoundException if no user exists with the given ID or an unexpected error occurs
     */
    @Override
    public User get(Long id) {
        log.debug("Attempting to retrieve user from database by id: {}", id);
        try {
            return jdbcTemplate.queryForObject(SELECT_USER_BY_ID_QUERY, of("id", id), new UserRowMapper());
        } catch (EmptyResultDataAccessException exception) {
            log.error("User not found in our database by ID: {}", id);
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found in our database: " + id);
        } catch (Exception exception) {
            log.error("Unexpected error retrieving user by id '{}': {}", id, exception.getMessage(), exception);
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("An unexpected error occurred while retrieving user by id: " + id);
        }
    }

    /**
     * Not yet implemented; returns null.
     *
     * @param id   the id of the user to update
     * @param data the new user data
     * @return null
     */
    @Override
    public User update(Long id, User data) {
        throw new UnsupportedOperationException("Use updateUserDetails(UpdateForm) instead.");
    }

    /**
     * Not yet implemented; no-op.
     *
     * @param id the id of the user to delete
     */
    @Override
    public void delete(Long id) {

    }

    /**
     * Retrieves a user from the database by their email address.
     * Attempts to find the user, logs appropriate debug/error messages,
     * and throws an exception if the user is not found.
     *
     * @param email the email address to search for (case-insensitive)
     * @return the User object if found
     * @throws ApiException if the user is not found in the database
     */
    @Override
    public User getUserByEmail(String email) {
        log.debug("Attempting to retrieve user from database by email: {}", email);
        try {
            User user = jdbcTemplate.queryForObject(SELECT_USER_BY_EMAIL_QUERY, of("email", email), new UserRowMapper());
            log.debug("User successfully retrieved from database for email: {}", email);
            return user;
        } catch (EmptyResultDataAccessException exception) {
            log.error("User not found in our database: {}", email);
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found in our database: " + email);
        } catch (Exception exception) {
            log.error("Unexpected error retrieving user by email '{}': {}", email, exception.getMessage(), exception);
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("An unexpected error occurred while retrieving user by email: " + email);
        }
    }

    /**
     * Generates a fresh 2FA code for the given user, replaces any prior code,
     * and hands dispatch off to {@link NotificationService#sendTwoFactorCode}.
     * <p>
     * Steps, in order:
     * <ol>
     *   <li>Generate a 7-character uppercase alphanumeric code via
     *       {@link org.apache.commons.lang3.RandomStringUtils#randomAlphanumeric}.</li>
     *   <li>Compute an expiration timestamp 24 hours from now in the
     *       {@code yyyy-MM-dd HH:mm:ss} format MySQL expects.</li>
     *   <li>Delete any existing {@code twofactorverifications} row for this
     *       user so only one code is valid at a time (single-use guarantee).</li>
     *   <li>Insert the new code with its expiration via
     *       {@code INSERT_2FA_CODE_BY_USER_ID_QUERY}.</li>
     *   <li>Delegate the actual outbound SMS to
     *       {@link NotificationService#sendTwoFactorCode}. The Twilio call inside
     *       that service is currently commented out to avoid charges during
     *       development; the service logs the code instead so it can be read
     *       from the application log during testing.</li>
     * </ol>
     * Called from the login flow when {@code using_mfa = true} on the user row.
     *
     * @param userDTO the authenticated user receiving the code; provides id
     *                (for the delete/insert), email (for logging), first name
     *                (for the SMS body greeting), and phone number (the SMS recipient)
     * @throws ApiException if any database operation fails
     */
    @Override
    public void sendVerificationCode(UserDTO userDTO) {
        String expirationDate = format(addDays(new Date(), 1), DATE_FORMAT);
        String verificationCode = randomAlphanumeric(7).toUpperCase();

        try {
            log.info("User with email '{}' is using 2FA/MFA: sending verification code.", userDTO.getEmail());
            jdbcTemplate.update(DELETE_2FA_CODE_BY_USER_ID, of("id", userDTO.getId()));
            jdbcTemplate.update(INSERT_2FA_CODE_BY_USER_ID_QUERY, of("userId", userDTO.getId(), "code", verificationCode, "expirationDate", expirationDate));

            notificationService.sendTwoFactorCode(userDTO.getFirstName(), userDTO.getPhoneNumber(), verificationCode);
            log.debug("2FA code successfully delete/replaced on user with email: {}", userDTO.getEmail());
        } catch (Exception exception) {
            log.error("Unexpected error retrieving user by email '{}': {}", userDTO.getEmail(), exception.getMessage(), exception);
            throw new ApiException("An unexpected error occurred while retrieving user by email: " + userDTO.getEmail());
        }
    }

    /**
     * Verifies that a 2FA code exists, is not expired, and belongs to the given email.
     *
     * <p>If the code is valid, the verification row is deleted (single-use).
     */
    @Override
    public User verifyCode(String email, String code) {
        if (isVerificationCodeExpired(code))
            throw new ApiException("This code has expired. Please request a new code to verify your account.");
        try {
            log.info("User with email '{}' is attempting to use 2FA/MFA: verifying code.", email);
            User userByCode = jdbcTemplate.queryForObject(SELECT_USER_BY_USER_CODE_QUERY, of("code", code), new UserRowMapper());
            User userByEmail = jdbcTemplate.queryForObject(SELECT_USER_BY_EMAIL_QUERY, of("email", email), new UserRowMapper());
            if (userByCode.getEmail().equalsIgnoreCase(userByEmail.getEmail())) {
                jdbcTemplate.update(DELETE_2FA_CODE_BY_CODE_QUERY, of("code", code));
                return userByCode;
            } else {
                throw new BadCredentialsException("Code is not valid. Please try again!");
            }
        } catch (BadCredentialsException ex) {
            log.error("Invalid 2FA code verification attempt for email: {}", email);
            throw ex;
        } catch (EmptyResultDataAccessException e) {
            log.error("The User is not found in our database: {}", email);
            throw new UsernameNotFoundException("User not found in our database: " + email);
        } catch (Exception exception) {
            log.error("Unexpected error during 2FA code verification for email '{}': {}", email, exception.getMessage(), exception);
            throw new BadCredentialsException("An unexpected error occurred while verifying the code.");
        }
    }

    /**
     * Kicks off the forgot-password flow for the user identified by the given
     * email by minting a one-time reset URL, persisting it with an expiration,
     * and emailing it to the user.
     * <p>
     * Steps, in order:
     * <ol>
     *   <li>Reject if no user exists with that email. <em>Note:</em> this
     *       currently throws {@link ApiException} with a message that reveals
     *       whether the email exists — a user-enumeration vulnerability. A
     *       future hardening pass should swap this for a silent no-op that
     *       still returns 200, so attackers can't probe the user database
     *       via the reset endpoint.</li>
     *   <li>Compute an expiration 24 hours from now and look up the full
     *       {@link User} row so the first-name greeting is available for the
     *       email body.</li>
     *   <li>Mint a bare UUID reset key and build its clickable link from it via
     *       {@link #getVerificationURL(String, String)} with
     *       {@link VerificationType#PASSWORD}.</li>
     *   <li>Delete any prior {@code resetpasswordverifications} row for this
     *       user so only the newest link is valid (single-use guarantee).</li>
     *   <li>Insert the new row via {@code INSERT_PASSWORD_VERIFICATION_QUERY}
     *       carrying {@code (user_id, url, expiration_date)} — where the {@code url}
     *       column stores the bare UUID key, not the full link.</li>
     *   <li>Delegate the email send to
     *       {@link NotificationService#sendPasswordResetVerification}, which
     *       dispatches asynchronously so this request doesn't block on SMTP.</li>
     * </ol>
     * The recipient clicks the emailed link, lands on the frontend
     * {@code VerifyComponent}, and continues into the password-reset form
     * which ultimately calls {@link #setNewPassword(Long, String, String)}.
     *
     * @param email the recipient's email address (case-insensitive lookup)
     * @throws ApiException            if the email is not found in the database
     * @throws BadCredentialsException if any persistence step fails
     */
    @Override
    public void resetPassword(String email) {
        if (getEmailCount(email.trim().toLowerCase()) <= 0)
            throw new ApiException("Email not found in our database");
        try {
            String expirationDate = format(addDays(new Date(), 1), DATE_FORMAT);
            User user = getUserByEmail(email);
            // Store the bare UUID key; the host-bearing URL is built only for the email body (see create()).
            String key = UUID.randomUUID().toString();
            String verificationURL = getVerificationURL(key, PASSWORD.getType());
            jdbcTemplate.update(DELETE_PASSWORD_VERIFICATION_BY_USER_ID_QUERY, of("userId", user.getId()));
            jdbcTemplate.update(INSERT_PASSWORD_VERIFICATION_QUERY, of("userId", user.getId(), "url", key, "expirationDate", expirationDate));
            log.info("Password reset verification url {} sent to user with email: {}", verificationURL, email);
            notificationService.sendPasswordResetVerification(user.getFirstName(), email, verificationURL);
        } catch (Exception exception) {
            log.error("Unexpected error during password reset verification '{}'", exception.getMessage(), exception);
            throw new BadCredentialsException("An unexpected error occurred while verifying the code.");
        }
    }

    /**
     * Resolves and validates a password reset key/link.
     *
     * <p>This method only verifies the key is present and not expired; updating the password is
     * performed by {@link #setNewPassword(Long, String, String)} once the caller has the userID
     * returned by this step.
     */
    @Override
    public User verifyPasswordKey(String key) {
        if (isLinkExpired(key))
            throw new ApiException("This link is not valid. Please request a new password reset link.");
        try {
            // Look up by the bare UUID key stored at reset-request time — host-independent, no URL rebuild.
            return jdbcTemplate.queryForObject(SELECT_USER_BY_PASSWORD_URL_QUERY, of("url", key), new UserRowMapper());
        } catch (EmptyResultDataAccessException e) {
            log.error(e.getMessage());
            throw new ApiException("This link is not valid. Please request a new password reset link.");
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("An unexpected error occurred. Please try again.");
        }
    }

    /**
     * Checks whether a password-reset verification row has expired, keyed by its bare UUID.
     *
     * <p>Only {@code resetpasswordverifications} rows carry an {@code expiration_date};
     * account verifications never expire, which is why this takes no verification-type
     * argument and queries the reset table directly.
     *
     * @param key the bare UUID stored in the {@code url} column at reset-request time
     * @return {@code true} if the row is expired
     */
    private boolean isLinkExpired(String key) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(SELECT_EXPIRATION_BY_URL, of("url", key), Boolean.class));
        } catch (EmptyResultDataAccessException e) {
            log.error(e.getMessage());
            throw new ApiException("This link is not valid. Please request a new password reset link.");
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("An unexpected error occurred. Please try again.");
        }
    }

    /**
     * Completes the forgot-password reset flow by setting a new password for the
     * user identified by {@code userID}.
     * <p>
     * Called by the controller's {@code PUT /user/new/password} endpoint after the
     * reset link has already been validated by
     * {@link #verifyPasswordKey(String)} in the prior step of the flow — at that
     * point the frontend holds the user's ID, so the new password can be submitted
     * in the request body without ever placing the URL key (or the password
     * itself) in a query string.
     * <p>
     * Persists the encoded password via
     * {@link com.bob.angularspringbootfullstack.query.UserQuery#UPDATE_USER_PASSWORD_BY_ID_QUERY},
     * which also stamps {@code password_changed_at = NOW()}. The token-validation
     * layer reads that column to reject any JWT issued before the reset, so old
     * sessions cannot continue using stale credentials. Finally, the now-consumed
     * reset row is deleted via
     * {@link com.bob.angularspringbootfullstack.query.UserQuery#DELETE_PASSWORD_VERIFICATION_BY_USER_ID_QUERY}
     * to make the reset link single-use.
     * <p>
     * Note: no expiry recheck is performed here — that responsibility lives in
     * {@link #verifyPasswordKey(String)}, the step that hands the userID to the
     * client. Re-validating here would be redundant and would require
     * reconstructing a URL we no longer have.
     *
     * @param userID          the user whose password is being reset, obtained from
     *                        the prior {@code verifyPasswordKey} response
     * @param newPassword     the new plaintext password (encoded before persistence)
     * @param confirmPassword must equal {@code newPassword}; mismatch raises
     *                        {@link ApiException}
     */
    @Override
    public void setNewPassword(Long userID, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword))
            throw new ApiException("Passwords do not match. Please try again!");
        try {
            jdbcTemplate.update(UPDATE_USER_PASSWORD_BY_ID_QUERY,
                    of("userId", userID, "password", requireNonNull(passwordEncoder.encode(newPassword))));
            jdbcTemplate.update(DELETE_PASSWORD_VERIFICATION_BY_USER_ID_QUERY, of("userId", userID));
            log.info("Password successfully reset for user with id: {}", userID);
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("An unexpected error occurred. Please try again.");
        }
    }

    /**
     * Verifies an account verification key by enabling the user and returning the user.
     */
    @Override
    public User verifyAccountKey(String key) {
        try {
            // Look up by the bare UUID key stored at registration — host-independent, no URL rebuild.
            User user = jdbcTemplate.queryForObject(SELECT_USER_BY_ACCOUNT_QUERY, of("url", key), new UserRowMapper());
            jdbcTemplate.update(UPDATE_USER_ENABLED_QUERY, of("enabled", true, "id", user.getId()));
            // Log successful account verification for auditing
            log.info("Account successfully verified for user with email: {}", user.getEmail());
            return user;
        } catch (EmptyResultDataAccessException e) {
            log.error("This code is not valid. Please retry your login attempt.");
            throw new UsernameNotFoundException("This code is not valid. Please attempt to login again.");
        } catch (Exception exception) {
            log.error("Unexpected error during 2FA code verification for email '{}'", exception.getMessage(), exception);
            throw new BadCredentialsException("An unexpected error occurred while verifying the account.");
        }
    }

    /**
     * Updates the profile details of an existing user in the database.
     *
     * @param user the form data containing the updated user fields
     * @return the updated {@link User} fetched after the writing of the new details to the database
     * @throws ApiException if the email is already taken by another account, or if any other database error occurs.
     *                      See {@code getUserDetailsSQLParameterSource} for how the UpdateForm is mapped to SQL parameters
     */
    @Override
    public User updateUserDetails(UpdateForm user) {
        try {
            jdbcTemplate.update(UPDATE_USER_DETAILS_QUERY, (getUserDetailsSQLParameterSource(user)));
            return get(user.getId());
        } catch (EmptyResultDataAccessException e) {
            log.error("We cannot update the user details because we cannot find the user in our database with id: {}", user.getId());
            throw new UsernameNotFoundException("User not found. Please try again.");
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.error("Email already in use during profile update for user id {}: {}", user.getId(), e.getMessage());
            throw new ApiException("An error occurred while updating your profile. Please try again.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An Error has occurred. Please try again.");
        }
    }

    /**
     * Validates the current password, then persists the new one alongside a
     * {@code password_changed_at} timestamp so old tokens are rejected on the
     * next request.
     */
    @Override
    public void updatePassword(Long userID, String currentPassword, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword))
            throw new ApiException("Your passwords do not match. Please try again!");
        User user = get(userID);
        if (passwordEncoder.matches(currentPassword, user.getPassword())) {
            try {
                jdbcTemplate.update(UPDATE_USER_PASSWORD_BY_ID_QUERY, of("userId", userID, "password", requireNonNull(passwordEncoder.encode(newPassword))));
            } catch (Exception exception) {
                throw new ApiException("An unexpected error occurred. Please try again.");
            }
        } else {
            throw new ApiException("Current password is incorrect. Please try again.");
        }

    }

    /**
     * Persists the enabled and notLocked flags for the given user.
     * <p>
     * Maps directly to {@link com.bob.angularspringbootfullstack.query.UserQuery#UPDATE_USER_SETTINGS_QUERY}.
     * Both flags are required — the endpoint's {@code @Valid SettingsForm} guarantees
     * neither is null before this method is reached.
     *
     * @param userID    the ID of the user whose settings should change
     * @param enabled   {@code true} to activate the account, {@code false} to deactivate it
     * @param notLocked {@code true} to unlock the account, {@code false} to lock it
     * @throws ApiException if any database error occurs
     */
    @Override
    public void updateAccountSettings(Long userID, Boolean enabled, Boolean notLocked) {
        try {
            jdbcTemplate.update(UPDATE_USER_SETTINGS_QUERY, of("userId", userID, "enabled", enabled, "notLocked", notLocked));
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("An unexpected error occurred. Please try again.");
        }
    }

    /**
     * Flips the {@code using2FA} flag for the user identified by the given email.
     * <p>
     * Requires the user to have a phone number on their profile; throws
     * {@link ApiException} if the phone number is blank, since 2FA codes are
     * delivered via SMS. Reads the current flag value, inverts it in memory, then
     * persists the new value with
     * {@link com.bob.angularspringbootfullstack.query.UserQuery#TOGGLE_USER_2FA_QUERY}.
     *
     * @param email the email address of the user toggling MFA
     * @return the updated {@link User} entity with the new {@code using2FA} value
     * @throws ApiException if no phone number is set, or if any database error occurs
     */
    @Override
    public User toggleMFA(String email) {
        User user = getUserByEmail(email);
        if (isBlank(user.getPhoneNumber())) {
            throw new ApiException("A phone number is required to enable 2FA/MFA. Please add a phone number to your profile and try again.");
        }
        user.setUsing2FA(!user.isUsing2FA());
        try {
            jdbcTemplate.update(TOGGLE_USER_2FA_QUERY, of("using2FA", user.isUsing2FA(), "email", email));
            return user;
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("Unable to update 2FA/MFA setting at this time. Please try again.");
        }

    }

    /**
     * Saves a new profile image to disk, constructs the public URL where it can be fetched,
     * and updates the user's {@code image_url} column in the database.
     * <p>
     * The three steps must succeed together: if the file saves but the DB update fails,
     * the stored URL would be out of sync. A future improvement would wrap this in a
     * transaction with rollback-on-failure.
     *
     * @param userDTO the authenticated user whose image is being changed; its
     *                {@code imageUrl} field is updated in-place so the caller
     *                sees the new URL without a second DB fetch
     * @param image   the uploaded image file from the multipart request
     */
    @Override
    public void updateProfileImage(UserDTO userDTO, MultipartFile image) {
        String userImageURL = setUserImageUrl(userDTO.getEmail());
        userDTO.setImageUrl(userImageURL);
        saveImage(userDTO.getEmail(), image);
        jdbcTemplate.update(UPDATE_USER_IMAGE_URL_QUERY, of("imageUrl", userImageURL, "userId", userDTO.getId()));
    }

    /**
     * Builds the public URL for a user's profile image.
     * The URL points to the public {@code GET /user/image/{email}.png} controller endpoint
     * which reads the file from disk and returns its bytes — not the raw filesystem path.
     *
     * @param email the user's email address, used as the image filename
     * @return the fully qualified URL the browser can use to load the image
     */
    private String setUserImageUrl(String email) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/user/image/" + email + ".png").toUriString();
    }

    /**
     * Writes the uploaded image file to {@code {app.image.storage-path}/{email}.png}.
     * Creates the target directory if it does not already exist.
     * If a previous image exists for this user, it is overwritten ({@code REPLACE_EXISTING}).
     *
     * @param email the user's email address, used as the filename on disk
     * @param image the uploaded image file from the multipart request
     * @throws ApiException if the directory cannot be created or the file cannot be written
     */
    private void saveImage(String email, MultipartFile image) {
        Path fileStorageLocation = Paths.get(imageStoragePath).toAbsolutePath().normalize();
        if (!Files.exists(fileStorageLocation)) {
            try {
                Files.createDirectories(fileStorageLocation);
            } catch (Exception e) {
                log.error("Could not create the directory where the uploaded files will be stored.", e);
                throw new ApiException("Could not create the directory where the uploaded files will be stored..An error occurred while saving the image. Please try again.");
            }
            log.info("Created directory for profile images at: {}", fileStorageLocation);
        }
        try {
            Files.copy(image.getInputStream(), fileStorageLocation.resolve(email + ".png"), REPLACE_EXISTING);
            log.info("Profile image saved successfully for user with email: {}", email);
        } catch (IOException e) {
            log.error("An error occurred while saving the profile image for user with email '{}': {}", email, e.getMessage(), e);
            throw new ApiException(e.getMessage());
        }
    }

    /**
     * Builds the named SQL parameter source for the update-user-details query.
     * <p>
     * This method maps the fields from the UpdateForm to the corresponding named parameters expected by the UPDATE_USER_DETAILS_QUERY. It also normalizes the email and handles any necessary transformations.
     * </p>
     *
     * @param user the form data to map into SQL parameters
     * @return a {@link SqlParameterSource} ready for use with {@code jdbcTemplate.update}
     */
    private SqlParameterSource getUserDetailsSQLParameterSource(UpdateForm user) {
        return new MapSqlParameterSource()
                .addValue("id", user.getId())
                .addValue("firstName", user.getFirstName())
                .addValue("lastName", user.getLastName())
                .addValue("email", user.getEmail().trim().toLowerCase())
                .addValue("imageUrl", user.getImageUrl())
                .addValue("phoneNumber", user.getPhoneNumber())
                .addValue("address", user.getAddress())
                .addValue("title", user.getTitle())
                .addValue("bio", user.getBio());
    }

    /**
     * Checks whether a 2FA verification code has expired.
     *
     * @param code verification code
     * @return {@code true} if expired
     */
    private boolean isVerificationCodeExpired(String code) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(CHECK_2FA_CODE_EXPIRE_DATE, of("code", code), Boolean.class));
        } catch (EmptyResultDataAccessException e) {
            log.error("This code is not valid. Please attempt to login again.");
            throw new UsernameNotFoundException("This code is not valid. Please attempt to login again.");
        } catch (Exception exception) {
            log.error("Unexpected error during 2FA code verification for email '{}'", exception.getMessage(), exception);
            throw new BadCredentialsException("An unexpected error occurred while verifying the code.");
        }
    }

    /**
     * Required by Spring Security's {@link UserDetailsService}; loads a user for authentication.
     *
     * <p>The returned {@link UserPrincipal} contains the {@link User} plus the user's {@link Role}
     * used to derive authorities.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        log.debug("Spring Security is attempting to load user by email: {}", email);
        try {
            User user = getUserByEmail(email);
            log.info("We have found this user in our database with the following address: {} ", email);
            log.debug("Building UserPrincipal for user with email: {} and id: {}", email, user.getId());
            log.info("User with email '{}' has 2FA/MFA enabled: {}", email, user.isUsing2FA());
            return new UserPrincipal(user, roleRepository.getRoleByUserId(user.getId()));
        } catch (ApiException e) {
            log.warn("User lookup failed for email '{}': {}", email, e.getMessage());
            throw new UsernameNotFoundException("User not found in our database: " + email);
        }
    }
}


