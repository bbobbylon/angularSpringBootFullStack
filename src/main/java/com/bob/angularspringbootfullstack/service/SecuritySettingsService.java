package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.SecuritySettings;

/**
 * Read/write access to the single admin-tunable {@code securitysettings} row (FUTURE-ENHANCEMENTS
 * "Anomaly signal tuning UI"), consulted live by {@link com.bob.angularspringbootfullstack.service.serviceimpl.LoginRiskServiceImpl#assess}
 * on every login rather than cached at startup, so a change from the settings panel takes effect
 * on the very next sign-in without a redeploy.
 */
public interface SecuritySettingsService {

    /**
     * The current settings row. Every override field may be {@code null}, meaning "use the
     * {@code app.security.anomaly.*} default" — callers resolve that fallback themselves rather
     * than this method baking in {@code application.yml} values, which keeps this service ignorant
     * of what its only caller today happens to default to.
     *
     * @return the current settings; never {@code null} even if the row is somehow missing (see
     *         {@link com.bob.angularspringbootfullstack.service.serviceimpl.SecuritySettingsServiceImpl}
     *         for how that case degrades to "no overrides on record")
     */
    SecuritySettings getSettings();

    /**
     * Overwrites all three overrides. Passing {@code null} for any of them clears that override back
     * to its env default — this is a full replace, not a partial patch, so a caller that wants to
     * change only one field must resend the other two's current values.
     *
     * @param anomalyEnabled       the new override, or {@code null} to clear it
     * @param anomalyHistoryLimit  the new override, or {@code null} to clear it
     * @param maxConcurrentSessions the new override, or {@code null} to clear it; {@code <= 0} means
     *                              "no cap", the same as leaving it unset
     * @param updatedBy            id of the administrator making the change, for the audit columns
     * @return the settings row as persisted
     */
    SecuritySettings updateSettings(Boolean anomalyEnabled, Integer anomalyHistoryLimit, Integer maxConcurrentSessions, Long updatedBy);
}
