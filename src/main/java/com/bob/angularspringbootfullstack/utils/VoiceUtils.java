package com.bob.angularspringbootfullstack.utils;

import com.twilio.rest.api.v2010.account.Call;
import com.twilio.twiml.TwiMLException;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Pause;
import com.twilio.twiml.voice.Say;
import com.twilio.type.PhoneNumber;
import com.twilio.type.Twiml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VoiceUtils places an outbound phone call that speaks a 2FA code via Twilio's text-to-speech,
 * used as the delivery fallback when {@link SMSUtils#sendSMS} fails.
 * <p>
 * The motivating case is A2P 10DLC: Twilio requires US long-code SMS senders to register an A2P
 * campaign, and traffic sent before that registration clears is rejected at the API call (the
 * Twilio SDK throws) rather than delivered. Twilio's Voice API carries no such registration
 * requirement, so a call placed with the same credentials succeeds today regardless of campaign
 * status. This also gives a real end user a way to receive their code without SMS at all — the
 * alternative, reading it from the application log, is only reachable by whoever has CloudWatch
 * access, which an external user never does.
 * <p>
 * TwiML (the XML that tells Twilio what to say) is generated in-process from a fixed template and
 * passed directly on the call-creation request via {@link Twiml}, so no public webhook endpoint is
 * needed — nothing new to expose from an already-stateless backend.
 * <p>
 * Shares credentials and the sender number with {@link SMSUtils}: a Twilio phone number is
 * voice-capable by default, so no additional environment variables or AWS Secrets Manager entries
 * are required beyond what SMS already uses. Same graceful-degradation contract as {@link SMSUtils}
 * — with no credentials configured, the code is logged instead of a call being placed, so the flow
 * stays completable in dev/CI without a Twilio account.
 */
public class VoiceUtils {

    private static final Logger log = LoggerFactory.getLogger(VoiceUtils.class);

    /**
     * Places a call to {@code toNumber} that greets the recipient by name and reads the code aloud
     * twice, character by character, with a pause between repetitions.
     *
     * @param toNumber  recipient phone number, any shape {@link SMSUtils#toE164US} accepts
     * @param firstName recipient's first name, used in the spoken greeting
     * @param code      the 2FA code to read aloud
     */
    public static void sendVerificationCall(String toNumber, String firstName, String code) {
        if (!SMSUtils.isConfigured()) {
            log.warn("Twilio is not configured; voice call not placed. Code for {}: {}", toNumber, code);
            return;
        }
        SMSUtils.ensureTwilioInitialized();
        try {
            Call.creator(
                    new PhoneNumber(SMSUtils.toE164US(toNumber)),
                    new PhoneNumber(SMSUtils.FROM_NUMBER),
                    new Twiml(buildTwiml(firstName, code))
            ).create();
            log.info("Voice call dispatched via Twilio to {}", toNumber);
        } catch (TwiMLException e) {
            // buildTwiml assembles fixed, self-controlled XML from constant templates and cannot
            // fail at runtime; this only satisfies the checked signature toXml() carries.
            log.error("Failed to build TwiML for voice call to {}: {}", toNumber, e.getMessage(), e);
        }
    }

    /**
     * Assembles the call script: a spoken greeting, the code spelled out, a one-second pause, then
     * the code spelled out again — mirroring how Google/Authy-style "call me" fallbacks read a code
     * twice so a listener without a pen ready can catch it the second time.
     *
     * @param firstName recipient's first name
     * @param code      the 2FA code to read aloud
     * @return the TwiML document as XML
     * @throws TwiMLException never in practice; the input is always this class's own fixed template
     */
    static String buildTwiml(String firstName, String code) throws TwiMLException {
        Say greeting = say("Hello " + firstName + ". This is Tessera App calling with your verification code.");
        Say spelledCode = say(spellOut(code));
        return new VoiceResponse.Builder()
                .say(greeting)
                .say(spelledCode)
                .pause(new Pause.Builder().length(1).build())
                .say(spelledCode)
                .build()
                .toXml();
    }

    private static Say say(String text) {
        return new Say.Builder(text)
                .voice(Say.Voice.POLLY_JOANNA)
                .language(Say.Language.EN_US)
                .build();
    }

    /**
     * Inserts a pause after every character so Twilio's text-to-speech reads an alphanumeric code
     * one character at a time instead of guessing at a pronunciation for it as a word — the same
     * problem spelling out "A1B2C3D" over the phone runs into if you don't pause between letters.
     *
     * @param code the 2FA code, e.g. {@code "A1B2C3D"}
     * @return the code with every character comma-separated, e.g. {@code "A, 1, B, 2, C, 3, D"}
     */
    static String spellOut(String code) {
        StringBuilder spelled = new StringBuilder(code.length() * 3);
        for (int i = 0; i < code.length(); i++) {
            spelled.append(code.charAt(i));
            if (i < code.length() - 1) {
                spelled.append(", ");
            }
        }
        return spelled.toString();
    }
}
