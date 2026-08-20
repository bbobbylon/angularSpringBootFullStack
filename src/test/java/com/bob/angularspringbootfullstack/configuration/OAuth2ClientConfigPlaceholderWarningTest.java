package com.bob.angularspringbootfullstack.configuration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the boot-time warning added for the documented-but-previously-unguarded gap: a federated
 * provider whose client id in Secrets Manager is still the literal {@code CHANGE_ME} placeholder
 * (see {@code documentation/PHASE-2-ADDITIONS.md} §8.2/8.3). Deliberately a warning, not a
 * fail-fast guard like {@code JwtSecretGuard} — see {@link OAuth2ClientConfig#warnIfPlaceholder}'s
 * Javadoc for why refusing to boot over an optional feature would be the wrong trade.
 *
 * <p>Same {@link ReflectionTestUtils}-populated-config pattern as
 * {@link OAuth2ClientConfigRedirectUriTest}: the point is the bean-construction logic, not Spring's
 * property binding.
 */
class OAuth2ClientConfigPlaceholderWarningTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(OAuth2ClientConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static OAuth2ClientConfig configWith(String googleClientId, String githubClientId, String microsoftClientId) {
        OAuth2ClientConfig config = new OAuth2ClientConfig();
        ReflectionTestUtils.setField(config, "googleClientId", googleClientId);
        ReflectionTestUtils.setField(config, "googleClientSecret", "secret");
        ReflectionTestUtils.setField(config, "githubClientId", githubClientId);
        ReflectionTestUtils.setField(config, "githubClientSecret", "secret");
        ReflectionTestUtils.setField(config, "microsoftClientId", microsoftClientId);
        ReflectionTestUtils.setField(config, "microsoftClientSecret", "secret");
        ReflectionTestUtils.setField(config, "microsoftTenant", "common");
        ReflectionTestUtils.setField(config, "redirectBaseUrl", "");
        return config;
    }

    private boolean anyWarningContains(String fragment) {
        return appender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains(fragment));
    }

    @ParameterizedTest(name = "''{0}'' is flagged as a placeholder")
    @ValueSource(strings = {"CHANGE_ME", "CHANGE_ME.apps.googleusercontent.com", "change_me_google_secret_id", "Change_Me_123"})
    @DisplayName("a CHANGE_ME-shaped google client id logs a warning naming the provider")
    void placeholderGoogleClientIdWarns(String placeholder) {
        configWith(placeholder, "", "").federatedProviderCatalog();

        assertTrue(anyWarningContains("google"), "expected a warning naming 'google'; got: " + appender.list);
        assertTrue(anyWarningContains("CHANGE_ME"), "warning should explain WHY: " + appender.list);
    }

    @Test
    @DisplayName("a real-looking github client id logs no warning")
    void realGithubClientIdDoesNotWarn() {
        configWith("", "a1b2c3d4e5f6g7h8i9j0", "").federatedProviderCatalog();

        assertFalse(anyWarningContains("github"), "a real-looking credential must not be flagged: " + appender.list);
    }

    @Test
    @DisplayName("an unconfigured provider (blank client id) logs no warning")
    void unconfiguredProviderDoesNotWarn() {
        configWith("", "", "").federatedProviderCatalog();

        assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
                "an intentionally-unconfigured provider is not a placeholder — nothing to warn about: " + appender.list);
    }

    @Test
    @DisplayName("each placeholder provider is named independently — one bad credential doesn't mask another")
    void multiplePlaceholdersEachWarnByName() {
        configWith("CHANGE_ME_google", "CHANGE_ME_github", "real-microsoft-app-id").federatedProviderCatalog();

        assertTrue(anyWarningContains("google"));
        assertTrue(anyWarningContains("github"));
        assertFalse(anyWarningContains("microsoft"), "the real microsoft credential must not be flagged: " + appender.list);
    }
}
