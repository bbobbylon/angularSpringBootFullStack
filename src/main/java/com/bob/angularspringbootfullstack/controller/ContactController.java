package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.form.ContactForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.time.LocalTime.now;
import static org.springframework.http.HttpStatus.OK;

/**
 * Unauthenticated public Contact Us submission — {@code POST /contact}.
 *
 * <h3>Why this is its own controller</h3>
 * The same reasoning {@link PublicServicesController} documents for {@code GET /services/public}
 * applies here: this is the one route in the app a visitor with no account and no intent to ever
 * see authenticated data needs to reach, so it gets its own narrow {@code permitAll} seam rather
 * than an exception carved into a controller whose every other method is gated.
 *
 * <p>{@code /contact} must stay in lockstep between {@code Constants.PUBLIC_URLS} (the
 * {@code SecurityFilterChain} matcher) and {@code Constants.PUBLIC_ROUTES} ({@code CustomAuthFilter}'s
 * skip list) — a route public in one but not the other breaks the moment a visitor's browser still
 * carries a stale {@code Authorization} header from an earlier session on the same machine.
 *
 * <p>Rate-limited like every other endpoint by {@code RateLimitFilter}'s global tier (not the
 * tighter auth tier — this is not a credential-guessing target) — 200 requests/minute per IP, which
 * is ample for a legitimate visitor and a meaningful brake on scripted abuse.
 */
@RestController
@RequestMapping(path = "/contact")
@RequiredArgsConstructor
public class ContactController {

    private final NotificationService notificationService;

    /**
     * Accepts a Contact Us submission and forwards it to the app's mailbox.
     *
     * @param form the validated name/email/subject/message
     * @return 200 OK — dispatch is async and always reports success once accepted, matching every
     *         other {@link NotificationService} call site; a delivery failure is logged server-side
     *         only, since there is no session or account state for the visitor to check
     */
    @PostMapping
    public ResponseEntity<HttpResponse> submit(@RequestBody @Valid ContactForm form) {
        notificationService.sendContactMessage(form.getName(), form.getEmail(), form.getSubject(), form.getMessage());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("Thanks for reaching out — we'll get back to you soon.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }
}
