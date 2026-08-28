package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.enumeration.OrgMfaMethod;
import com.bob.angularspringbootfullstack.model.Organization;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

/**
 * OrganizationRowMapper converts {@code organizations} table rows into {@link Organization}
 * objects, for {@code OrganizationRepoImpl}-style reads within {@code OrganizationServiceImpl}
 * (Organization CRUD — FUTURE-ENHANCEMENTS.md §3.2).
 * <p>
 * Mirrors {@link RoleRowMapper}'s shape: Spring JDBC calls {@link #mapRow} once per result row,
 * and Lombok's {@code @SuperBuilder} assembles the strongly typed object.
 */
public class OrganizationRowMapper implements RowMapper<Organization> {
    @Override
    public Organization mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return Organization.builder()
                .id(resultSet.getLong("id"))
                .name(resultSet.getString("name"))
                .status(resultSet.getString("status"))
                .description(resultSet.getString("description"))
                .contactEmail(resultSet.getString("contact_email"))
                .website(resultSet.getString("website"))
                .tenantUuid(resultSet.getString("tenant_uuid"))
                .mfaAllowedMethods(mfaMethodNames(resultSet.getString("mfa_allowed_methods")))
                .featureFlags(featureFlagLabels(resultSet.getString("feature_flags")))
                .createdAt(createdAt == null ? null : createdAt.toLocalDateTime())
                .build();
    }

    /**
     * Resolves the stored MFA-policy CSV through {@link OrgMfaMethod#parseCsv}, then back to plain
     * names for the model — the row mapper's job is presenting recognized values, not exposing the
     * enum type itself to callers that only want strings (matching {@link Organization}'s own
     * {@code Set<String>} field type).
     */
    private static Set<String> mfaMethodNames(String csv) {
        return OrgMfaMethod.parseCsv(csv).stream().map(Enum::name).collect(toSet());
    }

    /**
     * Splits the feature-flags CSV into its labels, unlike {@link #mfaMethodNames} not filtered
     * against any enum — these are free-form, so every non-blank token is kept as written.
     */
    private static List<String> featureFlagLabels(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(label -> !label.isBlank()).toList();
    }
}
