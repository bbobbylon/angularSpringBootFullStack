package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.constants.CapabilityCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Specs for {@link CustomAccessDeniedHandler}'s backend-driven i18n resolution
 * (FUTURE-ENHANCEMENTS.md §3.3) — the part {@link CapabilityCatalog} deliberately no longer does
 * itself now that it only resolves a request to a message key. See {@code CapabilityCatalogTest}
 * for the request → key mapping; this class covers key → localized text.
 *
 * <p>Uses a real {@link ResourceBundleMessageSource} pointed at the {@code messages*.properties}
 * bundles actually shipped in {@code src/main/resources}, rather than a mock, because the
 * property most worth protecting here is that those files parse and resolve correctly through
 * {@code MessageFormat} — a mocked {@code MessageSource} would prove nothing about the apostrophe
 * escaping in the properties files themselves.
 */
class CustomAccessDeniedHandlerTest {

    private CustomAccessDeniedHandler handler;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        handler = new CustomAccessDeniedHandler(messageSource);
    }

    private static MockHttpServletRequest request(String method, String path, Locale locale) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(path);
        request.addPreferredLocale(locale);
        return request;
    }

    private static String reasonFrom(MockHttpServletResponse response) throws Exception {
        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        return body.get("reason").asText();
    }

    @Test
    @DisplayName("resolves the English message when the client requests English")
    void resolvesEnglishMessage() throws Exception {
        MockHttpServletRequest request = request("PATCH", "/admin/user/12/role", Locale.ENGLISH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertEquals("You don't have permission to assign roles — contact your administrator.",
                reasonFrom(response));
    }

    @Test
    @DisplayName("resolves a translated message when the client requests a supported language")
    void resolvesTranslatedMessage() throws Exception {
        MockHttpServletRequest request = request("PATCH", "/admin/user/12/role", Locale.forLanguageTag("es"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertEquals("No tienes permiso para asignar roles — contacta a tu administrador.",
                reasonFrom(response));
    }

    @Test
    @DisplayName("an unsupported requested language falls back to the English default bundle")
    void unsupportedLanguageFallsBackToEnglish() throws Exception {
        MockHttpServletRequest request = request("PATCH", "/admin/user/12/role", Locale.forLanguageTag("ja"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertEquals("You don't have permission to assign roles — contact your administrator.",
                reasonFrom(response));
    }

    @Test
    @DisplayName("an unmapped path resolves the default action key, translated")
    void unmappedPathResolvesTranslatedDefault() throws Exception {
        MockHttpServletRequest request = request("GET", "/something/unmapped", Locale.GERMAN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertEquals("Fehlende Berechtigung: diese Aktion — wenden Sie sich an Ihren Administrator.",
                reasonFrom(response));
    }

    @Test
    @DisplayName("the message never leaks internal vocabulary or record existence, in every supported language")
    void messagesAreNonEnumerating() throws Exception {
        String[] paths = {
                "/admin/user/12/role", "/admin/security/overview", "/customer/delete/9",
                "/customer/invoice/update/4", "/something/unmapped",
        };
        Locale[] locales = {
                Locale.ENGLISH, Locale.forLanguageTag("es"), Locale.FRENCH, Locale.GERMAN,
                Locale.forLanguageTag("pt"), Locale.forLanguageTag("zh"),
        };

        for (Locale locale : locales) {
            for (String path : paths) {
                MockHttpServletRequest req = request("GET", path, locale);
                MockHttpServletResponse response = new MockHttpServletResponse();
                handler.handle(req, response, new AccessDeniedException("denied"));
                String message = reasonFrom(response);

                assertFalse(message.contains("UPDATE:"), "leaked an authority string: " + message);
                assertFalse(message.contains("READ:"), "leaked an authority string: " + message);
                assertFalse(message.contains("DELETE:"), "leaked an authority string: " + message);
                assertFalse(message.toLowerCase(Locale.ROOT).contains("role_"), "leaked a role name: " + message);
                assertFalse(message.matches(".*\\d+.*"), "leaked a record identifier: " + message);
            }
        }
    }
}
