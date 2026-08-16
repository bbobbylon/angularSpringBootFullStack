package com.bob.angularspringbootfullstack.constants;

/**
 * The single definition of what counts as an acceptable phone number for SMS 2FA (NFR-SEC).
 * <p>
 * Mirrors {@link PasswordPolicy}: the rule was previously inlined directly on
 * {@code UpdateForm.phoneNumber} as {@code ^\+?[0-9. ()-]{7,25}$} — a pattern that validates almost
 * nothing. It accepts anything from 7 to 25 characters of digits and punctuation, so
 * {@code "1234567"} (7 digits) or a 20-digit string of zeros both pass. The only thing that
 * actually catches a malformed number is {@code SMSUtils.toE164US} at send time — by then the
 * value is already saved on the account and the failure surfaces as an unexplained missing text,
 * not a validation error the user can act on immediately.
 *
 * <h3>Why this shape of rule</h3>
 * US/Canada numbers only (matching {@code SMSUtils}'s hardcoded {@code +1} country code):
 * an optional country code ({@code 1} or {@code +1}), then exactly ten digits, with common
 * formatting characters (spaces, dashes, dots, parentheses) allowed between groups but not
 * required. This is a shape check, not a live-number check — it cannot know whether a
 * correctly-formatted number is actually reachable, only that it has the right number of digits
 * in the right places to become a valid E.164 US number once normalized.
 *
 * <p>Deliberately NOT enforced: North American Numbering Plan rules about which digits an area
 * code or exchange may start with. That is a real constraint but a moving one (the NANP
 * periodically opens new area codes), and getting it wrong would reject legitimate numbers for a
 * marginal gain over the digit-count check already doing the useful work.
 */
public final class PhonePolicy {

    private PhonePolicy() {
    }

    /**
     * Optional {@code 1} or {@code +1} country code, then three digits (optionally parenthesised),
     * then two more groups of three and four digits, each pair separated by an optional space,
     * dot, or dash. Every separator is independently optional so {@code "8084824518"},
     * {@code "(808) 482-4518"}, {@code "808-482-4518"}, and {@code "+18084824518"} all match, but
     * a string with the wrong digit count in any group — including the {@code "1234567"} case
     * that motivated this class — cannot.
     */
    public static final String PATTERN =
            "^(\\+?1[-.\\s]?)?\\(?[0-9]{3}\\)?[-.\\s]?[0-9]{3}[-.\\s]?[0-9]{4}$";

    /** The message shown when {@link #PATTERN} rejects a phone number. */
    public static final String MESSAGE =
            "Enter a valid 10-digit US phone number, e.g. (808) 482-4518.";
}
