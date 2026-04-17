package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.rowmapper.RoleRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_USER;
import static com.bob.angularspringbootfullstack.query.RoleQuery.*;
import static java.util.Objects.requireNonNull;

@Repository
@RequiredArgsConstructor
@Slf4j
public class RoleRepoImpl implements RoleRepo<Role> {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public Role create(Role data) {
        return null;
    }

    @Override
    public java.util.Collection<Role> list(int page, int pageSize) {
        return null;
    }

    @Override
    public Role get(Long id) {
        return null;
    }

    @Override
    public Role update(Long id, Role data) {
        return null;
    }

    @Override
    public void delete(Long id) {

    }

    @Override
    public void addRoleToUser(Long userId, String roleName) {
        log.info("Adding role {} to user with ID {}", roleName, userId);
        try {
            /* Here we need to find the name of the role in the database, and then we will get the ID of the role, and then we will add the role to the user by
             inserting a new record in the user_role table with the user ID and the role ID. We will use a query to get the role by name, and then we will use another
              query to insert the role to the user.*/
            Role role = jdbcTemplate.queryForObject(SELECT_ROLE_BY_NAME_QUERY, Map.of("name", roleName), new RoleRowMapper());
            // here we will update the user in the UserRole MySQL table. We will inject the user by their ID into the role by the role's ID which we just fetched in the previous line.
            jdbcTemplate.update(INSERT_ROLE_TO_USER_QUERY, Map.of("userId", userId, "roleId", requireNonNull(role).getId()));

        } catch (EmptyResultDataAccessException e) {
            throw new ApiException("Can't find role via name" + ROLE_USER.name());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }

    }

    @Override
    public Role getRoleByUserId(Long userId) {
        log.info("Getting role to user with ID {}", userId);
        try {
            /* Here we need to find the name of the role in the database, and then we will get the ID of the role, and then we will add the role to the user by
             inserting a new record in the user_role table with the user ID and the role ID. We will use a query to get the role by name, and then we will use another
              query to insert the role to the user.*/
            return jdbcTemplate.queryForObject(SELECT_ROLE_BY_ID_QUERY, Map.of("id", userId), new RoleRowMapper());

        } catch (EmptyResultDataAccessException e) {
            throw new ApiException("Can't find role via name" + ROLE_USER.name());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    @Override
    public Role getRoleByUserEmail(String email) {
        return null;
    }

    @Override
    public void upateUserRole(Long userId, String roleName) {

    }
}
