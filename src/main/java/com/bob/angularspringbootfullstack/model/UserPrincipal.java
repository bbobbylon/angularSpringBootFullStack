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
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * USERPRINCIPAL - SPRING SECURITY'S VIEW OF THE USER
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <p>
 * UserPrincipal is the bridge between our domain model (User) and Spring Security.
 * <p>
 * It implements UserDetails interface, which is Spring Security's contract for:
 * "Here's everything Spring Security needs to know about this user for authentication/authorization"
 * <p>
 * WHEN IS USERPRINCIPAL CREATED:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <p>
 * During Authentication (Login):
 * 1. User POST /user/login with email + password
 * 2. DaoAuthenticationProvider calls:
 * userDetailsService.loadUserByUsername(email)
 * → UserRepoImpl.loadUserByUsername(email)
 * ↓
 * 3. UserRepoImpl:
 * a) Queries: SELECT * FROM users WHERE email = ?
 * b) Gets User entity
 * c) Gets user's role: roleRepository.getRoleByUserId(user.id)
 * d) Extracts authorities from role.permission
 * e) Creates UserPrincipal(user, authorities)
 * f) Returns UserPrincipal
 * ↓
 * 4. DaoAuthenticationProvider:
 * a) Gets UserPrincipal.getPassword()
 * b) Compares with provided password using passwordEncoder
 * c) If match: creates Authentication(userPrincipal, authorities)
 * ↓
 * 5. Spring Security:
 * a) Sets SecurityContextHolder.getContext().setAuthentication(auth)
 * b) UserPrincipal is now in SecurityContext
 * ↓
 * 6. Throughout the request:
 * a) SecurityContextHolder.getContext().getAuthentication().getPrincipal()
 * b) Returns the UserPrincipal
 * c) Available in controller, service, etc.
 * <p>
 * <p>
 * WHAT INFORMATION DOES USERPRINCIPAL CONTAIN:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <p>
 * 1. User Entity:
 * - id, firstName, lastName, email
 * - password (BCrypt hash)
 * - enabled, notLocked, using2FA
 * - imageUrl, address, phoneNumber, etc.
 * <p>
 * 2. Authorities (Permissions):
 * - String like: "READ:USER,UPDATE:USER,DELETE:USER"
 * - Parsed into List<GrantedAuthority>
 * - Used for authorization checks (@PreAuthorize, hasAnyAuthority, etc.)
 * <p>
 * <p>
 * GRANTED AUTHORITIES EXPLAINED:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <p>
 * GrantedAuthority represents ONE permission/role the user has.
 * <p>
 * In our app, we use PERMISSION-BASED authorities (not role-based):
 * <p>
 * Traditional Role-Based:
 * - User has role: ROLE_ADMIN
 * - Admin can do everything
 * - Not fine-grained
 * <p>
 * Permission-Based (WHAT WE USE):
 * - User has permissions: READ:USER, UPDATE:USER, DELETE:USER
 * - Each permission is specific
 * - More flexible, fine-grained access control
 * <p>
 * Examples:
 * - Regular user: ["READ:USER", "UPDATE:USER"]
 * - Admin: ["READ:USER", "READ:CUSTOMER", "UPDATE:USER", "UPDATE:CUSTOMER", "DELETE:USER", "DELETE:CUSTOMER"]
 * <p>
 * In database (roles table):
 * id | name | permission
 * 1  | USER | READ:USER,UPDATE:USER
 * 2  | ADMIN| READ:USER,READ:CUSTOMER,UPDATE:USER,UPDATE:CUSTOMER,DELETE:USER,DELETE:CUSTOMER
 * <p>
 * UserPrincipal.getAuthorities() converts permission string to List<GrantedAuthority>:
 * Input:  "READ:USER,UPDATE:USER,DELETE:USER"
 * Split:  ["READ:USER", "UPDATE:USER", "DELETE:USER"]
 * Map:    [SimpleGrantedAuthority("READ:USER"), SimpleGrantedAuthority("UPDATE:USER"), SimpleGrantedAuthority("DELETE:USER")]
 * <p>
 * Spring Security then uses this List to check permissions:
 *
 * @PreAuthorize("hasAnyAuthority('DELETE:USER')") → Checks if user's authorities contain "DELETE:USER"
 * → If YES: allows method
 * → If NO: throws AccessDeniedException
 * <p>
 * <p>
 * SECURITY FLOW WITH USERPRINCIPAL:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <p>
 * Scenario: User tries DELETE /user/delete/5
 * <p>
 * 1. Request reaches SecurityFilterChain
 * 2. SecurityFilterChain checks rule: DELETE /user/delete/** requires hasAnyAuthority("DELETE:USER")
 * 3. Gets Authentication from SecurityContextHolder (has UserPrincipal)
 * 4. Calls UserPrincipal.getAuthorities()
 * → "READ:USER,UPDATE:USER,DELETE:USER"
 * → Split and convert to List<GrantedAuthority>
 * 5. Checks if List contains SimpleGrantedAuthority("DELETE:USER")
 * 6. YES: allows request to controller
 * 7. NO: calls CustomAccessDeniedHandler → returns 403 Forbidden
 * <p>
 * <p>
 * USERDETAILS METHODS EXPLAINED:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <p>
 * Spring Security calls these methods to determine if user can authenticate/access resources:
 * <p>
 * 1. getAuthorities() → List<GrantedAuthority>
 * - Returns user's permissions
 * - Used for authorization checks
 * - Example: ["READ:USER", "UPDATE:USER"]
 * <p>
 * 2. getPassword() → String
 * - Returns BCrypt password hash
 * - Used during authentication to verify password
 * - Must match password submitted by user (after BCrypt.matches())
 * <p>
 * 3. getUsername() → String
 * - Returns username (in our case: email)
 * - Spring Security uses this as the principal identifier
 * - Logged in logs, displayed in UI, etc.
 * <p>
 * 4. isAccountNonExpired() → boolean
 * - Is the user's account still valid?
 * - If returns false: Spring Security rejects even if password correct
 * - We return true (not implemented)
 * <p>
 * 5. isAccountNonLocked() → boolean
 * - Is the user's account locked?
 * - Use this for disabling accounts after failed login attempts
 * - We return user.isNotLocked() (comes from database)
 * - If user tries to brute-force login: lock account (set to false)
 * - Attacker can't login even with correct password until unlocked
 * <p>
 * 6. isCredentialsNonExpired() → boolean
 * - Have the credentials (password) expired?
 * - If returns false: user must change password
 * - We return true (not implemented)
 * <p>
 * 7. isEnabled() → boolean
 * - Is the user account enabled/active?
 * - If returns false: account is disabled
 * - We return user.isEnabled() (comes from database)
 * - Disable accounts for: non-verified emails, users who unsubscribed, etc.
 * <p>
 * <p>
 * AUTHENTICATION FLOW USING USERPRINCIPAL:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <p>
 * During DaoAuthenticationProvider.authenticate():
 * <p>
 * 1. Gets UserPrincipal from UserDetailsService.loadUserByUsername()
 * 2. Calls UserPrincipal.isEnabled()
 * → If false: throw DisabledException
 * 3. Calls UserPrincipal.isAccountNonLocked()
 * → If false: throw LockedException
 * 4. Calls UserPrincipal.isAccountNonExpired()
 * → If false: throw AccountExpiredException
 * 5. Calls UserPrincipal.getPassword()
 * → Gets BCrypt hash
 * 6. Compares with provided password using passwordEncoder.matches()
 * → If don't match: throw BadCredentialsException
 * 7. Calls UserPrincipal.isCredentialsNonExpired()
 * → If false: throw CredentialsExpiredException
 * 8. All checks passed:
 * → Create new UsernamePasswordAuthenticationToken(userPrincipal, null, authorities)
 * → Set authenticated to true
 * → Return Authentication
 * 9. Spring Security stores in SecurityContextHolder
 * 10. Request continues to controller
 *
 */
@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {
    /**
     * The actual user entity with profile information
     */
    @Getter
    private final User user;
    /**
     * Comma-separated permissions/authorities (e.g., "READ:USER,UPDATE:USER,DELETE:USER"). Now, we are going to be switching over to the Role object instead, because it will be a cleaner way since our authenticationManager is calling the database, and then we are making a second call when we are hitting the /login resource (in the /login method we see authManager.authenticate() as well as user = userService.getUserByEmail. Instead of this, we should make a singular call. We will do this by using the Role object instead of permissions
     *
     */
    //private final String permissions;
    private final Role role;

    /**
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * GET AUTHORITIES - CONVERTS PERMISSION STRING TO GRANTEDAUTHORITY LIST
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * <p>
     * This method is CRITICAL for authorization in Spring Security.
     * It's called every time Spring Security needs to check what user can do.
     * <p>
     * PROCESS STEP-BY-STEP:
     * <p>
     * Input: permissions = "READ:USER,UPDATE:USER,DELETE:USER"
     * <p>
     * 1. stream(permissions.split(","))
     * - Split string by comma
     * - Input: "READ:USER,UPDATE:USER,DELETE:USER"
     * - Output: ["READ:USER", "UPDATE:USER", "DELETE:USER"]
     * - Create Stream from array
     * <p>
     * 2. .map(SimpleGrantedAuthority::new)
     * - For each String permission
     * - Create new SimpleGrantedAuthority(permission)
     * - SimpleGrantedAuthority is Spring's simple GrantedAuthority implementation
     * - Stream transforms to Stream<SimpleGrantedAuthority>
     * - Examples:
     * * "READ:USER" → SimpleGrantedAuthority("READ:USER")
     * * "UPDATE:USER" → SimpleGrantedAuthority("UPDATE:USER")
     * * "DELETE:USER" → SimpleGrantedAuthority("DELETE:USER")
     * <p>
     * 3. .collect(toList())
     * - Collect all SimpleGrantedAuthority objects into List
     * - Output: [SimpleGrantedAuthority("READ:USER"), SimpleGrantedAuthority("UPDATE:USER"), SimpleGrantedAuthority("DELETE:USER")]
     * <p>
     * HOW THIS IS USED IN SECURITY:
     * <p>
     * When SecurityFilterChain checks: .requestMatchers(DELETE, "/user/delete/**").hasAnyAuthority("DELETE:USER")
     * <p>
     * Spring calls this method:
     * 1. Gets Authentication from SecurityContextHolder
     * 2. Gets UserPrincipal from Authentication.getPrincipal()
     * 3. Calls UserPrincipal.getAuthorities()
     * 4. Iterates through returned List<GrantedAuthority>
     * 5. Checks if any authority.getAuthority() equals "DELETE:USER"
     * 6. If found: Allow request
     * 7. If not found: Deny request (403 Forbidden)
     *
     * @return List of GrantedAuthority objects representing user's permissions
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Example: "READ:USER,UPDATE:USER,DELETE:USER"
/*        return stream(permissions.split(","))
                .map(p -> new SimpleGrantedAuthority(p.trim()))  // .trim() removes spaces
                .collect(toList());*/
        return stream(this.role.getPermission().split(","))
                .map(p -> new SimpleGrantedAuthority(p.trim()))  // .trim() removes spaces
                .collect(toList());
    }

    /**
     * Returns the user's password (BCrypt hash).
     * <p>
     * Used during authentication to verify password matches.
     * <p>
     * Flow:
     * 1. DaoAuthenticationProvider gets this password
     * 2. Gets provided password from authentication request
     * 3. Calls: passwordEncoder.matches(providedPassword, this.getPassword())
     * 4. BCrypt compares them
     * 5. Returns true/false
     *
     * @return BCrypt password hash
     */
    @Override
    public @Nullable String getPassword() {
        return this.user.getPassword();
    }

    /**
     * Returns the user's username (email in our case).
     * <p>
     * Spring Security uses this as the principal identifier.
     * <p>
     * Uses:
     * - Logging/audit trails
     * - UI display: "Welcome, bob@example.com"
     * - Authorization checks: @AuthenticationPrincipal String username
     *
     * @return user's email address (used as username)
     */
    @Override
    public String getUsername() {
        return this.user.getEmail();
    }

    /**
     * Is the user's account expired?
     * <p>
     * If returns false: Spring Security rejects authentication.
     * Use for: password reset deadline, trial expiration, etc.
     * <p>
     * We always return true (not using expiration).
     *
     * @return true if account is not expired
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * Is the user's account locked?
     * <p>
     * If returns false: account is locked, cannot authenticate.
     * Use for: failed login attempt countermeasure, admin lockout, etc.
     * <p>
     * We return: user.isNotLocked() (boolean from database)
     * After failed login attempts: set to false to lock account
     * User cannot login until admin unlocks
     *
     * @return true if account is NOT locked
     */
    @Override
    public boolean isAccountNonLocked() {
        return this.user.isNotLocked();
    }

    /**
     * Have the user's credentials (password) expired?
     * <p>
     * If returns false: user must change password.
     * Use for: enforcing regular password changes.
     * <p>
     * We always return true (not enforcing expiration).
     *
     * @return true if credentials are not expired
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Is the user's account enabled?
     * <p>
     * If returns false: account is disabled.
     * Use for: email not verified, subscription inactive, etc.
     * <p>
     * We return: user.isEnabled() (boolean from database)
     * Example flow:
     * 1. User registers: isEnabled = false (email not verified)
     * 2. User clicks verification link: isEnabled = true
     * 3. Now user can login
     * 4. If user deletes account: isEnabled = false
     * 5. User cannot login anymore
     *
     * @return true if user account is enabled
     */
    @Override
    public boolean isEnabled() {
        return this.user.isEnabled();
    }

    // now we can get a user from the authentication instead of having to make two calls. Instead of returning just the email we can return the entire user object. This is going to be really helpful because we can get the user's profile information from the authentication instead of having to make a second call to the database to get the user by email.
    public UserDTO getUser() {
        return fromUser(this.user, role);
    }
}
