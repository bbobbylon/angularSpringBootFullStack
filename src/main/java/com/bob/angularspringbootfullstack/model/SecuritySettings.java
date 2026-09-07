package com.bob.angularspringbootfullstack.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The single pinned row of {@code securitysettings} — admin-tunable overrides for the anomaly
 * detection knobs that {@link com.bob.angularspringbootfullstack.service.serviceimpl.LoginRiskServiceImpl}
 * otherwise reads only from {@code app.security.anomaly.*} at startup (SRS §3.1, FUTURE-ENHANCEMENTS
 * "Anomaly signal tuning UI").
 *
 * <p>Every field here is nullable and a {@code null} means "no override" — the caller falls back to
 * the env-driven default rather than treating {@code null} as a value in its own right. That is what
 * lets an admin clear an override from the settings panel and hand control back to
 * {@code application.yml} without this table needing a third "unset" sentinel alongside true/false.
 *
 * @see com.bob.angularspringbootfullstack.service.SecuritySettingsService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecuritySettings {
    private Long id;
    /** {@code null} = use {@code app.security.anomaly.enabled}; otherwise the admin's override. */
    private Boolean anomalyEnabled;
    /** {@code null} = use {@code app.security.anomaly.history-limit}; otherwise the admin's override. */
    private Integer anomalyHistoryLimit;
    /**
     * {@code null} = use {@code app.security.max-concurrent-sessions}; otherwise the admin's
     * override. Read by {@code SessionServiceImpl}, not the anomaly-detection classes above — this
     * row is shared by two unrelated admin-tunable knobs, not just anomaly detection, because a
     * second single-row settings table would duplicate the exact "one row, no key to look up" shape
     * this one already has. Either value, a value {@code <= 0} means "no cap" (unlimited), the same
     * failure-mode-safe default as leaving the field unset — an admin fat-fingering {@code 0} turns
     * the feature off rather than revoking every session on the next login.
     */
    private Integer maxConcurrentSessions;
    private LocalDateTime updatedAt;
    /** Id of the administrator who last changed this row; {@code null} if never edited. */
    private Long updatedBy;
}
