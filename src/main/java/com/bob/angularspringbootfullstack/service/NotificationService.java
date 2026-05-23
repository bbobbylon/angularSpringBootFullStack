package com.bob.angularspringbootfullstack.service;

/**
 * Contract for dispatching outbound user notifications (email, SMS) across the
 * verification flows. Acts as the single collaborator the data layer talks to
 * whenever it needs to "tell the user something," so callers stay agnostic of
 * the underlying channel (SMTP via {@link EmailService}, Twilio via
 * {@code SMSUtils}, etc.) and of the async dispatch policy.
 * <p>
 * Each method represents one concrete use case rather than a generic
 * {@code send(type, payload)} so the call sites read clearly and so each
 * payload can carry only the fields it actually needs.
 */
public interface NotificationService {

    /**
     * Sends the account-activation email containing the verification URL the
     * recipient must click to flip their {@code enabled} flag to {@code true}.
     * Invoked from the registration flow once the user row and verification
     * URL have been persisted.
     *
     * @param firstName       recipient's first name, used in the email greeting
     * @param email           recipient's email address (the {@code To:} header)
     * @param verificationURL one-time activation link embedded in the body
     */
    void sendAccountVerification(String firstName, String email, String verificationURL);

    /**
     * Sends the password-reset email containing the verification URL that
     * lands the recipient on the password-reset form. Invoked from the
     * forgot-password flow once the reset URL has been persisted with its
     * expiration timestamp.
     *
     * @param firstName       recipient's first name, used in the email greeting
     * @param email           recipient's email address (the {@code To:} header)
     * @param verificationURL one-time reset link embedded in the body
     */
    void sendPasswordResetVerification(String firstName, String email, String verificationURL);

    /**
     * Sends the 2FA verification code to the recipient's phone via SMS.
     * Invoked from the login flow when the authenticated user has MFA enabled
     * and has just had a fresh code persisted.
     * <p>
     * The actual Twilio send is currently disabled to avoid charges during
     * development; the implementation logs the code instead. See
     * {@link com.bob.angularspringbootfullstack.utils.SMSUtils#sendSMS}.
     *
     * @param firstName   recipient's first name, used in the SMS body
     * @param phoneNumber recipient's phone number (no country-code prefix;
     *                    {@code SMSUtils} prepends {@code +1} for US numbers)
     * @param code        the 7-character 2FA code the recipient must enter
     */
    void sendTwoFactorCode(String firstName, String phoneNumber, String code);
}
