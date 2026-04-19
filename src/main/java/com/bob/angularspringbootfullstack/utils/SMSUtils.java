package com.bob.angularspringbootfullstack.utils;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;


/**
 * SMSUtils is a utility class for sending SMS messages using the Twilio API.
 * 
 * This class provides a static method to send SMS messages to users for
 * 2FA verification codes and other notifications. Credentials are stored as
 * class constants and should be securely managed (preferably via environment variables).
 *
 * Warning: Each SMS sent incurs a cost with Twilio. Use judiciously in production.
 */
public class SMSUtils {

    /** Twilio Account SID (removed for security) */
    public static final String FROM_NUMBER = "";
    /** Twilio Auth Token Part 1 (removed for security) */
    public static final String FAKE_ONE = "";
    /** Twilio Auth Token Part 2 (removed for security) */
    public static final String FAKE_TWO = "";

    /**
     * Sends an SMS message to the specified phone number using Twilio.
     * 
     * This method:
     * 1. Initializes the Twilio client with credentials
     * 2. Creates a Message object with recipient, sender, and message body
     * 3. Sends the message and prints it to console for logging
     *
     * Note: Phone numbers should be in E.164 format (e.g., "+11234567890").
     * The method prepends "+1" to the provided number for US numbers.
     *
     * @param toNumber the recipient's phone number (without country code, US numbers assumed)
     * @param messageBody the SMS message text to send
     * @throws Exception if Twilio API call fails or credentials are invalid
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
