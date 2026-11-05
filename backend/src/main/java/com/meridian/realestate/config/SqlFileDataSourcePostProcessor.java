package com.meridian.realestate.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Runs the whole application from the SQL files, with no database to point it at.
 *
 * <p><b>Why this class exists.</b> The schema and the entire demonstration portfolio are the Flyway
 * chain in {@code db/migration}. Normally those files are applied to a PostgreSQL somebody had to
 * install, create and configure first. This makes them self-sufficient: at startup it starts a
 * PostgreSQL on this machine, in a temporary directory, and the migration chain seeds it. Nothing is
 * read from any pre-existing database, and nothing written survives the process.</p>
 *
 * <p><b>Why PostgreSQL and not an in-memory database.</b> The chain cannot be run by anything else. It
 * is written in PL/pgSQL - nine {@code DO $$} blocks with loops and {@code RAISE EXCEPTION} guards,
 * {@code gen_random_uuid()}, {@code LATERAL}, {@code generate_series}, partial indexes, {@code jsonb},
 * {@code ON CONFLICT}, interval arithmetic. Porting it would be a rewrite rather than a translation,
 * and a rewritten seed produces <i>reconstructed</i> data rather than the same data. Starting a real
 * PostgreSQL is the only way to honour "the same data" exactly, so the demonstration portfolio is
 * byte-identical to what an installation seeds - including the relative dating in {@code V23}, which
 * anchors every lease and invoice to the current date.</p>
 *
 * <p><b>Why an {@link EnvironmentPostProcessor}.</b> The datasource is built from the resolved
 * environment, so by bean-creation time the URL has already been read. This is the same reason
 * {@link DataSourceUrlNormaliser} is one, and it is registered beside it in
 * {@code META-INF/spring.factories}.</p>
 *
 * <p><b>Ordering, which is deliberate.</b> {@link DataSourceUrlNormaliser} also calls
 * {@code addFirst}. Adding first puts a source at the top of the list, so the processor that runs
 * <i>last</i> wins. This one therefore declares {@link Ordered#LOWEST_PRECEDENCE} so that an ephemeral
 * database, when it has been asked for explicitly, is authoritative over whatever the environment
 * happened to carry.</p>
 *
 * <p><b>What it deliberately does not do.</b> It does not touch {@code DB_SCHEMA}, so the schema, the
 * migrations and Hibernate's {@code default_schema} behave exactly as they do against a real server:
 * Flyway still owns the schema and still creates it, and {@code SchemaConformanceTest} and
 * {@code ddl-auto: validate} still hold the same line. It does not weaken {@link SecretStartupGuard}:
 * the URL it publishes is a {@code localhost} one, so it is correctly recognised as local.</p>
 */
public class SqlFileDataSourcePostProcessor implements EnvironmentPostProcessor, Ordered {

  /**
   * A logger that holds its messages until there is a logging system to write them to.
   *
   * <p>Needed because of when this runs. Every {@code EnvironmentPostProcessor} is invoked during
   * {@code ApplicationEnvironmentPreparedEvent}, and Boot configures Logback on the <i>same</i> event
   * but later, so an ordinary logger here writes to nothing: the warnings below - the one telling the
   * operator this instance is deliberately ephemeral, above all - would be dropped without trace.
   * {@link DeferredLogFactory} is Boot's answer, and its constructor is injected by
   * {@code EnvironmentPostProcessorApplicationListener}. See {@link LoggingApplicationListener}.</p>
   */
  private final org.apache.commons.logging.Log log;

  /** Kept for the one line that must not depend on the factory having been injected. */
  private static final Logger fallbackLog =
      LoggerFactory.getLogger(SqlFileDataSourcePostProcessor.class);

  /** The property that selects the mode. Mirrors {@code app.data-source} in application.yml. */
  static final String MODE_PROPERTY = "app.data-source";

  /** The mode that runs from the SQL files. The default, {@code postgres}, is unchanged behaviour. */
  static final String SQL_FILE_MODE = "sqlfiles";

  /** {@code app.env} values for which an ephemeral database is legitimate. */
  private static final Set<String> LOCAL_ENVIRONMENTS = Set.of("development", "local", "test");

  private static final String SOURCE_NAME = "sqlFileDataSource";

  /**
   * The shape of the temporary cluster directories this class creates:
   * {@code meridian-sqlfiles-pg-<owning jvm pid>-<random>}.
   *
   * <p>The owning process id is in the <i>name</i> rather than in a marker file written afterwards
   * because the name is assigned atomically by {@code createTempDirectory}. A marker file would leave
   * a window - directory on disk, marker not yet written - in which a concurrent start could decide
   * the directory was unowned and delete it, and that window is precisely a cluster being initialised.
   * The random suffix is what {@code createTempDirectory} appends to keep the name unique.</p>
   */
  private static final Pattern CLUSTER_DIRECTORY =
      Pattern.compile("^meridian-sqlfiles-pg-(\\d+)-(\\d+)$");

  /** The prefix those names share, so a directory of ours is recognisable before it is parsed. */
  static final String DATA_DIRECTORY_PREFIX = "meridian-sqlfiles-pg-";

  /** How long a cluster is given to shut down on its way out before it is killed outright. */
  private static final int SHUTDOWN_GRACE_SECONDS = 10;

  /** The cluster the embedded server initialises itself with. Not a credential: it never leaves. */
  private static final String EMBEDDED_SUPERUSER = "postgres";
  private static final String EMBEDDED_DATABASE = "postgres";

  /**
   * The running server, held statically because this hook is not a bean.
   *
   * <p>Guarded so that a second context in the same JVM - which is what a test suite creates - reuses
   * the server rather than starting a second one on a second port.</p>
   */
  private static EmbeddedPostgres embedded;

  public SqlFileDataSourcePostProcessor(DeferredLogFactory logFactory) {
    this.log = logFactory.getLog(SqlFileDataSourcePostProcessor.class);
  }

  @Override
  public int getOrder() {
    // Runs last so that its addFirst lands above every other source, including DataSourceUrlNormaliser.
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {

    if (!isSqlFileMode(environment)) return;

    refuseIfNotLocal(environment);

    if (!environment.getProperty("DATABASE_URL", "").isBlank()) {
      log.warn(
          "DATABASE_URL is set but "
              + MODE_PROPERTY
              + "="
              + SQL_FILE_MODE
              + " was asked for, so it is ignored: this instance is reading the SQL files, not that "
              + "database.");
    }

    EmbeddedPostgres server = embedded();
    int port = server.getPort();

    Map<String, Object> values = new LinkedHashMap<>();
    values.put("spring.datasource.url", server.getJdbcUrl(EMBEDDED_SUPERUSER, EMBEDDED_DATABASE));
    values.put("spring.datasource.username", EMBEDDED_SUPERUSER);
    values.put("spring.datasource.password", EMBEDDED_SUPERUSER);

    environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, values));

    log.warn(
        "Data source is the SQL files: an ephemeral PostgreSQL is listening on localhost:"
            + port
            + " and the schema and the demonstration portfolio are being applied from db/migration "
            + "alone. Nothing is read from another database and nothing written here survives a "
            + "restart.");
  }

  /**
   * Start the embedded server once, or hand back the one already running.
   *
   * <p>A fresh temporary directory each time is the point rather than an accident: the migration
   * chain then applies from empty on every start. A persistent cluster would instead skip everything
   * Flyway had already recorded, and the demonstration data would quietly age until the trend charts
   * fell outside their window and the leases all read as expired.</p>
   */
  private synchronized EmbeddedPostgres embedded() {
    if (embedded != null && embedded.getProcess().isAlive()) return embedded;

    sweepAbandonedClusters();

    try {
      Path dataDirectory =
          Files.createTempDirectory(DATA_DIRECTORY_PREFIX + ProcessHandle.current().pid() + "-");
      embedded =
          EmbeddedPostgres.builder()
              // Port 0 asks the operating system for a free one, so an installation that already runs
              // a PostgreSQL on 5432 is not disturbed and two instances can run side by side.
              .setPort(0)
              .setDataDirectory(dataDirectory)
              .setCleanDataDirectory(true)
              // Explicit rather than relied upon: the server has to be stopped when this JVM exits,
              // or a killed build leaves a postgres process behind holding the port.
              .setRegisterShutdownHook(true)
              .start();
      return embedded;
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Could not start the embedded PostgreSQL that serves the SQL-file data source. On Windows "
              + "this usually means the Microsoft Visual C++ 2013 Redistributable is not installed; "
              + "alternatively set "
              + MODE_PROPERTY
              + "=postgres and point DB_HOST/DB_NAME at an existing server.",
          ex);
    }
  }

  /**
   * Reclaim clusters left behind by an earlier run that was killed rather than shut down.
   *
   * <p><b>Why this is necessary.</b> The server is stopped by a shutdown hook, and a JVM runs
   * shutdown hooks only when it is asked to stop - a {@code SIGTERM}, a Ctrl-C. A {@code SIGKILL}, a
   * debugger, or a build tool tearing down a process group skips them, and the postmaster is then left
   * running under nobody's supervision with its temporary directory still on disk. Nothing else would
   * ever remove either, so an instance killed a few times would accumulate database servers and data
   * directories for as long as the machine stays up. Running the sweep before each start means the
   * cost of a crash is paid once, by the next start.</p>
   *
   * <p><b>How it decides.</b> The owning process id comes from the directory name, so there is no
   * guessing and no window: a cluster whose owner is gone cannot be being used by anyone, whether it
   * died before its server started or left one running. A cluster whose owner is still alive belongs
   * to a running instance and is left alone - which is what keeps two instances side by side safe.
   * A directory that carries the prefix but not the shape is left alone too: it is not something this
   * class created, and deleting it would be guessing.</p>
   */
  private void sweepAbandonedClusters() {
    Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
    List<Path> abandoned;
    try (Stream<Path> entries = Files.list(tempDirectory)) {
      abandoned =
          entries
              .filter(Files::isDirectory)
              .filter(SqlFileDataSourcePostProcessor::isAbandoned)
              .toList();
    } catch (IOException ex) {
      // A temporary directory that cannot be listed is not a reason to refuse to start.
      log.debug("Could not inspect " + tempDirectory + " for abandoned SQL-file clusters.", ex);
      return;
    }

    for (Path cluster : abandoned) {
      log.info(
          "Reclaiming the embedded PostgreSQL left behind at "
              + cluster
              + " by a previous run that was killed rather than shut down.");
      stopPostmaster(cluster);
      if (!FileUtils.deleteQuietly(cluster.toFile())) {
        log.warn(
            "Left "
                + cluster
                + " in place: it could not be removed, which usually means something still holds "
                + "it.");
      }
    }
  }

  /** True when this directory is one of ours and the process that owned it is no longer running. */
  static boolean isAbandoned(Path directory) {
    Matcher matcher = CLUSTER_DIRECTORY.matcher(directory.getFileName().toString());
    if (!matcher.matches()) return false;

    long owner;
    try {
      owner = Long.parseLong(matcher.group(1));
    } catch (NumberFormatException ex) {
      return false;
    }
    // Alive means a running instance is using it. Anything else - gone, or never existed - means the
    // directory is nobody's, whatever it still contains.
    return ProcessHandle.of(owner).filter(ProcessHandle::isAlive).isEmpty();
  }

  /** Stop the server holding this cluster, so its files can be removed on every platform. */
  private static void stopPostmaster(Path cluster) {
    Path pidFile = cluster.resolve("postmaster.pid");
    if (!Files.isRegularFile(pidFile)) return;
    try {
      long pid = Long.parseLong(Files.readAllLines(pidFile).getFirst().trim());
      ProcessHandle.of(pid)
          .ifPresent(
              postmaster -> {
                postmaster.destroy();
                try {
                  postmaster.onExit().get(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException ex) {
                  // Refused to go quietly. It is not serving anyone, so it does not get a vote.
                  postmaster.destroyForcibly();
                }
              });
    } catch (IOException | RuntimeException ex) {
      fallbackLog.debug("Could not stop the postmaster recorded in {}.", pidFile, ex);
    }
  }

  /** True when this installation asked to be run from the SQL files. */
  static boolean isSqlFileMode(ConfigurableEnvironment environment) {
    String mode = environment.getProperty(MODE_PROPERTY, "");
    return mode.trim().toLowerCase(Locale.ROOT).equals(SQL_FILE_MODE);
  }

  /**
   * Refuse an ephemeral database off a developer's machine.
   *
   * <p>The failure this prevents is a production instance that starts, serves the demonstration
   * portfolio, looks perfectly healthy, and loses every record its users create when it restarts -
   * because a mode meant for looking at the SQL files was inherited rather than chosen. Which
   * environment this is, is the question, so {@code app.env} is the test.</p>
   */
  static void refuseIfNotLocal(ConfigurableEnvironment environment) {
    String env = environment.getProperty("app.env", "development");
    if (env == null || env.isBlank()) return;
    if (LOCAL_ENVIRONMENTS.contains(env.trim().toLowerCase(Locale.ROOT))) return;

    throw new IllegalStateException(
        "Refusing to start: "
            + MODE_PROPERTY
            + "="
            + SQL_FILE_MODE
            + " runs an ephemeral PostgreSQL that is discarded on every restart, and this instance "
            + "is configured as environment '"
            + env
            + "'. That mode is for looking at the SQL files on a development machine. Set "
            + MODE_PROPERTY
            + "=postgres and point DB_HOST/DB_NAME/DATABASE_URL at a real database for this "
            + "installation.");
  }
}
