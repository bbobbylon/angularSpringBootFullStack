package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.constants.CapabilityCatalog;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.utils.AuthDiagnosticsLogger;
import com.bob.angularspringbootfullstack.utils.BrowserErrorPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

import static java.time.LocalTime.now;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * CustomAccessDeniedHandler handles authorization failures for authenticated users.
 * This component implements Spring Security's AccessDeniedHandler interface,
 * which is invoked when an authenticated user lacks the required permissions/roles
 * to access a protected resource.
 * Unlike authentication failures (401 Unauthorized), access denied is a 403 Forbidden
 * status that means the user is authenticated but not authorized.
 * This custom implementation returns a JSON response instead of the default error page,
 * providing consistency with our API's response format.
 * <p>
 * {@code messageSource} backs backend-driven i18n (FUTURE-ENHANCEMENTS.md §3.3): the 403 body's
 * {@code reason} is resolved from {@code messages*.properties} via {@link CapabilityCatalog}'s
 * key rather than a hardcoded English sentence, so the SPA's language switcher is honored on the
 * server as well as the client for this one shared surface.
 */
@Component
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final MessageSource messageSource;
    /**
     * Handles authorization failures by returning a custom 403 response.
     * <p>
     * This method is called when Spring Security's authorization checks fail
     * (e.g., when a user lacks the required role for an endpoint).
     * It returns a JSON response with our custom HttpResponse format.
     * <p>
     * Steps:
     * 1. Builds an HttpResponse with 403 FORBIDDEN status and permission-denied message
     * 2. Sets a response content type to application/json
     * 3. Sets HTTP status code to 403
     * 4. Serializes the HttpResponse to JSON using ObjectMapper
     * 5. Writes JSON to a response output stream
     * 6. Flushes the output to send a response to a client
     *
     * @param request               the HTTP request that triggered access denied
     * @param response              the HTTP response to write error details to
     * @param accessDeniedException the access denied exception that was thrown
     * @throws IOException if writing to response fails
     *                     //@throws ServletException if the servlet operation fails
     */
    @Override
    public void handle(@NonNull HttpServletRequest request, HttpServletResponse response, @NonNull AccessDeniedException accessDeniedException) throws IOException {
        // Console-only RBAC diagnostics: record WHO was forbidden from WHAT, with the authorities
        // they actually held, so an operator can tell a missing role grant from a genuine over-reach.
        // The SecurityContext is still populated on this thread (CustomAuthFilter set it earlier in
        // the chain); the client-visible 403 body below is unchanged.
        AuthDiagnosticsLogger.logForbidden(SecurityContextHolder.getContext().getAuthentication(), request);

        // Name the capability the caller was missing (ROADMAP §2 — permission-denied UX at the API
        // level). The previous message — "You don't have enough permission to access this
        // resource!" — was identical for every endpoint, so a user who could not save a customer
        // and a user who could not reassign a role were told the same thing and neither learned
        // what to ask for. CapabilityCatalog derives the message key from the request's method and
        // path, because this handler runs inside the filter chain, before any controller method
        // has been selected, and so has nothing else to go on.
        //
        // Still non-enumerating: the phrase names a capability only, never whether a particular
        // record or account exists — which matters because a 403 also covers out-of-scope
        // resources, and "this exists but is not yours" must stay indistinguishable from "you may
        // not do this".
        //
        // Locale comes from HttpServletRequest#getLocale() — the Servlet API's own Accept-Language
        // parser — rather than LocaleContextHolder, because this handler runs inside the Spring
        // Security filter chain, before DispatcherServlet's LocaleResolver would ever populate
        // that holder for this request.
        //
        // Note this body is written straight to the output stream rather than returned from a
        // controller, so ErrorDetailScrubber's ResponseBodyAdvice does not see it. The message
        // therefore survives in production — correctly, since it is deliberate user-facing text
        // rather than the incidental exception detail that advice exists to strip.
        Locale locale = request.getLocale();
        String actionText = messageSource.getMessage(CapabilityCatalog.actionKeyFor(request), null, locale);
        String reason = messageSource.getMessage(CapabilityCatalog.MESSAGE_TEMPLATE_KEY, new Object[]{actionText}, locale);

        // Same 403, different presentation, when a human navigated here instead of the SPA calling
        // us — see CustomAuthenticationEntryPoint for the full rationale. The capability phrase is
        // reused verbatim, so both representations say exactly the same thing and neither reveals
        // whether the underlying record exists.
        if (BrowserErrorPage.isBrowserNavigation(request)) {
            BrowserErrorPage.write(response, FORBIDDEN.value(),
                    "403 · Forbidden",
                    "You don't have access to this",
                    reason,
                    "/", "Back to dashboard");
            return;
        }

        HttpResponse httpResponse = HttpResponse.builder()
                .timeStamp(now().toString())
                .reason(reason)
                .status(FORBIDDEN)
                .statusCode(FORBIDDEN.value())
                .build();
        response.setContentType("application/json");
        response.setStatus(FORBIDDEN.value());

        OutputStream out = response.getOutputStream();
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(out, httpResponse);
        out.flush();
    }
}
