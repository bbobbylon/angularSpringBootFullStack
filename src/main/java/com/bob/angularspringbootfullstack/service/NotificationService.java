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
     * Sends the 2FA verification code to the recipient's phone. Invoked from the login flow when
     * the authenticated user has MFA enabled and has just had a fresh code persisted.
     * <p>
     * Delivery prefers {@link com.bob.angularspringbootfullstack.utils.TwilioVerifyUtils}, which is
     * exempt from US A2P 10DLC campaign registration for OTP-only traffic — see that class's Javadoc
     * for why — and generates/owns the code itself, so {@code code} is ignored on this path. It
     * tries the {@code "sms"} channel first and falls back to {@code "call"} if that throws, keeping
     * voice available as a fallback without a second integration. Only when Twilio Verify isn't
     * configured (no {@code TWILIO_VERIFY_SERVICE_SID}, e.g. dev/CI) does this fall back to reading
     * the given {@code code} aloud via
     * {@link com.bob.angularspringbootfullstack.utils.VoiceUtils#sendVerificationCall} — the
     * original workaround, kept as the no-Twilio-account degradation path. With no Twilio
     * credentials configured at all, the code is logged instead of a call being placed, so the flow
     * stays completable without any Twilio account.
     *
     * @param firstName   recipient's first name, used in the spoken greeting on the voice-fallback path
     * @param phoneNumber recipient's phone number (no country-code prefix;
     *                    {@code TwilioVerifyUtils}/{@code VoiceUtils}/{@code SMSUtils} prepend
     *                    {@code +1} for US numbers)
     * @param code        the 7-character 2FA code the recipient must enter, used only when Twilio
     *                    Verify is not configured
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
