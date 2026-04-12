package com.bob.angularspringbootfullstack.query;

public class UserQuery {

    // these values are being set based on our UserRepoImpl.getSqlParameterSource method, where we set our keys (first variable in .addValue()). Instead of using the "?" syntax, we will use this since we are using the named parameter rather than the JDBC template
    public static final String INSERT_USER_QUERY = "INSERT INTO users (first_name, last_name, email, password) VALUES (:firstName, :lastName, :email, :password)";
    public static final String COUNT_USER_EMAIL_QUERY = "SELECT COUNT(*) FROM users WHERE email = :email";
    public static final String INSERT_ACCOUNT_VERIFICATION_URL_QUERY = "INSERT INTO accountverifications (user_id, url) VALUES (:userId, url)";

}
