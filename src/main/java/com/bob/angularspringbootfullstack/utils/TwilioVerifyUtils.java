package com.bob.angularspringbootfullstack.utils;

import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TwilioVerifyUtils dispatches and redeems 2FA codes through Twilio's Verify API, the
 * managed alternative to hand-rolling code generation with {@link SMSUtils}/{@link VoiceUtils}.
 * <p>
 * <b>Why Verify instead of Messaging/Voice directly.</b> Twilio's A2P 10DLC carrier registration
 * requirement — the reason {@link VoiceUtils} exists as a workaround in the first place, see its
 * class Javadoc — carries an explicit exception for Verify: "if you're only using 10DLC numbers to
 * send user verification text messages, you can use Twilio Verify rather than registering for A2P
 * 10DLC" (Twilio's own Programmable Messaging/A2P 10DLC compliance docs). Verify traffic rides
 * Twilio's own managed sender pool, not the account's registered {@code TWILIO_FROM_NUMBER}, which
 * is precisely what makes it exempt — attaching that pending long-code number to the Verify
 * Service's SMS configuration in the Twilio console would defeat the point and route the OTP
 * straight back through the same blocked campaign.
 * <p>
 * <b>What Verify takes over.</b> Unlike {@code UserRepoImpl.issueVerificationCode}, no code is
 * generated or persisted on this side of the integration — Twilio generates it, enforces its own
 * 10-minute expiry and check-attempt limit, and deletes the challenge once it is approved, expired,
 * or exhausted. {@link #startVerification} only asks Twilio to begin a challenge on a given
 * channel; {@link #checkVerification} only asks whether a user-submitted code satisfies the
 * challenge currently pending for that number. Both {@code "sms"} and {@code "call"} are the same
 * API with a different {@code channel} argument, so the voice fallback this class offers costs
 * nothing beyond a second call with a different string — no separate TwiML, unlike
 * {@link VoiceUtils#buildTwiml}.
 * <p>
 * Same graceful-degradation contract as {@link SMSUtils}/{@link VoiceUtils}: with no Verify Service
 * SID configured, callers are expected to check {@link #isConfigured()} and fall back to the local
 * code-generation path themselves (see {@code NotificationServiceImpl#sendTwoFactorCode} and
 * {@code UserRepoImpl#verifyCode}) rather than this class silently degrading — there is no local
 * code to log here, since Twilio never tells this application what the code is.
 */
public class TwilioVerifyUtils {

    private static final Logger log = LoggerFactory.getLogger(TwilioVerifyUtils.class);

    /** Twilio Verify Service SID ("VAxxxxxxxx…"), loaded from the TWILIO_VERIFY_SERVICE_SID env var. */
    public static final String VERIFY_SERVICE_SID = System.getenv("TWILIO_VERIFY_SERVICE_SID");

    /**
     * Starts a Verify challenge for {@code toNumber} on the given channel and returns once Twilio
     * has accepted it — the same "accepted, not delivered" caveat {@link SMSUtils#sendSMS} carries
     * applies here too, which is exactly why callers should be prepared to retry on the {@code
     * "call"} channel rather than trust a single {@code "sms"} attempt.
     *
     * @param toNumber recipient phone number, any shape {@link SMSUtils#toE164US} accepts
     * @param channel  {@code "sms"} or {@code "call"}
     */
    public static void startVerification(String toNumber, String channel) {
        SMSUtils.ensureTwilioInitialized();
        Verification verification = Verification.creator(VERIFY_SERVICE_SID, SMSUtils.toE164US(toNumber), channel).create();
        log.info("Twilio Verify {} challenge dispatched to {}, status={}", channel, toNumber, verification.getStatus());
    }

    /**
     * Checks {@code code} against the Verify challenge currently pending for {@code toNumber}.
     *
     * @param toNumber recipient phone number, any shape {@link SMSUtils#toE164US} accepts
     * @param code     the code the user submitted
     * @return {@code true} only when Twilio reports the challenge as {@code "approved"}
     */
    public static boolean checkVerification(String toNumber, String code) {
        SMSUtils.ensureTwilioInitialized();
        VerificationCheck check = VerificationCheck.creator(VERIFY_SERVICE_SID)
                .setTo(SMSUtils.toE164US(toNumber))
                .setCode(code)
                .create();
        log.info("Twilio Verify check for {}: status={}", toNumber, check.getStatus());
        return "approved".equals(check.getStatus());
    }

    /**
     * True only when the shared Twilio credentials and the Verify Service SID are all present.
     *
     * @return whether {@link #startVerification}/{@link #checkVerification} should be attempted
     */
    public static boolean isConfigured() {
        return isConfigured(SMSUtils.ACCOUNT_SID, SMSUtils.AUTH_TOKEN, VERIFY_SERVICE_SID);
    }

    /**
     * The configuration rule, expressed over explicit values so it can be tested — mirrors
     * {@link SMSUtils#isConfigured(String, String, String)} for the same reason: the {@code static
     * final} fields it reads can't be rebound from a test.
     *
     * @param accountSid       the Twilio account SID
     * @param authToken        the Twilio auth token
     * @param verifyServiceSid the Verify Service SID
     * @return {@code true} only when every value is present and non-blank
     */
    static boolean isConfigured(String accountSid, String authToken, String verifyServiceSid) {
        return isPresent(accountSid) && isPresent(authToken) && isPresent(verifyServiceSid);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
