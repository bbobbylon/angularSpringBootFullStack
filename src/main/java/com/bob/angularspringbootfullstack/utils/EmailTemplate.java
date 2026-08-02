package com.bob.angularspringbootfullstack.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the branded HTML body used by every transactional email this application sends.
 * <p>
 * This is the email-medium counterpart to the SPA's design system in
 * {@code tesseraapp/src/styles.css}: the same dark surface, iris accent and hairline borders, so an
 * activation email and the screen it links to are recognisably one product. It exists as a separate
 * renderer rather than a template file because the project deliberately carries no template engine
 * (no Thymeleaf/Freemarker in {@code pom.xml}) — adding one for three emails would be a dependency
 * and a second rendering pipeline in exchange for markup that has to stay inline anyway.
 * <p>
 * <strong>Why the markup looks dated on purpose.</strong> Email clients are not browsers. Outlook
 * renders with Word's HTML engine, Gmail strips {@code <style>} blocks and rewrites CSS, and none of
 * them can be relied on for flexbox or grid. So this class emits table-based layout with every rule
 * inline, hex colours instead of {@code rgba()} (Word drops the latter), and a "bulletproof" button
 * built from a table cell rather than a styled anchor. Rounded corners and shadows are included but
 * treated as progressive enhancement — where they are unsupported the message degrades to squared
 * panels rather than breaking.
 * <p>
 * Every caller-supplied value is HTML-escaped by {@link #escape(String)} on the way in. That matters
 * because {@code firstName} is user-controlled at registration and the risk summaries passed to the
 * step-up emails are assembled from request metadata — neither may be allowed to close a tag.
 * <p>
 * Consumed exclusively by
 * {@link com.bob.angularspringbootfullstack.service.serviceimpl.EmailServiceImpl}, which pairs each
 * rendered HTML body with a plain-text alternative in a {@code multipart/alternative} message so
 * text-only clients still get a readable email.
 *
 * @see com.bob.angularspringbootfullstack.service.serviceimpl.EmailServiceImpl
 */
public final class EmailTemplate {

    // --- Palette ------------------------------------------------------------------------------
    // Mirrors the dark theme in styles.css, flattened to opaque hex because Word's rendering engine
    // discards rgba() and would fall back to transparent (i.e. white) panels in Outlook.

    /** Page backdrop — matches {@code --surface-0}. */
    private static final String BG = "#0a0c12";
    /** Card surface — sits between {@code --surface-1} and {@code --surface-2}. */
    private static final String CARD = "#12161f";
    /** Inset panel used for code chips and callouts — matches {@code --surface-3}. */
    private static final String INSET = "#1d2433";
    /** Opaque stand-in for {@code --hairline}. */
    private static final String BORDER = "#222a3a";
    /** Slightly brighter divider for inset panels. */
    private static final String BORDER_STRONG = "#2b3346";
    /** Headline text — {@code --text-strong}. */
    private static final String TEXT_STRONG = "#f3f5f9";
    /** Body copy — {@code --text}. */
    private static final String TEXT = "#c3cad9";
    /** Secondary copy — {@code --text-muted}. */
    private static final String MUTED = "#8a93a6";
    /** Footer / legal copy — {@code --text-faint}. */
    private static final String FAINT = "#5b6477";
    /** Primary action colour — {@code --accent} ("electric iris"). */
    private static final String ACCENT = "#6b5bff";
    /** Lighter accent used for the brand wordmark — {@code --accent-strong}. */
    private static final String ACCENT_SOFT = "#8674ff";
    /** Warning rule colour for security callouts — {@code --danger}. */
    private static final String DANGER = "#fb7185";

    /**
     * Web-safe font stack. The app's IBM Plex Sans is self-hosted and therefore unavailable to a
     * mail client, so this resolves to each platform's native UI face instead of shipping a webfont
     * that most clients would refuse to load anyway.
     */
    private static final String FONT =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";

    /** Monospace stack for verification codes, where digit alignment carries meaning. */
    private static final String FONT_MONO =
            "'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace";

    /** Static-only utility. */
    private EmailTemplate() {
    }

    /**
     * Starts a new email body.
     *
     * @return a fresh {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Escapes the five XML-significant characters so caller-supplied text cannot break out of the
     * surrounding markup.
     * <p>
     * Written by hand rather than pulled from a library: {@code commons-lang3} removed
     * {@code StringEscapeUtils} (it moved to {@code commons-text}, which this project does not
     * depend on), and adding a dependency for one five-character substitution is not a trade worth
     * making. Single quotes are escaped too because attribute values here are single-quote-free but
     * future edits may not be.
     *
     * @param value raw text, possibly {@code null}
     * @return the escaped text, or an empty string when {@code value} is {@code null}
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Fluent accumulator for one email body.
     * <p>
     * Blocks are appended in call order and rendered into the shell by {@link #build()}, so the
     * reading order of the calling code is the reading order of the finished email. Each method
     * escapes its inputs immediately, which means an escaped fragment is never re-escaped later.
     */
    public static final class Builder {

        /** Rendered HTML fragments, in the order they will appear inside the card. */
        private final List<String> blocks = new ArrayList<>();

        /** Hidden preview line shown by inbox list views next to the subject. */
        private String preheader = "";

        /** Small uppercase label above the heading, naming the kind of message this is. */
        private String eyebrow = "";

        /** The email's headline. */
        private String heading = "";

        /**
         * Sets the preheader — the snippet an inbox shows after the subject line.
         * <p>
         * Without one, clients fall back to scraping the first visible text, which for a branded
         * layout is usually the wordmark or the greeting; supplying it explicitly is the difference
         * between "TesseraApp Bob" and "Confirm your email address to activate your account."
         *
         * @param text plain sentence, escaped on the way in
         * @return this builder
         */
        public Builder preheader(String text) {
            this.preheader = escape(text);
            return this;
        }

        /**
         * Sets the small uppercase label rendered above the heading.
         *
         * @param text short category label, e.g. {@code "Account activation"}
         * @return this builder
         */
        public Builder eyebrow(String text) {
            this.eyebrow = escape(text);
            return this;
        }

        /**
         * Sets the email's headline.
         *
         * @param text headline copy
         * @return this builder
         */
        public Builder heading(String text) {
            this.heading = escape(text);
            return this;
        }

        /**
         * Appends a body paragraph.
         *
         * @param text paragraph copy
         * @return this builder
         */
        public Builder paragraph(String text) {
            blocks.add("<p style=\"margin:0 0 16px 0;font-family:" + FONT + ";font-size:15px;"
                    + "line-height:1.65;color:" + TEXT + ";\">" + escape(text) + "</p>");
            return this;
        }

        /**
         * Appends the primary call-to-action button plus a copyable plain-URL fallback.
         * <p>
         * The fallback is not optional politeness: corporate gateways and text-mode clients
         * routinely strip or fail to render anchor styling, and a verification email whose only
         * affordance is an unrendered button is a dead end. {@code word-break} is set so a long
         * UUID URL wraps inside the card instead of forcing horizontal scroll on mobile.
         *
         * @param label button text
         * @param url   absolute destination URL
         * @return this builder
         */
        public Builder button(String label, String url) {
            String safeUrl = escape(url);
            blocks.add(
                    "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                            + "style=\"margin:26px 0 20px 0;\"><tr>"
                            + "<td align=\"center\" bgcolor=\"" + ACCENT + "\" style=\"border-radius:999px;\">"
                            + "<a href=\"" + safeUrl + "\" style=\"display:inline-block;padding:13px 30px;"
                            + "font-family:" + FONT + ";font-size:15px;font-weight:600;color:#ffffff;"
                            + "text-decoration:none;border-radius:999px;\">" + escape(label) + "</a>"
                            + "</td></tr></table>"
                            + "<p style=\"margin:0 0 4px 0;font-family:" + FONT + ";font-size:12px;"
                            + "line-height:1.5;color:" + MUTED + ";\">"
                            + "If the button does not work, paste this link into your browser:</p>"
                            + "<p style=\"margin:0 0 8px 0;font-family:" + FONT_MONO + ";font-size:12px;"
                            + "line-height:1.6;color:" + ACCENT_SOFT + ";word-break:break-all;\">"
                            + "<a href=\"" + safeUrl + "\" style=\"color:" + ACCENT_SOFT + ";"
                            + "text-decoration:underline;\">" + safeUrl + "</a></p>");
            return this;
        }

        /**
         * Appends a one-time code rendered as a large monospaced chip.
         * <p>
         * Displayed rather than linked because the recipient has to retype it into a screen they
         * already have open — the whole point of a second factor is that the code travels on a
         * different channel from the session consuming it.
         *
         * @param code the numeric or alphanumeric one-time code
         * @return this builder
         */
        public Builder code(String code) {
            blocks.add("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                    + "border=\"0\" style=\"margin:22px 0;\"><tr><td align=\"center\" bgcolor=\"" + INSET + "\" "
                    + "style=\"border:1px solid " + BORDER_STRONG + ";border-radius:12px;padding:18px 12px;"
                    + "font-family:" + FONT_MONO + ";font-size:30px;font-weight:600;letter-spacing:0.28em;"
                    + "color:" + TEXT_STRONG + ";\">" + escape(code) + "</td></tr></table>");
            return this;
        }

        /**
         * Appends a muted informational note, for expiry rules and similar fine print.
         *
         * @param text note copy
         * @return this builder
         */
        public Builder note(String text) {
            blocks.add("<p style=\"margin:0 0 12px 0;font-family:" + FONT + ";font-size:13px;"
                    + "line-height:1.6;color:" + MUTED + ";\">" + escape(text) + "</p>");
            return this;
        }

        /**
         * Appends a security callout — a left-ruled panel in the danger colour.
         * <p>
         * Reserved for the "if this wasn't you" instruction. It is visually separated from the body
         * because that sentence is the only part of a security email a compromised user must not
         * skim past.
         *
         * @param text warning copy
         * @return this builder
         */
        public Builder warning(String text) {
            blocks.add("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                    + "border=\"0\" style=\"margin:22px 0 4px 0;\"><tr>"
                    + "<td bgcolor=\"" + INSET + "\" style=\"border-left:3px solid " + DANGER + ";"
                    + "border-radius:8px;padding:14px 16px;font-family:" + FONT + ";font-size:13px;"
                    + "line-height:1.65;color:" + TEXT + ";\">" + escape(text) + "</td>"
                    + "</tr></table>");
            return this;
        }

        /**
         * Renders the accumulated blocks into the branded shell.
         * <p>
         * The shell is a centred 560px card on a dark backdrop, preceded by the wordmark and
         * followed by a footer disclaiming the automated sender. {@code color-scheme: dark light}
         * tells clients that honour it not to apply their own dark-mode colour inversion on top of
         * an already-dark design, which is what otherwise turns the card washed-out grey in
         * Apple Mail.
         *
         * @return a complete, standalone HTML document ready to be used as the {@code text/html}
         *         part of a {@code multipart/alternative} message
         */
        public String build() {
            StringBuilder html = new StringBuilder(2048);

            html.append("<!DOCTYPE html><html lang=\"en\"><head>")
                    .append("<meta charset=\"utf-8\">")
                    .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                    .append("<meta name=\"color-scheme\" content=\"dark light\">")
                    .append("<meta name=\"supported-color-schemes\" content=\"dark light\">")
                    .append("<title>").append(heading).append("</title>")
                    .append("</head>")
                    .append("<body style=\"margin:0;padding:0;background-color:").append(BG).append(";\">");

            // Hidden preview text. The trailing zero-width spaces stop clients from padding the
            // snippet with whatever markup follows, a long-standing Gmail behaviour.
            html.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;")
                    .append("mso-hide:all;\">").append(preheader)
                    .append("&#8203;&#8203;&#8203;&#8203;&#8203;</div>");

            html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                    .append("border=\"0\" bgcolor=\"").append(BG)
                    .append("\" style=\"background-color:").append(BG).append(";padding:32px 12px;\">")
                    .append("<tr><td align=\"center\">")
                    .append("<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" ")
                    .append("border=\"0\" style=\"width:100%;max-width:560px;\">");

            // Wordmark. Rendered as text rather than an image so it survives the default
            // "images are blocked until you trust this sender" state of most clients.
            html.append("<tr><td style=\"padding:0 4px 18px 4px;font-family:").append(FONT)
                    .append(";font-size:17px;font-weight:700;letter-spacing:-0.01em;color:")
                    .append(TEXT_STRONG).append(";\">Tessera<span style=\"color:").append(ACCENT_SOFT)
                    .append(";\">App</span></td></tr>");

            html.append("<tr><td bgcolor=\"").append(CARD).append("\" style=\"background-color:")
                    .append(CARD).append(";border:1px solid ").append(BORDER)
                    .append(";border-radius:16px;padding:32px 30px;\">");

            if (!eyebrow.isEmpty()) {
                html.append("<p style=\"margin:0 0 10px 0;font-family:").append(FONT)
                        .append(";font-size:11px;font-weight:600;letter-spacing:0.14em;")
                        .append("text-transform:uppercase;color:").append(ACCENT_SOFT).append(";\">")
                        .append(eyebrow).append("</p>");
            }
            if (!heading.isEmpty()) {
                html.append("<h1 style=\"margin:0 0 18px 0;font-family:").append(FONT)
                        .append(";font-size:23px;line-height:1.3;font-weight:600;color:")
                        .append(TEXT_STRONG).append(";\">").append(heading).append("</h1>");
            }
            blocks.forEach(html::append);

            html.append("</td></tr>");

            html.append("<tr><td style=\"padding:20px 4px 0 4px;font-family:").append(FONT)
                    .append(";font-size:12px;line-height:1.6;color:").append(FAINT).append(";\">")
                    .append("This is an automated message from TesseraApp — please do not reply.")
                    .append("</td></tr>");

            html.append("</table></td></tr></table></body></html>");
            return html.toString();
        }
    }
}
