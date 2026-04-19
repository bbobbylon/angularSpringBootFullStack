package com.bob.angularspringbootfullstack.utils;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;


public class SMSUtils {

    // TODO READ BELOW!
    //these have been removed for security purposes. Feel free to re-add later during showcase of the application
    public static final String FROM_NUMBER = "";
    public static final String SID_KEY = "";
    public static final String TOKEN_KEY = "";

    public static void sendSMS(String toNumber, String messageBody) {
        Twilio.init(SID_KEY, TOKEN_KEY);
        Message message = Message.creator(
                new PhoneNumber("+1" + toNumber),
                new PhoneNumber(FROM_NUMBER),
                messageBody
        ).create();
        System.out.println(messageBody);
    }
}
