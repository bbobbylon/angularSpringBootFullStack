package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.VerificationType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.service.EmailService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;


/**
 * Default {@link EmailService} implementation. Composes a plain-text
 * {@link SimpleMailMessage} for account and password verification flows and
 * dispatches it through Spring's {@link JavaMailSender}, which is auto-wired by
 * {@code MailSenderAutoConfiguration} from the {@code spring.mail.*} properties
 * in {@code application-dev.yml} (Gmail SMTP credentials supplied via the
 * {@code MAIL_USERNAME} and {@code MAIL_PASSWORD} environment variables).
 * <p>
 * Callers are expected to invoke this on a background worker — typically
 * {@link com.bob.angularspringbootfullstack.service.serviceimpl.NotificationServiceImpl},
 * which wraps every send in {@link java.util.concurrent.CompletableFuture#runAsync}
 * so the originating HTTP thread returns to the client without waiting on the
 * SMTP round-trip. This class itself stays synchronous and exception-transparent.
 */
@Slf4j
@Service
@AllArgsConstructor
public class EmailServiceImpl implements EmailService {
    private final JavaMailSender mailSender;

    /**
     * Composes a {@link SimpleMailMessage} carrying the verification link and
     * dispatches it via the configured {@link JavaMailSender}.
     * <p>
     * Exceptions are intentionally <em>not</em> caught here so that any
     * {@link org.springframework.mail.MailException} (parse failures, SMTP
     * connection issues, auth failures) propagates to
     * {@link com.bob.angularspringbootfullstack.service.serviceimpl.NotificationServiceImpl},
     * whose {@code .exceptionally(...)} on the {@code CompletableFuture} logs a
     * single, accurate error entry. Catching here would force the caller to
     * log a misleading success.
     *
     * @param firstName        recipient's first name, used in the body greeting
     * @param email            recipient's email address (the {@code To:} header)
     * @param verificationURL  the one-time link embedded in the body
     * @param verificationType {@code ACCOUNT} or {@code PASSWORD} — drives the
     *                         subject line and body template selected in
     *                         {@link #getEmailMessage}
     */
    @Override
    public void sendVerificationEmail(String firstName, String email, String verificationURL, VerificationType verificationType) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(email);
        msg.setFrom("bobsangularemail@gmail.com");
        msg.setText(getEmailMessage(firstName, verificationURL, verificationType));
        msg.setSubject(String.format("TesseraApp - %s Verification Email", StringUtils.capitalize(verificationType.name().toLowerCase())));
        mailSender.send(msg);
        log.info("Verification email ({}) dispatched to {}", verificationType.getType(), email);
    }

    /**
     * Selects the plain-text body template for the given verification flow and
     * substitutes the recipient's first name and verification URL into it.
     * <p>
     * Keeping the body construction private and switch-based localises template
     * changes to a single method: when a new {@link VerificationType} is added
     * (e.g. an email-change confirmation), the new {@code case} branch is the
     * only place to touch besides the enum itself.
     *
     * @param firstName        recipient's first name, used in the greeting
     * @param verificationURL  the link to embed in the body
     * @param verificationType {@code ACCOUNT} or {@code PASSWORD}
     * @return the rendered email body as a plain {@link String}
     * @throws ApiException if {@code verificationType} has no template branch —
     *                      a defensive guard against future enum values being
     *                      added without an accompanying template
     */
    private String getEmailMessage(String firstName, String verificationURL, VerificationType verificationType) {
        switch (verificationType) {
            case PASSWORD -> {
                return "Hello " + firstName + "\n\n Reset Password Request. Please click the link to begin the password reset flow.\n\n" + verificationURL + "\n\n If you did not request a password reset, please ignore this email.";
            }
            case ACCOUNT -> {
                return "Hello " + firstName + "\n\n Welcome to TesseraApp! Please click the link to activate your account.\n\n" + verificationURL + "\n\n If you did not create an account, please ignore this email.";
            }
            default -> throw new ApiException("Unable to send email. Please try again later.");

        }
    }
}
