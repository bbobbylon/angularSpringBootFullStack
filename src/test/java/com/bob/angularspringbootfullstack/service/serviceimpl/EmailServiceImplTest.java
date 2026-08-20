package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.model.MfaAdoption;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.model.Stats;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link EmailServiceImpl#sendInvoiceEmail} — no Spring context, no SMTP.
 * <p>
 * {@link JavaMailSender#createMimeMessage()} is stubbed to return a real, session-backed
 * {@link MimeMessage} rather than a mock: {@link org.springframework.mail.javamail.MimeMessageHelper}
 * calls straight through to {@code jakarta.mail} internals ({@code setContent}, header
 * manipulation, MIME part assembly) that a Mockito mock of {@code MimeMessage} cannot fake
 * convincingly. The message handed to {@link JavaMailSender#send(MimeMessage)} is captured and
 * inspected directly for the subject, recipient and attachment it should carry. No
 * {@code JavaMailSender}-dependent code had a test anywhere in this repo before this class, so it
 * establishes the pattern rather than following an existing one.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailServiceImpl emailService;

    @Test
    @DisplayName("sendInvoiceEmail sends to the customer's address with the PDF attached")
    void sendInvoiceEmail_attachesPdf_andSendsToCustomer() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        byte[] pdfBytes = "%PDF-1.4 test".getBytes();

        emailService.sendInvoiceEmail("Acme Corp", "billing@acme.test", "A3F9KQ2B", pdfBytes);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).isEqualTo("TesseraApp - Invoice A3F9KQ2B");
        assertThat(sent.getAllRecipients()).extracting(Object::toString).containsExactly("billing@acme.test");

        Multipart multipart = (Multipart) sent.getContent();
        boolean hasAttachment = false;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                hasAttachment = true;
                assertThat(part.getFileName()).isEqualTo("invoice-A3F9KQ2B.pdf");
            }
        }
        assertThat(hasAttachment).isTrue();
    }

    @Test
    @DisplayName("sendReportDigestEmail composes a digest addressed to the recipient, subject carrying the scope label")
    void sendReportDigestEmail_composesDigestForRecipient() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        Stats stats = new Stats(12, 34, 5678.9);
        SecurityOverview overview = new SecurityOverview(
                7, true, java.util.Map.of(),
                List.of(), SecurityOverview.PageInfo.of(0, 50, 3),
                List.of(),
                List.of(), SecurityOverview.PageInfo.of(0, 50, 1),
                new MfaAdoption(10, 6, 2, 2),
                5, 4);

        emailService.sendReportDigestEmail("admin@acme.test", "Acme Org", stats, overview);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).isEqualTo("TesseraApp - Report Digest: Acme Org");
        assertThat(sent.getAllRecipients()).extracting(Object::toString).containsExactly("admin@acme.test");

        // Both the text/plain and text/html alternative carry the same figures, so it doesn't
        // matter which MIME part order the helper produced them in — concatenate and assert once.
        // MimeMessageHelper's multipart=true constructor nests multipart/alternative inside
        // multipart/related inside multipart/mixed (MULTIPART_MODE_MIXED_RELATED, used so the
        // same helper call also supports sendWithAttachment's attachment part), so this has to
        // recurse rather than assume every direct child is a leaf text part.
        StringBuilder body = new StringBuilder();
        appendTextContent((Multipart) sent.getContent(), body);
        assertThat(body.toString())
                .contains("Acme Org")
                .contains("12")
                .contains("34")
                .contains("$5,678.90")
                .contains("3 suspicious logins")
                .contains("1 restricted accounts")
                .contains("80.0% MFA coverage")
                .contains("5 active sessions");
    }

    /**
     * Walks every part of a (possibly nested) {@link Multipart}, appending each leaf text part's
     * content to {@code out}. Recurses into any part whose content is itself a {@link Multipart} —
     * the shape {@link MimeMessageHelper}'s {@code MULTIPART_MODE_MIXED_RELATED} produces — so the
     * caller does not need to know how deep the {@code text/plain}/{@code text/html} alternative is
     * nested.
     */
    private static void appendTextContent(Multipart multipart, StringBuilder out) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            Object content = multipart.getBodyPart(i).getContent();
            if (content instanceof Multipart nested) {
                appendTextContent(nested, out);
            } else {
                out.append(content);
            }
        }
    }
}
