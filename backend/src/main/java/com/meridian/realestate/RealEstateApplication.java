package com.meridian.realestate;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Meridian Estates — Real Estate Management Dashboard.
 *
 * <p>A single Spring Boot application serving one firm's portfolio: properties and their units, the
 * ownership behind them, the tenants and leases that occupy them, the rent and payments they
 * produce, the leads, listings, viewings and applications that fill them, the sales pipeline that
 * sells them, and the maintenance and expenses they cost.</p>
 *
 * <p>PostgreSQL is the only runtime. The schema is owned by the Flyway migration chain in
 * {@code db/migration} and is validated — never generated — by Hibernate at startup.</p>
 */
@SpringBootApplication
public class RealEstateApplication {

  /**
   * The zone the firm's business day is measured in.
   *
   * <p>IST — because a property firm billing rent in Mumbai means "today" as the Indian business
   * day, not as UTC midnight. An invoice due on the 5th is due on the 5th locally, and the
   * dashboard's overdue and expiring-lease figures must not move because a server happened to be
   * provisioned in another region.</p>
   */
  public static final String DEFAULT_TIME_ZONE = "Asia/Kolkata";

  /**
   * The environment variable that overrides it, for an installation in another country.
   *
   * <p>Read here rather than from {@code application.yml} deliberately — see {@link #pinTimeZone()}.</p>
   */
  public static final String TIME_ZONE_ENV = "APP_TIMEZONE";

  public static void main(String[] args) {
    pinTimeZone();
    SpringApplication.run(RealEstateApplication.class, args);
  }

  /**
   * Pins the JVM's default time zone before anything opens a database connection.
   *
   * <p><b>This is not cosmetic, and it is not belt-and-braces.</b> The PostgreSQL JDBC driver sends
   * the JVM's default zone as the {@code TimeZone} startup parameter of every connection it opens.
   * PostgreSQL 18 no longer accepts the legacy alias {@code Asia/Calcutta} — the value a great many
   * Indian Windows machines still report — and refuses the connection outright with
   * {@code FATAL: invalid value for parameter "TimeZone"} (SQLSTATE 22023). The application then
   * cannot start at all: Flyway never reaches its first migration, so the failure presents as a
   * database that "does not work" rather than as a clock setting.</p>
   *
   * <p>It has to happen here, before {@code SpringApplication.run}, rather than in configuration.
   * The driver reads the default zone when it connects, which is during Flyway's initial migration;
   * a {@code @ConfigurationProperties} bean is not bound until later in the context refresh, so a
   * value set in YAML would arrive too late to matter.</p>
   *
   * <p>An unusable {@code APP_TIMEZONE} falls back to the default rather than failing startup: a
   * typo in an environment variable should cost a warning, not the whole installation.</p>
   */
  private static void pinTimeZone() {
    String requested = System.getenv(TIME_ZONE_ENV);
    String zoneId = requested == null || requested.isBlank() ? DEFAULT_TIME_ZONE : requested.trim();

    ZoneId zone;
    try {
      zone = ZoneId.of(zoneId);
    } catch (DateTimeException ex) {
      System.err.println(
          "APP_TIMEZONE '" + zoneId + "' is not a recognised time zone; using " + DEFAULT_TIME_ZONE);
      zone = ZoneId.of(DEFAULT_TIME_ZONE);
    }

    TimeZone.setDefault(TimeZone.getTimeZone(zone));
    System.setProperty("user.timezone", zone.getId());
  }
}
