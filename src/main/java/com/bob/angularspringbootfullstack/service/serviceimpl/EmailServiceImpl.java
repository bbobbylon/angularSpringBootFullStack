package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.VerificationType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.model.Stats;
import com.bob.angularspringbootfullstack.service.EmailService;
import com.bob.angularspringbootfullstack.utils.EmailTemplate;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;


/**
 * Default {@link EmailService} implementation. Composes a {@code multipart/alternative}
 * {@link MimeMessage} — a plain-text part plus the branded HTML rendered by
 * {@link EmailTemplate} — for account verification, password reset and step-up flows, and
 * dispatches it through Spring's {@link JavaMailSender}, which is auto-wired by
 * {@code MailSenderAutoConfiguration} from the {@code spring.mail.*} properties in
 * {@code application-dev.yml} (Gmail SMTP credentials supplied via the {@code MAIL_USERNAME} and
 * {@code MAIL_PASSWORD} environment variables).
 * <p>
 * <strong>Why multipart rather than HTML alone.</strong> Every message carries both
 * representations and lets the client pick. That keeps the emails readable in text-only clients and
 * in the plain-text preview panes some corporate gateways force, and it materially helps
 * deliverability — an HTML-only body with a link in it is a classic spam signal, and these are
 * exactly the messages that must not land in a junk folder. The plain-text part is written
 * deliberately, not machine-stripped from the markup.
 * <p>
 * Callers are expected to invoke this on a background worker — typically
 * {@link NotificationServiceImpl}, which wraps every send in
 * {@link java.util.concurrent.CompletableFuture#runAsync} so the originating HTTP thread returns to
 * the client without waiting on the SMTP round-trip. This class itself stays synchronous and
 * exception-transparent.
 *
 * @see EmailTemplate
 */
@Slf4j
@Service
@AllArgsConstructor
public class EmailServiceImpl implements EmailService {

    /** Envelope sender. Must match the authenticated Gmail account or the relay rejects the message. */
    private static final String FROM_ADDRESS = "bobsangularemail@gmail.com";

    /** Display name shown in the recipient's inbox instead of the raw address. */
    private static final String FROM_NAME = "TesseraApp";

    private final JavaMailSender mailSender;

    /**
     * Composes and dispatches the verification email carrying an activation or password-reset link.
     * <p>
     * Exceptions are intentionally <em>not</em> caught here so that any
     * {@link org.springframework.mail.MailException} (parse failures, SMTP connection issues, auth
     * failures) propagates to {@link NotificationServiceImpl}, whose {@code .exceptionally(...)} on
     * the {@code CompletableFuture} logs a single, accurate error entry. Catching here would force
     * the caller to log a misleading success.
     *
     * @param firstName        recipient's first name, used in the body greeting
     * @param email            recipient's email address (the {@code To:} header)
     * @param verificationURL  the one-time link embedded in the body — an absolute <em>frontend</em>
     *                         URL built by {@code UserRepoImpl#getVerificationURL}, deliberately
     *                         outside the API's {@code /user/**} namespace so it resolves to the SPA
     *                         even when both are served from one origin
     * @param verificationType {@code ACCOUNT} or {@code PASSWORD} — drives the subject line and the
     *                         copy selected in {@link #copyFor}
     */
    @Override
    public void sendVerificationEmail(String firstName, String email, String verificationURL, VerificationType verificationType) {
        VerificationCopy copy = copyFor(verificationType);
        String subject = String.format("TesseraApp - %s Verification Email",
                StringUtils.capitalize(verificationType.name().toLowerCase()));

        String plain = "Hello " + firstName + "\n\n"
                + copy.intro() + "\n\n"
                + verificationURL + "\n\n"
                + copy.note();

        String html = EmailTemplate.builder()
                .preheader(copy.preheader())
                .eyebrow(copy.eyebrow())
                .heading(copy.heading())
                .paragraph("Hello " + firstName + ",")
                .paragraph(copy.intro())
                .button(copy.ctaLabel(), verificationURL)
                .note(copy.note())
                .build();

        send(email, subject, plain, html);
        log.info("Verification email ({}) dispatched to {}", verificationType.getType(), email);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Like {@link #sendVerificationEmail}, exceptions propagate rather than being swallowed —
     * {@code NotificationServiceImpl} owns the failure logging, and for this flow it additionally
     * writes the code to the server log so a developer running without SMTP configured can still
     * complete a step-up challenge.
     */
    @Override
    public void sendStepUpCodeEmail(String firstName, String email, String code, String reasonSummary) {
        String subject = "TesseraApp - Verify this sign-in";

        String plain = "Hello " + firstName + "\n\n"
                + "We noticed a sign-in to your TesseraApp account from " + reasonSummary + ", "
                + "so we asked for one extra step before letting it through.\n\n"
                + "Your verification code is: " + code + "\n\n"
                + "Enter it on the verification screen to finish signing in. The code expires in 24 hours "
                + "and can be used once.\n\n"
                + "If this wasn't you, do NOT enter the code. Someone else knows your password — "
                + "change it immediately and consider enabling an authenticator app under Security Center.";

        String html = EmailTemplate.builder()
                .preheader("Your one-time verification code for an unusual sign-in.")
                .eyebrow("Sign-in verification")
                .heading("Verify this sign-in")
                .paragraph("Hello " + firstName + ",")
                .paragraph("We noticed a sign-in to your TesseraApp account from " + reasonSummary
                        + ", so we asked for one extra step before letting it through.")
                .code(code)
                .note("Enter this code on the verification screen to finish signing in. "
                        + "It expires in 24 hours and can be used once.")
                .warning("If this wasn't you, do NOT enter the code. Someone else knows your password — "
                        + "change it immediately and consider enabling an authenticator app under "
                        + "Security Center.")
                .build();

        send(email, subject, plain, html);
        log.info("Step-up verification code dispatched to {}", email);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Carries no code: the recipient is already completing a second-factor challenge on a
     * channel they control. Its only job is to explain <em>why</em> that challenge appeared.
     */
    @Override
    public void sendSecurityAlertEmail(String firstName, String email, String reasonSummary) {
        String subject = "TesseraApp - Unusual sign-in attempt";

        String plain = "Hello " + firstName + "\n\n"
                + "Someone signed in to your TesseraApp account from " + reasonSummary + ". "
                + "Because it didn't match your usual sign-in pattern, we required your second factor "
                + "before granting access.\n\n"
                + "If that was you, no action is needed.\n\n"
                + "If it wasn't, your password is compromised even though the sign-in was blocked at the "
                + "second factor. Change it now, and review your active sessions under Security Center.";

        String html = EmailTemplate.builder()
                .preheader("We required your second factor for a sign-in that looked unusual.")
                .eyebrow("Security alert")
                .heading("Unusual sign-in attempt")
                .paragraph("Hello " + firstName + ",")
                .paragraph("Someone signed in to your TesseraApp account from " + reasonSummary
                        + ". Because it didn't match your usual sign-in pattern, we required your "
                        + "second factor before granting access.")
                .paragraph("If that was you, no action is needed.")
                .warning("If it wasn't, your password is compromised even though the sign-in was "
                        + "blocked at the second factor. Change it now, and review your active "
                        + "sessions under Security Center.")
                .build();

        send(email, subject, plain, html);
        log.info("Security alert email dispatched to {}", email);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sent to this application's own {@link #FROM_ADDRESS} — there is no separate support
     * mailbox configured, and adding one is a config change, not a code one, whenever that becomes
     * worth doing. The visitor's address goes on {@code Reply-To}, never {@code From}: setting an
     * unverified third-party address as the envelope sender is exactly the shape SPF/DKIM checks
     * reject, and would risk the message never arriving at all.
     */
    @Override
    public void sendContactMessage(String name, String email, String subject, String message) {
        String mailSubject = "TesseraApp Contact Us: " + subject;

        String plain = "New Contact Us submission\n\n"
                + "From: " + name + " <" + email + ">\n"
                + "Subject: " + subject + "\n\n"
                + message;

        String html = EmailTemplate.builder()
                .preheader("New Contact Us submission from " + name)
                .eyebrow("Contact Us")
                .heading(subject)
                .paragraph("From: " + name + " (" + email + ")")
                .paragraph(message)
                .note("Reply directly to this email to respond — Reply-To is set to the sender's address.")
                .build();

        sendWithReplyTo(FROM_ADDRESS, mailSubject, plain, html, email);
        log.info("Contact Us submission from {} <{}> forwarded to {}", name, email, FROM_ADDRESS);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Exceptions propagate rather than being swallowed, matching every other method here —
     * {@code NotificationServiceImpl} owns the failure logging for whichever caller wraps this in
     * a {@link java.util.concurrent.CompletableFuture}.
     */
    @Override
    public void sendInvoiceEmail(String customerName, String customerEmail, String invoiceNumber, byte[] pdfBytes) {
        String subject = "TesseraApp - Invoice " + invoiceNumber;

        String plain = "Hello " + customerName + "\n\n"
                + "Please find attached your invoice (" + invoiceNumber + ") from TesseraApp.\n\n"
                + "If you have any questions about this invoice, please reply to this email.";

        String html = EmailTemplate.builder()
                .preheader("Your invoice " + invoiceNumber + " is attached as a PDF.")
                .eyebrow("Invoice")
                .heading("Invoice " + invoiceNumber)
                .paragraph("Hello " + customerName + ",")
                .paragraph("Please find attached your invoice (" + invoiceNumber + ") from TesseraApp.")
                .note("If you have any questions about this invoice, please reply to this email.")
                .build();

        sendWithAttachment(customerEmail, subject, plain, html, "invoice-" + invoiceNumber + ".pdf", pdfBytes);
        log.info("Invoice {} emailed to {}", invoiceNumber, customerEmail);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Exceptions propagate rather than being swallowed, matching every other method here. The
     * scheduled caller ({@code SchedulingConfig}) and the manual caller
     * ({@code ReportDigestServiceImpl}) both log per-recipient failures themselves so one bad
     * address in a batch of admins does not stop the loop from reaching the rest.
     */
    @Override
    public void sendReportDigestEmail(String recipientEmail, String scopeLabel, Stats stats, SecurityOverview overview) {
        String subject = "TesseraApp - Report Digest: " + scopeLabel;
        String totalBilled = String.format("$%,.2f", stats.getTotalBilled());
        String mfaCoverage = overview.mfaAdoption().mfaCoveragePercent() + "%";

        String plain = "Report digest: " + scopeLabel + "\n\n"
                + "Business overview\n"
                + "Total customers: " + stats.getTotalCustomers() + "\n"
                + "Total invoices: " + stats.getTotalInvoices() + "\n"
                + "Total billed: " + totalBilled + "\n\n"
                + "Security overview (last " + overview.windowDays() + " days)\n"
                + "Suspicious logins: " + overview.suspiciousLoginsPage().totalElements() + "\n"
                + "Restricted accounts: " + overview.restrictedAccountsPage().totalElements() + "\n"
                + "MFA coverage: " + mfaCoverage + "\n"
                + "Active sessions: " + overview.activeSessions() + "\n\n"
                + "Sign in to TesseraApp for the full interactive dashboard.";

        String html = EmailTemplate.builder()
                .preheader("Your TesseraApp report digest for " + scopeLabel)
                .eyebrow("Report digest")
                .heading(scopeLabel)
                .paragraph("Here is the latest snapshot for " + scopeLabel + ".")
                .paragraph("Business overview: " + stats.getTotalCustomers() + " customers, "
                        + stats.getTotalInvoices() + " invoices, " + totalBilled + " billed.")
                .paragraph("Security overview (last " + overview.windowDays() + " days): "
                        + overview.suspiciousLoginsPage().totalElements() + " suspicious logins, "
                        + overview.restrictedAccountsPage().totalElements() + " restricted accounts, "
                        + mfaCoverage + " MFA coverage, " + overview.activeSessions() + " active sessions.")
                .note("Sign in to TesseraApp for the full interactive dashboard.")
                .build();

        send(recipientEmail, subject, plain, html);
        log.info("Report digest ({}) dispatched to {}", scopeLabel, recipientEmail);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendOrganizationCreatedEmail(String adminFirstName, String adminEmail, String organizationName) {
        String subject = "TesseraApp - Organization '" + organizationName + "' created";

        String plain = "Hello " + adminFirstName + "\n\n"
                + "You created the organization \"" + organizationName + "\" on TesseraApp.\n\n"
                + "You can manage its settings, members, and roles from the Organizations screen at any time.";

        String html = EmailTemplate.builder()
                .preheader("You created \"" + organizationName + "\" on TesseraApp.")
                .eyebrow("Organization created")
                .heading(organizationName)
                .paragraph("Hello " + adminFirstName + ",")
                .paragraph("You created the organization \"" + organizationName + "\" on TesseraApp.")
                .note("You can manage its settings, members, and roles from the Organizations screen at any time.")
                .build();

        send(adminEmail, subject, plain, html);
        log.info("Organization-created confirmation dispatched to {} for organization '{}'", adminEmail, organizationName);
    }

    /**
     * Builds and sends one {@code multipart/alternative} message.
     * <p>
     * {@link MimeMessageHelper#setText(String, String)} is what creates the two-part body: the
     * first argument becomes {@code text/plain}, the second {@code text/html}, and the client
     * displays whichever it prefers (in practice, HTML where available).
     * <p>
     * The checked {@link MessagingException} and {@link UnsupportedEncodingException} are rewrapped
     * as {@link MailPreparationException} rather than declared, for two reasons. First, the
     * interface contract is exception-transparent but unchecked — {@code NotificationServiceImpl}
     * invokes these methods inside a {@link Runnable}, which cannot propagate a checked exception at
     * all. Second, {@code MailPreparationException} is a {@code MailException}, so a composition
     * failure lands in the exact same {@code .exceptionally(...)} handler as an SMTP failure and the
     * caller's error logging keeps working unchanged.
     *
     * @param to      recipient address
     * @param subject subject line
     * @param plain   the {@code text/plain} alternative
     * @param html    the {@code text/html} alternative, as rendered by {@link EmailTemplate}
     * @throws MailPreparationException if the message cannot be composed
     */
    private void send(String to, String subject, String plain, String html) {
        sendWithReplyTo(to, subject, plain, html, null);
    }

    /**
     * {@link #send}, with an optional {@code Reply-To} address for the one flow
     * ({@link #sendContactMessage}) where the recipient should reply to someone other than
     * {@link #FROM_ADDRESS}.
     *
     * @param replyTo address to set as {@code Reply-To}, or {@code null} to omit the header entirely
     *                (every other caller — the header only makes sense when the reply audience
     *                differs from the sender)
     */
    private void sendWithReplyTo(String to, String subject, String plain, String html, String replyTo) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            // multipart=true is what allows a text/html part alongside text/plain; without it the
            // helper writes a single-part message and setText(String, String) is rejected.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(FROM_ADDRESS, FROM_NAME);
            if (replyTo != null) {
                helper.setReplyTo(replyTo);
            }
            helper.setSubject(subject);
            helper.setText(plain, html);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Unable to compose outbound email", e);
        }
        mailSender.send(message);
    }

    /**
     * {@link #sendWithReplyTo}, plus one file attachment — used only by {@link #sendInvoiceEmail}.
     * <p>
     * Kept as its own method rather than adding attachment parameters to
     * {@link #sendWithReplyTo}/{@link #send}: every other caller in this class sends exactly two
     * body parts and no attachment, so threading an always-null attachment through their call sites
     * would only make the common case harder to read for the sake of the one exception.
     * {@link MimeMessageHelper}'s {@code multipart=true} constructor already builds a
     * {@code MULTIPART_MODE_MIXED_RELATED} message, which supports an attachment alongside the
     * {@code text/plain} + {@code text/html} alternative {@link MimeMessageHelper#setText(String, String)}
     * produces — no different MIME mode is needed for this to work.
     *
     * @param to                 recipient address
     * @param subject            subject line
     * @param plain              the {@code text/plain} alternative
     * @param html               the {@code text/html} alternative, as rendered by {@link EmailTemplate}
     * @param attachmentFilename the name the attachment is saved as by the recipient's mail client
     * @param attachmentBytes    the attachment's raw bytes
     * @throws MailPreparationException if the message cannot be composed
     */
    private void sendWithAttachment(String to, String subject, String plain, String html,
                                     String attachmentFilename, byte[] attachmentBytes) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(FROM_ADDRESS, FROM_NAME);
            helper.setSubject(subject);
            helper.setText(plain, html);
            helper.addAttachment(attachmentFilename, new ByteArrayResource(attachmentBytes));
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Unable to compose outbound email", e);
        }
        mailSender.send(message);
    }

    /**
     * Selects the copy for the given verification flow.
     * <p>
     * Keeping every string for a flow in one record preserves the property the previous
     * switch-based body builder had — adding a {@link VerificationType} means touching this one
     * method and the enum, nothing else — while now also feeding the plain-text and HTML bodies
     * from a single source, so the two representations cannot drift apart.
     *
     * @param verificationType {@code ACCOUNT} or {@code PASSWORD}
     * @return the copy bundle for that flow
     * @throws ApiException if {@code verificationType} has no branch — a defensive guard against a
     *                      future enum value being added without accompanying copy
     */
    private VerificationCopy copyFor(VerificationType verificationType) {
        return switch (verificationType) {
            case PASSWORD -> new VerificationCopy(
                    "Reset your TesseraApp password.",
                    "Password reset",
                    "Reset your password",
                    "We received a request to reset the password on your TesseraApp account. "
                            + "Use the link below to choose a new one.",
                    "Reset my password",
                    "If you did not request a password reset, you can ignore this email — "
                            + "your password will not change.");
            case ACCOUNT -> new VerificationCopy(
                    "Confirm your email address to activate your account.",
                    "Account activation",
                    "Welcome to TesseraApp",
                    "Your account has been created. Confirm your email address to activate it and "
                            + "sign in for the first time.",
                    "Activate my account",
                    "If you did not create this account, you can ignore this email — "
                            + "it will not be activated.");
            default -> throw new ApiException("Unable to send email. Please try again later.");
        };
    }

    /**
     * The user-facing strings for one verification flow, shared by the plain-text and HTML bodies.
     *
     * @param preheader inbox preview line
     * @param eyebrow   small uppercase category label above the heading
     * @param heading   the email's headline
     * @param intro     the explanatory paragraph preceding the link
     * @param ctaLabel  the button's label
     * @param note      the closing "if this wasn't you" fine print
     */
    private record VerificationCopy(String preheader, String eyebrow, String heading, String intro,
                                    String ctaLabel, String note) {
    }
}
