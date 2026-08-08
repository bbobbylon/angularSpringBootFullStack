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
     * Delivery goes through {@link com.bob.angularspringbootfullstack.utils.SMSUtils#sendSMS},
     * which sends a real Twilio text when credentials are configured and logs the code instead
     * when they are not, so the flow stays completable in dev/CI without a Twilio account.
     *
     * @param firstName   recipient's first name, used in the SMS body
     * @param phoneNumber recipient's phone number (no country-code prefix;
     *                    {@code SMSUtils} prepends {@code +1} for US numbers)
     * @param code        the 7-character 2FA code the recipient must enter
     */
    void sendTwoFactorCode(String firstName, String phoneNumber, String code);

    /**
     * Emails a one-time step-up code to an account whose sign-in was flagged as anomalous and
     * which has no enrolled second factor (SRS FR-TPF-1).
     *
     * <p>Email — not SMS — is the channel here: it is the address the account is keyed on and is
     * guaranteed present, whereas a phone number is optional and the Twilio path only dispatches
     * when credentials are configured. A step-up that cannot be delivered would lock the
     * legitimate user out of their own account.
     *
     * @param firstName     recipient's first name, used in the greeting
     * @param email         recipient's email address
     * @param code          the one-time verification code
     * @param reasonSummary human-readable description of what looked unusual
     */
    void sendStepUpCode(String firstName, String email, String code, String reasonSummary);

    /**
     * Emails a security alert for a flagged sign-in that was challenged by an already-enrolled
     * second factor (SRS FR-TPF-1).
     *
     * @param firstName     recipient's first name, used in the greeting
     * @param email         recipient's email address
     * @param reasonSummary human-readable description of what looked unusual
     */
    void sendSecurityAlert(String firstName, String email, String reasonSummary);
}
