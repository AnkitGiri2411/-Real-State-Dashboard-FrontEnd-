package com.meridian.realestate.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a non-local installation that is still carrying a development secret.
 *
 * <p>The committed defaults exist so a developer can clone the product and run it. The risk is that
 * the same defaults travel to a client's server, where a signing key published in a public
 * repository would let anyone mint a valid token for that installation. This guard closes that
 * door: against a database that is not on this machine, a development-default {@code JWT_SECRET}
 * stops startup with a sentence naming the variable.</p>
 *
 * <p>It <b>never prints the value</b> — only the name of the variable and why it is unacceptable.
 * A log that echoes a secret is itself the leak.</p>
 *
 * <p>The check is on the <i>database host</i> rather than {@code APP_ENV} alone, because the
 * dangerous case is "pointed at a real database" and that is a fact about the datasource, not a
 * label somebody typed into a config file.</p>
 */
@Component
public class SecretStartupGuard {

  private static final Logger log = LoggerFactory.getLogger(SecretStartupGuard.class);

  /** The committed development default. Never acceptable off this machine. */
  static final String DEVELOPMENT_JWT_SECRET =
      "meridian-estates-local-development-secret-key-2026-very-long-random-value";

  /** 256 bits is the shortest HMAC key HS256 should be handed. */
  private static final int MINIMUM_SECRET_LENGTH = 32;

  private final Environment environment;
  private final AppProperties properties;
  private final String jwtSecret;

  public SecretStartupGuard(
      Environment environment,
      AppProperties properties,
      @Value("${jwt.secret:}") String jwtSecret) {
    this.environment = environment;
    this.properties = properties;
    this.jwtSecret = jwtSecret;
  }

  /**
   * Verify the installation's secrets before the application serves anything.
   *
   * <p>Called from {@link ApplicationContext} startup rather than a request path, so a
   * misconfigured installation fails immediately and loudly instead of at the first sign-in.</p>
   */
  @jakarta.annotation.PostConstruct
  public void verify() {
    String datasourceUrl = environment.getProperty("spring.datasource.url", "");
    boolean localDatabase = pointsAtThisMachine(datasourceUrl);

    if (localDatabase) {
      if (DEVELOPMENT_JWT_SECRET.equals(jwtSecret)) {
        log.warn(
            "JWT_SECRET is still the development default. This is acceptable only for local "
                + "development; set a real secret before this installation is reachable.");
      }
      return;
    }

    if (jwtSecret == null || jwtSecret.isBlank()) {
      throw new IllegalStateException(
          "Refusing to start: JWT_SECRET is not configured and the database is not local. "
              + "Set JWT_SECRET to a long random value (at least "
              + MINIMUM_SECRET_LENGTH
              + " characters).");
    }
    if (DEVELOPMENT_JWT_SECRET.equals(jwtSecret)) {
      throw new IllegalStateException(
          "Refusing to start: JWT_SECRET is still the development default and the database is not "
              + "local. Set JWT_SECRET to a long random value unique to this installation.");
    }
    if (jwtSecret.length() < MINIMUM_SECRET_LENGTH) {
      throw new IllegalStateException(
          "Refusing to start: JWT_SECRET is shorter than "
              + MINIMUM_SECRET_LENGTH
              + " characters. Generate one with: openssl rand -base64 48");
    }
    if (!properties.isLocal()) {
      log.info("SecretStartupGuard passed for environment '{}'", properties.getEnv());
    }
  }

  /**
   * True when the JDBC URL addresses this machine.
   *
   * <p>A container database on another host is not local even though it may be development; that
   * is deliberate, because a shared development database holds other people's data.</p>
   */
  private static boolean pointsAtThisMachine(String jdbcUrl) {
    if (jdbcUrl == null || jdbcUrl.isBlank()) return false;
    try {
      // jdbc:postgresql://host:port/db  ->  authority is everything after "//"
      int schemeEnd = jdbcUrl.indexOf("//");
      if (schemeEnd < 0) return false;
      String authority = jdbcUrl.substring(schemeEnd + 2);
      int slash = authority.indexOf('/');
      if (slash >= 0) authority = authority.substring(0, slash);
      int colon = authority.lastIndexOf(':');
      String host = colon >= 0 ? authority.substring(0, colon) : authority;
      if (host.isBlank()) return true;

      String normalized = host.toLowerCase();
      if (normalized.equals("localhost") || normalized.equals("[::1]") || normalized.equals("::1")) {
        return true;
      }
      // A loopback address in any of its dotted forms.
      URI probe = URI.create("http://" + host);
      String address = probe.getHost();
      return address != null
          && (address.startsWith("127.") || address.equals("0:0:0:0:0:0:0:1"));
    } catch (RuntimeException ex) {
      // An address we cannot parse is treated as remote, which is the safe direction.
      return false;
    }
  }
}
