package com.bob.angularspringbootfullstack.model;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

/**
 * UserPrincipal implements Spring Security's UserDetails interface.
 *
 * This adapter class wraps a User entity and permission string to provide
 * Spring Security with the necessary authentication and authorization information.
 * It bridges the domain User model with Spring Security's UserDetails contract.
 *
 * When a user is authenticated, this object is created and used throughout
 * the Spring Security context to determine user permissions and access rights.
 */
@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {
    /** The user entity containing user profile information */
    private final User user;
    /** Comma-separated permission string (e.g., "USER_PERMISSION,ADMIN_PERMISSION") */
    private final String permissions;

    /**
     * Extracts the user's authorities/permissions from the permissions string.
     * Splits the comma-separated permission string and converts each permission
     * into a SimpleGrantedAuthority object for Spring Security.
     *
     * @return a collection of GrantedAuthority objects representing the user's permissions
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return stream(permissions.split(",")).map(p -> new SimpleGrantedAuthority(p.trim())).collect(toList());
    }

    /**
     * Returns the user's password (used during authentication).
     * This password is encrypted (BCrypted) and stored in the User entity.
     *
     * @return the user's encrypted password
     */
    @Override
    public @Nullable String getPassword() {
        return this.user.getPassword();
    }

    /**
     * Returns the user's username (which is the email in this application).
     * Spring Security uses this as the unique identifier for the user.
     *
     * @return the user's email address
     */
    @Override
    public String getUsername() {
        return this.user.getEmail();
    }

    /**
     * Checks if the user account has not expired.
     * Always returns true in this implementation.
     *
     * @return true if account is not expired
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * Checks if the user account is locked.
     * Returns the inverse of the user's isNotLocked flag.
     * Locked accounts cannot authenticate.
     *
     * @return true if account is not locked
     */
    @Override
    public boolean isAccountNonLocked() {
        return this.user.isNotLocked();
    }

    /**
     * Checks if the user's credentials (password) have not expired.
     * Always returns true in this implementation.
     *
     * @return true if credentials are not expired
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Checks if the user account is enabled.
     * Disabled accounts cannot authenticate even if other conditions are met.
     *
     * @return true if user account is enabled
     */
    @Override
    public boolean isEnabled() {
        return this.user.isEnabled();
    }
}
