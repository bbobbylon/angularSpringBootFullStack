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
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * USERPRINCIPAL - SPRING SECURITY'S VIEW OF THE USER
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * 
 * UserPrincipal is the bridge between our domain model (User) and Spring Security.
 * 
 * It implements UserDetails interface, which is Spring Security's contract for:
 * "Here's everything Spring Security needs to know about this user for authentication/authorization"
 * 
 * WHEN IS USERPRINCIPAL CREATED:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * 
 * During Authentication (Login):
 * 1. User POST /user/login with email + password
 * 2. DaoAuthenticationProvider calls:
 *    userDetailsService.loadUserByUsername(email)
 *    → UserRepoImpl.loadUserByUsername(email)
 *    ↓
 * 3. UserRepoImpl:
 *    a) Queries: SELECT * FROM users WHERE email = ?
 *    b) Gets User entity
 *    c) Gets user's role: roleRepository.getRoleByUserId(user.id)
 *    d) Extracts authorities from role.permission
 *    e) Creates UserPrincipal(user, authorities)
 *    f) Returns UserPrincipal
 *    ↓
 * 4. DaoAuthenticationProvider:
 *    a) Gets UserPrincipal.getPassword()
 *    b) Compares with provided password using passwordEncoder
 *    c) If match: creates Authentication(userPrincipal, authorities)
 *    ↓
 * 5. Spring Security:
 *    a) Sets SecurityContextHolder.getContext().setAuthentication(auth)
 *    b) UserPrincipal is now in SecurityContext
 *    ↓
 * 6. Throughout the request:
 *    a) SecurityContextHolder.getContext().getAuthentication().getPrincipal()
 *    b) Returns the UserPrincipal
 *    c) Available in controller, service, etc.
 * 
 * 
 * WHAT INFORMATION DOES USERPRINCIPAL CONTAIN:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * 
 * 1. User Entity:
 *    - id, firstName, lastName, email
 *    - password (BCrypt hash)
 *    - enabled, notLocked, using2FA
 *    - imageUrl, address, phoneNumber, etc.
 * 
 * 2. Authorities (Permissions):
 *    - String like: "READ:USER,UPDATE:USER,DELETE:USER"
 *    - Parsed into List<GrantedAuthority>
 *    - Used for authorization checks (@PreAuthorize, hasAnyAuthority, etc.)
 * 
 * 
 * GRANTED AUTHORITIES EXPLAINED:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * 
 * GrantedAuthority represents ONE permission/role the user has.
 * 
 * In our app, we use PERMISSION-BASED authorities (not role-based):
 * 
 * Traditional Role-Based:
 * - User has role: ROLE_ADMIN
 * - Admin can do everything
 * - Not fine-grained
 * 
 * Permission-Based (WHAT WE USE):
 * - User has permissions: READ:USER, UPDATE:USER, DELETE:USER
 * - Each permission is specific
 * - More flexible, fine-grained access control
 * 
 * Examples:
 * - Regular user: ["READ:USER", "UPDATE:USER"]
 * - Admin: ["READ:USER", "READ:CUSTOMER", "UPDATE:USER", "UPDATE:CUSTOMER", "DELETE:USER", "DELETE:CUSTOMER"]
 * 
 * In database (roles table):
 * id | name | permission
 * 1  | USER | READ:USER,UPDATE:USER
 * 2  | ADMIN| READ:USER,READ:CUSTOMER,UPDATE:USER,UPDATE:CUSTOMER,DELETE:USER,DELETE:CUSTOMER
 * 
 * UserPrincipal.getAuthorities() converts permission string to List<GrantedAuthority>:
 * Input:  "READ:USER,UPDATE:USER,DELETE:USER"
 * Split:  ["READ:USER", "UPDATE:USER", "DELETE:USER"]
 * Map:    [SimpleGrantedAuthority("READ:USER"), SimpleGrantedAuthority("UPDATE:USER"), SimpleGrantedAuthority("DELETE:USER")]
 * 
 * Spring Security then uses this List to check permissions:
 * @PreAuthorize("hasAnyAuthority('DELETE:USER')")
 * → Checks if user's authorities contain "DELETE:USER"
 * → If YES: allows method
 * → If NO: throws AccessDeniedException
 * 
 * 
 * SECURITY FLOW WITH USERPRINCIPAL:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * 
 * Scenario: User tries DELETE /user/delete/5
 * 
 * 1. Request reaches SecurityFilterChain
 * 2. SecurityFilterChain checks rule: DELETE /user/delete/** requires hasAnyAuthority("DELETE:USER")
 * 3. Gets Authentication from SecurityContextHolder (has UserPrincipal)
 * 4. Calls UserPrincipal.getAuthorities()
 *    → "READ:USER,UPDATE:USER,DELETE:USER"
 *    → Split and convert to List<GrantedAuthority>
 * 5. Checks if List contains SimpleGrantedAuthority("DELETE:USER")
 * 6. YES: allows request to controller
 * 7. NO: calls CustomAccessDeniedHandler → returns 403 Forbidden
 * 
 * 
 * USERDETAILS METHODS EXPLAINED:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * 
 * Spring Security calls these methods to determine if user can authenticate/access resources:
 * 
 * 1. getAuthorities() → List<GrantedAuthority>
 *    - Returns user's permissions
 *    - Used for authorization checks
 *    - Example: ["READ:USER", "UPDATE:USER"]
 * 
 * 2. getPassword() → String
 *    - Returns BCrypt password hash
 *    - Used during authentication to verify password
 *    - Must match password submitted by user (after BCrypt.matches())
 * 
 * 3. getUsername() → String
 *    - Returns username (in our case: email)
 *    - Spring Security uses this as the principal identifier
 *    - Logged in logs, displayed in UI, etc.
 * 
 * 4. isAccountNonExpired() → boolean
 *    - Is the user's account still valid?
 *    - If returns false: Spring Security rejects even if password correct
 *    - We return true (not implemented)
 * 
 * 5. isAccountNonLocked() → boolean
 *    - Is the user's account locked?
 *    - Use this for disabling accounts after failed login attempts
 *    - We return user.isNotLocked() (comes from database)
 *    - If user tries to brute-force login: lock account (set to false)
 *    - Attacker can't login even with correct password until unlocked
 * 
 * 6. isCredentialsNonExpired() → boolean
 *    - Have the credentials (password) expired?
 *    - If returns false: user must change password
 *    - We return true (not implemented)
 * 
 * 7. isEnabled() → boolean
 *    - Is the user account enabled/active?
 *    - If returns false: account is disabled
 *    - We return user.isEnabled() (comes from database)
 *    - Disable accounts for: non-verified emails, users who unsubscribed, etc.
 * 
 * 
 * AUTHENTICATION FLOW USING USERPRINCIPAL:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * 
 * During DaoAuthenticationProvider.authenticate():
 * 
 * 1. Gets UserPrincipal from UserDetailsService.loadUserByUsername()
 * 2. Calls UserPrincipal.isEnabled()
 *    → If false: throw DisabledException
 * 3. Calls UserPrincipal.isAccountNonLocked()
 *    → If false: throw LockedException
 * 4. Calls UserPrincipal.isAccountNonExpired()
 *    → If false: throw AccountExpiredException
 * 5. Calls UserPrincipal.getPassword()
 *    → Gets BCrypt hash
 * 6. Compares with provided password using passwordEncoder.matches()
 *    → If don't match: throw BadCredentialsException
 * 7. Calls UserPrincipal.isCredentialsNonExpired()
 *    → If false: throw CredentialsExpiredException
 * 8. All checks passed:
 *    → Create new UsernamePasswordAuthenticationToken(userPrincipal, null, authorities)
 *    → Set authenticated to true
 *    → Return Authentication
 * 9. Spring Security stores in SecurityContextHolder
 * 10. Request continues to controller
 * 
 */
@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {
    /** The actual user entity with profile information */
    private final User user;
    /** Comma-separated permissions/authorities (e.g., "READ:USER,UPDATE:USER,DELETE:USER") */
    private final String permissions;

    /**
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * GET AUTHORITIES - CONVERTS PERMISSION STRING TO GRANTEDAUTHORITY LIST
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * 
     * This method is CRITICAL for authorization in Spring Security.
     * It's called every time Spring Security needs to check what user can do.
     * 
     * PROCESS STEP-BY-STEP:
     * 
     * Input: permissions = "READ:USER,UPDATE:USER,DELETE:USER"
     * 
     * 1. stream(permissions.split(","))
     *    - Split string by comma
     *    - Input: "READ:USER,UPDATE:USER,DELETE:USER"
     *    - Output: ["READ:USER", "UPDATE:USER", "DELETE:USER"]
     *    - Create Stream from array
     * 
     * 2. .map(SimpleGrantedAuthority::new)
     *    - For each String permission
     *    - Create new SimpleGrantedAuthority(permission)
     *    - SimpleGrantedAuthority is Spring's simple GrantedAuthority implementation
     *    - Stream transforms to Stream<SimpleGrantedAuthority>
     *    - Examples:
     *      * "READ:USER" → SimpleGrantedAuthority("READ:USER")
     *      * "UPDATE:USER" → SimpleGrantedAuthority("UPDATE:USER")
     *      * "DELETE:USER" → SimpleGrantedAuthority("DELETE:USER")
     * 
     * 3. .collect(toList())
     *    - Collect all SimpleGrantedAuthority objects into List
     *    - Output: [SimpleGrantedAuthority("READ:USER"), SimpleGrantedAuthority("UPDATE:USER"), SimpleGrantedAuthority("DELETE:USER")]
     * 
     * HOW THIS IS USED IN SECURITY:
     * 
     * When SecurityFilterChain checks: .requestMatchers(DELETE, "/user/delete/**").hasAnyAuthority("DELETE:USER")
     * 
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
        return stream(permissions.split(","))
                .map(p -> new SimpleGrantedAuthority(p.trim()))  // .trim() removes spaces
                .collect(toList());
    }

    /**
     * Returns the user's password (BCrypt hash).
     * 
     * Used during authentication to verify password matches.
     * 
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
     * 
     * Spring Security uses this as the principal identifier.
     * 
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
     * 
     * If returns false: Spring Security rejects authentication.
     * Use for: password reset deadline, trial expiration, etc.
     * 
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
     * 
     * If returns false: account is locked, cannot authenticate.
     * Use for: failed login attempt countermeasure, admin lockout, etc.
     * 
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
     * 
     * If returns false: user must change password.
     * Use for: enforcing regular password changes.
     * 
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
     * 
     * If returns false: account is disabled.
     * Use for: email not verified, subscription inactive, etc.
     * 
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
}
