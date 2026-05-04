package com.bob.angularspringbootfullstack.model;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.fromUser;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

/**
 * Spring Security UserDetails adapter for the application's User entity.
 *
 * Wraps a User and its Role so Spring Security can verify credentials, read
 * granted authorities, and check account status flags. Constructed by
 * UserRepoImpl#loadUserByUsername during authentication and stored as the
 * principal of the SecurityContext Authentication for the duration of a request.
 * Authorities come from the comma-separated permission string on the Role
 * (e.g. "READ:USER,UPDATE:USER,DELETE:USER"), each segment becoming a
 * SimpleGrantedAuthority.
 */
@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {
    @Getter
    private final User user;
    private final Role role;

    /**
     * Splits the role's comma-separated permission string into one
     * SimpleGrantedAuthority per entry, trimming whitespace.
     *
     * Spring Security calls this to evaluate hasAnyAuthority(...) rules and
     * @PreAuthorize expressions against the current user.
     *
     * @return the user's granted authorities
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return stream(this.role.getPermission().split(","))
                .map(p -> new SimpleGrantedAuthority(p.trim()))
                .collect(toList());
    }

    /**
     * Returns the user's BCrypt-hashed password for the authentication provider
     * to compare against the password supplied at login.
     *
     * @return the stored BCrypt hash
     */
    @Override
    public @Nullable String getPassword() {
        return this.user.getPassword();
    }

    /**
     * Returns the user's email, which serves as the Spring Security username
     * (the principal identifier).
     *
     * @return the user's email
     */
    @Override
    public String getUsername() {
        return this.user.getEmail();
    }

    /**
     * Always true: account expiration is not modeled in this application.
     *
     * @return true
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * Returns whether the account is unlocked, sourced from the User's
     * notLocked flag in the database.
     *
     * @return true when the account is not locked
     */
    @Override
    public boolean isAccountNonLocked() {
        return this.user.isNotLocked();
    }

    /**
     * Always true: credential expiration (forced password rotation) is not
     * modeled in this application.
     *
     * @return true
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Returns whether the account is enabled, sourced from the User's enabled
     * flag in the database. New accounts start disabled until the email is
     * verified.
     *
     * @return true when the account is enabled
     */
    @Override
    public boolean isEnabled() {
        return this.user.isEnabled();
    }

    /**
     * Returns the wrapped user as a UserDTO with role/permission fields
     * flattened in. Lets the controller pull profile data straight off the
     * Authentication without a second database lookup.
     *
     * @return a UserDTO view of the wrapped user and role
     */
    public UserDTO getUser() {
        return fromUser(this.user, role);
    }
}
