package com.bob.angularspringbootfullstack.utils;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SMSUtils sends SMS messages (2FA codes, notifications) via the Twilio API.
 * <p>
 * Credentials come from environment variables (TWILIO_FROM_NUMBER,
 * TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN) and must never be hardcoded.
 * <p>
 * <b>Graceful degradation (production-safe):</b> when the Twilio credentials are not
 * configured, {@link #sendSMS} does NOT attempt a network call that would throw — it
 * logs the message at WARN and returns. This lets the 2FA flow run in dev/CI without a
 * Twilio account (the verification code reaches the developer via the log) while a
 * fully-configured production deployment delivers real texts with no code change.
 * <p>
 * Warning: every real SMS incurs a Twilio cost. Use judiciously in production.
 */
@SuppressWarnings("unused")
public class SMSUtils {

    private static final Logger log = LoggerFactory.getLogger(SMSUtils.class);

    /** Twilio sender phone number, loaded from TWILIO_FROM_NUMBER env var. */
    public static final String FROM_NUMBER  = System.getenv("TWILIO_FROM_NUMBER");
    /** Twilio Account SID, loaded from TWILIO_ACCOUNT_SID env var. */
    public static final String ACCOUNT_SID  = System.getenv("TWILIO_ACCOUNT_SID");
    /** Twilio Auth Token, loaded from TWILIO_AUTH_TOKEN env var. */
    public static final String AUTH_TOKEN   = System.getenv("TWILIO_AUTH_TOKEN");

    /**
     * Sends an SMS to the given number, or logs it when Twilio is unconfigured.
     * <p>
     * Phone numbers should be supplied without a country code; "+1" (US) is prepended
     * to form E.164 (e.g. "+11234567890").
     *
     * @param toNumber    recipient phone number without country code (US assumed)
     * @param messageBody the SMS text to send
     */
    public static void sendSMS(String toNumber, String messageBody) {
        if (!isConfigured()) {
            // No credentials → do not call Twilio (it would throw). Surface the code
            // in the log so dev/CI 2FA still works without a Twilio account.
            log.warn("Twilio is not configured; SMS not sent. Code/message for {}: {}", toNumber, messageBody);
            return;
        }
        Twilio.init(ACCOUNT_SID, AUTH_TOKEN);
        Message.creator(
                new PhoneNumber("+1" + toNumber),
                new PhoneNumber(FROM_NUMBER),
                messageBody
        ).create();
        log.info("SMS dispatched via Twilio to {}", toNumber);
    }

    /**
     * True only when all three Twilio settings are present and non-blank.
     * <p>
     * This is the switch that decides whether 2FA texts are really sent or merely logged, so it is
     * the one behaviour in this class worth testing. The environment-reading form below cannot be
     * exercised directly — the three fields are {@code static final} and initialised from
     * {@link System#getenv} at class-initialisation time, which no test can rebind — so the decision
     * itself lives in the pure {@link #isConfigured(String, String, String)} overload and this method
     * only supplies the ambient values.
     *
     * @return whether a real Twilio call should be attempted
     */
    static boolean isConfigured() {
        return isConfigured(FROM_NUMBER, ACCOUNT_SID, AUTH_TOKEN);
    }

    /**
     * The configuration rule, expressed over explicit values so it can be tested.
     * <p>
     * All three credentials are required together: a partially configured deployment must fall to
     * the logging path rather than attempt a call that would throw inside the 2FA flow. Blank is
     * treated as absent because an unset environment variable frequently arrives as an empty string
     * through a container's env-file plumbing rather than as {@code null}.
     *
     * @param fromNumber the Twilio sender number
     * @param accountSid the Twilio account SID
     * @param authToken  the Twilio auth token
     * @return {@code true} only when every value is present and non-blank
     */
    static boolean isConfigured(String fromNumber, String accountSid, String authToken) {
        return isPresent(fromNumber) && isPresent(accountSid) && isPresent(authToken);
    }

    /**
     * @param value a credential value straight from the environment
     * @return whether the value carries anything usable
     */
    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
