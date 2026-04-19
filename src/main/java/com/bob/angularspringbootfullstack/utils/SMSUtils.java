package com.bob.angularspringbootfullstack.utils;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;


public class SMSUtils {
    public static final String FROM_NUMBER = "+16414581251";
    public static final String SID_KEY = "AC691e30e92e8535257f18c987b8d6fd6c";
    public static final String TOKEN_KEY = "d60bc05628ead422f00d61c72090962f";

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
