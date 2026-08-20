package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Request body for {@code PATCH /admin/security/anomaly-settings}.
 *
 * <p>Both fields are nullable by design — unlike {@code SettingsForm}, {@code null} here is a
 * meaningful value ("clear this override, fall back to the env default"), not a validation
 * failure, so neither field carries {@code @NotNull}. {@link Min}/{@link Max} are skipped by Bean
 * Validation for a {@code null} value, so a caller clearing {@code historyLimit} is unaffected by
 * the range check that guards an actual override.
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
}
