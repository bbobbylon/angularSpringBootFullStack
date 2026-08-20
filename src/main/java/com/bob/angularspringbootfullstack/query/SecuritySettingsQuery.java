package com.bob.angularspringbootfullstack.query;

/**
 * Named-parameter SQL constants for the {@code securitysettings} table — the single pinned row
 * (id = 1) an admin uses to override the env-driven anomaly detection defaults at runtime. See
 * {@code schema.sql}'s "Security settings" block for why the row is seeded with {@code INSERT
 * IGNORE} instead of the {@code ON DUPLICATE KEY UPDATE} the rest of this codebase's seeds use.
 */
public class SecuritySettingsQuery {

    /**
     * Reads the single settings row. Never returns no rows in a correctly-booted application —
     * {@code schema.sql} guarantees id 1 exists — but {@link com.bob.angularspringbootfullstack.service.serviceimpl.SecuritySettingsServiceImpl}
     * still treats an empty result as "no override on record" rather than throwing, so a
     * mid-migration database never blocks a login on a missing settings row.
     */
    public static final String SELECT_SECURITY_SETTINGS_QUERY =
            "SELECT id, anomaly_enabled, anomaly_history_limit, updated_at, updated_by FROM securitysettings WHERE id = 1";

    /**
     * Overwrites both override columns and stamps who changed them and when.
     * Parameters: anomalyEnabled (nullable), anomalyHistoryLimit (nullable), updatedBy.
     *
     * <p>Both value columns are always written, including as {@code NULL} — a {@code PATCH} with a
     * null field means "clear this override back to the env default", not "leave it alone", so the
     * statement has no partial-update branch to get that distinction wrong.
     */
    public static final String UPDATE_SECURITY_SETTINGS_QUERY =
            "UPDATE securitysettings SET anomaly_enabled = :anomalyEnabled, anomaly_history_limit = :anomalyHistoryLimit, "
                    + "updated_at = NOW(), updated_by = :updatedBy WHERE id = 1";
}
