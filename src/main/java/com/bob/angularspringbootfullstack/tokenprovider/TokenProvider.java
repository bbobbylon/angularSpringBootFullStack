package com.bob.angularspringbootfullstack.tokenprovider;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.InvalidClaimException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.repo.SessionRepo;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static com.auth0.jwt.algorithms.Algorithm.HMAC512;
import static com.bob.angularspringbootfullstack.constants.Constants.*;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

/**
 * Issues and verifies JWTs for authenticated users.
 * <p>
 * Access tokens carry the user's authorities, use the numeric user ID as the
 * subject, and expire in 30 minutes; refresh tokens carry the subject (user ID)
 * plus a {@code jti} and expire in 5 days. Both carry the refresh-session FAMILY
 * id in the {@code sid} claim (plan.md M5): the jti identifies one concrete
 * refresh token for rotation/reuse detection, while the family ties rotations
 * together into the one "session" the Security Center lists and revokes — see
 * {@code SessionService}. Both are signed with HMAC512 using the secret from
 * application properties. Verification intentionally does not require the
 * "authorities" claim, so refresh tokens remain valid; CustomAuthFilter then
 * refuses to authenticate any token that lacks authorities.
 * <p>
 * Access-token revocation (FUTURE-ENHANCEMENTS §3.1): {@link #isTokenValid} checks the token's
 * {@code sid} family against {@link SessionRepo#isFamilyRevoked}, so any of
 * {@code SessionService}'s revoke paths (single-session revoke, "log out everywhere else",
 * password-change "revoke all", or server-initiated reuse detection) takes effect on the
 * already-issued access token immediately rather than only once it naturally expires.
 */
@Component
@RequiredArgsConstructor
public class TokenProvider {

    private final UserService userService;
    private final SessionRepo sessionRepo;
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Generates a JWT access token for the given UserPrincipal.
     * The token includes issuer, audience, issued at, subject (user ID),
     * authorities (permissions/roles), the refresh-session family ({@code sid}),
     * and an expiration time (30 minutes).
     * The token is signed using HMAC512 with the secret key.
     * <p>
     * The {@code sid} claim is checked against the session store's revoked flag on every
     * request (see {@link #isTokenValid}) — access tokens are no longer fully stateless
     * (superseding the original NFR-PERF-2 framing; see {@link #isTokenValid}'s Javadoc for
     * why the marginal cost is small). It also lets the sessions endpoint and the SPA mark
     * which listed session the caller is currently on.
     *
     * @param userPrincipal an authenticated user
     * @param sessionFamily the refresh-session family minted by {@code SessionService}
     * @return a signed JWT access token as a String
     */
    public String createAccessToken(UserPrincipal userPrincipal, String sessionFamily) {
        return JWT.create()
                .withIssuer(BOBBYLON_LLC)
                .withAudience(BOBS_MANAGEMENT)
                .withIssuedAt(new Date())
                .withSubject(String.valueOf(userPrincipal.getUser().getId()))
                .withArrayClaim(AUTHORITIES, getClaimsFromUser(userPrincipal))
                .withClaim(SESSION_FAMILY, sessionFamily)
                .withExpiresAt(new Date(currentTimeMillis() + ACCESS_TOKEN_EXPIRE_TIME))
                .sign(HMAC512(secret.getBytes()));
    }

    /**
     * Flattens the principal's authorities into a String array suitable for
     * embedding as the "authorities" JWT claim.
     *
     * @param userPrincipal an authenticated user
     * @return the authority names (e.g. "READ:USER")
     */
    private String[] getClaimsFromUser(UserPrincipal userPrincipal) {
        return userPrincipal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toArray(String[]::new);
    }

    /**
     * Generates a JWT refresh token for the given UserPrincipal.
     * The refresh token includes issuer, audience, issued at, subject (user ID),
     * the rotation identifiers ({@code jti} + {@code sid} family), and an
     * expiration time (5 days). It does NOT include authorities.
     * The token is signed using HMAC512 with the secret key.
     * <p>
     * The jti is the row key of this token's {@code refreshsessions} record: the
     * refresh endpoint resolves it server-side, which is what turns a bare JWT
     * into a revocable, rotation-tracked session token (FR-JWT-5).
     *
     * @param userPrincipal an authenticated user
     * @param jti           unique id of this concrete refresh token
     * @param sessionFamily the family tying this token's rotations into one session
     * @return a signed JWT refresh token as a String
     */
    public String createRefreshToken(UserPrincipal userPrincipal, String jti, String sessionFamily) {
        return JWT.create()
                .withIssuer(BOBBYLON_LLC)
                .withAudience(BOBS_MANAGEMENT)
                .withIssuedAt(new Date())
                .withJWTId(jti)
                .withClaim(SESSION_FAMILY, sessionFamily)
                //returns a string, but we will convert it to type Long when we extract the subject from the token. The subject is the user's ID.
                .withSubject(String.valueOf(userPrincipal.getUser().getId()))
                .withExpiresAt(new Date(currentTimeMillis() + REFRESH_TOKEN_EXPIRE_TIME))
                .sign(HMAC512(secret.getBytes()));
    }

    /**
     * Verifies the token and returns its {@code jti} — the rotation identifier the
     * refresh endpoint resolves against the {@code refreshsessions} table. Null for
     * legacy tokens minted before M5 (their holders must re-authenticate once).
     *
     * @param token the raw refresh JWT
     * @return the JWT ID, or null when the token predates rotation support
     * @throws JWTVerificationException if signature, issuer, or expiration verification fails
     */
    public String getTokenId(String token) {
        return getJWTVerifier().verify(token).getId();
    }

    /**
     * Creates and returns a JWTVerifier instance using the secret key and HMAC512 algorithm.
     * This verifier is used to validate the signature and claims of JWT tokens.
     *
     * @return a configured JWTVerifier instance
     * @throws RuntimeException if the secret is invalid or the verifier cannot be created
     */
    private JWTVerifier getJWTVerifier() {
        JWTVerifier verifier;
        try {
            Algorithm alg = HMAC512(secret);
            // Do not require the 'authorities' claim at verification time because refresh tokens
            // do not include authorities. Claim presence is enforced only when authorities are needed.
            verifier = JWT.require(alg).withIssuer(BOBBYLON_LLC).build();
        } catch (JWTVerificationException e) {
            throw new JWTVerificationException(TOKEN_UNVERIFIABLE);
        }
        return verifier;
    }

    /**
     * Verifies the token and returns its {@code sid} (session family) claim, present on
     * both token types since M5. Used by the sessions endpoint to mark the caller's
     * current session in the device list.
     *
     * @param token a raw access or refresh JWT
     * @return the session family, or null for pre-M5 tokens
     * @throws JWTVerificationException if signature, issuer, or expiration verification fails
     */
    public String getSessionFamily(String token) {
        Claim claim = getJWTVerifier().verify(token).getClaim(SESSION_FAMILY);
        return claim == null || claim.isNull() ? null : claim.asString();
    }

    /**
     * Extracts the authorities (roles/permissions) from a JWT token.
     * The authorities are stored as a claim in the token and are converted
     * back into a list of GrantedAuthority objects for use by Spring Security.
     *
     * @param token the JWT token
     * @return a list of GrantedAuthority objects representing the user's permissions
     */
    public List<GrantedAuthority> getAuthorities(String token) {
        String[] claims = getClaimsFromToken(token);
        return stream(claims).map(SimpleGrantedAuthority::new).collect(toList());
    }

    /**
     * Verifies the token and returns its "authorities" claim as a String array.
     * <p>
     * Returns an empty array when the claim is missing or null, so refresh
     * tokens (which intentionally omit authorities) verify without throwing;
     * the caller decides whether to authenticate based on the array being
     * non-empty.
     *
     * @param token a JWT
     * @return the authority strings, or an empty array if the claim is absent
     * @throws JWTVerificationException if signature, issuer, or expiration verification fails
     */
    private String[] getClaimsFromToken(String token) {
        JWTVerifier verifier = getJWTVerifier();
        DecodedJWT decoded = verifier.verify(token);
        Claim claim = decoded.getClaim(AUTHORITIES);
        if (claim == null || claim.isNull()) {
            return new String[0];
        }
        String[] arr = claim.asArray(String.class);
        return arr == null ? new String[0] : arr;
    }

    /**
     * Builds an authenticated UsernamePasswordAuthenticationToken for the
     * SecurityContext, using the user loaded by ID as the principal and
     * stamping web details (IP, session id) onto it from the request.
     *
     * @param userID      the user's numeric ID (subject extracted from the access token)
     * @param authorities authorities pulled from the token
     * @param request     the current HTTP request, used to attach WebAuthenticationDetails
     * @return a fully populated Authentication ready to place in the SecurityContext
     */
    public Authentication getAuthentication(Long userID, List<GrantedAuthority> authorities, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userService.getUserById(userID), null, authorities);
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authToken;
    }

    /**
     * Returns true when the token is structurally valid, not expired, and was
     * issued after both the user's last password change AND last role change.
     * Used by CustomAuthFilter as a gate before extracting authorities and
     * authenticating the request.
     * <p>
     * The {@code passwordChangedAt} check ensures tokens issued before a password
     * change are invalidated — preventing stolen pre-change tokens from remaining
     * usable. The {@code rolesChangedAt} check (FUTURE-ENHANCEMENTS §3.1) does the
     * same for a role change: without it, a demoted user's already-issued access
     * token keeps carrying its old, now-revoked authorities for up to the full
     * 30-minute TTL, since the "authorities" claim is baked in at mint time and
     * never re-derived per request. Both checks share one already-loaded
     * {@code UserDTO} rather than two separate lookups.
     * <p>
     * A third gate runs first: the token's {@code sid} (session family) is checked
     * against {@link SessionRepo#isFamilyRevoked}. This is what actually closes
     * "access tokens have no revocation path" (FUTURE-ENHANCEMENTS §3.1) — the two
     * checks above only cover password/role changes, not a user (or an admin, or
     * reuse detection) explicitly revoking one specific session. A revoked family
     * fails fast here, before the {@code userService.getUserById} call below, which
     * already ran unconditionally on every request for the password/role checks —
     * so this adds one indexed point-lookup on {@code refreshsessions.family} to a
     * path that was already making a DB round trip, not a second DB hit where there
     * was previously none. Legacy pre-M5 tokens with no {@code sid} claim skip this
     * check entirely, exactly as {@link #getSessionFamily} already treats them.
     *
     * @param userID the numeric user ID previously extracted via {@link #getSubject(String, HttpServletRequest)}
     * @param token  the raw JWT
     * @return true if all checks pass
     * @throws JWTVerificationException if token verification fails
     */
    public boolean isTokenValid(Long userID, String token) {
        if (Objects.isNull(userID)) return false;
        JWTVerifier verifier = getJWTVerifier();
        if (isTokenExpired(verifier, token)) return false;

        String family = getSessionFamily(token);
        if (family != null && sessionRepo.isFamilyRevoked(family)) return false;

        var user = userService.getUserById(userID);
        LocalDateTime passwordChangedAt = user.getPasswordChangedAt();
        LocalDateTime rolesChangedAt = user.getRolesChangedAt();
        if (passwordChangedAt == null && rolesChangedAt == null) return true;

        // Reject tokens issued before whichever of the two invalidation events happened most
        // recently — a token must postdate BOTH the last password change and the last role
        // change, not merely the more recent of the two by coincidence.
        LocalDateTime issuedAt = verifier.verify(token).getIssuedAt()
                .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        if (passwordChangedAt != null && !issuedAt.isAfter(passwordChangedAt)) return false;
        return rolesChangedAt == null || issuedAt.isAfter(rolesChangedAt);
    }

    /**
     * Verifies the token and returns true when its "exp" claim is before now.
     *
     * @param verifier the configured JWTVerifier
     * @param token    the raw JWT
     * @return true if the token is past its expiration
     * @throws JWTVerificationException if verification fails
     */
    private boolean isTokenExpired(JWTVerifier verifier, String token) {
        Date expiration = verifier.verify(token).getExpiresAt();
        return expiration.before(new Date());
    }

    /**
     * Verifies the token and returns its subject (the user's ID).
     * <p>
     * Catches the JWT library's failure modes and remaps them so callers see
     * exceptions with consistent semantics: expired tokens and
     * InvalidClaimException become a generic JWTVerificationException (401),
     * JWTDecodeException/IllegalArgumentException become a
     * BadCredentialsException with a client-safe message ("Could not decode
     * the token..."), and any other verification failure becomes an
     * ApiException. The original library message is stashed on the request
     * attributes "expiredMessage", "invalidClaim", or "invalidToken" for
     * server-side logging.
     *
     * @param token   the raw JWT
     * @param request the current request, used to stash error context
     * @return the subject claim (the user's ID)
     * @throws JWTVerificationException for all token verification failures
     */
    public Long getSubject(String token, HttpServletRequest request) throws JWTVerificationException {
        try {
            // returns a string, which we convert into type Long.
            return Long.valueOf(getJWTVerifier().verify(token).getSubject());
        } catch (TokenExpiredException e) {
            // Store the detail server-side for logging but never expose it to the client.
            request.setAttribute("expiredMessage", e.getMessage());
            throw new JWTVerificationException("Authentication failed.");
        } catch (InvalidClaimException e) {
            request.setAttribute("invalidClaim", e.getMessage());
            throw e;
        } catch (com.auth0.jwt.exceptions.JWTDecodeException | IllegalArgumentException decodeEx) {
            // Token couldn't be decoded (not valid Base64 or malformed).
            //
            // This MUST stay inside the JWTVerificationException family. ExceptionUtils#processError
            // decides 401-vs-400 with `exception instanceof JWTVerificationException`, so rethrowing
            // as BadCredentialsException (as this line used to) silently downgraded every malformed
            // token to 400 — and token.interceptor.ts retries only on 401, so the silent refresh
            // never fired for a truncated or corrupted token. Confirmed against the live ALB
            // 2026-08-02: `Bearer bad.token` returned 400, not 401. See ROADMAP §2.4(a).
            //
            // The old BadCredentialsException also overloaded that type to mean two unrelated
            // things — "wrong email/password" and "unparseable token" — which HandleException then
            // had to tell apart by string-matching the message for "Could not decode". Keeping the
            // JWT failures in their own family removes the need for that guess entirely.
            String msg = "Could not decode the token. The input is not a valid Base64-encoded JWT.";
            request.setAttribute("invalidToken", decodeEx.getMessage());
            throw new JWTVerificationException(msg);
        } catch (JWTVerificationException verificationEx) {
            // Any other verification issue (invalid signature, failed claim check). Same reasoning as
            // above: previously rethrown as ApiException, which also lands in processError's 400
            // branch. A token with a bad signature is an authentication failure, not a bad request.
            String msg = "Invalid token. " + verificationEx.getMessage();
            request.setAttribute("invalidToken", verificationEx.getMessage());
            throw new JWTVerificationException(msg);
        }
    }
}