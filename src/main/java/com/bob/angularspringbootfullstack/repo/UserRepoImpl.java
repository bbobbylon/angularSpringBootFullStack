package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_USER;
import static com.bob.angularspringbootfullstack.enumeration.VerificationType.ACCOUNT;
import static com.bob.angularspringbootfullstack.query.UserQuery.*;
import static java.util.Objects.requireNonNull;

// here the actual logic/ db queries will be implemented.
@Repository
@RequiredArgsConstructor
@Slf4j
public class UserRepoImpl implements UserRepo<User> {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RoleRepo<Role> roleRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public User create(User user) {
/*      here is what we need to do in this method:
        check for unique email, if unique, Save new user
        throw exception for duplicate email
        add role to user
        give verification url
        save url in verification table
        send email to user with verification url
        finally, we can return the new user
        throw exceptions for any errors that may occur during the process*/
        if (getEmailCount(user.getEmail().trim().toLowerCase()) > 0)
            throw new ApiException("Email already exists, please use a different email address and try again");
        //we want to get the ID of the user to give them roles and such
        try {
            // holder is for getting the generated ID of the new user we just created, and parameterSource is for mapping the user object to the SQL query parameters
            KeyHolder holder = new GeneratedKeyHolder();
            SqlParameterSource parameterSource = getSqlParameterSource(user);
            // once we have the parameters, we must update the database with the new user, and we will use the holder to get the generated ID of the new user
            jdbcTemplate.update(INSERT_USER_QUERY, parameterSource, holder);
            user.setId(requireNonNull(holder.getKey()).longValue()); // this key is the newly generated ID of the user we just created, and we set it to the user object so we can return it later. We use longValue() since we defined the User.Id as a type Long
            // we will give a role to a user now
            roleRepository.addRoleToUser(user.getId(), ROLE_USER.name());
            // we want to generate a random UUID since we are using this same method to generate the password verification email, password verification url, and new account verification url. This is so that we can leverage this and reuse it for other use-cases.
            String verificationURL = getVerificationURL(UUID.randomUUID().toString(), ACCOUNT.getType());

            jdbcTemplate.update(INSERT_VERIFICATION_URL_QUERY, Map.of("userId", user.getId(), "url", verificationURL, "type", ACCOUNT.getType()));

        } catch {
            (EmptyResultDataAccessException)
        }
        catch(Exception exception){

        }

        return null;
    }

    // this class is of type Integer since we want to count the number of emails which match the query.
    private Integer getEmailCount(String email) {
        //this will count to see how many emails we have that match the query, and it should be 0 if the email is unique, and greater than 0 if the email already exists in the database
        return jdbcTemplate.queryForObject(COUNT_USER_EMAIL_QUERY, Map.of("email", email), Integer.class);
    }

    // this method is for mapping the user object to the SQL query parameters. We will use it in the create method to insert the new user into the database
    // this is what the user will be giving us upon registration
    private SqlParameterSource getSqlParameterSource(User user) {
        return new MapSqlParameterSource()
                .addValue("firstName", user.getFirstName())
                .addValue("lastName", user.getLastName())
                .addValue("email", user.getEmail().trim().toLowerCase())
                // we don't want to store the raw password in the database, so we will use the Spring BCryptEncoder
                .addValue("password", passwordEncoder.encode(user.getPassword()));
    }

    // we are defining "key" here since every URL will need a specific unique key. It will be type String
    private String getVerificationURL(String key, String type) {
        // this is the backend URL, one of the three options which we mentioned above
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/user/verify" + type + "/" + key).toUriString();
    }

    @Override
    public Collection<User> list(int page, int pageSize) {
        return List.of();
    }

    @Override
    public User get(Long id) {
        return null;
    }

    @Override
    public User update(Long id, User data) {
        return null;
    }

    @Override
    public void delete(Long id) {

    }
}
