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
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

import static com.auth0.jwt.algorithms.Algorithm.HMAC512;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

/**
 * Issues and verifies JWTs for authenticated users.
 *
 * Access tokens carry the user's authorities and expire in 30 minutes; refresh
 * tokens carry only the subject (email) and expire in 5 days. Both are signed
 * with HMAC512 using the secret from application properties. Verification
 * intentionally does not require the "authorities" claim so refresh tokens
 * remain valid; CustomAuthFilter then refuses to authenticate any token that
 * lacks authorities.
 */
@Component
@RequiredArgsConstructor
public class TokenProvider {
    private static final String BOBBYLON_LLC = "BOBBYLON_LLC";
    private static final String BOBS_MANAGEMENT = "BOBS_MANAGEMENT";
    private static final String AUTHORITIES = "authorities";
    private static final long ACCESS_TOKEN_EXPIRE_TIME = 1_800_000;
    private static final long REFRESH_TOKEN_EXPIRE_TIME = 432_000_000;
    private static final String TOKEN_UNVERIFIABLE = "Invalid JWT secret key";
    private final UserService userService;
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Generates a JWT access token for the given UserPrincipal.
     * The token includes issuer, audience, issued at, subject (username),
     * authorities (permissions/roles), and an expiration time (30 minutes).
     * The token is signed using HMAC512 with the secret key.
     *
     * @param userPrincipal an authenticated user
     * @return a signed JWT access token as a String
     */
    public String createAccessToken(UserPrincipal userPrincipal) {
        return JWT.create()
                .withIssuer(BOBBYLON_LLC)
                .withAudience(BOBS_MANAGEMENT)
                .withIssuedAt(new Date())
                .withSubject(userPrincipal.getUsername())
                .withArrayClaim(AUTHORITIES, getClaimsFromUser(userPrincipal))
                .withExpiresAt(new Date(currentTimeMillis() + ACCESS_TOKEN_EXPIRE_TIME))
                .sign(HMAC512(secret.getBytes()));
    }

    /**
     * Flattens the principal's authorities into a String array suitable for
     * embedding as the "authorities" JWT claim.
     *
     * @param userPrincipal the authenticated user
     * @return the authority names (e.g. "READ:USER")
     */
    private String[] getClaimsFromUser(UserPrincipal userPrincipal) {
        return userPrincipal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toArray(String[]::new);
    }

    /**
     * Generates a JWT refresh token for the given UserPrincipal.
     * The refresh token includes issuer, audience, issued at, subject (username),
     * and an expiration time (5 days). It does NOT include authorities.
     * The token is signed using HMAC512 with the secret key.
     *
     * @param userPrincipal an authenticated user
     * @return a signed JWT refresh token as a String
     */
    public String createRefreshToken(UserPrincipal userPrincipal) {
        return JWT.create()
                .withIssuer(BOBBYLON_LLC)
                .withAudience(BOBS_MANAGEMENT)
                .withIssuedAt(new Date())
                .withSubject(userPrincipal.getUsername())
                .withExpiresAt(new Date(currentTimeMillis() + REFRESH_TOKEN_EXPIRE_TIME))
                .sign(HMAC512(secret.getBytes()));
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
     *
     * Returns an empty array when the claim is missing or null so refresh
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
     * Builds an authenticated UsernamePasswordAuthenticationToken for the
     * SecurityContext, using the user loaded by email as the principal and
     * stamping web details (IP, session id) onto it from the request.
     *
     * @param email       the user's email (subject extracted from the token)
     * @param authorities authorities pulled from the token
     * @param request     the current HTTP request, used to attach WebAuthenticationDetails
     * @return a fully populated Authentication ready to place in the SecurityContext
     */
    public Authentication getAuthentication(String email, List<GrantedAuthority> authorities, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userService.getUserByEmail(email), null, authorities);
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authToken;
    }

    /**
     * Returns true when the email is non-empty and the token verifies and is
     * not past its expiration. Used by CustomAuthFilter as a gate before
     * extracting authorities and authenticating the request.
     *
     * @param email the email previously extracted via {@link #getSubject(String, HttpServletRequest)}
     * @param token the raw JWT
     * @return true if both checks pass
     * @throws JWTVerificationException if token verification fails
     */
    public boolean isTokenValid(String email, String token) {
        JWTVerifier verifier = getJWTVerifier();
        return StringUtils.isNotEmpty(email) && !isTokenExpired(verifier, token);
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
     * Verifies the token and returns its subject (the user's email).
     *
     * Catches the JWT library's failure modes and remaps them so callers see
     * exceptions with consistent semantics: TokenExpiredException and
     * InvalidClaimException are rethrown as-is (the global handler maps them
     * to 401), JWTDecodeException/IllegalArgumentException become a
     * BadCredentialsException with a client-safe message ("Could not decode
     * the token..."), and any other verification failure becomes an
     * ApiException. The original library message is stashed on the request
     * attributes "expiredMessage", "invalidClaim", or "invalidToken" for
     * server-side logging.
     *
     * @param token   the raw JWT
     * @param request the current request, used to stash error context
     * @return the subject claim (the user's email)
     * @throws TokenExpiredException    if the token is expired
     * @throws InvalidClaimException    if a required claim doesn't match
     * @throws JWTVerificationException for other verification failures
     */
    public String getSubject(String token, HttpServletRequest request) throws JWTVerificationException {
        try {
            return getJWTVerifier().verify(token).getSubject();
        } catch (TokenExpiredException e) {
            request.setAttribute("expiredMessage", e.getMessage());
            throw e;
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