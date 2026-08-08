package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.VerificationType;
import com.bob.angularspringbootfullstack.service.EmailService;
import com.bob.angularspringbootfullstack.service.NotificationService;
import com.bob.angularspringbootfullstack.utils.SMSUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

import static com.bob.angularspringbootfullstack.enumeration.VerificationType.ACCOUNT;
import static com.bob.angularspringbootfullstack.enumeration.VerificationType.PASSWORD;

/**
 * Default {@link NotificationService} that fans dispatch out to channel-specific
 * collaborators. Email goes through {@link EmailService} (Spring's
 * {@code JavaMailSender}); SMS goes through {@link SMSUtils}, which sends for
 * real once Twilio credentials are configured and otherwise degrades to a log
 * line so the flow stays completable in dev/CI without a Twilio account.
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
        CompletableFuture.runAsync(() -> SMSUtils.sendSMS(phoneNumber,
                        "TesseraApp: Hi " + firstName + ", your 2FA code is " + code
                                + ". It expires in 24 hours. Reply STOP to opt out."))
                .exceptionally(throwable -> {
                    log.error("Failed to dispatch 2FA code to phone {}: {}",
                            phoneNumber, throwable.getMessage(), throwable);
                    return null;
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Delivery-failure fallback.</b> If the send fails — most commonly because no SMTP
     * credentials are configured on a developer machine — the code is written to the server log at
     * WARN so the challenge can still be completed locally. That is a deliberate dev affordance
     * mirroring the existing SMS path (which logs its code unconditionally), and it is why the log
     * line spells out the exposure: anyone who can read the application log can complete this one
     * challenge. In any deployed environment SMTP is configured, the send succeeds, and the code
     * never reaches the log. The alternative — failing closed — would lock a legitimate user out of
     * their own account because of an infrastructure fault, on a path they cannot retry past.
     */
    @Override
    public void sendStepUpCode(String firstName, String email, String code, String reasonSummary) {
        CompletableFuture
                .runAsync(() -> emailService.sendStepUpCodeEmail(firstName, email, code, reasonSummary))
                .exceptionally(throwable -> {
                    log.warn("Failed to email the step-up code to {} ({}). Falling back to the server log so the " +
                                    "challenge remains completable — NOTE: this exposes the code to anyone who can " +
                                    "read these logs, and only happens when mail delivery is unavailable. Code: {}",
                            email, throwable.getMessage(), code);
                    return null;
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Purely informational, so a delivery failure is logged and dropped — there is no fallback
     * channel and nothing the user must act on to complete their sign-in.
     */
    @Override
    public void sendSecurityAlert(String firstName, String email, String reasonSummary) {
        CompletableFuture
                .runAsync(() -> emailService.sendSecurityAlertEmail(firstName, email, reasonSummary))
                .exceptionally(throwable -> {
                    log.error("Failed to send the security alert email to {}: {}",
                            email, throwable.getMessage(), throwable);
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
