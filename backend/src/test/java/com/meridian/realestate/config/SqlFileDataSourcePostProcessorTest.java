package com.meridian.realestate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

/**
 * The SQL-file mode rule, asserted without starting a server.
 *
 * <p>Two things are worth pinning here, and neither needs PostgreSQL. The first is that the default
 * costs nothing: an installation that says nothing about {@code app.data-source} is left exactly as it
 * was, with no property source of ours in front of its datasource. The second is that an ephemeral
 * database cannot be reached by accident - a non-development environment is refused <i>before</i> a
 * server is started, so a misconfigured production instance fails immediately instead of coming up
 * healthy and then losing everything its users write.</p>
 *
 * <p>The mode that actually starts a server is covered by {@code SqlFileDataTest}, which boots the
 * application against it and asserts the seeded portfolio is present.</p>
 */
@DisplayName("SqlFileDataSourcePostProcessor: when the SQL files are the data source")
class SqlFileDataSourcePostProcessorTest {

  private final SqlFileDataSourcePostProcessor processor =
      new SqlFileDataSourcePostProcessor(new DeferredLogs());

  private static MockEnvironment environment(String mode, String env) {
    MockEnvironment environment = new MockEnvironment();
    if (mode != null) environment.setProperty(SqlFileDataSourcePostProcessor.MODE_PROPERTY, mode);
    if (env != null) environment.setProperty("app.env", env);
    return environment;
  }

  private static MockEnvironment local(String mode) {
    return environment(mode, "development");
  }

  // ------------------------------------------------------------------ the rule

  @Test
  @DisplayName("sqlfiles selects the mode")
  void sqlfilesSelectsTheMode() {
    assertThat(SqlFileDataSourcePostProcessor.isSqlFileMode(local("sqlfiles"))).isTrue();
  }

  @Test
  @DisplayName("the value is read case-insensitively and with surrounding space ignored")
  void theModeIsReadForgivingly() {
    assertThat(SqlFileDataSourcePostProcessor.isSqlFileMode(local("SQLFILES"))).isTrue();
    assertThat(SqlFileDataSourcePostProcessor.isSqlFileMode(local("SqlFiles"))).isTrue();
    assertThat(SqlFileDataSourcePostProcessor.isSqlFileMode(local("  sqlfiles  "))).isTrue();
  }

  @Test
  @DisplayName("postgres, and saying nothing at all, both keep the database this installation was given")
  void everythingElseIsTheDatabase() {
    assertThat(SqlFileDataSourcePostProcessor.isSqlFileMode(local("postgres"))).isFalse();
    // The default arrives as application.yml's own `${APP_DATA_SOURCE:postgres}`, but a blank value
    // must not be read as "sqlfiles" either - an unset variable is not a request for a demo.
    assertThat(SqlFileDataSourcePostProcessor.isSqlFileMode(local(""))).isFalse();
    assertThat(SqlFileDataSourcePostProcessor.isSqlFileMode(local("   "))).isFalse();
    assertThat(SqlFileDataSourcePostProcessor.isSqlFileMode(local("sqlfile"))).isFalse();
    assertThat(SqlFileDataSourcePostProcessor.isSqlFileMode(local("postgresql"))).isFalse();
  }

  @Test
  @DisplayName("the default path publishes no datasource override and starts nothing")
  void theDefaultPathIsUntouched() {
    MockEnvironment environment = local("postgres");

    processor.postProcessEnvironment(environment, new SpringApplication());

    // Nothing of ours in front of the installation's own configuration: DB_HOST/DATABASE_URL still
    // decide the datasource exactly as they did before this class existed.
    assertThat(environment.getPropertySources().contains("sqlFileDataSource")).isFalse();
    assertThat(environment.getProperty("spring.datasource.url")).isNull();
  }

  @Test
  @DisplayName("an absent setting is treated as the database, not as the SQL files")
  void anAbsentSettingIsTheDatabase() {
    MockEnvironment environment = environment(null, "development");

    processor.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getPropertySources().contains("sqlFileDataSource")).isFalse();
  }

  // --------------------------------------------------------------- the guard

  @Test
  @DisplayName("an ephemeral database is refused in an environment that is not a development one")
  void nonLocalEnvironmentsAreRefused() {
    assertThatThrownBy(
            () ->
                processor.postProcessEnvironment(
                    environment("sqlfiles", "production"), new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Refusing to start")
        .hasMessageContaining("production")
        // The message has to name the thing to change, since the operator is looking at a startup log.
        .hasMessageContaining(SqlFileDataSourcePostProcessor.MODE_PROPERTY)
        .hasMessageContaining("postgres");
  }

  @Test
  @DisplayName("the refusal happens before a server is started, so a wrong environment fails fast")
  void theGuardRunsBeforeTheServerStarts() {
    // If the guard were reached after `embedded()` this test would start PostgreSQL and then throw,
    // turning a five-millisecond misconfiguration check into a slow one - and leaving a server behind.
    assertThatThrownBy(
            () ->
                processor.postProcessEnvironment(
                    environment("sqlfiles", "staging"), new SpringApplication()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("development, local and test are the environments that may use it")
  void localEnvironmentsPassTheGuard() {
    for (String env : new String[] {"development", "local", "test", "DEVELOPMENT", " Local "}) {
      assertThatCode(
              () -> SqlFileDataSourcePostProcessor.refuseIfNotLocal(environment("sqlfiles", env)))
          .as("app.env=%s must be allowed", env)
          .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("an unset environment is treated as local, matching AppProperties.isLocal")
  void anUnsetEnvironmentIsLocal() {
    assertThatCode(
            () -> SqlFileDataSourcePostProcessor.refuseIfNotLocal(environment("sqlfiles", null)))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> SqlFileDataSourcePostProcessor.refuseIfNotLocal(environment("sqlfiles", "")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("it runs last, so an explicitly requested ephemeral database wins over DATABASE_URL")
  void itRunsLast() {
    /*
     * Both hooks call addFirst, so the later one lands on top - ordering is the whole mechanism by
     * which `sqlfiles` overrides a DATABASE_URL that is still set in the environment. Both orders are
     * declared explicitly rather than inherited, because two hooks tied at LOWEST_PRECEDENCE would be
     * ordered by their line order in spring.factories, which is not a place to keep a rule like this.
     */
    assertThat(processor.getOrder())
        .as("the SQL-file source must be unambiguously last")
        .isEqualTo(Ordered.LOWEST_PRECEDENCE);
    assertThat(new DataSourceUrlNormaliser().getOrder())
        .as("the platform connection string must be normalised before it is overridden")
        .isLessThan(processor.getOrder());
  }

  // ------------------------------------------------- clusters left by a killed run

  @Test
  @DisplayName("a cluster whose owning process is gone is abandoned, server or no server")
  void aClusterWhoseOwnerIsGoneIsAbandoned() {
    /*
     * The whole reason the sweep exists: a run that was killed rather than asked to stop leaves its
     * cluster behind, and it is the common case, not the exotic one. A pid no live process holds
     * stands in for the JVM that owned it.
     */
    assertThat(
            SqlFileDataSourcePostProcessor.isAbandoned(
                Path.of("meridian-sqlfiles-pg-999999999-1234567890")))
        .isTrue();
  }

  @Test
  @DisplayName("a cluster whose owner is still running is left alone, so two instances can coexist")
  void aClusterWhoseOwnerIsAliveIsKept() {
    long thisProcess = ProcessHandle.current().pid();

    assertThat(
            SqlFileDataSourcePostProcessor.isAbandoned(
                Path.of("meridian-sqlfiles-pg-" + thisProcess + "-42")))
        .as("this JVM is running, so its cluster is in use")
        .isFalse();
  }

  @Test
  @DisplayName("only directories this class named are ever considered for removal")
  void nothingElseIsEverConsidered() {
    /*
     * A sweep that deletes by guesswork is worse than the leak it fixes. Every shape it does not
     * recognise has to fall on the side of being left in place.
     */
    assertThat(SqlFileDataSourcePostProcessor.isAbandoned(Path.of("meridian-sqlfiles-pg-999999999")))
        .as("the earlier single-segment name is not a shape we create")
        .isFalse();
    assertThat(SqlFileDataSourcePostProcessor.isAbandoned(Path.of("meridian-sqlfiles-pg-abc-42")))
        .isFalse();
    assertThat(SqlFileDataSourcePostProcessor.isAbandoned(Path.of("meridian-sqlfiles-pg-notapid-12")))
        .isFalse();
    assertThat(SqlFileDataSourcePostProcessor.isAbandoned(Path.of("some-other-temp-directory")))
        .isFalse();
    assertThat(SqlFileDataSourcePostProcessor.isAbandoned(Path.of("meridian-sqlfiles-pg-")))
        .isFalse();
  }
}
