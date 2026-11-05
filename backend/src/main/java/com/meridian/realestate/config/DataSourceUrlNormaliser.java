package com.meridian.realestate.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Accepts a hosted platform's database URL for {@code DATABASE_URL}, not only a JDBC one.
 *
 * <p><b>Why this class exists.</b> {@code application.yml} documents {@code DATABASE_URL} as "an
 * optional full JDBC URL that overrides the parts above", and every value it falls back to is
 * {@code jdbc:}-prefixed. Managed platforms do not hand out that form: Render's
 * {@code fromDatabase: connectionString} resolves to {@code postgres://user:password@host/db}, and
 * Heroku's is the same shape. The PostgreSQL JDBC driver only accepts a {@code jdbc:}-prefixed URL,
 * so an installation wired the normal way fails at startup with a connection error that names none
 * of the actual cause.</p>
 *
 * <p><b>What it does instead.</b> When {@code DATABASE_URL} is a bare {@code postgres://} or
 * {@code postgresql://} URI, it is decomposed here - before any bean is created - into
 * {@code spring.datasource.url}, {@code .username} and {@code .password}, taken from the URI's own
 * parts. An already-JDBC value, and every other scheme, is left completely alone, so local
 * development driven by {@code DB_HOST}/{@code DB_PORT} is unaffected.</p>
 *
 * <p><b>Two details that are deliberate.</b> The credentials are read through {@link URI}, not
 * {@code URLDecoder}: a URI decodes its own {@code %xx} escapes and leaves {@code +} as a literal
 * plus, which is what a base64 password character means in that position, whereas form decoding
 * would silently turn it into a space and produce an authentication failure nobody could explain.
 * And the value is never logged - only the fact that a URL was normalised - because a log line
 * echoing a connection string is itself the leak.</p>
 *
 * <p><b>Why it declares an order.</b> {@link SqlFileDataSourcePostProcessor} also publishes
 * {@code spring.datasource.url}, and both call {@code addFirst} - so the hook that runs <i>last</i> is
 * the one that wins. Without an explicit order the two would tie at {@code LOWEST_PRECEDENCE} and
 * their relative order would come down to the order they happen to be listed in
 * {@code META-INF/spring.factories}: correct today, silently wrong the first time somebody sorts that
 * file. Declaring the order makes the relationship a property of the classes rather than of a list,
 * and leaves room for the SQL-file source to be unambiguously last.</p>
 */
public class DataSourceUrlNormaliser implements EnvironmentPostProcessor, Ordered {

  private static final Logger log = LoggerFactory.getLogger(DataSourceUrlNormaliser.class);

  private static final String SOURCE_NAME = "normalisedDatabaseUrl";
  private static final String DATABASE_URL = "DATABASE_URL";
  private static final int DEFAULT_POSTGRES_PORT = 5432;

  @Override
  public int getOrder() {
    // Late enough that application.yml has been read, and before the SQL-file source so that asking
    // for an ephemeral database overrides a platform connection string rather than the other way round.
    return Ordered.LOWEST_PRECEDENCE - 100;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {

    String raw = environment.getProperty(DATABASE_URL);
    if (raw == null || raw.isBlank()) return;

    // Already what the driver expects, or something only the driver understands. Either way the
    // application's own configuration is the better authority than this class.
    if (raw.startsWith("jdbc:")) return;

    URI uri;
    try {
      uri = new URI(raw.trim());
    } catch (URISyntaxException ex) {
      // Deliberately does not include the value: it carries the password.
      throw new IllegalStateException(
          DATABASE_URL + " is set but is not a parseable URL. Expected the platform's connection "
              + "string (postgres://user:password@host:port/database) or a jdbc:postgresql: URL.",
          ex);
    }

    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("postgres") || scheme.equalsIgnoreCase("postgresql"))) {
      // Not a platform Postgres URL. Leave it for the datasource to interpret or reject.
      return;
    }

    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalStateException(
          DATABASE_URL + " is set but names no host. Expected "
              + "postgres://user:password@host:port/database.");
    }

    int port = uri.getPort() < 0 ? DEFAULT_POSTGRES_PORT : uri.getPort();
    String database = pathToDatabaseName(uri);
    if (database.isBlank()) {
      throw new IllegalStateException(
          DATABASE_URL + " is set but names no database. Expected "
              + "postgres://user:password@host:port/database.");
    }

    StringBuilder jdbcUrl =
        new StringBuilder("jdbc:postgresql://")
            .append(host)
            .append(':')
            .append(port)
            .append('/')
            .append(database);
    // A platform may add options (sslmode, for instance). They are PostgreSQL connection properties
    // either way, so they are carried across rather than dropped.
    if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
      jdbcUrl.append('?').append(uri.getQuery());
    }

    Map<String, Object> values = new LinkedHashMap<>();
    values.put("spring.datasource.url", jdbcUrl.toString());
    applyCredentials(uri.getUserInfo(), values);

    // Highest precedence, so it wins over application.yml's own spring.datasource entries while
    // leaving the file readable as the documentation of what the parts mean.
    environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, values));

    log.info(
        "DATABASE_URL was a platform connection string; normalised it to a JDBC URL against host "
            + "'{}' and database '{}'.",
        host,
        database);
  }

  /** {@code postgres://host/db} and {@code postgres://host:5432/db} -> {@code db}. */
  private static String pathToDatabaseName(URI uri) {
    String path = uri.getPath();
    if (path == null) return "";
    String trimmed = path.startsWith("/") ? path.substring(1) : path;
    return trimmed.trim();
  }

  /**
   * Split {@code user:password} into the two datasource properties.
   *
   * <p>Only the <b>first</b> colon separates them, because a generated password may itself contain
   * one. A userInfo with no colon is a username with no password, which is a legitimate peer-auth
   * setup rather than an error.</p>
   */
  private static void applyCredentials(String userInfo, Map<String, Object> values) {
    if (userInfo == null || userInfo.isBlank()) return;
    int separator = userInfo.indexOf(':');
    if (separator < 0) {
      values.put("spring.datasource.username", userInfo);
      return;
    }
    values.put("spring.datasource.username", userInfo.substring(0, separator));
    values.put("spring.datasource.password", userInfo.substring(separator + 1));
  }
}
