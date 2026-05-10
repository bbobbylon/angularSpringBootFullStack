package com.bob.angularspringbootfullstack.rowmapper;

import com.bob.angularspringbootfullstack.model.Stats;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class StatsRowMapper implements RowMapper<Stats> {
    /**
     * Maps a single database row to a Role object.
     * <p>
     * This method is called by Spring JDBC for each row in the query result.
     * It extracts values from the ResultSet and builds a Role object using
     * the builder pattern provided by Lombok's @SuperBuilder annotation.
     * <p>
     * Column mappings:
     * - Database: Java field
     * - id → id
     * - name → name
     * - permission → permission
     *
     * @param resultSet the SQL result set positioned at the current row
     * @param rowNum    the row number (0-indexed)
     * @return a fully initialized Role object
     * @throws SQLException if database access error occurs
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
