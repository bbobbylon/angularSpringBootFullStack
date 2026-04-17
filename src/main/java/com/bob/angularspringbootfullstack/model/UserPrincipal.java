package com.bob.angularspringbootfullstack.model;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

// whenever we return this object and give it to Spring Security, we can remove the information from our User/permissions object and give it to the GrantedAuthority method for the loadUserByUsername interface.
@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {
    //when we want to return the data for grantedAuthorities, we are going to need to return the user and the permissions. This is because if we look at the GrantedAuthority class, we see that it requires a string parameter for the authority, and we need to provide that string from our permissions field.
    private final User user;
    // by defining these two variables, we can easily access the methods inside GrantedAuthority.
    private final String permissions;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // we are going to return the permissions as a list of granted authorities.
        // We are going to split the permissions string by comma, and then we are going to map each permission to a SimpleGrantedAuthority object, and then we are going to collect it into a list.
        return stream(permissions.split(",".trim())).map(SimpleGrantedAuthority::new).collect(toList());
    }

    @Override
    public @Nullable String getPassword() {
        return this.user.getPassword();
    }

    @Override
    public String getUsername() {
        return this.user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return this.user.isNotLocked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return this.user.isEnabled();
    }
}
