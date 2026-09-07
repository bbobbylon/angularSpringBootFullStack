package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Request body for {@code PATCH /admin/security/anomaly-settings}.
 *
 * <p>Despite the class and endpoint name, this form now also carries {@link #maxConcurrentSessions}
 * — a knob unrelated to anomaly detection that rides the same single-row {@code securitysettings}
 * table (see that model's Javadoc) rather than getting its own table and endpoint pair for one
 * field. A rename was deliberately not taken on with this feature: it would ripple into
 * {@code CapabilityCatalog}'s 403 message keys, the Postman/Bruno/cURL API-testing collections, and
 * {@code documentation/GUIDE.md}, none of which this change otherwise touches. Revisit the name only
 * if a third unrelated knob shows up and the "anomaly" label becomes actively misleading rather than
 * just imprecise.
 *
 * <p>All three fields are nullable by design — unlike {@code SettingsForm}, {@code null} here is a
 * meaningful value ("clear this override, fall back to the env default"), not a validation
 * failure, so none of them carries {@code @NotNull}. {@link Min}/{@link Max} are skipped by Bean
 * Validation for a {@code null} value, so a caller clearing one field is unaffected by the range
 * check that guards an actual override on another.
 */
@Data
public class AnomalySettingsForm {

    /** {@code null} clears the override; otherwise whether anomaly detection should run. */
    private Boolean enabled;

    /**
     * {@code null} clears the override; otherwise how many recent logins {@code LoginRiskServiceImpl}
     * compares a new sign-in against. Bounded to a sane range — zero would make every login look
     * like a first-ever login (never flagged), and an unbounded value would let an admin turn a
     * per-login check into an unbounded table scan.
     */
    @Min(value = 1, message = "History limit must be at least 1")
    @Max(value = 500, message = "History limit cannot exceed 500")
    private Integer historyLimit;

    /**
     * {@code null} clears the override; otherwise the cap {@code SessionServiceImpl} enforces on
     * login. {@code 0} is accepted as "no cap" (the same as {@code null}) rather than rejected,
     * because a numeric input's natural empty/reset state is {@code 0}, and refusing it would force
     * the admin UI to special-case clearing this one field differently from the two above. Capped at
     * 50 — a real user's browsers/devices count is small, and an admin who wants "effectively
     * unlimited" should clear the override, not encode "unlimited" as a very large number.
     */
    @Min(value = 0, message = "Max concurrent sessions cannot be negative")
    @Max(value = 50, message = "Max concurrent sessions cannot exceed 50")
    private Integer maxConcurrentSessions;
}
