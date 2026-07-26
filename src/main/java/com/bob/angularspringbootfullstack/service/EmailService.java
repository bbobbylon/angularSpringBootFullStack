package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.enumeration.VerificationType;

/**
 * Contract for sending verification emails (account activation, password reset).
 * <p>
 * Implementations wrap Spring's {@link org.springframework.mail.javamail.JavaMailSender}
 * and are responsible for composing and dispatching the message. Called by the
 * password reset and account creation flows in {@code UserServiceImpl} once a
 * verification URL has been generated and persisted.
 */
public interface EmailService {
    /**
     * Sends a verification email containing the given {@code verificationURL} to
     * the supplied recipient.
     *
     * @param firstName        recipient's first name, used in the email greeting
     * @param email            recipient's email address (the {@code To:} header)
     * @param verificationURL  the one-time link the recipient clicks to verify;
     *                         embedded in the email body
     * @param verificationType {@code ACCOUNT} or {@code PASSWORD} — controls the
     *                         subject line and template the implementation uses
     */
    void sendVerificationEmail(String firstName, String email, String verificationURL, VerificationType verificationType);

    /**
     * Sends a one-time step-up code to an account whose sign-in was flagged as anomalous
     * (SRS FR-TPF-1).
     *
     * <p>Used only for accounts with <em>no</em> enrolled second factor: a user with an
     * authenticator or SMS 2FA is already being challenged, so they receive
     * {@link #sendSecurityAlertEmail} instead. The body states why the extra step was required, so
     * a user who did not attempt this sign-in learns their password is compromised.
     *
     * @param firstName     recipient's first name, used in the email greeting
     * @param email         recipient's email address (the {@code To:} header)
     * @param code          the one-time verification code to submit on the verify screen
     * @param reasonSummary human-readable description of what looked unusual
     *                      (e.g. {@code "a new device and a new network location"})
     */
    void sendStepUpCodeEmail(String firstName, String email, String code, String reasonSummary);

    /**
     * Notifies an account owner that a sign-in from an unfamiliar device or location was flagged
     * and challenged (SRS FR-TPF-1).
     *
     * <p>Sent when the account already has a second factor, so the challenge itself was going to
     * happen anyway and carries no explanation. This is the out-of-band signal that turns a routine
     * "enter your code" prompt into actionable information.
     *
     * @param firstName     recipient's first name, used in the email greeting
     * @param email         recipient's email address (the {@code To:} header)
     * @param reasonSummary human-readable description of what looked unusual
     */
    void sendSecurityAlertEmail(String firstName, String email, String reasonSummary);
}
