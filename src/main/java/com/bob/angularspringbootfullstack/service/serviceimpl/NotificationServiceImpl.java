package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.VerificationType;
import com.bob.angularspringbootfullstack.service.EmailService;
import com.bob.angularspringbootfullstack.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

import static com.bob.angularspringbootfullstack.enumeration.VerificationType.ACCOUNT;
import static com.bob.angularspringbootfullstack.enumeration.VerificationType.PASSWORD;

/**
 * Default {@link NotificationService} that fans dispatch out to channel-specific
 * collaborators. Email goes through {@link EmailService} (Spring's
 * {@code JavaMailSender}); SMS would go through
 * {@link com.bob.angularspringbootfullstack.utils.SMSUtils} but is currently
 * stubbed with a log line to avoid Twilio charges during development.
 * <p>
 * All sends run on {@link CompletableFuture#runAsync(Runnable)}'s common
 * {@code ForkJoinPool} so the HTTP thread that triggered the operation
 * (registration, password reset, 2FA login step) returns to the client without
 * waiting on the SMTP/SMS round-trip. Failures are funneled through
 * {@link CompletableFuture#exceptionally(java.util.function.Function)} into
 * SLF4J so they show up alongside the rest of the app's structured logs rather
 * than being lost to {@code stderr} via the {@code ForkJoinPool}'s default
 * uncaught-exception handler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final EmailService emailService;

    /** {@inheritDoc} */
    @Override
    public void sendAccountVerification(String firstName, String email, String verificationURL) {
        dispatchVerificationEmail(firstName, email, verificationURL, ACCOUNT);
    }

    /** {@inheritDoc} */
    @Override
    public void sendPasswordResetVerification(String firstName, String email, String verificationURL) {
        dispatchVerificationEmail(firstName, email, verificationURL, PASSWORD);
    }

    /** {@inheritDoc} */
    @Override
    public void sendTwoFactorCode(String firstName, String phoneNumber, String code) {
        CompletableFuture.runAsync(() -> {
            // TODO: enable SMS sending when ready (Twilio messages incur cost).
            // SMSUtils.sendSMS(phoneNumber,
            //     "Hi " + firstName + ", your 2FA code is: " + code + ". It expires in 24 hours.");
            log.info("2FA code dispatch requested for phone {} (SMS send disabled to avoid Twilio charges). Code: {}",
                    phoneNumber, code);
        }).exceptionally(throwable -> {
            log.error("Failed to dispatch 2FA code to phone {}: {}",
                    phoneNumber, throwable.getMessage(), throwable);
            return null;
        });
    }

    /**
     * Shared async wrapper for both email-based verification flows. Hands the
     * composition off to {@link EmailService#sendVerificationEmail} on a
     * worker thread and routes any failure into a single SLF4J error log
     * carrying the verification type and recipient for support debugging.
     *
     * @param firstName       recipient's first name (used in greeting)
     * @param email           recipient's address (the {@code To:} header)
     * @param verificationURL the one-time link embedded in the body
     * @param type            {@code ACCOUNT} or {@code PASSWORD}; drives the
     *                        subject line and body template
     */
    private void dispatchVerificationEmail(String firstName, String email, String verificationURL, VerificationType type) {
        CompletableFuture
                .runAsync(() -> emailService.sendVerificationEmail(firstName, email, verificationURL, type))
                .exceptionally(throwable -> {
                    log.error("Failed to send {} verification email to {}: {}",
                            type.getType(), email, throwable.getMessage(), throwable);
                    return null;
                });
    }
}
