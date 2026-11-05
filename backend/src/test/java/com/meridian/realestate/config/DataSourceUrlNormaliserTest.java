package com.meridian.realestate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * The platform connection-string rule, asserted without a database or a Spring context.
 *
 * <p>This is the one piece of configuration standing between a managed platform and a running
 * installation: get it wrong and the service dies at startup with a driver error that names none of
 * the real cause. The cases worth pinning are the ones a generated credential actually produces -
 * a percent-escaped character, a literal plus from base64, a colon inside the password - plus the
 * two the rule must <i>not</i> touch: an ordinary JDBC URL and a pair of local {@code DB_*} values.</p>
 */
@DisplayName("DataSourceUrlNormaliser: platform connection strings")
class DataSourceUrlNormaliserTest {

  private final DataSourceUrlNormaliser normaliser = new DataSourceUrlNormaliser();

  /** Run the hook the way Spring Boot does, and hand back the environment it produced. */
  private MockEnvironment normalise(String databaseUrl) {
    MockEnvironment environment = new MockEnvironment();
    if (databaseUrl != null) environment.setProperty("DATABASE_URL", databaseUrl);
    normaliser.postProcessEnvironment(environment, new SpringApplication());
    return environment;
  }

  @Test
  @DisplayName("a bare postgres:// URL becomes the JDBC URL the driver requires")
  void barePostgresUrlBecomesJdbc() {
    MockEnvironment environment =
        normalise("postgres://real_estate:secret@dpg-abc123-a:5432/real_estate");

    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:postgresql://dpg-abc123-a:5432/real_estate");
    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("real_estate");
    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("secret");
  }

  @Test
  @DisplayName("postgresql:// is accepted too, and a missing port means 5432")
  void postgresqlSchemeWithoutPortDefaultsThePort() {
    // Render's own connection string carries no port, so this is the shape that actually arrives.
    MockEnvironment environment = normalise("postgresql://real_estate:secret@dpg-abc123-a/real_estate");

    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:postgresql://dpg-abc123-a:5432/real_estate");
  }

  @Test
  @DisplayName("a percent-escaped password is decoded, not passed through escaped")
  void percentEscapesAreDecoded() {
    // %40 is '@' and %3A is ':' - both legal in a generated password and both fatal if left encoded.
    MockEnvironment environment = normalise("postgres://user:p%40ss%3Aword@host:5432/db");

    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("p@ss:word");
  }

  @Test
  @DisplayName("a plus in a password stays a plus")
  void plusIsNotTurnedIntoASpace() {
    // The reason this class uses URI rather than URLDecoder: form decoding would read '+' as a
    // space, and the installation would fail to authenticate with a password that looks correct.
    MockEnvironment environment = normalise("postgres://user:a+b+c@host:5432/db");

    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("a+b+c");
  }

  @Test
  @DisplayName("only the first colon separates the user from the password")
  void onlyTheFirstColonSeparatesTheCredentials() {
    MockEnvironment environment = normalise("postgres://user:pa%3Ass:word@host:5432/db");

    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("user");
    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("pa:ss:word");
  }

  @Test
  @DisplayName("connection options survive the rewrite")
  void queryParametersAreCarriedAcross() {
    MockEnvironment environment =
        normalise("postgres://user:pw@host:5432/db?sslmode=require&ApplicationName=meridian");

    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:postgresql://host:5432/db?sslmode=require&ApplicationName=meridian");
  }

  @Test
  @DisplayName("an already-JDBC URL is left exactly as it was")
  void jdbcUrlIsUntouched() {
    String jdbc = "jdbc:postgresql://localhost:5432/real_estate";
    MockEnvironment environment = normalise(jdbc);

    // No property source at all, so application.yml remains the only authority.
    assertThat(environment.getProperty("spring.datasource.url")).isNull();
  }

  @Test
  @DisplayName("an absent DATABASE_URL changes nothing, so DB_HOST et al still drive local runs")
  void absentValueLeavesConfigurationAlone() {
    assertThat(normalise(null).getProperty("spring.datasource.url")).isNull();
    assertThat(normalise("").getProperty("spring.datasource.url")).isNull();
    assertThat(normalise("   ").getProperty("spring.datasource.url")).isNull();
  }

  @Test
  @DisplayName("another database's scheme is not this class's business")
  void otherSchemesAreLeftAlone() {
    assertThat(normalise("mysql://user:pw@host:3306/db").getProperty("spring.datasource.url"))
        .isNull();
    assertThat(normalise("redis://host:6379").getProperty("spring.datasource.url")).isNull();
  }

  @Test
  @DisplayName("a username with no password is accepted rather than refused")
  void credentialsWithoutAPasswordAreSupported() {
    MockEnvironment environment = normalise("postgres://real_estate@host:5432/db");

    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("real_estate");
    assertThat(environment.getProperty("spring.datasource.password")).isNull();
  }

  @Test
  @DisplayName("a malformed URL fails loudly, and never echoes the value")
  void unparseableValuesFailWithoutLeakingTheSecret() {
    assertThatThrownBy(() -> normalise("postgres://user:pa ss@host:5432/db"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DATABASE_URL")
        // The password must not appear in the message that reaches a log.
        .hasMessageNotContaining("pa ss");
  }

  @Test
  @DisplayName("a URL naming no host or no database is refused with the shape it expected")
  void incompleteUrlsAreRefused() {
    assertThatThrownBy(() -> normalise("postgres:///real_estate"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("names no host");

    assertThatThrownBy(() -> normalise("postgres://host:5432"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("names no database");
  }
}
