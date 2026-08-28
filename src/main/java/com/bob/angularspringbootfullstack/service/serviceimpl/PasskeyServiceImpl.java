package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.OrgMfaMethod;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.PasskeyService;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bob.angularspringbootfullstack.query.PasskeyQuery.*;

/**
 * JDBC-backed implementation of the passkey (WebAuthn) lifecycle (the "add a passkey option"
 * capability), following the same service-owns-the-logic, direct-{@code NamedParameterJdbcTemplate}
 * convention {@code TotpServiceImpl} uses for the other auth-flow-only tables — no separate
 * Repo/RepoImpl layer, since these queries exist only to serve this one service.
 *
 * <p><b>Why webauthn4j directly, not Spring Security's WebAuthn module.</b> This application is
 * deliberately stateless JWT (see {@code CustomAuthFilter}); Spring Security's own
 * {@code spring-security-webauthn} assumes cookie/session authentication for the ceremony state.
 * Using the verification library directly and wiring the ceremony's transient state through
 * {@link WebAuthnChallengeStore} keeps the same architecture every other auth primitive in this
 * codebase already uses.
 *
 * <p><b>Persistence shape.</b> Rather than hand-decomposing and re-storing a credential's public
 * key, {@link #finishRegistration} re-serializes the already-verified {@code AttestationObject} via
 * webauthn4j's own {@link ObjectConverter} and stores those bytes verbatim; {@link #finishAuthentication}
 * re-parses them with the same converter to rebuild a {@code CredentialRecord} for verification. This
 * round-trips through the library's own most-exercised code path (attestation-object CBOR
 * (de)serialization is literally step one of every registration this library ever parses) instead of
 * depending on serializing the polymorphic {@code COSEKey} type in isolation.
 *
 * <p><b>Anti-enumeration on login (NFR-SEC-7).</b> {@link #finishAuthentication} throws one identical
 * message whether the challenge is unknown/expired, the credential id is unrecognized, or the
 * signature fails to verify — unlike TOTP's challenge (which is already scoped to one known account),
 * a usernameless passkey assertion carries no account context worth protecting differently case by case.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasskeyServiceImpl implements PasskeyService {

    /** Relying party display name shown inside the platform's passkey UI. */
    private static final String RP_NAME = "TesseraApp";
    /** ES256 and RS256 — the two algorithms every major authenticator and browser supports. */
    private static final List<Map<String, Object>> PUB_KEY_CRED_PARAMS = List.of(
            Map.of("type", "public-key", "alg", -7),
            Map.of("type", "public-key", "alg", -257));
    /** How long the browser will wait on the platform passkey prompt before giving up, in ms. */
    private static final long CEREMONY_TIMEOUT_MS = 120_000;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final WebAuthnChallengeStore challengeStore;
    private final OrganizationService organizationService;

    private final WebAuthnManager webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager();
    private final ObjectConverter objectConverter = new ObjectConverter();

    /**
     * SPA origin this app trusts for the relying party (env {@code UI_APP_URL}), the same default
     * every other origin-sensitive property in this codebase reads from.
     */
    @Value("${ui.app.url:http://localhost:4200}")
    private String uiAppUrl;

    /**
     * Relying party id override. Blank (the default) derives the id from {@link #uiAppUrl}'s host —
     * correct for the single-origin deployment this app ships today. WebAuthn requires the rpId be
     * exactly the origin's host or a registrable parent of it, so a future split-origin deployment
     * (API and SPA on different hosts) would need this set explicitly.
     */
    @Value("${webauthn.rp-id:${WEBAUTHN_RP_ID:}}")
    private String rpIdOverride;

    /**
     * Relying party origin override. Blank (the default) uses {@link #uiAppUrl} verbatim. Kept
     * distinct from {@code oauth2.redirect-base-url} in {@code OAuth2ClientConfig} even though they
     * often agree: that property answers "what does the OAuth provider redirect back to", this one
     * answers "what origin issued this WebAuthn ceremony" — the same two questions that property's
     * own Javadoc explains can diverge under a split-origin deployment.
     */
    @Value("${webauthn.origin:${WEBAUTHN_ORIGIN:}}")
    private String originOverride;

    /**
     * {@inheritDoc}
     */
    @Override
    public CeremonyOptions beginRegistration(Long userId, String email) {
        Challenge challenge = challengeStore.mintForRegistration(userId);
        // excludeCredentials needs the WebAuthn credential id (never exposed elsewhere), not the
        // PasskeyCredentialSummary's DB id, so it is built directly from the raw rows rather than
        // going through listCredentials()'s public-facing summary shape.
        List<Map<String, Object>> excludeList = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(SELECT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY, Map.of("userId", userId))) {
            excludeList.add(Map.of(
                    "type", "public-key",
                    "id", row.get("credential_id")));
        }
        Map<String, Object> publicKey = new LinkedHashMap<>();
        publicKey.put("rp", Map.of("id", rpId(), "name", RP_NAME));
        publicKey.put("user", Map.of(
                "id", base64Url(String.valueOf(userId).getBytes(StandardCharsets.UTF_8)),
                "name", email,
                "displayName", email));
        publicKey.put("challenge", WebAuthnChallengeStore.encodeChallenge(challenge));
        publicKey.put("pubKeyCredParams", PUB_KEY_CRED_PARAMS);
        publicKey.put("timeout", CEREMONY_TIMEOUT_MS);
        publicKey.put("excludeCredentials", excludeList);
        publicKey.put("authenticatorSelection", Map.of(
                "residentKey", "required",
                "requireResidentKey", true,
                "userVerification", "required"));
        publicKey.put("attestation", "none");
        return new CeremonyOptions(publicKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public PasskeyCredentialSummary finishRegistration(Long userId, String deviceName, String credentialJson) {
        RegistrationData registrationData = parseRegistration(credentialJson);
        String challengeKey = WebAuthnChallengeStore.encodeChallenge(registrationData.getCollectedClientData().getChallenge());
        WebAuthnChallengeStore.RedeemedChallenge redeemed = challengeStore
                .redeem(challengeKey, WebAuthnChallengeStore.Purpose.REGISTER)
                .orElseThrow(() -> new ApiException("This registration attempt has expired. Please try again."));
        if (!userId.equals(redeemed.userId())) {
            // Defense in depth: the endpoint is already authenticated, so this should be
            // unreachable, but a challenge minted for one user must never register a credential
            // against a different one.
            log.warn("[WEBAUTHN] Registration challenge userId mismatch: minted for {}, completed as {}", redeemed.userId(), userId);
            throw new ApiException("This registration attempt is no longer valid. Please try again.");
        }

        ServerProperty serverProperty = new ServerProperty(origin(), rpId(), redeemed.challenge(), null);
        RegistrationParameters registrationParameters = new RegistrationParameters(serverProperty, null, true, true);
        try {
            webAuthnManager.verify(registrationData, registrationParameters);
        } catch (VerificationException e) {
            log.warn("[WEBAUTHN] Registration verification failed for userId={}: {}", userId, e.getMessage());
            throw new ApiException("We couldn't verify that passkey. Please try again.");
        }

        AttestationObject attestationObject = registrationData.getAttestationObject();
        AttestedCredentialData attestedCredentialData = attestationObject.getAuthenticatorData().getAttestedCredentialData();
        String credentialId = base64Url(attestedCredentialData.getCredentialId());
        String attestationObjectBase64 = Base64.getEncoder()
                .encodeToString(objectConverter.getCborConverter().writeValueAsBytes(attestationObject));
        String aaguid = attestedCredentialData.getAaguid().toString();
        String transports = registrationData.getTransports() == null ? null :
                registrationData.getTransports().stream().map(Object::toString)
                        .reduce((a, b) -> a + "," + b).orElse(null);

        if (!jdbcTemplate.queryForList(SELECT_PASSKEY_CREDENTIAL_BY_CREDENTIAL_ID_QUERY, Map.of("credentialId", credentialId)).isEmpty()) {
            throw new ApiException("This passkey is already registered on an account.");
        }
        if (!organizationService.isMfaMethodAllowed(userId, OrgMfaMethod.PASSKEY)) {
            throw new ApiException("Your organization does not allow passkey MFA. Contact your admin for allowed options.");
        }

        jdbcTemplate.update(INSERT_PASSKEY_CREDENTIAL_QUERY, Map.of(
                "userId", userId,
                "credentialId", credentialId,
                "attestationObject", attestationObjectBase64,
                "aaguid", aaguid,
                "transports", transports == null ? "" : transports,
                "deviceName", deviceName == null || deviceName.isBlank() ? "Passkey" : deviceName));
        jdbcTemplate.update(UPDATE_USER_USING_PASSKEY_QUERY, Map.of("usingPasskey", true, "userId", userId));
        log.info("[WEBAUTHN] Passkey registered for userId={}", userId);

        // SELECT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY orders newest-first, so the row just inserted
        // is always first — simpler than threading the new row's generated id back out by hand.
        return listCredentials(userId).getFirst();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PasskeyCredentialSummary> listCredentials(Long userId) {
        return jdbcTemplate.query(SELECT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY, Map.of("userId", userId),
                (rs, rowNum) -> new PasskeyCredentialSummary(
                        rs.getLong("id"),
                        rs.getString("device_name"),
                        rs.getString("transports"),
                        rs.getTimestamp("created_at"),
                        rs.getTimestamp("last_used_at")));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteCredential(Long userId, Long credentialId) {
        jdbcTemplate.update(DELETE_PASSKEY_CREDENTIAL_BY_ID_AND_USER_ID_QUERY, Map.of("id", credentialId, "userId", userId));
        syncUsingPasskeyFlag(userId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteAllCredentials(Long userId) {
        jdbcTemplate.update(DELETE_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY, Map.of("userId", userId));
        jdbcTemplate.update(UPDATE_USER_USING_PASSKEY_QUERY, Map.of("usingPasskey", false, "userId", userId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CeremonyOptions beginAuthentication() {
        Challenge challenge = challengeStore.mintForAuthentication();
        Map<String, Object> publicKey = new LinkedHashMap<>();
        publicKey.put("challenge", WebAuthnChallengeStore.encodeChallenge(challenge));
        publicKey.put("timeout", CEREMONY_TIMEOUT_MS);
        publicKey.put("rpId", rpId());
        // allowCredentials is deliberately omitted: a usernameless/discoverable login lets the
        // browser offer every passkey it holds for this relying party, not a server-supplied list.
        publicKey.put("userVerification", "required");
        return new CeremonyOptions(publicKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public AuthenticationResult finishAuthentication(String credentialJson) {
        AuthenticationData authenticationData = parseAuthentication(credentialJson);
        String challengeKey = WebAuthnChallengeStore.encodeChallenge(authenticationData.getCollectedClientData().getChallenge());
        WebAuthnChallengeStore.RedeemedChallenge redeemed = challengeStore
                .redeem(challengeKey, WebAuthnChallengeStore.Purpose.AUTHENTICATE)
                .orElseThrow(() -> new ApiException("This sign-in attempt has expired. Please try again."));

        String credentialId = base64Url(authenticationData.getCredentialId());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_PASSKEY_CREDENTIAL_BY_CREDENTIAL_ID_QUERY, Map.of("credentialId", credentialId));
        if (rows.isEmpty()) {
            // Same message as every other failure branch here — an unrecognized credential id must
            // read identically to an expired challenge or a bad signature (NFR-SEC-7).
            log.debug("[WEBAUTHN] Authentication attempted with an unknown credential id.");
            throw new ApiException("This sign-in attempt has expired. Please log in again.");
        }
        Map<String, Object> row = rows.getFirst();
        Long userId = ((Number) row.get("user_id")).longValue();
        Long dbId = ((Number) row.get("id")).longValue();
        long storedSignCount = ((Number) row.get("sign_count")).longValue();
        byte[] attestationObjectBytes = Base64.getDecoder().decode((String) row.get("attestation_object"));
        AttestationObject storedAttestationObject = objectConverter.getCborConverter()
                .readValue(attestationObjectBytes, AttestationObject.class);
        AttestedCredentialData attestedCredentialData = storedAttestationObject.getAuthenticatorData().getAttestedCredentialData();

        CredentialRecordImpl credentialRecord = new CredentialRecordImpl(
                null, null, null, null,
                storedSignCount, attestedCredentialData, null, null, null, null);
        ServerProperty serverProperty = new ServerProperty(origin(), rpId(), redeemed.challenge(), null);
        AuthenticationParameters authenticationParameters = new AuthenticationParameters(
                serverProperty, credentialRecord, null, true, true);

        try {
            webAuthnManager.verify(authenticationData, authenticationParameters);
        } catch (VerificationException e) {
            log.warn("[WEBAUTHN] Authentication verification failed for credentialId hash={}: {}", credentialId.hashCode(), e.getMessage());
            throw new ApiException("This sign-in attempt has expired. Please log in again.");
        }

        long newSignCount = authenticationData.getAuthenticatorData().getSignCount();
        jdbcTemplate.update(UPDATE_PASSKEY_SIGN_COUNT_QUERY, Map.of("id", dbId, "signCount", newSignCount));
        log.info("[WEBAUTHN] Passkey sign-in for userId={}", userId);
        return new AuthenticationResult(userId);
    }

    /**
     * Parses the browser's registration response JSON, translating webauthn4j's own parse failure
     * into this application's generic exception type.
     *
     * <p>Catches {@link RuntimeException} broadly, not just {@link DataConversionException}: JSON
     * that is syntactically valid but missing an expected field (e.g. no {@code response} object)
     * makes webauthn4j throw a raw {@code NullPointerException} from inside its own parser rather
     * than its documented conversion exception — confirmed by this class's own test suite. Either
     * way the input is garbage from an unauthenticated-adjacent endpoint, and it must produce this
     * app's normal clean error, never a bare 500 with a library stack trace attached.
     */
    private RegistrationData parseRegistration(String credentialJson) {
        try {
            return webAuthnManager.parseRegistrationResponseJSON(credentialJson);
        } catch (RuntimeException e) {
            throw new ApiException("That passkey response could not be understood. Please try again.");
        }
    }

    /**
     * Parses the browser's authentication response JSON, translating webauthn4j's own parse failure
     * into the same generic message every other failure branch of {@link #finishAuthentication} uses.
     * See {@link #parseRegistration} for why {@link RuntimeException} is caught broadly rather than
     * only {@link DataConversionException}.
     */
    private AuthenticationData parseAuthentication(String credentialJson) {
        try {
            return webAuthnManager.parseAuthenticationResponseJSON(credentialJson);
        } catch (RuntimeException e) {
            throw new ApiException("This sign-in attempt has expired. Please log in again.");
        }
    }

    /**
     * Clears the denormalized {@code users.using_passkey} flag once a user's last credential is
     * removed, mirroring how TOTP's flag tracks {@code totpcredentials}.
     */
    private void syncUsingPasskeyFlag(Long userId) {
        Long remaining = jdbcTemplate.queryForObject(COUNT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY, Map.of("userId", userId), Long.class);
        jdbcTemplate.update(UPDATE_USER_USING_PASSKEY_QUERY, Map.of("usingPasskey", remaining != null && remaining > 0, "userId", userId));
    }

    /** Base64url-encodes without padding — the encoding every WebAuthn binary field uses on the wire. */
    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Resolves the relying party id: the explicit override if set, else {@link #uiAppUrl}'s host.
     */
    private String rpId() {
        if (rpIdOverride != null && !rpIdOverride.isBlank()) return rpIdOverride;
        return URI.create(uiAppUrl).getHost();
    }

    /**
     * Resolves the trusted origin for {@code ServerProperty}: the explicit override if set, else
     * {@link #uiAppUrl} verbatim.
     */
    private Origin origin() {
        String value = (originOverride != null && !originOverride.isBlank()) ? originOverride : uiAppUrl;
        return new Origin(value);
    }
}
