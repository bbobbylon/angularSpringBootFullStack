package com.bob.angularspringbootfullstack.dto;

import com.bob.angularspringbootfullstack.enumeration.LoginRiskReason;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The verdict of the FR-TPF-1 anomaly check for one sign-in attempt.
 *
 * <p>Produced by {@link com.bob.angularspringbootfullstack.service.LoginRiskService} immediately
 * after the first factor succeeds and consumed by
 * {@link com.bob.angularspringbootfullstack.controller.UserController#login} to decide whether the
 * session may be issued straight away or must survive a step-up challenge first.
 *
 * <p><b>Never serialised to the client.</b> Unlike most DTOs in this package this one does not
 * appear in an {@code HttpResponse} body. Telling a caller "we flagged this as a new device" would
 * hand an attacker a free oracle for probing which of their stolen credentials look familiar to
 * the system; the client only ever sees the same neutral "enter your verification code" response
 * a normally-2FA-enabled user would get. The detail travels to the audit log and the account
 * owner's email instead — both channels the legitimate user controls.
 *
 * @param reasons the signals that fired; empty means the sign-in looked ordinary
 */
public record LoginRiskAssessment(List<LoginRiskReason> reasons) {

    /** Shared instance for the common case — an unremarkable sign-in. */
    public static final LoginRiskAssessment NONE = new LoginRiskAssessment(List.of());

    /** Canonicalises the reason list so the record is genuinely immutable. */
    public LoginRiskAssessment {
        reasons = List.copyOf(reasons);
    }

    /**
     * Whether this sign-in should be escalated to step-up re-verification.
     *
     * @return true when at least one risk signal fired
     */
    public boolean elevated() {
        return !reasons.isEmpty();
    }

    /**
     * A human-readable summary of the signals, for the audit row's {@code detail} column and the
     * step-up email body — e.g. {@code "a new device and a new network location"}.
     *
     * @return the joined reason labels, or an empty string when nothing fired
     */
    public String describe() {
        if (reasons.isEmpty()) {
            return "";
        }
        if (reasons.size() == 1) {
            return reasons.getFirst().getLabel();
        }
        // Two signals today; the join stays correct if more are ever added.
        List<String> labels = reasons.stream().map(LoginRiskReason::getLabel).collect(Collectors.toList());
        String last = labels.removeLast();
        return String.join(", ", labels) + " and " + last;
    }
}
