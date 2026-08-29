package com.bob.angularspringbootfullstack;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full Spring context end to end — every {@code @Configuration}, every JDBC-backed
 * service, the JPA {@code EntityManagerFactory}, the security filter chain — the one test in this
 * suite that proves the application actually starts, not just that its individual classes behave
 * correctly in isolation.
 *
 * <p><b>Testcontainers, not a real local/CI MySQL (FUTURE-ENHANCEMENTS.md §5).</b> This test used
 * to require {@code MYSQL_HOST}/{@code MYSQL_PORT}/{@code MYSQL_DATABASE} (the "dev" profile's
 * fallbacks in {@code application-dev.yml}) to resolve to an already-running, already-schema'd
 * MySQL instance — a developer's local {@code db2}, or a CI service container wired up out of
 * band. That made it the one suite that broke in a database-less environment, and the one suite
 * whose pass/fail depended on state outside the test itself (whatever schema a given MySQL
 * instance happened to have applied). {@link #mysql} replaces that with a throwaway container this
 * test owns for its own lifetime — the only external dependency left is a working Docker daemon.
 *
 * <p><b>Why no manual property wiring is needed.</b> {@link ServiceConnection @ServiceConnection}
 * on the container field registers a {@code JdbcConnectionDetails} bean that Spring Boot's
 * {@code DataSourceAutoConfiguration} prefers over the raw {@code spring.datasource.*} properties
 * — so the dev profile's {@code MYSQL_HOST=127.0.0.1} fallback is present in the environment but
 * never actually consulted; the real connection goes to the container's mapped port instead.
 *
 * <p><b>Why the schema still ends up correct with zero extra setup.</b> {@code application.yml}
 * already sets {@code spring.sql.init.mode: always}, so {@code schema.sql} (the idempotent,
 * DROP-free definition of every JDBC-managed table) runs automatically against whatever
 * {@code DataSource} the context resolves — a fresh container included — exactly the same code
 * path that already applies it against Aiven on every dev boot. Hibernate's {@code ddl-auto:
 * update} then layers the JPA-managed Customer/Invoice/Services tables on top, same as always.
 * Nothing about the schema-provisioning story changed; only where the target database comes from
 * did.
 */
@Testcontainers
@SpringBootTest
class AngularSpringBootFullStackApplicationTests {

    /**
     * Pinned to {@code 8.0} to match every deployed environment (Aiven, ECS/RDS-adjacent, and the
     * {@code db2} local instance this replaces) — a newer major version can differ in default SQL
     * mode and authentication plugin behavior, and this test's whole point is to prove the app
     * boots against a database shaped like production's, not merely "some MySQL".
     */
    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Test
    void contextLoads() {
    }

}
