package com.bob.angularspringbootfullstack.dto;

import lombok.Builder;
import lombok.Value;

/**
 * The raw {@code client_id}/{@code client_secret} pair returned exactly once, at registration
 * time ({@code OAuthClientServiceImpl#register}). Unlike an API key — where the single raw value
 * doubles as its own displayable prefix — a client-credentials pair has two independently
 * generated halves, one public ({@code clientId}, shown forever in the admin UI) and one secret
 * ({@code clientSecret}, never retrievable again after this response), so a single {@code String}
 * return value cannot carry both.
 */
@Value
@Builder
public class OAuthClientCredentials {
    String clientId;
    String clientSecret;
}
