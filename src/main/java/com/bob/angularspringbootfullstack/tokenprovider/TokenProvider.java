package com.bob.angularspringbootfullstack.tokenprovider;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
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

/*
In this class we are generating the tokens for the user. The following methods are used to generate the tokens:
- createAccessToken: generates an access token for the user
- createRefreshToken: generates a refresh token for the user
- getClaimsFromUser: gets the claims from the userPrincipal, which is the user that is logged in, then we are mapping the authorities to a string array, and finally we are returning the array
we use UserPrincipal because it has the user and the permissions that we need to generate the token. We are using the JWT library to generate the tokens, and we are using the HMAC512 algorithm to sign the tokens with a secret key. The secret key is stored in the application.properties file and is injected into this class using the @Value annotation. The access token expires in 30 minutes, and the refresh token expires in 5 days.
 */
@Component
public class TokenProvider {
    private static final String BOBBYLON_LLC = "BOBBYLON_LLC";
    private static final String BOBS_MANAGEMENT = "BOBS_MANAGEMENT";
    private static final String AUTHORITIES = "authorities";
    private static final long ACCESS_TOKEN_EXPIRE_TIME = 1_800_000;
    private static final long REFRESH_TOKEN_EXPIRE_TIME = 432_000_000;
    private static final String TOKEN_UNVERIFIABLE = "Invalid JWT secret key";
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
        String[] claims = getClaimsFromUser(userPrincipal);
        return JWT.create()
                .withIssuer(BOBBYLON_LLC)
                .withAudience(BOBS_MANAGEMENT)
                .withIssuedAt(new Date())
                .withSubject(userPrincipal.getUsername())
                .withArrayClaim(AUTHORITIES, claims)
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
        String[] claims = getClaimsFromUser(userPrincipal);
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
        // "::new" is a reference to the constructor of SimpleGrantedAuthority
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
        return verifier.verify(token).getClaim(AUTHORITIES).asArray(String.class);
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
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(email, null, authorities);
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authToken;
    }
}