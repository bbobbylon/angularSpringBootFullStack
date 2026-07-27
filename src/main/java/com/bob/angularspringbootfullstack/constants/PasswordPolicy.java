package com.bob.angularspringbootfullstack.constants;

/**
 * The single definition of what counts as an acceptable password (NFR-SEC).
 *
 * <p>Password rules were previously expressed as a bare {@code @Size(min = 8)} on the registration
 * model, and nowhere at all on the two forms that <em>change</em> a password. A user could register
 * with an eight-character password, then immediately reset it to {@code "1"} — the reset path
 * enforced only {@code @NotEmpty}. Centralising the rule here is what makes the three entry points
 * (register, change, reset) agree, so strength cannot be bypassed by choosing a different door.
 *
 * <h3>Why this shape of rule</h3>
 * The pattern requires length plus a mix of character classes. That is a deliberately modest bar:
 * composition rules are a weak proxy for real strength — {@code "Passw0rd!"} satisfies every class
 * requirement and is among the first guesses any attacker makes — but they do eliminate the
 * genuinely trivial cases ({@code "12345678"}, {@code "password"}) that dominate real credential
 * stuffing lists.
 *
 * <p>The strong version of this control is a breach-corpus check (Have I Been Pwned's
 * k-anonymity API: send the first five characters of the SHA-1 hash, match the remainder locally,
 * so the password never leaves the server). That catches {@code "Passw0rd!"} precisely because
 * composition rules do not. It is tracked as follow-on work rather than done here because it adds
 * an outbound network dependency to the registration path, which needs its own timeout and
 * fail-open policy decision.
 *
 * <p>Deliberately NOT enforced: a maximum length (BCrypt truncates beyond 72 bytes, but rejecting
 * long passphrases pushes users toward shorter ones), and forced rotation (NIST 800-63B advises
 * against it — it produces predictable increments).
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /** Minimum acceptable length. */
    public static final int MIN_LENGTH = 8;

    /**
     * Requires at least one lowercase letter, one uppercase letter, and one digit, with no
     * whitespace, over at least {@link #MIN_LENGTH} characters.
     *
     * <p>Built from lookaheads so the classes can appear in any order — a rule that demanded a
     * particular arrangement would reject perfectly good passwords for no security reason.
     * Symbols are allowed and encouraged but not required: mandating them measurably pushes users
     * toward the same handful of predictable substitutions ({@code a→@}, {@code s→$}) that
     * attackers enumerate first.
     */
    public static final String PATTERN = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)\\S{" + MIN_LENGTH + ",}$";

    /**
     * The message shown when {@link #PATTERN} rejects a password.
     *
     * <p>States the whole rule up front rather than reporting one failure at a time, so a user is
     * not walked through three successive rejections to discover requirements that could have been
     * shown once.
     */
    public static final String MESSAGE =
            "Password must be at least " + MIN_LENGTH + " characters and include an uppercase letter, "
                    + "a lowercase letter, and a number, with no spaces.";
}
