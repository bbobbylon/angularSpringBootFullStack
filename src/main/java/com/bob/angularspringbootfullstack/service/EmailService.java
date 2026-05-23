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
}
