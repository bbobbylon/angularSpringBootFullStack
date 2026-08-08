package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * Request body for {@code POST /user/webauthn/enroll/complete}: the nickname the user gave this
 * passkey plus the browser's {@code PublicKeyCredential.toJSON()} registration response, forwarded
 * to {@code PasskeyService} as-is. {@code credential} is bound as a raw {@link JsonNode} rather than
 * a typed DTO because its shape is the WebAuthn spec's own JSON serialization contract, not this
 * application's — webauthn4j parses it directly.
 *
 * <p><b>{@code tools.jackson}, not {@code com.fasterxml.jackson}.</b> Spring Framework 7 /
 * Spring Boot 4's {@code @RequestBody} binding runs on Jackson 3, whose databind classes moved to
 * the {@code tools.jackson.databind} package (only {@code jackson-annotations} kept the old
 * {@code com.fasterxml.jackson.annotation} namespace — see {@code model/User.java}'s
 * {@code @JsonInclude} for that half). Binding this field as the classic Jackson 2
 * {@code com.fasterxml.jackson.databind.JsonNode} compiles fine but fails at request time with
 * {@code InvalidDefinitionException: Cannot construct instance of JsonNode (no Creators...)} —
 * Spring's Jackson-3-backed converter has no idea that foreign class is the same concept.
 * {@code ExceptionUtils} already uses the correct {@code tools.jackson.databind.ObjectMapper};
 * this mirrors it. A few other classes ({@code RateLimitFilter}, {@code JacksonConfig}, the
 * 401/403 handlers) still construct their own standalone Jackson 2 {@code ObjectMapper} and work
 * fine doing so — they never go through Spring MVC's message-converter pipeline, so the mismatch
 * never surfaces for them.
 */
@Data
public class PasskeyRegisterVerifyForm {

    /** User-supplied nickname, e.g. "MacBook Touch ID". Blank falls back to a generic label. */
    private String deviceName;

    /** The browser's registration response, verbatim. */
    @NotNull(message = "Passkey credential response is required")
    private JsonNode credential;
}
