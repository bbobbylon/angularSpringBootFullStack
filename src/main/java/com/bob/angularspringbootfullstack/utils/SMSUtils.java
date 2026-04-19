package com.bob.angularspringbootfullstack.utils;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;


public class SMSUtils {

    // TODO READ BELOW!
    //these have been removed for security purposes. Feel free to re-add later during showcase of the application
    public static final String FROM_NUMBER = "";
    public static final String FAKE_ONE = "";
    public static final String FAKE_TWO = "";

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
