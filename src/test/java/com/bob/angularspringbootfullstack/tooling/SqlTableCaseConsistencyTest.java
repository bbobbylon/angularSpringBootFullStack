package com.bob.angularspringbootfullstack.tooling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Portability guard: every table named in a {@code query/*Query} SQL constant must be spelled
 * exactly as {@code src/main/resources/schema.sql} declares it, character for character.
 * <p>
 * <b>Why this exists.</b> Twice now a query has shipped with the wrong capitalisation and passed
 * every local check before failing in production. MySQL decides table-name case-sensitivity from
 * {@code lower_case_table_names}, whose default differs by platform: {@code 1} on Windows (names are
 * folded to lowercase on disk and compared case-insensitively) and {@code 0} on Linux (names are
 * stored verbatim and compared case-sensitively). A developer machine running native Windows MySQL
 * therefore resolves {@code users}, {@code Users}, and {@code USERS} to the same table, while the
 * Linux-hosted deployment target (Aiven) resolves only the exact spelling and answers anything else
 * with {@code Table 'db.x' doesn't exist}. The mistake is invisible until the query runs against the
 * real server, at which point it surfaces as a 500 on a live endpoint.
 * <p>
 * The mismatch is easy to make here because this schema is deliberately split in two. The JDBC-owned
 * tables are plain lowercase ({@code users}, {@code roles}, {@code userroles}, {@code userevents},
 * …), whereas the JPA-owned tables are quoted <i>capitalised</i> ({@code `Customer`},
 * {@code `Invoice`}, {@code `Services`}) because Hibernate derives their names from the entity class
 * names and {@code globally_quoted_identifiers: true} makes it emit them quoted — see
 * {@link JpaSchemaSyncTest}, which guards the columns of that same half. So the correct convention
 * depends on which half of the domain a query touches, and there is no single rule a reviewer can
 * apply by eye. This test applies the only rule that is actually true: match the DDL.
 * <p>
 * <b>Why reflection rather than reading the source.</b> The constants are read off the compiled
 * classes, so the strings examined are exactly what reaches the database — including compiler-folded
 * concatenation, which lets a query be split across source lines without a table name being cut in
 * half. Scanning the {@code .java} text instead would also match prose: the surrounding Javadoc in
 * this package legitimately contains phrases like "performs a JOIN across users" and
 * {@code FROM customer} used as a counter-example, both of which would register as fake violations.
 * <p>
 * Column names need no such guard — MySQL compares those case-insensitively on every platform.
 *
 * @see JpaSchemaSyncTest the sibling guard, which keeps schema.sql's columns in step with the JPA
 * entity mappings
 */
class SqlTableCaseConsistencyTest {

    /** The hand-applied schema; the single source of truth for what tables exist and how they are spelled. */
    private static final Path SCHEMA_SQL = Path.of("src", "main", "resources", "schema.sql");

    /** Source directory scanned to discover the query classes, so a newly added one is covered automatically. */
    private static final Path QUERY_SOURCE_DIR = Path.of(
            "src", "main", "java", "com", "bob", "angularspringbootfullstack", "query");

    /** Package the discovered file names are resolved against to load the compiled classes. */
    private static final String QUERY_PACKAGE = "com.bob.angularspringbootfullstack.query";

    /** Captures the table name from each {@code CREATE TABLE [IF NOT EXISTS] [`]name[`]} in the schema. */
    private static final Pattern DECLARED_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Captures the table name following a {@code FROM}, {@code JOIN}, {@code INTO} or {@code UPDATE}.
     * <p>
     * A derived table ({@code FROM (SELECT ...)}) needs no special handling: {@code (} cannot start
     * the identifier group, so the match simply fails at that position and the scan continues into
     * the subquery, where the real table reference is picked up instead.
     */
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "\\b(?:FROM|JOIN|INTO|UPDATE)\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("every table named in a *Query constant matches schema.sql's exact spelling")
    void queryConstantsSpellTableNamesAsTheSchemaDeclaresThem() throws Exception {
        Set<String> declaredTables = declaredTables();
        // A regex that silently stopped matching would make this test vacuously green, which is the
        // one failure mode a guard like this must not have. Assert the extraction found a plausible
        // schema before drawing any conclusion from it.
        assertTrue(declaredTables.size() >= 15,
                "Expected to extract the CREATE TABLE names from " + SCHEMA_SQL + ", got: " + declaredTables);

        Map<String, Set<String>> referencesByConstant = tableReferencesByConstant();
        assertFalse(referencesByConstant.isEmpty(),
                "Expected to find SQL constants referencing tables in " + QUERY_PACKAGE
                        + "; found none, which means this guard is not actually inspecting anything.");

        List<String> violations = new ArrayList<>();
        referencesByConstant.forEach((constant, referencedTables) -> {
            for (String referenced : referencedTables) {
                if (declaredTables.contains(referenced)) {
                    continue;
                }
                String declaredSpelling = declaredTables.stream()
                        .filter(declared -> declared.equalsIgnoreCase(referenced))
                        .findFirst()
                        .orElse(null);
                violations.add(declaredSpelling != null
                        ? constant + " queries `" + referenced + "` but schema.sql declares `"
                        + declaredSpelling + "`. Case-insensitive on a developer's Windows MySQL, "
                        + "\"table doesn't exist\" on the Linux-hosted server."
                        : constant + " queries `" + referenced + "`, which schema.sql does not declare "
                        + "at all — either the table is missing from the schema or the name is a typo.");
            }
        });

        if (!violations.isEmpty()) {
            fail("SQL table names disagree with schema.sql:\n  - " + String.join("\n  - ", violations)
                    + "\n\nFix the constant to use the schema's exact spelling (see "
                    + getClass().getSimpleName() + " for why this only breaks in production).");
        }
    }

    /**
     * Collects the exact table names {@code schema.sql} creates.
     * <p>
     * Line comments are stripped first because the file documents its own idempotency strategy using
     * the literal DDL keywords ("every statement uses CREATE TABLE IF NOT EXISTS and …"), which the
     * pattern would otherwise read as declaring tables named {@code and} and {@code is}.
     *
     * @return every declared table name, backticks removed, in declaration order
     * @throws IOException if the schema file cannot be read
     */
    private static Set<String> declaredTables() throws IOException {
        String schemaSql = Files.readString(SCHEMA_SQL).replaceAll("(?m)--.*$", "");
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = DECLARED_TABLE.matcher(schemaSql);
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return tables;
    }

    /**
     * Reads every {@code static final String} constant off the compiled query classes and extracts
     * the tables each one touches.
     *
     * @return tables keyed by {@code SimpleClassName.CONSTANT_NAME}, so a failure names the exact
     * constant to edit; constants containing no table reference are omitted
     * @throws Exception if a query class cannot be loaded or a constant cannot be read
     */
    private static Map<String, Set<String>> tableReferencesByConstant() throws Exception {
        Map<String, Set<String>> referencesByConstant = new TreeMap<>();
        for (Class<?> queryClass : queryClasses()) {
            for (Field field : queryClass.getDeclaredFields()) {
                if (field.getType() != String.class
                        || !Modifier.isStatic(field.getModifiers())
                        || !Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                String sql = (String) field.get(null);
                if (sql == null) {
                    continue;
                }
                Set<String> tables = new LinkedHashSet<>();
                Matcher matcher = TABLE_REFERENCE.matcher(sql);
                while (matcher.find()) {
                    tables.add(matcher.group(1));
                }
                if (!tables.isEmpty()) {
                    referencesByConstant.put(queryClass.getSimpleName() + "." + field.getName(), tables);
                }
            }
        }
        return referencesByConstant;
    }

    /**
     * Discovers the query classes by listing the package's source directory rather than naming them
     * here, so a query class added later is covered without anyone remembering to register it — the
     * failure mode that would quietly reopen the very gap this test closes.
     *
     * @return the loaded query classes, in file-name order
     * @throws Exception if the directory cannot be listed or a class cannot be loaded
     */
    private static List<Class<?>> queryClasses() throws Exception {
        List<String> classNames;
        try (Stream<Path> sourceFiles = Files.list(QUERY_SOURCE_DIR)) {
            classNames = sourceFiles
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(".java"))
                    .map(fileName -> fileName.substring(0, fileName.length() - ".java".length()))
                    .sorted()
                    .toList();
        }
        assertFalse(classNames.isEmpty(), "No query sources found under " + QUERY_SOURCE_DIR);

        List<Class<?>> queryClasses = new ArrayList<>();
        for (String className : classNames) {
            queryClasses.add(Class.forName(QUERY_PACKAGE + "." + className));
        }
        return queryClasses;
    }
}
