package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.VerificationType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.rowmapper.UserRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_USER;
import static com.bob.angularspringbootfullstack.enumeration.VerificationType.ACCOUNT;
import static com.bob.angularspringbootfullstack.enumeration.VerificationType.PASSWORD;
import static com.bob.angularspringbootfullstack.query.UserQuery.*;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.apache.commons.lang3.time.DateFormatUtils.format;
import static org.apache.commons.lang3.time.DateUtils.addDays;

// here the actual logic/ db queries will be implemented.
@Repository
@RequiredArgsConstructor
@Slf4j
public class UserRepoImpl implements UserRepo<User>, UserDetailsService {
    //standard MySQL date format
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    // here we are injecting some BEANS
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RoleRepo<Role> roleRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * Creates a new user in the database with a transactional context.
     * <p>
     * This method performs the following steps:
     * 1. Validates that the email is unique; throws an exception if duplicate
     * 2. Inserts the user into the database and retrieves the generated user ID
     * 3. Assigns the default ROLE_USER role to the new user
     * 4. Generates a unique account verification URL using a UUID
     * 5. Stores the verification URL in the database for email verification flow
     * 6. Sets user status flags (enabled, notLocked)
     * 7. Returns the created user with its ID set
     *
     * @param user the user object containing registration information (firstName, lastName, email, password)
     * @return the created User with ID populated from the database
     * @throws ApiException if email already exists or any database operation fails
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

            String verificationURL = getVerificationURL(UUID.randomUUID().toString(), ACCOUNT.getType());
            jdbcTemplate.update(INSERT_ACCOUNT_VERIFICATION_URL_QUERY, of("userId", user.getId(), "url", verificationURL, "type", ACCOUNT.getType()));
            // Log the account verification link for auditing/debugging (do not log passwords)
            log.info("Account verification url {} sent to user with email: {}", verificationURL, user.getEmail());

            user.setEnabled(true);
            user.setNotLocked(true);
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
    private Integer getEmailCount(String email) {
        return jdbcTemplate.queryForObject(COUNT_USER_EMAIL_QUERY, of("email", email), Integer.class);
    }

    /**
     * Maps a User entity to SQL parameter source for database insert operations.
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
     * Generates a verification URL for account activation or password reset.
     * Constructs a backend URL that users click to verify their account or reset password.
     *
     * @param key  a unique identifier (typically UUID) for this verification instance
     * @param type the verification type (ACCOUNT or PASSWORD_RESET)
     * @return the full verification URL as a String
     */
    private String getVerificationURL(String key, String type) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/user/verify/" + type + "/" + key).toUriString();
    }

    /**
     * Retrieves an unimplemented paginated list of users.
     * This method is a placeholder for future implementation.
     *
     * @param page     the page number to retrieve
     * @param pageSize the number of users per page
     * @return an empty collection (not yet implemented)
     */
    @Override
    public Collection<User> list(int page, int pageSize) {
        return List.of();
    }

    /**
     * Retrieves an unimplemented user by ID.
     * This method is a placeholder for future implementation.
     *
     * @param id the user ID to retrieve
     * @return null (not yet implemented)
     */
    @Override
    public User get(Long id) {
        return null;
    }

    /**
     * Updates an unimplemented user record.
     * This method is a placeholder for future implementation.
     *
     * @param id   the ID of the user to update
     * @param data the updated user data
     * @return null (not yet implemented)
     */
    @Override
    public User update(Long id, User data) {
        return null;
    }

    /**
     * Deletes an unimplemented user record.
     * This method is a placeholder for future implementation.
     *
     * @param id the ID of the user to delete
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
     * Sends a 2FA verification code to the user via SMS.
     * <p>
     * This method performs the following steps:
     * 1. Generates a random 7-character alphanumeric verification code
     * 2. Calculates the expiration date (24 hours from now)
     * 3. Deletes any existing 2FA codes for the user
     * 4. Inserts the new 2FA code into the database
     * 5. Sends the code to the user's phone number via SMS (commented out for cost)
     *
     * @param userDTO the user who will receive the verification code
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

            // TODO: Uncomment when ready to send real SMS (costs money per SMS)
            // sendSMS(userDTO.getPhoneNumber(), "From: AngularSpringBootFullStack App, To: " + userDTO.getPhoneNumber() + ", Message: Your 2FA verification code is: " + verificationCode + ". It will expire in 24 hours.");
            log.info("Verification code: {}", verificationCode);
            log.debug("2FA code successfully delete/replaced on user with email: {}", userDTO.getEmail());
        } catch (Exception exception) {
            log.error("Unexpected error retrieving user by email '{}': {}", userDTO.getEmail(), exception.getMessage(), exception);
            throw new ApiException("An unexpected error occurred while retrieving user by email: " + userDTO.getEmail());
        }
    }

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
            log.error("User not found in our database: {}", email);
            throw new UsernameNotFoundException("User not found in our database: " + email);
        } catch (Exception exception) {
            log.error("Unexpected error during 2FA code verification for email '{}': {}", email, exception.getMessage(), exception);
            throw new BadCredentialsException("An unexpected error occurred while verifying the code.");
        }
    }

    @Override
    public void resetPassword(String email) {
        if (getEmailCount(email.trim().toLowerCase()) <= 0)
            throw new ApiException("Email not found in our database");
        try {
            String expirationDate = format(addDays(new Date(), 1), DATE_FORMAT);
            User user = getUserByEmail(email);
            String verificationURL = getVerificationURL(UUID.randomUUID().toString(), PASSWORD.getType());
            jdbcTemplate.update(DELETE_PASSWORD_VERIFICATION_BY_USER_ID_QUERY, of("userId", user.getId()));
            jdbcTemplate.update(INSERT_PASSWORD_VERIFICATION_QUERY, of("userId", user.getId(), "url", verificationURL, "expirationDate", expirationDate));
            log.info("Password reset verification url {} sent to user with email: {}", verificationURL, email);
            // TODO send email with url to our user
        } catch (Exception exception) {
            log.error("Unexpected error during password reset verification '{}'", exception.getMessage(), exception);
            throw new BadCredentialsException("An unexpected error occurred while verifying the code.");
        }
    }

    @Override
    public User verifyPasswordKey(String key) {
        if (isLinkExpired(key, PASSWORD))
            throw new ApiException("This link is not valid. Please request a new password reset link.");
        try {
            User user = jdbcTemplate.queryForObject(SELECT_USER_BY_PASSWORD_URL_QUERY, of("url", getVerificationURL(key, PASSWORD.getType())), new UserRowMapper());
            // Delete the password verification row by user_id. Use the existing constant for deleting by user id
            // jdbcTemplate.update(DELETE_PASSWORD_VERIFICATION_BY_USER_ID_QUERY, of("userId", user.getId())); // remove the verification record for this user
            return user;
        } catch (EmptyResultDataAccessException e) {
            log.error(e.getMessage());
            throw new ApiException("This link is not valid. Please request a new password reset link.");
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("An unexpected error occurred. Please try again.");
        }
    }

    @Override
    public void setNewPassword(String key, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword))
            throw new ApiException("Passwords do not match. Please try again!");
        if (isLinkExpired(key, PASSWORD))
            throw new ApiException("This link is not valid. Please request a new password reset link.");
        try {
            // Resolve the full verification URL once and look up the user tied to it so we can log the email
            String url = getVerificationURL(key, PASSWORD.getType());
            User user = jdbcTemplate.queryForObject(SELECT_USER_BY_PASSWORD_URL_QUERY, of("url", url), new UserRowMapper());

            jdbcTemplate.update(UPDATE_USER_PASSWORD_BY_URL_QUERY, of("password", passwordEncoder.encode(newPassword), "url", url));
            jdbcTemplate.update(DELETE_VERIFICATION_BY_URL_QUERY, of("url", url));

            // Log the actual user's email for auditing (safe because password is not logged)
            log.info("Password successfully reset for user with email: {}", user.getEmail());
        } catch (EmptyResultDataAccessException e) {
            log.error(e.getMessage());
            throw new ApiException("This link is not valid. Please request a new password reset link.");
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("An unexpected error occurred. Please try again.");
        }
    }

    @Override
    public User verifyAccountKey(String key) {
        try {
            User user = jdbcTemplate.queryForObject(SELECT_USER_BY_ACCOUNT_QUERY, of("url", getVerificationURL(key, ACCOUNT.getType())), new UserRowMapper());
            jdbcTemplate.update(UPDATE_USER_ENABLED_QUERY, of("enabled", true, "id", user.getId()));
            // Log successful account verification for auditing
            log.info("Account successfully verified for user with email: {}", user.getEmail());
            return user;
        } catch (EmptyResultDataAccessException e) {
            log.error("This code is not valid. Please attempt to login again.");
            throw new UsernameNotFoundException("This code is not valid. Please attempt to login again.");
        } catch (Exception exception) {
            log.error("Unexpected error during 2FA code verification for email '{}'", exception.getMessage(), exception);
            throw new BadCredentialsException("An unexpected error occurred while verifying the account.");
        }
    }

    private boolean isLinkExpired(String key, VerificationType password) {
        try {
            return jdbcTemplate.queryForObject(SELECT_EXPIRATION_BY_URL, of("url", getVerificationURL(key, password.getType())), Boolean.class);
        } catch (EmptyResultDataAccessException e) {
            log.error(e.getMessage());
            throw new ApiException("This link is not valid. Please request a new password reset link.");
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("An unexpected error occurred. Please try again.");
        }
    }

    private Boolean isVerificationCodeExpired(String code) {
        try {
            return jdbcTemplate.queryForObject(CHECK_2FA_CODE_EXPIRE_DATE, of("code", code), Boolean.class);
        } catch (EmptyResultDataAccessException e) {
            log.error("This code is not valid. Please attempt to login again.");
            throw new UsernameNotFoundException("This code is not valid. Please attempt to login again.");
        } catch (Exception exception) {
            log.error("Unexpected error during 2FA code verification for email '{}'", exception.getMessage(), exception);
            throw new BadCredentialsException("An unexpected error occurred while verifying the code.");
        }
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        log.debug("Spring Security is attempting to load user by email: {}", email);
        User user = null;
        try {
            user = getUserByEmail(email);
        } catch (ApiException e) {
            log.warn("User lookup failed for email '{}': {}", email, e.getMessage());
            throw new UsernameNotFoundException("User not found in our database: " + email);
        }
        if (user == null) {
            log.error("User not found in our database. Are you searching for the right person? Entered email: {}", email);
            throw new UsernameNotFoundException("User not found in our database: " + email);
        } else {
            log.info("We have found this user in our database with the following address: {} ", email);
            log.debug("Building UserPrincipal for user with email: {} and id: {}", email, user.getId());
            log.info("User with email '{}' has 2FA/MFA enabled: {}", email, user.isUsing2FA());
            return new UserPrincipal(user, roleRepository.getRoleByUserId(user.getId()));
        }
    }
}


