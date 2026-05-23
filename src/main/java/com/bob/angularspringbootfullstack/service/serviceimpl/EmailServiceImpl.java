package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.VerificationType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.service.EmailService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
 

@Slf4j
@Service
@AllArgsConstructor
public class EmailServiceImpl implements EmailService {
    private final JavaMailSender mailSender;

    /**
     * @param firstName
     * @param verificationURL
     * @param email
     * @param verificationType
     */
    @Override
    public void sendVerificationEmail(String firstName, String verificationURL, String email, VerificationType verificationType) {
        try {

            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(email);
            msg.setFrom("");
            msg.setText(getEmailMessage(firstName, verificationURL, verificationType));
            msg.setSubject(String.format("Secure Capita - %s Verification Email", StringUtils.capitalize(verificationType.name().toLowerCase())));
            mailSender.send(msg);
            log.info(mailSender.toString(), firstName);
        } catch (Exception e) {
            log.error("Failed to send email to {}", email, e);
        }
    }

    private String getEmailMessage(String firstName, String verificationURL, VerificationType verificationType) {
        switch (verificationType) {
            case PASSWORD -> {
                return "Hello " + firstName + "\n\n Reset Password Request. Please click the link to begin the password reset flow.\n\n" + verificationURL + "\n\n If you did not request a password reset, please ignore this email.";
            }
            case ACCOUNT -> {
                return "Hello " + firstName + "\n\n Welcome to Secure Capita! Please click the link to activate your account.\n\n" + verificationURL + "\n\n If you did not create an account, please ignore this email.";
            }
            default -> throw new ApiException("Unable to send email. Please try again later.");

        }
    }
}
