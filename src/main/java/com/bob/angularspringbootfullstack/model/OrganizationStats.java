package com.bob.angularspringbootfullstack.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * The per-organization KPI row backing the dashboard-style Organizations page's card grid and
 * mini stat tiles ({@code GET /admin/organization/{id}/stats}).
 *
 * <p>{@link #stats} and {@link #statusBreakdown} are the exact same shapes
 * {@code AnalyticsController#getSummary} already returns, just narrowed to one organization's id
 * via {@code CustomerService}'s existing {@code *ForOrganizations} methods — this is not a new
 * aggregation, only a single-organization view of data the app already computes.
 *
 * <p><b>Deliberately no {@code @JsonInclude(NON_DEFAULT)}</b> (removed 2026-08-28, bug found live
 * on tesseraapp.dev): a brand-new or genuinely empty organization has {@code memberCount == 0}
 * and an all-zero {@link #stats} — the exact Java default for every field here, which
 * {@code NON_DEFAULT} silently dropped from the JSON. The Angular admin page's
 * {@code OrganizationInterface.memberCount: number} (never optional) then read {@code undefined}
 * and rendered a blank "Total Members" line instead of "0 Total Members". A zero here is real,
 * reportable data, not an absent/inapplicable value — see the identical fix on {@link Stats},
 * which this class embeds.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationStats {
    /** Number of users holding an ACTIVE membership in this organization. */
    private int memberCount;
    /** This organization's customer/invoice/revenue totals. */
    private Stats stats;
    /** This organization's per-status customer counts. */
    private Map<String, Integer> statusBreakdown;
}
