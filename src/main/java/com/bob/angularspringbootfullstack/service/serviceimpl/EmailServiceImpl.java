package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.VerificationType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.service.EmailService;
import com.bob.angularspringbootfullstack.utils.EmailTemplate;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;


/**
 * Default {@link EmailService} implementation. Composes a {@code multipart/alternative}
 * {@link MimeMessage} — a plain-text part plus the branded HTML rendered by
 * {@link EmailTemplate} — for account verification, password reset and step-up flows, and
 * dispatches it through Spring's {@link JavaMailSender}, which is auto-wired by
 * {@code MailSenderAutoConfiguration} from the {@code spring.mail.*} properties in
 * {@code application-dev.yml} (Gmail SMTP credentials supplied via the {@code MAIL_USERNAME} and
 * {@code MAIL_PASSWORD} environment variables).
 * <p>
 * <strong>Why multipart rather than HTML alone.</strong> Every message carries both
 * representations and lets the client pick. That keeps the emails readable in text-only clients and
 * in the plain-text preview panes some corporate gateways force, and it materially helps
 * deliverability — an HTML-only body with a link in it is a classic spam signal, and these are
 * exactly the messages that must not land in a junk folder. The plain-text part is written
 * deliberately, not machine-stripped from the markup.
 * <p>
 * Callers are expected to invoke this on a background worker — typically
 * {@link NotificationServiceImpl}, which wraps every send in
 * {@link java.util.concurrent.CompletableFuture#runAsync} so the originating HTTP thread returns to
 * the client without waiting on the SMTP round-trip. This class itself stays synchronous and
 * exception-transparent.
 *
 * @see EmailTemplate
 */
@Slf4j
@Service
@AllArgsConstructor
public class EmailServiceImpl implements EmailService {

    /** Envelope sender. Must match the authenticated Gmail account or the relay rejects the message. */
    private static final String FROM_ADDRESS = "bobsangularemail@gmail.com";

    /** Display name shown in the recipient's inbox instead of the raw address. */
    private static final String FROM_NAME = "TesseraApp";

    private final JavaMailSender mailSender;

    /**
     * Composes and dispatches the verification email carrying an activation or password-reset link.
     * <p>
     * Exceptions are intentionally <em>not</em> caught here so that any
     * {@link org.springframework.mail.MailException} (parse failures, SMTP connection issues, auth
     * failures) propagates to {@link NotificationServiceImpl}, whose {@code .exceptionally(...)} on
     * the {@code CompletableFuture} logs a single, accurate error entry. Catching here would force
     * the caller to log a misleading success.
     *
     * @param firstName        recipient's first name, used in the body greeting
     * @param email            recipient's email address (the {@code To:} header)
     * @param verificationURL  the one-time link embedded in the body — an absolute <em>frontend</em>
     *                         URL built by {@code UserRepoImpl#getVerificationURL}, deliberately
     *                         outside the API's {@code /user/**} namespace so it resolves to the SPA
     *                         even when both are served from one origin
     * @param verificationType {@code ACCOUNT} or {@code PASSWORD} — drives the subject line and the
     *                         copy selected in {@link #copyFor}
     */
    @Override
    public void sendVerificationEmail(String firstName, String email, String verificationURL, VerificationType verificationType) {
        VerificationCopy copy = copyFor(verificationType);
        String subject = String.format("TesseraApp - %s Verification Email",
                StringUtils.capitalize(verificationType.name().toLowerCase()));

        String plain = "Hello " + firstName + "\n\n"
                + copy.intro() + "\n\n"
                + verificationURL + "\n\n"
                + copy.note();

        String html = EmailTemplate.builder()
                .preheader(copy.preheader())
                .eyebrow(copy.eyebrow())
                .heading(copy.heading())
                .paragraph("Hello " + firstName + ",")
                .paragraph(copy.intro())
                .button(copy.ctaLabel(), verificationURL)
                .note(copy.note())
                .build();

        send(email, subject, plain, html);
        log.info("Verification email ({}) dispatched to {}", verificationType.getType(), email);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Like {@link #sendVerificationEmail}, exceptions propagate rather than being swallowed —
     * {@code NotificationServiceImpl} owns the failure logging, and for this flow it additionally
     * writes the code to the server log so a developer running without SMTP configured can still
     * complete a step-up challenge.
     */
    @Override
    public void sendStepUpCodeEmail(String firstName, String email, String code, String reasonSummary) {
        String subject = "TesseraApp - Verify this sign-in";

        String plain = "Hello " + firstName + "\n\n"
                + "We noticed a sign-in to your TesseraApp account from " + reasonSummary + ", "
                + "so we asked for one extra step before letting it through.\n\n"
                + "Your verification code is: " + code + "\n\n"
                + "Enter it on the verification screen to finish signing in. The code expires in 24 hours "
                + "and can be used once.\n\n"
                + "If this wasn't you, do NOT enter the code. Someone else knows your password — "
                + "change it immediately and consider enabling an authenticator app under Security Center.";

        String html = EmailTemplate.builder()
                .preheader("Your one-time verification code for an unusual sign-in.")
                .eyebrow("Sign-in verification")
                .heading("Verify this sign-in")
                .paragraph("Hello " + firstName + ",")
                .paragraph("We noticed a sign-in to your TesseraApp account from " + reasonSummary
                        + ", so we asked for one extra step before letting it through.")
                .code(code)
                .note("Enter this code on the verification screen to finish signing in. "
                        + "It expires in 24 hours and can be used once.")
                .warning("If this wasn't you, do NOT enter the code. Someone else knows your password — "
                        + "change it immediately and consider enabling an authenticator app under "
                        + "Security Center.")
                .build();

        send(email, subject, plain, html);
        log.info("Step-up verification code dispatched to {}", email);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Carries no code: the recipient is already completing a second-factor challenge on a
     * channel they control. Its only job is to explain <em>why</em> that challenge appeared.
     */
    @Override
    public void sendSecurityAlertEmail(String firstName, String email, String reasonSummary) {
        String subject = "TesseraApp - Unusual sign-in attempt";

        String plain = "Hello " + firstName + "\n\n"
                + "Someone signed in to your TesseraApp account from " + reasonSummary + ". "
                + "Because it didn't match your usual sign-in pattern, we required your second factor "
                + "before granting access.\n\n"
                + "If that was you, no action is needed.\n\n"
                + "If it wasn't, your password is compromised even though the sign-in was blocked at the "
                + "second factor. Change it now, and review your active sessions under Security Center.";

        String html = EmailTemplate.builder()
                .preheader("We required your second factor for a sign-in that looked unusual.")
                .eyebrow("Security alert")
                .heading("Unusual sign-in attempt")
                .paragraph("Hello " + firstName + ",")
                .paragraph("Someone signed in to your TesseraApp account from " + reasonSummary
                        + ". Because it didn't match your usual sign-in pattern, we required your "
                        + "second factor before granting access.")
                .paragraph("If that was you, no action is needed.")
                .warning("If it wasn't, your password is compromised even though the sign-in was "
                        + "blocked at the second factor. Change it now, and review your active "
                        + "sessions under Security Center.")
                .build();

        send(email, subject, plain, html);
        log.info("Security alert email dispatched to {}", email);
    }

    /**
     * Builds and sends one {@code multipart/alternative} message.
     * <p>
     * {@link MimeMessageHelper#setText(String, String)} is what creates the two-part body: the
     * first argument becomes {@code text/plain}, the second {@code text/html}, and the client
     * displays whichever it prefers (in practice, HTML where available).
     * <p>
     * The checked {@link MessagingException} and {@link UnsupportedEncodingException} are rewrapped
     * as {@link MailPreparationException} rather than declared, for two reasons. First, the
     * interface contract is exception-transparent but unchecked — {@code NotificationServiceImpl}
     * invokes these methods inside a {@link Runnable}, which cannot propagate a checked exception at
     * all. Second, {@code MailPreparationException} is a {@code MailException}, so a composition
     * failure lands in the exact same {@code .exceptionally(...)} handler as an SMTP failure and the
     * caller's error logging keeps working unchanged.
     *
     * @param to      recipient address
     * @param subject subject line
     * @param plain   the {@code text/plain} alternative
     * @param html    the {@code text/html} alternative, as rendered by {@link EmailTemplate}
     * @throws MailPreparationException if the message cannot be composed
     */
    private void send(String to, String subject, String plain, String html) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            // multipart=true is what allows a text/html part alongside text/plain; without it the
            // helper writes a single-part message and setText(String, String) is rejected.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(FROM_ADDRESS, FROM_NAME);
            helper.setSubject(subject);
            helper.setText(plain, html);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Unable to compose outbound email", e);
        }
        mailSender.send(message);
    }

    /**
     * Selects the copy for the given verification flow.
     * <p>
     * Keeping every string for a flow in one record preserves the property the previous
     * switch-based body builder had — adding a {@link VerificationType} means touching this one
     * method and the enum, nothing else — while now also feeding the plain-text and HTML bodies
     * from a single source, so the two representations cannot drift apart.
     *
     * @param verificationType {@code ACCOUNT} or {@code PASSWORD}
     * @return the copy bundle for that flow
     * @throws ApiException if {@code verificationType} has no branch — a defensive guard against a
     *                      future enum value being added without accompanying copy
     */
    private VerificationCopy copyFor(VerificationType verificationType) {
        return switch (verificationType) {
            case PASSWORD -> new VerificationCopy(
                    "Reset your TesseraApp password.",
                    "Password reset",
                    "Reset your password",
                    "We received a request to reset the password on your TesseraApp account. "
                            + "Use the link below to choose a new one.",
                    "Reset my password",
                    "If you did not request a password reset, you can ignore this email — "
                            + "your password will not change.");
            case ACCOUNT -> new VerificationCopy(
                    "Confirm your email address to activate your account.",
                    "Account activation",
                    "Welcome to TesseraApp",
                    "Your account has been created. Confirm your email address to activate it and "
                            + "sign in for the first time.",
                    "Activate my account",
                    "If you did not create this account, you can ignore this email — "
                            + "it will not be activated.");
            default -> throw new ApiException("Unable to send email. Please try again later.");
        };
    }

    /**
     * The user-facing strings for one verification flow, shared by the plain-text and HTML bodies.
     *
     * @param preheader inbox preview line
     * @param eyebrow   small uppercase category label above the heading
     * @param heading   the email's headline
     * @param intro     the explanatory paragraph preceding the link
     * @param ctaLabel  the button's label
     * @param note      the closing "if this wasn't you" fine print
     */
    private record VerificationCopy(String preheader, String eyebrow, String heading, String intro,
                                    String ctaLabel, String note) {
    }
}
