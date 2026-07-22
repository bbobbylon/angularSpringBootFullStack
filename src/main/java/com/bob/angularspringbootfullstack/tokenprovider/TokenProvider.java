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
 */
@Component
@RequiredArgsConstructor
public class TokenProvider {

    private final UserService userService;
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Generates a JWT access token for the given UserPrincipal.
     * The token includes issuer, audience, issued at, subject (user ID),
     * authorities (permissions/roles), the refresh-session family ({@code sid}),
     * and an expiration time (30 minutes).
     * The token is signed using HMAC512 with the secret key.
     * <p>
     * The {@code sid} claim does not gate validation — access tokens stay fully
     * stateless (NFR-PERF-2). It exists so the sessions endpoint and the SPA can
     * mark which listed session the caller is currently on.
     *
     * @param userPrincipal an authenticated user
     * @param sessionFamily the refresh-session family minted by {@code SessionService}
     * @return a signed JWT access token as a String
     */
    public String createAccessToken(UserPrincipal userPrincipal, String sessionFamily) {
        System.out.println("##################");
        System.out.println("Creating access token for user ID: " + userPrincipal.getUser().getId() + ", session family: " + sessionFamily);
        System.out.println("Secret value is:" + secret);
        System.out.println("##################");
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
     * issued after the user's last password change. Used by CustomAuthFilter as
     * a gate before extracting authorities and authenticating the request.
     * <p>
     * The {@code passwordChangedAt} check ensures tokens issued before a password
     * change are invalidated — preventing stolen pre-change tokens from remaining
     * usable.
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
        LocalDateTime passwordChangedAt = userService.getUserById(userID).getPasswordChangedAt();
        if (passwordChangedAt != null) {
            // Reject tokens that were issued before the last password change.
            LocalDateTime issuedAt = verifier.verify(token).getIssuedAt()
                    .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            return issuedAt.isAfter(passwordChangedAt);
        }
        return true;
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
            // Token couldn't be decoded (not valid Base64 or malformed). Map to a clear client error.
            String msg = "Could not decode the token. The input is not a valid Base64-encoded JWT.";
            request.setAttribute("invalidToken", decodeEx.getMessage());
            throw new org.springframework.security.authentication.BadCredentialsException(msg);
        } catch (JWTVerificationException verificationEx) {
            // Any other verification issues (signature invalid, claim checks) - return a clear message
            String msg = "Invalid token. " + verificationEx.getMessage();
            request.setAttribute("invalidToken", verificationEx.getMessage());
            throw new com.bob.angularspringbootfullstack.exception.ApiException(msg);
        }
    }
}