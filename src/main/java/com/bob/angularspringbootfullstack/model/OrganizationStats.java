package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * The per-organization KPI row backing the dashboard-style Organizations page's card grid and
 * mini stat tiles ({@code GET /admin/organization/{id}/stats}).
 *
 * <p>{@link #stats} and {@link #statusBreakdown} are the exact same shapes
 * {@code AnalyticsController#getSummary} already returns, just narrowed to one organization's id
 * via {@code CustomerService}'s existing {@code *ForOrganizations} methods — this is not a new
 * aggregation, only a single-organization view of data the app already computes.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class OrganizationStats {
    /** Number of users holding an ACTIVE membership in this organization. */
    private int memberCount;
    /** This organization's customer/invoice/revenue totals. */
    private Stats stats;
    /** This organization's per-status customer counts. */
    private Map<String, Integer> statusBreakdown;
}
