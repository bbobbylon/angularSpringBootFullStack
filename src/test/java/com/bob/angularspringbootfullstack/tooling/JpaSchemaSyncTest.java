package com.bob.angularspringbootfullstack.tooling;

import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.InvoiceLineItem;
import com.bob.angularspringbootfullstack.model.Services;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard: keeps {@code src/main/resources/schema.sql} in lockstep with the JPA-managed
 * entities (Customer / Invoice / Services and the InvoiceLineItem element collection).
 * <p>
 * <b>Why this exists.</b> Production runs {@code spring.jpa.hibernate.ddl-auto: validate}
 * (application-prod.yml), so Hibernate refuses to boot if a mapped table or column is missing from
 * the live schema — and that schema is initialised by hand from {@code schema.sql}. Because the app
 * sets {@code globally_quoted_identifiers = true}, the expected columns are quoted camelCase
 * ({@code `phoneNumber`}, {@code `invoiceNumber`}, …), which are easy to get wrong by hand. This
 * test asks Hibernate itself for the authoritative DDL (offline schema export, no database) and
 * asserts {@code schema.sql} contains every generated table and column. If someone adds an entity
 * field without updating {@code schema.sql}, this fails here — at build time — instead of at the
 * next production deploy.
 * <p>
 * It is also the documented, reproducible source of the DDL that was transplanted into
 * {@code schema.sql}: run it, read {@code target/generated-jpa-schema.sql}, copy the create-table
 * statements (as {@code CREATE TABLE IF NOT EXISTS}, FKs inlined for idempotency).
 */
class JpaSchemaSyncTest {

    private static final Path SCHEMA_SQL = Path.of("src", "main", "resources", "schema.sql");

    /** Matches every backtick-quoted identifier (table or column name) in the generated DDL. */
    private static final Pattern QUOTED_IDENTIFIER = Pattern.compile("`([A-Za-z0-9_]+)`");

    @Test
    @DisplayName("schema.sql contains every table and column Hibernate maps for the JPA entities")
    void schemaSqlMatchesHibernateMapping() throws IOException {
        String generatedDdl = generateCreateTableDdl();
        String schemaSql = Files.readString(SCHEMA_SQL);

        // Collect the quoted identifiers from the CREATE TABLE statements only. Hibernate emits its
        // foreign keys as separate ALTER TABLE statements with random constraint names (e.g.
        // `FK5v0hotbf...`) that we intentionally replaced with our own stable names in schema.sql,
        // so anything from the first "alter table" onward is excluded from the comparison.
        int alterIndex = generatedDdl.indexOf("alter table");
        String createSection = alterIndex >= 0 ? generatedDdl.substring(0, alterIndex) : generatedDdl;

        Set<String> expected = new LinkedHashSet<>();
        Matcher matcher = QUOTED_IDENTIFIER.matcher(createSection);
        while (matcher.find()) {
            expected.add(matcher.group(1));
        }
        assertTrue(expected.size() >= 20,
                "Expected to extract the JPA tables + columns from the generated DDL, got: " + expected);

        for (String identifier : expected) {
            assertTrue(schemaSql.contains("`" + identifier + "`"),
                    "schema.sql is missing the quoted identifier `" + identifier + "` that Hibernate's "
                            + "validate will require. Regenerate from target/generated-jpa-schema.sql "
                            + "(see " + getClass().getSimpleName() + ").");
        }
    }

    /**
     * Drives the JPA-standard schema-script generation
     * ({@code jakarta.persistence.schema-generation.scripts.*}) with the dialect pinned and
     * {@code allow_jdbc_metadata_access=false}, so a CREATE script is produced WITHOUT opening any
     * database connection. {@code globally_quoted_identifiers=true} mirrors the runtime so the
     * emitted identifiers match what {@code validate} expects.
     *
     * @return the generated CREATE/ALTER DDL as text
     */
    private static String generateCreateTableDdl() throws IOException {
        Path out = Path.of("target", "generated-jpa-schema.sql");
        Files.deleteIfExists(out);

        Map<String, Object> settings = new HashMap<>();
        settings.put(AvailableSettings.DIALECT, "org.hibernate.dialect.MySQLDialect");
        settings.put(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS, "true");
        settings.put(AvailableSettings.FORMAT_SQL, "true");
        settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
        settings.put("jakarta.persistence.schema-generation.scripts.create-target", out.toString());
        settings.put("hibernate.hbm2ddl.delimiter", ";");
        settings.put("hibernate.boot.allow_jdbc_metadata_access", "false");

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(settings)
                .build();
        try {
            Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(Customer.class)
                    .addAnnotatedClass(Invoice.class)
                    .addAnnotatedClass(Services.class)
                    .addAnnotatedClass(InvoiceLineItem.class)
                    .buildMetadata();
            try (SessionFactory ignored = metadata.buildSessionFactory()) {
                return Files.readString(out);
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
