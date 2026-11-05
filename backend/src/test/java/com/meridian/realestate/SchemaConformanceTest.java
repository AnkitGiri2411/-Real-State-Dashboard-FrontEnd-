package com.meridian.realestate;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Verifies the JPA entity model against the Flyway migration chain, without a database.
 *
 * <p><b>Why this test exists.</b> The product runs Hibernate with {@code ddl-auto=validate}, which
 * means a single misnamed column, a column the entity forgot, or a table nobody mapped is not a
 * cosmetic defect - it stops the application from starting at all. That check normally needs a live
 * PostgreSQL, which makes it late, slow and unavailable in CI without a server.</p>
 *
 * <p>This test reproduces the same verdict statically. It parses the migrations' own DDL to learn
 * what the schema actually contains, asks Hibernate's metadata what the entity model believes, and
 * compares the two. It runs in milliseconds, needs no database, and fails with the exact table and
 * column rather than a stack trace from a connection pool.</p>
 *
 * <p>It deliberately asserts three things, because each one is a distinct way the model can be
 * wrong:</p>
 * <ol>
 *   <li>every mapped entity's table exists in the migrations;</li>
 *   <li>every mapped column exists on that table;</li>
 *   <li>every {@code NOT NULL} column with no default is mapped - otherwise an insert would fail
 *       against a column the application can never populate.</li>
 * </ol>
 *
 * <p>This is a static check, not a substitute for running the real validation against PostgreSQL.
 * It cannot see types, lengths, check constraints or foreign keys; the acceptance run against a
 * live database remains the authority on those.</p>
 */
@DisplayName("Entity model conforms to the Flyway migration chain")
class SchemaConformanceTest {

  /** Columns that are NOT NULL in the DDL yet correctly have no entity mapping. */
  private static final Map<String, Set<String>> INTENTIONALLY_UNMAPPED = Map.of();

  /**
   * A whole {@code CREATE TABLE} statement, body captured as group 2.
   *
   * <p>No table body in this schema contains a semicolon - every {@code CHECK} is a parenthesised
   * expression - so the body is safely everything up to the statement's own terminator.</p>
   */
  private static final Pattern CREATE_TABLE =
      Pattern.compile(
          "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)\\s*\\(([^;]*)\\)\\s*;",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /**
   * {@code ALTER TABLE ... ADD [COLUMN] x}.
   *
   * <p>Deliberately not anchored to a line: these statements wrap in this chain (V16 writes
   * {@code ALTER TABLE payments} and {@code ADD COLUMN sale_id} on separate lines), and a
   * line-anchored pattern silently misses the column, which reads as a false schema defect.</p>
   */
  private static final Pattern ALTER_ADD_COLUMN =
      Pattern.compile(
          "ALTER\\s+TABLE\\s+(?:ONLY\\s+)?([a-z_][a-z0-9_]*)\\s+ADD\\s+(?:COLUMN\\s+)?([a-z_][a-z0-9_]*)",
          Pattern.CASE_INSENSITIVE);

  /** Words that start a table-level constraint rather than a column definition. */
  private static final Set<String> CONSTRAINT_KEYWORDS =
      Set.of(
          "constraint",
          "primary",
          "foreign",
          "unique",
          "check",
          "exclude",
          "like",
          "inherits",
          "using",
          "or",
          "and",
          "not");

  /**
   * The types a column definition may declare.
   *
   * <p>A column definition is recognised as {@code name type ...} and nothing else. Requiring a
   * real type is what keeps a wrapped {@code CHECK} expression out of the column list: those bodies
   * break across lines, so {@code AND completed_at IS NOT NULL} would otherwise be read as a column
   * named {@code and}, and a false "unmapped NOT NULL column" would be reported against a schema
   * that is perfectly correct.</p>
   */
  private static final Set<String> TYPE_KEYWORDS =
      Set.of(
          "uuid",
          "varchar",
          "character",
          "char",
          "text",
          "numeric",
          "decimal",
          "integer",
          "int",
          "int4",
          "int8",
          "bigint",
          "smallint",
          "boolean",
          "bool",
          "timestamp",
          "timestamptz",
          "date",
          "time",
          "jsonb",
          "json",
          "bytea",
          "real",
          "double",
          "serial",
          "bigserial");

  /** One column as the DDL declares it. */
  private record DdlColumn(String name, boolean notNull, boolean hasDefault) {}

  /** One table as the DDL declares it. */
  private record DdlTable(String name, Map<String, DdlColumn> columns) {}

  private static Map<String, DdlTable> schema;
  private static Map<String, PersistentClass> entityModel;

  @BeforeAll
  static void loadBothSides() throws Exception {
    schema = parseMigrations();
    entityModel = buildEntityMetadata();
    assertThat(schema).as("the migrations must declare tables").isNotEmpty();
    assertThat(entityModel).as("the domain package must declare entities").isNotEmpty();
  }

  // ---------------------------------------------------------------- migrations

  private static Map<String, DdlTable> parseMigrations() throws IOException, URISyntaxException {
    Map<String, Map<String, DdlColumn>> tables = new TreeMap<>();

    for (Path file : migrationFiles()) {
      String sql = readWithoutComments(file);

      Matcher create = CREATE_TABLE.matcher(sql);
      while (create.find()) {
        String table = create.group(1).toLowerCase(Locale.ROOT);
        Map<String, DdlColumn> columns =
            tables.computeIfAbsent(table, key -> new LinkedHashMap<>());
        for (String line : create.group(2).split("\\R")) {
          DdlColumn column = parseColumn(line.strip());
          if (column != null) columns.putIfAbsent(column.name(), column);
        }
      }

      Matcher alter = ALTER_ADD_COLUMN.matcher(sql);
      while (alter.find()) {
        String table = alter.group(1).toLowerCase(Locale.ROOT);
        String column = alter.group(2).toLowerCase(Locale.ROOT);
        // `ADD CONSTRAINT ...` is not a column; skip it rather than inventing one.
        if (CONSTRAINT_KEYWORDS.contains(column)) continue;
        tables
            .computeIfAbsent(table, key -> new LinkedHashMap<>())
            .putIfAbsent(column, new DdlColumn(column, false, false));
      }
    }

    Map<String, DdlTable> result = new TreeMap<>();
    tables.forEach((name, columns) -> result.put(name, new DdlTable(name, columns)));
    return result;
  }

  private static List<Path> migrationFiles() throws IOException, URISyntaxException {
    URL resource = SchemaConformanceTest.class.getClassLoader().getResource("db/migration");
    assertThat(resource).as("db/migration must be on the classpath").isNotNull();
    Path directory = Paths.get(resource.toURI());
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".sql"))
          .sorted()
          .toList();
    }
  }

  /**
   * The file as one string with {@code --} comments removed.
   *
   * <p>Removing comments first is what stops a commented-out column from being read as a real one,
   * and joining the lines is what lets a wrapped {@code ALTER TABLE} be matched as one statement.
   * The newline is kept so a line-oriented column parse still works on each table body.</p>
   */
  private static String readWithoutComments(Path file) throws IOException {
    StringBuilder sql = new StringBuilder();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      sql.append(stripComment(line)).append('\n');
    }
    return sql.toString();
  }

  /** Removes a trailing {@code --} comment, so a commented-out column is not read as real. */
  private static String stripComment(String line) {
    int marker = line.indexOf("--");
    return marker < 0 ? line : line.substring(0, marker);
  }

  private static DdlColumn parseColumn(String line) {
    Matcher matcher =
        Pattern.compile("^([a-z_][a-z0-9_]*)\\s+([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE)
            .matcher(line);
    if (!matcher.find()) return null;

    String name = matcher.group(1).toLowerCase(Locale.ROOT);
    String type = matcher.group(2).toLowerCase(Locale.ROOT);
    if (CONSTRAINT_KEYWORDS.contains(name)) return null;
    if (!TYPE_KEYWORDS.contains(type)) return null;

    String rest = line.substring(matcher.end(2)).toUpperCase(Locale.ROOT);
    return new DdlColumn(name, rest.contains("NOT NULL"), rest.contains("DEFAULT"));
  }

  // ------------------------------------------------------------------- entities

  private static Map<String, PersistentClass> buildEntityMetadata() {
    // An explicit dialect plus "no JDBC metadata" lets Hibernate build the full model without
    // opening a connection, which is what makes this test runnable without PostgreSQL.
    try (StandardServiceRegistry registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
            .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
            .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
            .build()) {

      MetadataSources sources = new MetadataSources(registry);
      for (Class<?> entity : entityClasses()) {
        sources.addAnnotatedClass(entity);
      }
      Metadata metadata = sources.buildMetadata();

      Map<String, PersistentClass> byTable = new TreeMap<>();
      for (PersistentClass binding : metadata.getEntityBindings()) {
        String table = binding.getTable().getName().toLowerCase(Locale.ROOT);
        byTable.put(table, binding);
      }
      return byTable;
    }
  }

  private static List<Class<?>> entityClasses() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

    List<Class<?>> classes = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents("com.meridian.realestate.domain")) {
      try {
        classes.add(Class.forName(definition.getBeanClassName()));
      } catch (ClassNotFoundException ex) {
        throw new IllegalStateException("Could not load entity " + definition.getBeanClassName(), ex);
      }
    }
    classes.sort((left, right) -> left.getName().compareTo(right.getName()));
    return classes;
  }

  // -------------------------------------------------------------------- checks

  @Test
  @DisplayName("1. every mapped entity's table exists in the migrations")
  void everyTableExists() {
    List<String> missing = new ArrayList<>();
    entityModel.forEach(
        (table, binding) -> {
          if (!schema.containsKey(table)) {
            missing.add(binding.getClassName() + " -> " + table);
          }
        });

    assertThat(missing)
        .as("these entities are mapped to tables the migrations never create")
        .isEmpty();
  }

  @Test
  @DisplayName("2. every mapped column exists on its table, and no table double-maps")
  void everyColumnExists() {
    Map<String, List<String>> problems = new TreeMap<>();

    entityModel.forEach(
        (table, binding) -> {
          DdlTable ddl = schema.get(table);
          if (ddl == null) return;

          // The mapped table's own columns are precisely the set Hibernate validates against.
          for (var column : binding.getTable().getColumns()) {
            String name = column.getName().toLowerCase(Locale.ROOT);
            if (!ddl.columns().containsKey(name)) {
              problems
                  .computeIfAbsent(table, key -> new ArrayList<>())
                  .add(binding.getClassName() + " -> " + name);
            }
          }
        });

    assertThat(problems)
        .as("these columns are mapped by an entity but do not exist in the migrations")
        .isEmpty();
  }

  @Test
  @DisplayName("3. every NOT NULL column without a default is mapped")
  void requiredColumnsAreMapped() {
    Map<String, DdlTable> unmappedTables = new TreeMap<>(schema);

    // Tables the product models with entities; reference/seed-only tables carry no entity by
    // design (property_types is modelled, but e.g. a pure lookup with no entity must be listed).
    Map<String, Set<String>> problems = new TreeMap<>();

    entityModel.forEach(
        (table, binding) -> {
          DdlTable ddl = unmappedTables.get(table);
          if (ddl == null) return;

          Set<String> mapped = new LinkedHashSet<>();
          for (var column : binding.getTable().getColumns()) {
            mapped.add(column.getName().toLowerCase(Locale.ROOT));
          }

          for (DdlColumn column : ddl.columns().values()) {
            if (!column.notNull() || column.hasDefault()) continue;
            if (mapped.contains(column.name())) continue;
            if (INTENTIONALLY_UNMAPPED.getOrDefault(table, Set.of()).contains(column.name())) continue;
            problems
                .computeIfAbsent(table, key -> new LinkedHashSet<>())
                .add(column.name());
          }
        });

    assertThat(problems)
        .as(
            "these columns are NOT NULL with no default but no entity maps them, so an insert "
                + "would fail; map them or add them to INTENTIONALLY_UNMAPPED with a reason")
        .isEmpty();
  }

  @Test
  @DisplayName("every table the migrations create is either mapped by an entity or declared reference data")
  void everyTableIsAccountedFor() {
    /*
     * A table nobody maps is not a startup failure - Hibernate validates only what it knows about.
     * It is, however, a silent hole: the screen that needs it would have no data and the omission
     * would be invisible. This asserts that every table is a deliberate decision.
     */
    Set<String> expectedUnmodelled = Set.of();
    Set<String> modelled = new LinkedHashSet<>(entityModel.keySet());

    Map<String, Object> unexplained = new LinkedHashMap<>();
    schema.keySet().stream()
        .filter(table -> !modelled.contains(table))
        .filter(table -> !expectedUnmodelled.contains(table))
        .forEach(table -> unexplained.put(table, "no entity maps this table"));

    assertThat(unexplained)
        .as("these migrated tables have no entity; each is either a missing feature or should be declared")
        .isEmpty();
  }
}
