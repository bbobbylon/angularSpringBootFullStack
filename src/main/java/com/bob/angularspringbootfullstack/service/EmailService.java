package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.enumeration.VerificationType;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.model.Stats;

/**
 * Contract for sending verification emails (account activation, password reset).
 * <p>
 * Implementations wrap Spring's {@link org.springframework.mail.javamail.JavaMailSender}
 * and are responsible for composing and dispatching the message. Called by the
 * password reset and account creation flows in {@code UserServiceImpl} once a
 * verification URL has been generated and persisted.
 */
public interface EmailService {
    /**
     * Sends a verification email containing the given {@code verificationURL} to
     * the supplied recipient.
     *
     * @param firstName        recipient's first name, used in the email greeting
     * @param email            recipient's email address (the {@code To:} header)
     * @param verificationURL  the one-time link the recipient clicks to verify;
     *                         embedded in the email body
     * @param verificationType {@code ACCOUNT} or {@code PASSWORD} — controls the
     *                         subject line and template the implementation uses
     */
    void sendVerificationEmail(String firstName, String email, String verificationURL, VerificationType verificationType);

    /**
     * Sends a one-time step-up code to an account whose sign-in was flagged as anomalous
     * (SRS FR-TPF-1).
     *
     * <p>Used only for accounts with <em>no</em> enrolled second factor: a user with an
     * authenticator or SMS 2FA is already being challenged, so they receive
     * {@link #sendSecurityAlertEmail} instead. The body states why the extra step was required, so
     * a user who did not attempt this sign-in learns their password is compromised.
     *
     * @param firstName     recipient's first name, used in the email greeting
     * @param email         recipient's email address (the {@code To:} header)
     * @param code          the one-time verification code to submit on the verify screen
     * @param reasonSummary human-readable description of what looked unusual
     *                      (e.g. {@code "a new device and a new network location"})
     */
    void sendStepUpCodeEmail(String firstName, String email, String code, String reasonSummary);

    /**
     * Notifies an account owner that a sign-in from an unfamiliar device or location was flagged
     * and challenged (SRS FR-TPF-1).
     *
     * <p>Sent when the account already has a second factor, so the challenge itself was going to
     * happen anyway and carries no explanation. This is the out-of-band signal that turns a routine
     * "enter your code" prompt into actionable information.
     *
     * @param firstName     recipient's first name, used in the email greeting
     * @param email         recipient's email address (the {@code To:} header)
     * @param reasonSummary human-readable description of what looked unusual
     */
    void sendSecurityAlertEmail(String firstName, String email, String reasonSummary);

    /**
     * Forwards a public Contact Us submission to the app's own mailbox (SRS §3.5 public-facing
     * surface). Unlike every other method here, the recipient is the team, not the visitor who
     * triggered the send — there is no account, and therefore no {@code firstName}/{@code email}
     * of a signed-in user to address it to.
     *
     * @param name    the visitor's supplied name
     * @param email   the visitor's supplied reply-to address — never verified, since there is no
     *                account to verify it against; set as the message's {@code Reply-To}, not its
     *                {@code From}, so the team can reply directly without spoofing the envelope sender
     * @param subject the visitor's supplied subject line
     * @param message the visitor's supplied message body
     */
    void sendContactMessage(String name, String email, String subject, String message);

    /**
     * Emails a PDF copy of an invoice to its owning customer (manual "Email Invoice" action —
     * POST-SUBMISSION-UPGRADES.md "PDF invoice attachments").
     * <p>
     * Unlike every flow above, this is triggered explicitly by an authenticated staff member from
     * {@code CustomerController#emailInvoice}, not by an account lifecycle or security event — there
     * is no {@code firstName} to greet with, only the customer's own name, and the recipient is
     * whichever customer the invoice belongs to rather than the account holder.
     *
     * @param customerName  the receiving customer's name, used in the greeting
     * @param customerEmail the receiving customer's email address (the {@code To:} header)
     * @param invoiceNumber the invoice's human-readable reference, used in the subject and the
     *                      attached file's name
     * @param pdfBytes      the rendered PDF, as produced by
     *                      {@code com.bob.angularspringbootfullstack.report.InvoicePdfReport#exportReport()}
     */
    void sendInvoiceEmail(String customerName, String customerEmail, String invoiceNumber, byte[] pdfBytes);

    /**
     * Emails a business + security snapshot to one recipient — the manual "Email me this report"
     * action on the Analytics screen and the payload the scheduled digest
     * ({@code SchedulingConfig}) sends to every organization's and the system's administrators
     * (POST-SUBMISSION-UPGRADES.md "Scheduled/on-demand report emails").
     * <p>
     * Unlike {@link #sendInvoiceEmail}, there is no attachment: the numbers are summarized inline
     * as branded paragraphs, matching {@link com.bob.angularspringbootfullstack.utils.EmailTemplate}'s
     * available blocks, which has no table/list renderer.
     *
     * @param recipientEmail the administrator's email address (the {@code To:} header)
     * @param scopeLabel     what the figures describe — an organization's name, or a system-wide
     *                       label — used in the subject line and heading so a recipient of several
     *                       digests (an application admin receiving the system-wide one, or an
     *                       organization admin of several orgs) can tell them apart at a glance
     * @param stats          the business rollup ({@code ReportDigestServiceImpl} resolves this to
     *                       the caller's scope before calling in)
     * @param overview       the security rollup, already resolved to the same scope as {@code stats}
     */
    void sendReportDigestEmail(String recipientEmail, String scopeLabel, Stats stats, SecurityOverview overview);
}
