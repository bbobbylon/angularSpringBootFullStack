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
 * In this class we are generating the tokens for the user. The following methods are used to generate the tokens:
 * - createAccessToken: generates an access token for the user
 * - createRefreshToken: generates a refresh token for the user
 * - getClaimsFromUser: gets the claims from the userPrincipal, which is the user that is logged in, then we are mapping the authorities to a string array, and finally we are returning the array
 * we use UserPrincipal because it has the user and the permissions that we need to generate the token. We are using the JWT library to generate the tokens, and we are using the HMAC512 algorithm to sign the tokens with a secret key. The secret key is stored in the application.properties file and is injected into this class using the @Value annotation. The access token expires in 30 minutes, and the refresh token expires in 5 days.
 * <p>
 * This token provider will be able to be injected and used to create the access and refresh tokens for the user.
 **/
@Component
//@RequiredArgsConstructor is for our dependency injection, it will generate a constructor with the required arguments, which in this case is the UserService. This allows us to inject the UserService into this class without having to write a constructor ourselves.
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

    // this method is for getting the AUTHORITIES from the userPrincipal, which is the user that is logged in, then we are mapping the authorities to a string array, and finally we are returning the array. This will allow us to get the permissions of the user.

    /**
     * Extracts the authorities (roles/permissions) from the UserPrincipal
     * and returns them as a String array. This is used to embed the user's
     * permissions into the JWT token as a claim.
     *
     * @param userPrincipal an authenticated user
     * @return an array of authority names (e.g., ROLE_USER, ROLE_ADMIN)
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
     * Retrieves the authorities' claim from the JWT token after verifying its signature.
     * Uses the JWTVerifier to ensure the token is valid and not tampered with.
     *
     * @param token the JWT token
     * @return an array of authority names extracted from the token
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

    // this is for building the filter of our Spring Security request flow

    /**
     * Creates an authentication token for Spring Security based on the provided email, authorities, and HTTP request.
     *
     * @param email       the user's email
     * @param authorities the user's granted authorities
     * @param request     the HTTP request
     * @return an Authentication object for Spring Security
     */
    public Authentication getAuthentication(String email, List<GrantedAuthority> authorities, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userService.getUserByEmail(email), null, authorities);
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authToken;
    }

    /**
     * Validates whether a JWT token is valid for the given email address.
     * <p>
     * A token is considered valid if:
     * 1. The email is not empty (StringUtils.isNotEmpty)
     * 2. The token has not expired (checked via isTokenExpired())
     * <p>
     * This method is typically called before attempting to extract user information from a token.
     * <p>
     * Flow:
     * 1. Check if email is provided (not null, not empty)
     * 2. Create JWTVerifier with secret key
     * 3. Verify token signature using verifier
     * 4. Extract expiration date from verified token
     * 5. Compare expiration date with current time
     * 6. Return true if email valid AND token not expired
     *
     * @param email the user's email to validate against token subject
     * @param token the JWT token string to validate
     * @return true if token is valid and not expired, false otherwise
     * @throws JWTVerificationException if token signature is invalid or secret key is wrong
     */
    public boolean isTokenValid(String email, String token) {
        JWTVerifier verifier = getJWTVerifier();
        return StringUtils.isNotEmpty(email) && !isTokenExpired(verifier, token);
    }

    /**
     * Checks if a JWT token has expired.
     * <p>
     * Extracts the expiration date from the token payload and compares it
     * with the current system time. A token is considered expired if its
     * expiration time is in the past.
     * <p>
     * How JWT expiration works:
     * 1. Token contains "exp" claim: Unix timestamp of expiration
     * 2. Example: exp = 1713247333 (April 16, 2024 at 10:22:13 UTC)
     * 3. Current time: 1713247400 (April 16, 2024 at 10:23:20 UTC)
     * 4. Current time > expiration → Token is expired
     * <p>
     * Internal process:
     * 1. verifier.verify(token) validates signature and returns DecodedJWT
     * 2. getExpiresAt() extracts the "exp" claim as a Date object
     * 3. expiration.before(new Date()) checks if expiration is before now
     * 4. Returns true if token is in the past (expired)
     *
     * @param verifier the JWTVerifier instance with secret key and issuer configured
     * @param token    the JWT token string to check for expiration
     * @return true if token has expired (expiration time is before current time), false if still valid
     * @throws JWTVerificationException if token signature is invalid
     */
    private boolean isTokenExpired(JWTVerifier verifier, String token) {
        Date expiration = verifier.verify(token).getExpiresAt();
        return expiration.before(new Date());
    }

    /**
     * Extracts the subject (username/email) from a JWT token.
     * <p>
     * The subject claim contains the user's email/username and is used to identify
     * which user the token belongs to. This is called after authentication to get
     * the user's identifier for database lookups, logging, etc.
     * <p>
     * Exception handling:
     * This method catches JWT verification exceptions, stores error details in the
     * HttpServletRequest as attributes for downstream handlers, and then re-throws
     * the exception to propagate it up the chain.
     * <p>
     * Exception types handled:
     * <p>
     * 1. TokenExpiredException:
     * - Thrown when token.exp < currentTime
     * - Example: User's token from 2 hours ago is no longer valid
     * - Stored in: request.setAttribute("expiredMessage", message)
     * - Re-thrown: Handler catches and returns 401 with "Token expired" message
     * - Frontend should: Request new token via refresh endpoint
     * <p>
     * 2. InvalidClaimException:
     * - Thrown when a claim doesn't match expected value
     * - Example: Issuer is not "BOBBYLON_LLC"
     * - Example: Audience is not "BOBS_MANAGEMENT"
     * - Stored in: request.setAttribute("invalidClaim", message)
     * - Re-thrown: Handler catches and returns 401 with "Invalid token" message
     * - Frontend should: User likely tampered with token, redirect to login
     * <p>
     * Flow example - Valid token:
     * 1. Client sends: Authorization: Bearer eyJhbGci...
     * 2. Filter extracts token and calls: getSubject(token, request)
     * 3. getJWTVerifier().verify(token) succeeds
     * 4. Returns: "bob@example.com"
     * 5. Filter uses email for database lookup
     * 6. Request proceeds to controller
     * <p>
     * Flow example - Expired token:
     * 1. Client sends: Authorization: Bearer eyJhbGci... (from 2 hours ago)
     * 2. Filter extracts token and calls: getSubject(token, request)
     * 3. getJWTVerifier().verify(token) throws TokenExpiredException
     * 4. Caught in catch block
     * 5. request.setAttribute("expiredMessage", exception.getMessage())
     * 6. Exception is re-thrown
     * 7. Filter catches TokenExpiredException
     * 8. Returns: HTTP 401 with message "Token has expired"
     * 9. Frontend receives 401 and refreshes token or redirects to login
     *
     * @param token   the JWT token string to extract subject from
     * @param request the HTTP servlet request for storing error attributes
     * @return the subject (username/email) from the verified token
     * @throws TokenExpiredException    if token has expired
     * @throws InvalidClaimException    if claims don't match expected values (issuer, audience)
     * @throws JWTVerificationException for any other JWT verification failures
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