package com.bob.angularspringbootfullstack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

/**
 * The RFC 6749 §5.1 access token response body for {@code POST /oauth/token}. Field names are
 * spec-mandated snake_case, not this codebase's usual camelCase — {@code @JsonProperty} pins them
 * explicitly since no global Jackson naming strategy is configured (see
 * {@code documentation/GUIDE.md} §7.13). Deliberately not an {@code HttpResponse} envelope: an
 * OAuth2 client library expects exactly this shape and nothing else.
 */
@Value
@Builder
public class OAuthTokenResponse {
    @JsonProperty("access_token")
    String accessToken;

    @JsonProperty("token_type")
    String tokenType;

    @JsonProperty("expires_in")
    long expiresIn;
}
