package com.bob.angularspringbootfullstack.utils;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;


/**
 * SMSUtils is a utility class for sending SMS messages using the Twilio API.
 * <p>
 * This class provides a static method to send SMS messages to users for
 * 2FA verification codes and other notifications. Credentials are read from
 * environment variables (TWILIO_FROM_NUMBER, TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN)
 * and must never be hardcoded or committed to source control.
 * <p>
 * Warning: Each SMS sent incurs a cost with Twilio. Use judiciously in production.
 */
@SuppressWarnings("unused")
public class SMSUtils {

    /** Twilio sender phone number, loaded from TWILIO_FROM_NUMBER env var. */
    public static final String FROM_NUMBER = System.getenv("TWILIO_FROM_NUMBER");
    /** Twilio Account SID, loaded from TWILIO_ACCOUNT_SID env var. */
    public static final String FAKE_ONE    = System.getenv("TWILIO_ACCOUNT_SID");
    /** Twilio Auth Token, loaded from TWILIO_AUTH_TOKEN env var. */
    public static final String FAKE_TWO    = System.getenv("TWILIO_AUTH_TOKEN");

    /**
     * Sends an SMS message to the specified phone number using Twilio.
     * <p>
     * This method:
     * 1. Initializes the Twilio client with credentials
     * 2. Creates a Message object with recipient, sender, and message body
     * 3. Sends the message and prints it to the console for logging
     * <p>
     * Note: Phone numbers should be in E.164 format (e.g., "+11234567890").
     * The method prepends "+1" to the provided number for US numbers.
     *
     * @param toNumber    the recipient's phone number (without country code, US numbers assumed)
     * @param messageBody the SMS message text to send
     *                    //@throws Exception if the Twilio API call fails or credentials are invalid
     */
    public static void sendSMS(String toNumber, String messageBody) {
        Twilio.init(FAKE_ONE, FAKE_TWO);
        Message message = Message.creator(
                new PhoneNumber("+1" + toNumber),
                new PhoneNumber(FROM_NUMBER),
                messageBody
        ).create();
        System.out.println(messageBody);
    }
}
