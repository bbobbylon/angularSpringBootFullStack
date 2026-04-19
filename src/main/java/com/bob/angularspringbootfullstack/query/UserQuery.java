package com.bob.angularspringbootfullstack.query;

/**
 * UserQuery contains all SQL query constants for user-related database operations.
 * <p>
 * These queries use named parameters (`:paramName`) instead of positional parameters (`?`)
 * to work with Spring's NamedParameterJdbcTemplate. Named parameters are set in the
 * MapSqlParameterSource using .addValue() method calls.
 * <p>
 * This centralized query class makes it easy to manage SQL statements and provides
 * a single point of change if table or column names are modified.
 */
public class UserQuery {
    /**
     * Inserts a new user into the users table.
     * Parameters: firstName, lastName, email, password
     * The database auto-generates the id field.
     */
    public static final String INSERT_USER_QUERY = "INSERT INTO users (first_name, last_name, email, password) VALUES (:firstName, :lastName, :email, :password)";

    /**
     * Counts the number of users with a specific email address.
     * Used for email uniqueness validation during registration.
     * Parameter: email
     */
    public static final String COUNT_USER_EMAIL_QUERY = "SELECT COUNT(*) FROM users WHERE email = :email";

    /**
     * Inserts an account verification record.
     * Stores the verification URL linked to a user for account activation flow.
     * Parameters: userId, url
     */
    public static final String INSERT_ACCOUNT_VERIFICATION_URL_QUERY = "INSERT INTO accountverifications (user_id, url) VALUES (:userId, :url)";

    /**
     * Selects all fields for a user by their email address.
     * Returns the complete user record for authentication and profile retrieval.
     * Parameter: email
     */
    public static final String SELECT_USER_BY_EMAIL_QUERY = "SELECT * FROM users WHERE email = :email";

    /**
     * Deletes existing 2FA verification codes for a user.
     * Used before inserting a new code to ensure only one valid code per user.
     * Parameter: id (user_id)
     */
    public static final String DELETE_2FA_CODE_BY_USER_ID = "DELETE FROM twofactorverifications WHERE user_id = :id";

    /**
     * Inserts a 2FA verification code for a user.
     * Stores the verification code with an expiration timestamp.
     * Parameters: userId, code, expirationDate
     */
    public static final String INSERT_2FA_CODE_BY_USER_ID_QUERY = "INSERT INTO twofactorverifications (user_id, code, expiration_date) VALUES (:userId, :code, :expirationDate)";

    public static final String SELECT_USER_BY_USER_CODE_QUERY = "SELECT * FROM users WHERE id = (SELECT user_id FROM twofactorverifications WHERE code = :code)";
    public static final String DELETE_2FA_CODE_BY_CODE_QUERY = "DELETE FROM twofactorverifications WHERE code = :code";
    public static final String CHECK_2FA_CODE_EXPIRE_DATE = "SELECT expiration_date < NOW() AS is_expired FROM twofactorverifications WHERE code = :code";
}
