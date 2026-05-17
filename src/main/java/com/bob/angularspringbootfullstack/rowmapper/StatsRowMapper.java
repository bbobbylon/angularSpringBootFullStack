package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.Stats;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps a single JDBC result row from {@link com.bob.angularspringbootfullstack.query.CustomerQuery#STATS_QUERY}
 * to a {@link Stats} object.
 * <p>
 * Spring JDBC calls this once per row; the Stats query always returns exactly one row.
 */
public class StatsRowMapper implements RowMapper<Stats> {

    /**
     * Maps the current result-set row to a {@link Stats} instance.
     * <p>
     * Column mappings:
     * <ul>
     *   <li>{@code total_customers} → {@link Stats#getTotalCustomers()}</li>
     *   <li>{@code total_invoices}  → {@link Stats#getTotalInvoices()}</li>
     *   <li>{@code total_billed}    → {@link Stats#getTotalBilled()}</li>
     * </ul>
     *
     * @param resultSet the SQL result set positioned at the current row
     * @param rowNum    the 0-based index of the current row
     * @return a fully populated {@link Stats} object
     * @throws SQLException if any column cannot be read from the result set
     */
    @Override
    public Stats mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return Stats.builder()
                .totalCustomers(resultSet.getInt("total_customers"))
                .totalInvoices(resultSet.getInt("total_invoices"))
                .totalBilled(resultSet.getDouble("total_billed"))
                .build();
    }
}
