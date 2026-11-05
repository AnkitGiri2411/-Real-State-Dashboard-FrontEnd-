package com.meridian.realestate;

import static org.assertj.core.api.Assertions.assertThat;

import com.meridian.realestate.config.SqlFileDataSourcePostProcessor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves the SQL-file data source end to end: no database to point at, the migrations alone produce
 * the demonstration portfolio.
 *
 * <p><b>What this covers that the other tests cannot.</b> {@code SqlFileDataSourcePostProcessorTest}
 * checks which mode is chosen and when the mode is refused, and it deliberately never starts a server.
 * {@code SchemaConformanceTest} checks the migrations against the entity model, statically. Neither
 * can answer the only question that really matters here - does an ephemeral PostgreSQL start, does the
 * chain apply to it, and does the seeded portfolio come out with the same rows an installation would
 * have? That needs a real server, so this test is the one that starts one.</p>
 *
 * <p><b>Why it is named {@code IT} and not {@code Test}.</b> Maven's Surefire only picks up
 * {@code *Test}, so this does not run in {@code mvn test}. That is on purpose and not an oversight: the
 * suite's documented property is that it needs no database, and starting one here would both break
 * that and add half a minute to every build. Run it when the SQL-file mode itself changes:</p>
 *
 * <pre>{@code
 * cd backend
 * mvn -B test -Dtest=SqlFileDataIT
 * }</pre>
 *
 * <p>It is also the fastest way to confirm the migrations still apply from empty after a change to
 * them, without creating a database by hand.</p>
 */
@DisplayName("The SQL files alone produce the demonstration portfolio")
class SqlFileDataIT {

  /** The schema the product owns, as {@code DB_SCHEMA} names it in application.yml. */
  private static final String SCHEMA = "real_estate";

  @Test
  @DisplayName("an ephemeral PostgreSQL starts, V1-V23 apply, and the portfolio is the seeded one")
  void theMigrationsAloneSeedThePortfolio() throws SQLException {
    // Exactly the environment the application builds, so this exercises the real entry point rather
    // than a re-implementation of it. The property is named as a literal on purpose: it is the key
    // application.yml declares, so this fails if the two ever stop agreeing.
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("app.data-source", "sqlfiles");
    environment.setProperty("app.env", "test");

    new SqlFileDataSourcePostProcessor(new DeferredLogs())
        .postProcessEnvironment(environment, new SpringApplication());

    String url = environment.getProperty("spring.datasource.url");
    String username = environment.getProperty("spring.datasource.username");
    String password = environment.getProperty("spring.datasource.password");
    assertThat(url).as("the hook must publish a datasource to use").isNotNull();

    Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:db/migration")
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .load()
        .migrate();

    try (Connection connection = DriverManager.getConnection(url, username, password)) {
      // Read from the history table rather than from the call's result: it is what an operator would
      // check, and it is the same evidence a real installation leaves behind.
      assertThat(appliedMigrations(connection)).as("the whole chain, not a subset").isEqualTo(23);
      assertThat(flywayVersion(connection)).isEqualTo("23");

      /*
       * The counts V22 and V23 assert on themselves. A clean apply already proves them - both files
       * RAISE EXCEPTION rather than finish if the portfolio is short - but asserting them here is what
       * turns "the migration succeeded" into a statement about the data a screen will read.
       */
      assertThat(count(connection, "properties")).isEqualTo(14);
      assertThat(count(connection, "units")).isEqualTo(222);
      assertThat(count(connection, "owners")).isEqualTo(22);
      assertThat(count(connection, "property_ownerships")).isEqualTo(23);
      assertThat(count(connection, "portfolios")).isEqualTo(3);
      assertThat(count(connection, "offices")).isEqualTo(3);
      assertThat(count(connection, "agent_profiles")).isEqualTo(5);

      // V23's half: the letting book and the ledger.
      assertThat(count(connection, "tenants")).isEqualTo(60);
      assertThat(count(connection, "buyers")).isEqualTo(25);
      assertThat(count(connection, "leads")).isEqualTo(30);
      assertThat(count(connection, "leases")).isEqualTo(166);
      assertThat(count(connection, "rental_applications")).isEqualTo(40);
      assertThat(count(connection, "viewings")).isEqualTo(60);
      assertThat(count(connection, "maintenance_requests")).isEqualTo(45);
      assertThat(count(connection, "property_expenses")).isEqualTo(220);
      assertThat(count(connection, "documents")).isEqualTo(194);
      assertThat(count(connection, "sales_opportunities")).isEqualTo(18);
      assertThat(count(connection, "conversations")).isEqualTo(28);
      assertThat(count(connection, "vendors")).isEqualTo(10);

      // Reference data that ships with the schema migrations rather than the seed ones.
      assertThat(count(connection, "permissions")).isGreaterThanOrEqualTo(95);
      assertThat(count(connection, "roles")).isEqualTo(8);
      assertThat(count(connection, "users")).isEqualTo(10);
      assertThat(count(connection, "property_types")).isEqualTo(9);
      assertThat(count(connection, "amenities")).isEqualTo(18);
      assertThat(count(connection, "unit_types")).isEqualTo(10);
      assertThat(count(connection, "expense_categories")).isEqualTo(20);

      /*
       * The ledger is deliberately not asserted exactly. V23 dates every invoice relative to
       * CURRENT_DATE - fourteen months of rent from today - so the number depends on which month the
       * test runs in, which is the property that keeps the demonstration current. A bound is the
       * honest assertion; an exact number would be a test that fails next month for the wrong reason.
       */
      assertThat(count(connection, "rent_invoices"))
          .as("fourteen months of rent for every tenancy")
          .isGreaterThan(1500);
      assertThat(count(connection, "payments")).isGreaterThan(1000);
    }
  }

  private static int count(Connection connection, String table) throws SQLException {
    // A literal table name, never a parameter: this is a test, and the names come from the constant
    // list above rather than from anything a caller supplies.
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("select count(*) from " + SCHEMA + "." + table)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  /**
   * How many migrations the schema's history records as applied and successful.
   *
   * <p>Filtered to rows that carry a version. Flyway records its own creation of the schema in the
   * same table, as {@code << Flyway Schema Creation >>} with no version, so an unfiltered count is one
   * more than the number of migrations and reads as an extra migration nobody wrote.</p>
   */
  private static int appliedMigrations(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "select count(*) from "
                    + SCHEMA
                    + ".flyway_schema_history where success and version is not null")) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private static String flywayVersion(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "select version from "
                    + SCHEMA
                    + ".flyway_schema_history where success and version is not null"
                    + " order by installed_rank desc limit 1")) {
      rows.next();
      return rows.getString(1);
    }
  }
}
