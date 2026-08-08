package com.bob.angularspringbootfullstack.tooling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Configuration guard: no JDBC connection string in this repository may leave the database
 * connection unencrypted, and the deployed profiles must refuse an unencrypted one outright.
 *
 * <p><b>The failure this exists to prevent.</b> The base datasource URL hardcoded
 * {@code useSSL=false} for most of this project's life. That is survivable against a MySQL on
 * {@code localhost} and indefensible against the managed database every deployed profile actually
 * points at, which is reached across the public internet — credentials and every row travelled in
 * plaintext. Nothing failed, nothing logged, and no other test in this suite would ever have noticed:
 * an unencrypted connection works perfectly. Only reading the configuration catches it, so that is
 * what this test does.
 *
 * <p><b>Why it checks the spelling and not just the intent.</b> {@code useSSL}/{@code requireSSL} are
 * the <em>legacy</em> switches. Connector/J 8.0.13+ replaced them with the single {@code sslMode}
 * enum, and the old pair is honoured only while {@code sslMode} is absent — so a file that says
 * {@code useSSL=true} looks secure in review while being one added parameter away from meaning
 * nothing. Requiring the modern spelling everywhere removes the ambiguity rather than documenting it.
 *
 * <p>The ladder, weakest first: {@code DISABLED}, {@code PREFERRED} (encrypt when offered, silently
 * fall back when not), {@code REQUIRED} (refuse to connect without TLS, but do not validate the
 * certificate), {@code VERIFY_CA}, {@code VERIFY_IDENTITY}. The base profile defaults to
 * {@code PREFERRED} so a bare local MySQL with no certificate still starts; the deployed profiles pin
 * {@code REQUIRED}, because a silent downgrade to plaintext is exactly what cannot be allowed on the
 * one leg that crosses the internet.
 *
 * <p><b>What this test does not claim.</b> {@code REQUIRED} stops passive eavesdropping, not an
 * active machine-in-the-middle — nothing verifies whose certificate is on the other end. Closing that
 * needs {@code VERIFY_IDENTITY} plus the provider's CA in a truststore inside the container, which is
 * tracked in {@code documentation/FUTURE-ENHANCEMENTS.md} and deliberately not asserted here.
 *
 * @see SqlTableCaseConsistencyTest the sibling guard for table-name spelling
 */
class DatasourceTlsConfigTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    /** Profiles that point at a managed database rather than a developer's localhost. */
    private static final String DEPLOYED_PROFILES = "prod, qa, stage";

    /**
     * Matches a literal JDBC URL. The character class stops at whitespace, quotes and {@code #}
     * so that a URL embedded in a {@code ^##^}-delimited Cloud Run {@code --set-env-vars} argument
     * yields just the URL and not the environment entries that follow it. Everything else passes
     * through, so an unresolved property placeholder such as the one wrapping {@code MYSQL_SSL_MODE}
     * in {@code application.yml} survives intact and can be asserted on as written.
     */
    private static final Pattern JDBC_URL = Pattern.compile("jdbc:mysql:[^\\s\"'#]*");

    /** Extensions worth scanning; anything else in this repository cannot hold a datasource URL. */
    private static final List<String> SCANNED_EXTENSIONS = List.of(".yml", ".yaml", ".sh", ".json", ".properties");

    /**
     * Directory names never descended into: build output, dependency trees and version-control
     * internals hold no hand-authored configuration, and {@code test} is excluded because this very
     * file contains both the URL-matching pattern and the word it searches for — scanning it would
     * make the guard fail on itself.
     */
    private static final List<String> EXCLUDED_DIRS =
            List.of("target", "node_modules", ".git", ".angular", "dist", "coverage", "test");

    /**
     * Reads a configuration file from the project root.
     *
     * @param relativePath path relative to the module root
     * @return the file's contents
     */
    private static String read(Path relativePath) throws IOException {
        assertTrue(Files.exists(relativePath), "expected configuration file is missing: " + relativePath);
        return Files.readString(relativePath);
    }

    @Test
    @DisplayName("the base datasource URL is driven by MYSQL_SSL_MODE and defaults to PREFERRED")
    void baseUrlUsesTheSslModeLadder() throws IOException {
        String applicationYml = read(RESOURCES.resolve("application.yml"));

        Matcher matcher = Pattern.compile("^\\s*url:\\s*(jdbc:mysql:\\S+)", Pattern.MULTILINE).matcher(applicationYml);
        assertTrue(matcher.find(), "application.yml no longer declares a spring.datasource.url");
        String url = matcher.group(1);

        assertTrue(url.contains("sslMode=${MYSQL_SSL_MODE:PREFERRED}"),
                "the URL must take its TLS mode from MYSQL_SSL_MODE so the deployed profiles can "
                        + "raise it to REQUIRED without rewriting the URL. Found: " + url);
        assertTrue(url.contains("allowPublicKeyRetrieval=${MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL:true}"),
                "allowPublicKeyRetrieval must be overridable — it is a machine-in-the-middle foothold "
                        + "on an unencrypted connection and the deployed profiles switch it off. Found: " + url);
    }

    /**
     * Each deployed profile must pin {@code REQUIRED} rather than inherit {@code PREFERRED}, whose
     * silent fallback to plaintext is the whole risk being closed here.
     */
    @ParameterizedTest(name = "the {0} profile refuses an unencrypted database connection")
    @ValueSource(strings = {"prod", "qa", "stage"})
    void deployedProfilesRequireTls(String profile) throws IOException {
        String yaml = read(RESOURCES.resolve("application-" + profile + ".yml"));

        assertTrue(Pattern.compile("^\\s*MYSQL_SSL_MODE:\\s*REQUIRED\\s*$", Pattern.MULTILINE).matcher(yaml).find(),
                "application-" + profile + ".yml must set MYSQL_SSL_MODE: REQUIRED — it points at a "
                        + "managed database reached over the public internet");
        assertTrue(Pattern.compile("^\\s*MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL:\\s*false\\s*$", Pattern.MULTILINE)
                        .matcher(yaml).find(),
                "application-" + profile + ".yml must set MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL: false — once "
                        + "TLS is mandatory the RSA key exchange it guards is unreachable, so leaving it on "
                        + "only preserves a downgrade foothold");
    }

    /**
     * Sweeps every configuration file in the repository, so a datasource URL added to a new pipeline
     * or environment template is covered without anyone remembering to extend this test.
     *
     * <p>One exemption exists and it is narrow: a Cloud SQL URL using {@code socketFactory=} does not
     * negotiate TLS itself, because the connector establishes an authenticated, encrypted channel
     * before MySQL's own handshake begins. {@code sslMode} on such a URL would be meaningless, so the
     * exemption keys on the presence of {@code socketFactory=} rather than on a file path.
     */
    @Test
    @DisplayName("every JDBC URL in the repository requests TLS with the modern sslMode spelling")
    void noConfiguredUrlOptsOutOfTls() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> candidates = new ArrayList<>();
        collectConfigFiles(Path.of("."), candidates);

        for (Path file : candidates) {
            String contents;
            try {
                contents = Files.readString(file);
            } catch (IOException | RuntimeException unreadable) {
                continue; // binary or non-UTF-8; it cannot hold a hand-written datasource URL
            }
            Matcher urls = JDBC_URL.matcher(normalizeWorkflowExpressions(contents));
            while (urls.find()) {
                String url = urls.group();
                if (url.contains("socketFactory=")) {
                    continue; // Cloud SQL connector owns transport security; see the Javadoc
                }
                if (url.toLowerCase(Locale.ROOT).contains("usessl")) {
                    violations.add(file + " uses the legacy useSSL switch, which Connector/J "
                            + "8.0.13+ ignores whenever sslMode is present: " + url);
                } else if (!url.contains("sslMode=")) {
                    violations.add(file + " declares a JDBC URL with no sslMode, so it inherits the "
                            + "driver default and may connect in plaintext: " + url);
                }
            }
        }

        assertTrue(candidates.size() > 5,
                "the sweep found almost nothing to read, which means the walk is broken rather than "
                        + "the configuration being clean");

        if (!violations.isEmpty()) {
            fail("JDBC URLs must request TLS explicitly (deployed profiles: " + DEPLOYED_PROFILES
                    + "):\n  " + String.join("\n  ", violations));
        }
    }

    /**
     * Collapses GitHub Actions expressions to an opaque token before the URL pattern runs.
     *
     * <p>Workflow files interpolate secrets as {@code ${{ secrets.NAME }}} — with spaces inside the
     * braces. Since the URL pattern treats whitespace as the end of the URL, a workflow URL would
     * otherwise be truncated at its first interpolated host and reported as missing {@code sslMode}
     * when the real URL a few characters later declares it. Substituting a space-free token keeps
     * the URL in one piece without loosening the boundary rule for every other file.
     *
     * @param contents raw file text
     * @return the same text with workflow expressions replaced by a placeholder token
     */
    private static String normalizeWorkflowExpressions(String contents) {
        return contents.replaceAll("\\$\\{\\{[^}]*}}", "WORKFLOW_EXPRESSION");
    }

    /**
     * Recursively gathers configuration files, pruning excluded directories as it descends rather
     * than filtering afterwards — {@code node_modules} alone holds tens of thousands of entries, and
     * walking into it would dominate the runtime of this test for no possible finding.
     *
     * @param directory  directory to descend into
     * @param collected  accumulator the matching files are appended to
     */
    private static void collectConfigFiles(Path directory, List<Path> collected) {
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (!EXCLUDED_DIRS.contains(name)) {
                        collectConfigFiles(entry, collected);
                    }
                } else if (isScannable(entry)) {
                    collected.add(entry);
                }
            }
        } catch (IOException unreadable) {
            // An unreadable directory cannot be asserted on; the size check below catches a walk
            // that failed broadly enough to matter.
        }
    }

    private static boolean isScannable(Path file) {
        String name = file.getFileName().toString();
        // Environment templates are tracked and reviewable; a developer's own .env is neither.
        if (name.startsWith(".env")) {
            return name.endsWith(".example");
        }
        return SCANNED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
