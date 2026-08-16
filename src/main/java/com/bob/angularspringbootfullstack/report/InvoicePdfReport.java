package com.bob.angularspringbootfullstack.report;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.InvoiceLineItem;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateFormatUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Renders a single {@link Invoice} as a one-page PDF document using OpenPDF.
 *
 * <p>Mirrors {@link InvoiceReport}'s two-phase shape — construct, then call the export method —
 * but at the scale of one invoice rather than a full list, and producing {@code byte[]} rather
 * than an {@link org.springframework.core.io.InputStreamResource}: the raw bytes are needed both
 * as an HTTP download body ({@code GET /customer/invoice/{invoiceId}/download/pdf}) and as a
 * {@code MimeMessageHelper} attachment ({@code POST /customer/invoice/{invoiceId}/email}), and
 * wrapping them once at each call site (via {@link org.springframework.core.io.ByteArrayResource})
 * is simpler than committing this class to one wrapper type.
 *
 * <p>A draft invoice (no linked {@link Customer} — see {@link Invoice#getCustomer()}) still
 * renders: the "Bill To" block prints a placeholder instead of throwing, since the download
 * endpoint has no reason to refuse a document the user can already see on screen. Emailing a
 * draft is refused one layer up, in {@code CustomerController#emailInvoice}, where there is no
 * address to send it to.
 */
@Slf4j
public class InvoicePdfReport {
    private final Invoice invoice;

    /**
     * @param invoice the invoice to render; its {@link Invoice#getServices()} line items and
     *                (possibly null) {@link Invoice#getCustomer()} are read at export time, not here
     */
    public InvoicePdfReport(Invoice invoice) {
        this.invoice = invoice;
    }

    /**
     * Renders the invoice and returns the finished document as raw PDF bytes.
     *
     * @return the PDF file contents, starting with the standard {@code %PDF-} header
     */
    public byte[] exportReport() {
        return generateReport();
    }

    /**
     * Builds the document — header, "Bill To" block, line-item table, total — and serializes it.
     *
     * @return the PDF bytes produced by {@link PdfWriter}
     * @throws ApiException if OpenPDF fails to write the document
     */
    private byte[] generateReport() {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, outputStream);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20);
            Font headingFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
            Font mutedFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.NORMAL, Color.GRAY);

            Paragraph title = new Paragraph("Invoice " + invoice.getInvoiceNumber(), titleFont);
            title.setSpacingAfter(4);
            document.add(title);

            String dateLine = invoice.getInvoiceDate() != null
                    ? DateFormatUtils.format(invoice.getInvoiceDate(), "yyyy-MM-dd")
                    : "—";
            Paragraph meta = new Paragraph("Status: " + invoice.getStatus() + "    Date: " + dateLine, mutedFont);
            meta.setSpacingAfter(18);
            document.add(meta);

            document.add(new Paragraph("Bill To", headingFont));
            Customer customer = invoice.getCustomer();
            if (customer != null) {
                document.add(new Paragraph(customer.getCustomerName(), bodyFont));
                if (customer.getEmail() != null) {
                    document.add(new Paragraph(customer.getEmail(), bodyFont));
                }
                if (customer.getAddress() != null) {
                    document.add(new Paragraph(customer.getAddress(), bodyFont));
                }
                if (customer.getPhoneNumber() != null) {
                    document.add(new Paragraph(customer.getPhoneNumber(), bodyFont));
                }
            } else {
                document.add(new Paragraph("No customer attached (draft invoice)", bodyFont));
            }
            Paragraph spacer = new Paragraph(" ");
            spacer.setSpacingAfter(10);
            document.add(spacer);

            PdfPTable table = new PdfPTable(2);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{3f, 1f});
            table.addCell(headerCell("Service", headingFont, Element.ALIGN_LEFT));
            table.addCell(headerCell("Price", headingFont, Element.ALIGN_RIGHT));
            List<InvoiceLineItem> services = invoice.getServices();
            if (services != null) {
                for (InvoiceLineItem item : services) {
                    table.addCell(new PdfPCell(new Phrase(item.getName(), bodyFont)));
                    PdfPCell priceCell = new PdfPCell(
                            new Phrase(String.format("%.2f", item.getPrice() != null ? item.getPrice() : 0.0), bodyFont));
                    priceCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    table.addCell(priceCell);
                }
            }
            document.add(table);

            Paragraph total = new Paragraph(
                    "Total: " + String.format("%.2f", invoice.getTotalAmount() != null ? invoice.getTotalAmount() : 0.0),
                    headingFont);
            total.setAlignment(Element.ALIGN_RIGHT);
            total.setSpacingBefore(14);
            document.add(total);

            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException | IOException e) {
            log.error("Error generating invoice PDF: {}", e.getMessage());
            throw new ApiException("Failed to generate invoice PDF");
        }
    }

    /**
     * Builds one bold table header cell.
     *
     * @param text  header label
     * @param font  the bold heading font
     * @param align a {@link Element} horizontal alignment constant
     * @return the finished cell, ready to add to the table
     */
    private static PdfPCell headerCell(String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(align);
        return cell;
    }
}
