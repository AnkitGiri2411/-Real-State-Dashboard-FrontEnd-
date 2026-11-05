package com.meridian.realestate.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.ImportEntityType;
import com.meridian.realestate.domain.ImportJob;
import com.meridian.realestate.domain.ImportJobRow;
import com.meridian.realestate.domain.ImportJobStatus;
import com.meridian.realestate.domain.ImportRowStatus;
import com.meridian.realestate.domain.ImportSourceType;
import com.meridian.realestate.domain.Lead;
import com.meridian.realestate.domain.LeadPriority;
import com.meridian.realestate.domain.LeadType;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.LeaseStatus;
import com.meridian.realestate.domain.Owner;
import com.meridian.realestate.domain.OwnerType;
import com.meridian.realestate.domain.Payment;
import com.meridian.realestate.domain.PaymentDirection;
import com.meridian.realestate.domain.PaymentMethod;
import com.meridian.realestate.domain.PaymentStatus;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyStatus;
import com.meridian.realestate.domain.PropertyType;
import com.meridian.realestate.domain.RentFrequency;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.TenantType;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.UnitFacing;
import com.meridian.realestate.domain.UnitFurnishing;
import com.meridian.realestate.domain.UnitStatus;
import com.meridian.realestate.domain.UnitType;
import com.meridian.realestate.dto.ImportDtos;
import com.meridian.realestate.dto.LeadDtos;
import com.meridian.realestate.dto.LeaseDtos;
import com.meridian.realestate.dto.PaymentDtos;
import com.meridian.realestate.dto.PropertyDtos;
import com.meridian.realestate.dto.UnitDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.ImportJobRepository;
import com.meridian.realestate.repository.ImportJobRowRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PaymentMethodRepository;
import com.meridian.realestate.repository.PaymentRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.PropertyTypeRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.UnitTypeRepository;
import com.meridian.realestate.repository.UserRepository;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data import.
 *
 * <h2>Two steps, on purpose</h2>
 * A file is staged and previewed, and only then confirmed. {@code preview} parses the upload in
 * memory, validates every row and records what it found - <b>without writing a single property,
 * unit, owner, tenant, lead, lease or receipt</b>. {@code confirm} is the only thing that writes,
 * and it writes only the rows that passed validation.
 *
 * <h2>The file is never written to disk</h2>
 * The upload is read into a byte array, its format is decided from the bytes themselves, and it is
 * parsed from an in-memory stream. There is no temporary file, so there is no directory to clean, no
 * path to traverse and no file name that is ever opened - the name is a label on the job and
 * nothing else.
 *
 * <h2>Format detection is by content, not by name</h2>
 * A browser's extension and MIME type are hints a caller controls, so neither decides the parser.
 * The magic bytes do: the ZIP signature for {@code xlsx}, the OLE2 signature for {@code xls}, and
 * otherwise a genuine delimited-text check. A file whose bytes match no supported format is refused
 * with {@code IMPORT_FILE_TYPE_UNSUPPORTED} rather than fed to a parser that would misread it.
 *
 * <h2>Idempotency</h2>
 * A job carries a unique {@code idempotencyKey}. {@code confirm} sets it to the key the caller
 * offers; a repeated confirm with the same key returns the first run's result and writes nothing,
 * and a <em>different</em> key against a job that is already committed is refused with
 * {@code IMPORT_IDEMPOTENCY_CONFLICT} rather than silently ignored. The unique column enforces the
 * same rule at the database, so it holds under a genuine race and not only in the happy path.
 *
 * <h2>Row failures do not undo the import</h2>
 * One bad line must not throw away ninety-nine good ones, so a row whose dependency cannot be
 * resolved at commit time - a property type that does not exist, a unit code no register holds - is
 * recorded as {@code FAILED} with its reason and the import carries on. Failures that would poison
 * the surrounding transaction (a genuine database constraint violation) are the one case this cannot
 * fully isolate inside a single transaction; the pre-commit validation is written to keep those
 * rare.
 *
 * <h2>Numbering, ownership and the trail are the same as a manual create</h2>
 * Every valid row is inserted through the domain service that owns its entity type -
 * {@link PropertyService#create}, {@link UnitService#create}, {@link LeadService#create},
 * {@link LeaseService#createLease} and {@link PaymentService#record} - never by writing to a
 * repository directly. That is what makes an imported record indistinguishable from one keyed in by
 * hand: the code is minted by {@link BusinessNumberService}, the record is stamped with the
 * signed-in account as its owner, the create path's own rules are enforced, and each insert leaves
 * its own audit entry. The commit itself is recorded under the {@link AuditActions#IMPORT} module
 * with {@link AuditActions#IMPORT_COMMITTED}. The entity types this module has no create service for
 * (owners and tenants) are simply not offered, rather than routed around the services.
 *
 * <h2>Insert-only</h2>
 * Nothing here updates or deletes anything. A correction is another import - which is why a
 * committed row records the identifier it produced and is never written a second time.
 */
@Service
public class ImportService {

  private static final Logger log = LoggerFactory.getLogger(ImportService.class);

  /** The hard ceiling on rows parsed, so a decompression-bomb spreadsheet cannot exhaust memory. */
  private static final int ABSOLUTE_MAX_ROWS = 100_000;

  /** The named columns each entity type accepts, in the order the preview shows them. */
  private static final Map<ImportEntityType, List<String>> COLUMNS = columns();

  /** The subset of those columns a row cannot be imported without. */
  private static final Map<ImportEntityType, List<String>> REQUIRED = required();

  private final ImportJobRepository jobs;
  private final ImportJobRowRepository rows;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final UserRepository users;
  private final ObjectMapper objectMapper;
  private final AppProperties config;

  // Lookups that resolve a file's codes to the identifiers a create request names.
  private final PropertyRepository propertyRepository;
  private final PropertyTypeRepository propertyTypes;
  private final UnitRepository unitRepository;
  private final UnitTypeRepository unitTypes;
  private final TenantRepository tenants;
  private final LeaseRepository leases;
  private final PaymentMethodRepository paymentMethods;

  // The domain services that actually insert. Routing through them - rather than the repositories -
  // is what makes an imported record identical to one keyed in by hand: the code is minted by
  // BusinessNumberService, the entry is written to the audit trail, the record is stamped with the
  // signed-in account as its owner, and every business rule the create path enforces is enforced.
  private final PropertyService propertyService;
  private final UnitService unitService;
  private final LeadService leadService;
  private final LeaseService leaseService;
  private final PaymentService paymentService;

  public ImportService(
      ImportJobRepository jobs,
      ImportJobRowRepository rows,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      UserRepository users,
      ObjectMapper objectMapper,
      AppProperties config,
      PropertyRepository propertyRepository,
      PropertyTypeRepository propertyTypes,
      UnitRepository unitRepository,
      UnitTypeRepository unitTypes,
      TenantRepository tenants,
      LeaseRepository leases,
      PaymentMethodRepository paymentMethods,
      PropertyService propertyService,
      UnitService unitService,
      LeadService leadService,
      LeaseService leaseService,
      PaymentService paymentService) {
    this.jobs = jobs;
    this.rows = rows;
    this.numbers = numbers;
    this.audit = audit;
    this.users = users;
    this.objectMapper = objectMapper;
    this.config = config;
    this.propertyRepository = propertyRepository;
    this.propertyTypes = propertyTypes;
    this.unitRepository = unitRepository;
    this.unitTypes = unitTypes;
    this.tenants = tenants;
    this.leases = leases;
    this.paymentMethods = paymentMethods;
    this.propertyService = propertyService;
    this.unitService = unitService;
    this.leadService = leadService;
    this.leaseService = leaseService;
    this.paymentService = paymentService;
  }

  private static Map<ImportEntityType, List<String>> columns() {
    Map<ImportEntityType, List<String>> map = new EnumMap<>(ImportEntityType.class);
    map.put(
        ImportEntityType.PROPERTY,
        List.of(
            "name", "propertyTypeCode", "status", "addressLine1", "addressLine2", "locality", "city",
            "state", "postalCode", "country", "acquisitionValue", "currentValuation",
            "valuationDate"));
    map.put(
        ImportEntityType.UNIT,
        List.of(
            "propertyCode", "unitTypeCode", "unitNumber", "floorNumber", "floorLabel", "bedrooms",
            "bathrooms", "balconies", "furnishing", "facing", "status", "leasable", "askingRent",
            "askingPrice"));
    map.put(
        ImportEntityType.OWNER,
        List.of(
            "ownerCode", "ownerType", "displayName", "primaryContactName", "email", "phone", "city"));
    map.put(
        ImportEntityType.TENANT,
        List.of(
            "tenantCode", "tenantType", "displayName", "primaryContactName", "email", "phone", "city"));
    map.put(
        ImportEntityType.LEAD,
        List.of(
            "leadType", "priority", "displayName", "companyName", "email", "phone", "alternatePhone",
            "preferredCity", "preferredLocality", "minBudget", "maxBudget", "minBedrooms",
            "requirementNotes", "nextAction", "expectedValue"));
    map.put(
        ImportEntityType.LEASE,
        List.of(
            "propertyCode", "unitCode", "tenantCode", "startDate", "endDate", "rentAmount",
            "rentFrequency", "dueDay", "depositAmount", "noticePeriodDays", "agreementReference"));
    map.put(
        ImportEntityType.PAYMENT,
        List.of(
            "leaseCode", "amount", "currency", "receivedAt", "methodCode", "status", "reference",
            "notes"));
    return map;
  }

  private static Map<ImportEntityType, List<String>> required() {
    Map<ImportEntityType, List<String>> map = new EnumMap<>(ImportEntityType.class);
    map.put(ImportEntityType.PROPERTY, List.of("name", "propertyTypeCode", "addressLine1", "city"));
    map.put(ImportEntityType.UNIT, List.of("propertyCode", "unitTypeCode", "unitNumber"));
    map.put(ImportEntityType.OWNER, List.of("displayName"));
    map.put(ImportEntityType.TENANT, List.of("displayName"));
    map.put(ImportEntityType.LEAD, List.of("displayName"));
    map.put(
        ImportEntityType.LEASE,
        List.of("unitCode", "tenantCode", "startDate", "endDate", "rentAmount"));
    map.put(ImportEntityType.PAYMENT, List.of("leaseCode", "amount", "receivedAt", "methodCode"));
    return map;
  }

  /**
   * The entity types this installation imports.
   *
   * <p>Five of the seven {@link ImportEntityType} values. {@code OWNER} and {@code TENANT} are
   * <em>not</em> offered because this module carries no create service for them, and the import must
   * route through the domain services rather than writing to a repository directly - so an option
   * that would have to bypass that rule is left out rather than quietly doing the wrong thing.</p>
   */
  private static final List<ImportEntityType> SUPPORTED =
      List.of(
          ImportEntityType.PROPERTY,
          ImportEntityType.UNIT,
          ImportEntityType.LEAD,
          ImportEntityType.LEASE,
          ImportEntityType.PAYMENT);

  /** The entity types this installation can import, so the screen offers only those. */
  public List<ImportDtos.SupportedImport> supportedImports() {
    List<ImportDtos.SupportedImport> supported = new ArrayList<>();
    for (ImportEntityType type : SUPPORTED) {
      supported.add(
          new ImportDtos.SupportedImport(
              type.name(), label(type), COLUMNS.get(type), REQUIRED.get(type)));
    }
    return supported;
  }

  // ----------------------------------------------------------------------------------------------
  // Preview
  // ----------------------------------------------------------------------------------------------

  /**
   * Parse and validate an uploaded file, staging the rows and writing nothing to the application
   * tables.
   *
   * @param entityType the entity type the file imports
   * @param filename the upload's name, used only as a label
   * @param content the file's bytes, in memory
   * @param actorId the signed-in account, recorded as the job's creator
   */
  @Transactional
  public ImportDtos.PreviewResult preview(
      ImportEntityType entityType, String filename, byte[] content, UUID actorId) {

    if (content == null || content.length == 0) {
      throw ApiException.badRequest("IMPORT_FILE_EMPTY: the uploaded file is empty");
    }

    long maxBytes = (long) config.getImports().getMaxFileSizeMb() * 1024L * 1024L;
    if (content.length > maxBytes) {
      throw ApiException.badRequest(
          "IMPORT_FILE_TOO_LARGE: that file is "
              + humanSize(content.length)
              + ", larger than this installation's "
              + config.getImports().getMaxFileSizeMb()
              + " MB limit");
    }

    ImportSourceType sourceType = detectSourceType(content);
    if (sourceType == null) {
      throw ApiException.badRequest(
          "IMPORT_FILE_TYPE_UNSUPPORTED: the file's content is not a CSV, xlsx or xls document");
    }

    ParsedFile parsed = parse(sourceType, content, entityType);

    if (parsed.header().isEmpty()) {
      throw ApiException.badRequest("IMPORT_HEADER_MISSING: the file has no header row");
    }

    List<String> missingRequired = missingRequiredColumns(entityType, parsed.header());
    if (!missingRequired.isEmpty()) {
      throw ApiException.badRequest(
          "IMPORT_REQUIRED_COLUMNS_MISSING: the file does not have "
              + String.join(", ", missingRequired),
          missingRequired.stream().map(column -> "column: " + column).toList());
    }

    if (parsed.rows().isEmpty()) {
      throw ApiException.badRequest("IMPORT_HEADER_MISSING: the file has a header but no data rows");
    }

    if (parsed.rows().size() > config.getImports().getMaxRows()) {
      throw ApiException.badRequest(
          "IMPORT_TOO_MANY_ROWS: that file has "
              + parsed.rows().size()
              + " rows; this installation imports at most "
              + config.getImports().getMaxRows()
              + " at a time");
    }

    ImportJob job = new ImportJob();
    job.setJobCode("ME-IMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
    job.setEntityType(entityType);
    job.setSourceType(sourceType);
    job.setOriginalFilename(filename == null || filename.isBlank() ? "upload." + extension(sourceType) : filename);
    job.setFileSizeBytes((long) content.length);
    job.setContentHash(sha256(content));
    job.setIdempotencyKey(UUID.randomUUID().toString());
    job.setStatus(ImportJobStatus.STAGED);
    job.setStagedAt(LocalDateTime.now());
    job.setCreatedBy(actorId);
    job.setColumnMapping(json(parsed.header()));
    ImportJob saved = jobs.save(job);

    int valid = 0;
    int invalid = 0;
    List<ImportDtos.RowSummary> previewRows = new ArrayList<>();
    List<ImportDtos.RowSummary> problems = new ArrayList<>();

    for (int index = 0; index < parsed.rows().size(); index++) {
      // The header is line 1, and index is zero-based, so the first data row is line 2 - the number
      // the operator will look for in their spreadsheet.
      int lineNumber = index + 2;
      List<String> values = parsed.rows().get(index);
      Map<String, String> raw = parsed.toRawRow(values);

      Validation validation = validate(entityType, raw, parsed.header());
      ImportJobRow row = new ImportJobRow();
      row.setJob(saved);
      row.setRowNumber(lineNumber);
      row.setRawData(json(raw));
      row.setMappedData(json(validation.mapped()));

      if (validation.errors().isEmpty()) {
        row.setStatus(ImportRowStatus.VALID);
        valid++;
      } else {
        row.setStatus(ImportRowStatus.INVALID);
        row.setValidationErrors(json(validation.errors()));
        invalid++;
      }
      if (!validation.warnings().isEmpty()) {
        row.setValidationWarnings(json(validation.warnings()));
      }

      ImportJobRow stored = rows.save(row);
      ImportDtos.RowSummary summary =
          new ImportDtos.RowSummary(
              stored.getId(),
              lineNumber,
              stored.getRawData(),
              stored.getMappedData(),
              stored.getStatus().name(),
              validation.errors(),
              validation.warnings(),
              null,
              null);
      if (previewRows.size() < 200) previewRows.add(summary);
      if (!validation.errors().isEmpty() && problems.size() < 200) problems.add(summary);
    }

    if (valid == 0) {
      // Nothing passed validation, so there is nothing to confirm. The transaction rolls back, so
      // the job and its staged rows leave no trace.
      throw ApiException.rule(
          "IMPORT_NO_VALID_ROWS",
          "Every row in that file failed validation, so there is nothing to import");
    }

    saved.setRowCount(valid + invalid);
    saved.setValidRowCount(valid);
    saved.setInvalidRowCount(invalid);
    saved.setStatus(ImportJobStatus.VALIDATED);
    saved.setValidatedAt(LocalDateTime.now());
    saved.setValidationSummary(
        json(Map.of("rowCount", valid + invalid, "valid", valid, "invalid", invalid)));
    jobs.save(saved);

    return new ImportDtos.PreviewResult(
        ImportDtos.JobSummary.from(saved), true, parsed.header(), previewRows, problems);
  }

  // ----------------------------------------------------------------------------------------------
  // Confirm
  // ----------------------------------------------------------------------------------------------

  /**
   * Write the staged, valid rows into the application tables.
   *
   * @param jobCode the staged job's code
   * @param idempotencyKey the deduplication key; a repeat returns the first result, a different key
   *     on a committed job is refused
   */
  @Transactional
  public ImportDtos.ConfirmResult confirm(String jobCode, String idempotencyKey, UUID actorId) {
    ImportJob job = requirePendingOrCommitted(jobCode);
    String key = blankToNull(idempotencyKey);

    if (job.getStatus() == ImportJobStatus.COMMITTED) {
      if (key != null && key.equals(job.getIdempotencyKey())) {
        // The whole point of the key: a double-click or a retry returns the first result and writes
        // nothing new.
        return resultOf(job, true);
      }
      throw ApiException.rule(
          "IMPORT_IDEMPOTENCY_CONFLICT",
          "Import "
              + job.getJobCode()
              + " was already committed"
              + (key == null
                  ? " and no idempotency key was supplied"
                  : " under a different idempotency key"));
    }

    if (!job.getStatus().isPending()) {
      throw ApiException.rule(
          "IMPORT_NOT_COMMITTABLE",
          "Import " + job.getJobCode() + " is " + job.getStatus() + " and cannot be committed");
    }

    if (key != null) {
      UUID jobId = job.getId();
      jobs.findByIdempotencyKey(key)
          .filter(other -> !other.getId().equals(jobId))
          .ifPresent(
              other -> {
                throw ApiException.duplicate(
                    "IMPORT_IDEMPOTENCY_CONFLICT",
                    "That idempotency key has already been used by another import");
              });
      job.setIdempotencyKey(key);
    }

    List<ImportJobRow> validRows =
        rows.findRowsWithStatus(job.getId(), List.of(ImportRowStatus.VALID));

    int inserted = 0;
    int skipped = 0;
    int failed = 0;
    List<ImportDtos.RowSummary> outcomes = new ArrayList<>();

    for (ImportJobRow row : validRows) {
      Map<String, String> data = readJsonMap(row.getMappedData());
      String message = null;
      UUID entityId = null;
      String entityLabel = null;
      ImportRowStatus outcome;
      try {
        Outcome result = insert(job.getEntityType(), data);
        outcome = result.status();
        entityId = result.entityId();
        entityLabel = result.entityLabel();
        message = result.message();
      } catch (RuntimeException ex) {
        // One bad line must not throw away the good ones: record it and carry on.
        log.warn("Import row {} of job {} failed: {}", row.getRowNumber(), job.getJobCode(), ex.getMessage());
        outcome = ImportRowStatus.FAILED;
        message = rootMessage(ex);
      }

      row.setStatus(outcome);
      if (entityId != null) row.setEntityId(entityId);
      if (entityLabel != null) row.setEntityLabel(entityLabel);
      if (outcome == ImportRowStatus.FAILED && message != null) {
        row.setValidationErrors(json(List.of(message)));
      } else if (outcome == ImportRowStatus.SKIPPED && message != null) {
        row.setValidationWarnings(json(List.of(message)));
      }
      rows.save(row);

      switch (outcome) {
        case INSERTED -> inserted++;
        case SKIPPED -> skipped++;
        default -> failed++;
      }
      if (outcomes.size() < 200) {
        outcomes.add(
            new ImportDtos.RowSummary(
                row.getId(),
                row.getRowNumber(),
                row.getRawData(),
                row.getMappedData(),
                outcome.name(),
                outcome == ImportRowStatus.FAILED && message != null ? List.of(message) : List.of(),
                outcome == ImportRowStatus.SKIPPED && message != null ? List.of(message) : List.of(),
                entityId,
                entityLabel));
      }
    }

    job.setInsertedRowCount(inserted);
    job.setSkippedRowCount(skipped);
    job.setStatus(ImportJobStatus.COMMITTED);
    job.setCommittedAt(LocalDateTime.now());
    job.setCommittedBy(actorId == null ? null : users.getReferenceById(actorId));
    if (job.getValidatedAt() == null) job.setValidatedAt(job.getCommittedAt());
    jobs.save(job);

    audit
        .entry()
        .asCurrentUser()
        .module(AuditActions.IMPORT)
        .action(AuditActions.IMPORT_COMMITTED)
        .description(
            "Committed import "
                + job.getJobCode()
                + ": "
                + inserted
                + " inserted, "
                + skipped
                + " skipped, "
                + failed
                + " failed")
        .entity("IMPORT", job.getId(), job.getJobCode())
        .metadata(Map.of("inserted", inserted, "skipped", skipped, "failed", failed))
        .record();

    return new ImportDtos.ConfirmResult(
        ImportDtos.JobSummary.from(job), inserted, skipped, failed, false, outcomes);
  }

  /** The result of an offer that changed nothing, reconstructed from what the job already records. */
  private ImportDtos.ConfirmResult resultOf(ImportJob job, boolean replay) {
    List<ImportDtos.RowSummary> outcomes =
        rows.findRowsWithStatus(
                job.getId(),
                List.of(ImportRowStatus.INSERTED, ImportRowStatus.SKIPPED, ImportRowStatus.FAILED))
            .stream()
            .limit(200)
            .map(
                row ->
                    new ImportDtos.RowSummary(
                        row.getId(),
                        row.getRowNumber(),
                        row.getRawData(),
                        row.getMappedData(),
                        row.getStatus().name(),
                        List.of(),
                        List.of(),
                        row.getEntityId(),
                        row.getEntityLabel()))
            .toList();
    long failed = rows.countByJobIdAndStatus(job.getId(), ImportRowStatus.FAILED);
    return new ImportDtos.ConfirmResult(
        ImportDtos.JobSummary.from(job),
        job.getInsertedRowCount(),
        job.getSkippedRowCount(),
        (int) failed,
        replay,
        outcomes);
  }

  // ----------------------------------------------------------------------------------------------
  // Reads
  // ----------------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public ImportDtos.JobSummary requireJob(String jobCode) {
    return ImportDtos.JobSummary.from(require(jobCode));
  }

  @Transactional(readOnly = true)
  public ImportDtos.PreviewResult detail(String jobCode) {
    ImportJob job = require(jobCode);
    List<ImportJobRow> jobRows = rows.findAllByJobIdOrderByRowNumberAsc(job.getId());
    List<ImportDtos.RowSummary> summaries =
        jobRows.stream()
            .map(
                row ->
                    new ImportDtos.RowSummary(
                        row.getId(),
                        row.getRowNumber(),
                        row.getRawData(),
                        row.getMappedData(),
                        row.getStatus().name(),
                        readJsonList(row.getValidationErrors()),
                        readJsonList(row.getValidationWarnings()),
                        row.getEntityId(),
                        row.getEntityLabel()))
            .toList();
    List<ImportDtos.RowSummary> problems =
        summaries.stream().filter(row -> "INVALID".equals(row.status()) || "FAILED".equals(row.status())).toList();
    List<String> columnNames =
        jobRows.isEmpty()
            ? List.of()
            : new ArrayList<>(readJsonMap(jobRows.get(0).getMappedData()).keySet());
    return new ImportDtos.PreviewResult(
        ImportDtos.JobSummary.from(job),
        job.getStatus().isPending() && job.getValidRowCount() > 0,
        columnNames,
        summaries,
        problems);
  }

  /** The import history, newest first, filtered by entity type and status. */
  @Transactional(readOnly = true)
  public org.springframework.data.domain.Page<ImportDtos.JobSummary> history(
      ImportEntityType entityType,
      ImportJobStatus status,
      org.springframework.data.domain.Pageable pageable) {

    List<ImportJob> all =
        entityType != null
            ? jobs.findAllByEntityTypeAndDeletedAtIsNull(entityType)
            : jobs.findAllByDeletedAtIsNull();

    List<ImportJob> filtered =
        all.stream()
            .filter(job -> status == null || job.getStatus() == status)
            .sorted(
                Comparator.comparing(
                    ImportJob::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

    int offset = (int) Math.min(pageable.getOffset(), filtered.size());
    int end = Math.min(offset + pageable.getPageSize(), filtered.size());
    List<ImportDtos.JobSummary> items =
        filtered.subList(offset, end).stream().map(ImportDtos.JobSummary::from).toList();
    return new org.springframework.data.domain.PageImpl<>(items, pageable, filtered.size());
  }

  private ImportJob require(String jobCode) {
    return jobs
        .findByJobCodeIgnoreCaseAndDeletedAtIsNull(jobCode)
        .orElseThrow(() -> ApiException.notFound("That import job does not exist"));
  }

  private ImportJob requirePendingOrCommitted(String jobCode) {
    return require(jobCode);
  }

  // ----------------------------------------------------------------------------------------------
  // Parsing and detection
  // ----------------------------------------------------------------------------------------------

  private record ParsedFile(ImportSourceType sourceType, List<String> header, List<List<String>> rows) {
    /** The raw row keyed by canonical column name, so unknown columns keep their own heading. */
    Map<String, String> toRawRow(List<String> values) {
      Map<String, String> row = new LinkedHashMap<>();
      for (int i = 0; i < header.size(); i++) {
        String key = header.get(i);
        if (key == null || key.isBlank()) continue;
        row.put(key, i < values.size() ? values.get(i) : null);
      }
      return row;
    }
  }

  private ParsedFile parse(ImportSourceType sourceType, byte[] content, ImportEntityType entityType) {
    return switch (sourceType) {
      case CSV -> parseCsv(content, entityType);
      case XLSX, XLS -> parseWorkbook(content, entityType);
    };
  }

  private ParsedFile parseCsv(byte[] content, ImportEntityType entityType) {
    try (CSVParser parser =
        CSVParser.parse(
            new ByteArrayInputStream(content),
            StandardCharsets.UTF_8,
            CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(false).setTrim(false).build())) {

      List<String> canonical = COLUMNS.get(entityType);
      List<List<String>> all = new ArrayList<>();
      for (CSVRecord record : parser) {
        List<String> row = new ArrayList<>();
        for (int i = 0; i < record.size(); i++) row.add(record.get(i));
        all.add(row);
      }
      return normalise(all, canonical);
    } catch (Exception ex) {
      throw ApiException.badRequest(
          "IMPORT_FILE_CONTENT_INVALID: the CSV could not be read (" + rootMessage(ex) + ")");
    }
  }

  private ParsedFile parseWorkbook(byte[] content, ImportEntityType entityType) {
    List<String> canonical = COLUMNS.get(entityType);
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      if (workbook.getNumberOfSheets() == 0) {
        throw ApiException.badRequest("IMPORT_FILE_CONTENT_INVALID: the workbook has no sheets");
      }
      Sheet sheet = workbook.getSheetAt(0);
      DataFormatter formatter = new DataFormatter();
      List<List<String>> all = new ArrayList<>();
      for (Row row : sheet) {
        List<String> values = new ArrayList<>();
        int last = Math.max(row.getLastCellNum(), 0);
        for (int c = 0; c < last; c++) {
          Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
          values.add(cell == null ? null : formatter.formatCellValue(cell));
        }
        all.add(values);
        if (all.size() > ABSOLUTE_MAX_ROWS) {
          throw ApiException.badRequest(
              "IMPORT_TOO_MANY_ROWS: the workbook has more rows than this installation will read");
        }
      }
      return normalise(all, canonical);
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw ApiException.badRequest(
          "IMPORT_FILE_CONTENT_INVALID: the workbook could not be read (" + rootMessage(ex) + ")");
    }
  }

  /** Split a parsed grid into a header (mapped to canonical names) and the data rows. */
  private ParsedFile normalise(List<List<String>> grid, List<String> canonical) {
    if (grid.isEmpty()) return new ParsedFile(null, List.of(), List.of());

    Map<String, String> canonicalByNormalised = new LinkedHashMap<>();
    for (String column : canonical) canonicalByNormalised.put(normalise(column), column);

    List<String> rawHeader = grid.get(0);
    List<String> header = new ArrayList<>();
    for (String cell : rawHeader) {
      String normalised = normalise(cell);
      header.add(canonicalByNormalised.getOrDefault(normalised, cell == null ? "" : cell.trim()));
    }

    List<List<String>> dataRows = new ArrayList<>();
    for (int i = 1; i < grid.size(); i++) {
      List<String> row = grid.get(i);
      // A row that is entirely blank is an artefact of how files end, not a row of data.
      boolean blank = row.stream().allMatch(value -> value == null || value.isBlank());
      if (blank) continue;
      dataRows.add(row);
    }
    return new ParsedFile(null, header, dataRows);
  }

  private List<String> missingRequiredColumns(ImportEntityType entityType, List<String> header) {
    List<String> present = new ArrayList<>();
    for (String cell : header) present.add(normalise(cell));
    List<String> missing = new ArrayList<>();
    for (String required : REQUIRED.get(entityType)) {
      if (!present.contains(normalise(required))) missing.add(required);
    }
    return missing;
  }

  /**
   * Decide the format from the content's own bytes.
   *
   * @return the detected source type, or {@code null} when the content matches nothing supported
   */
  static ImportSourceType detectSourceType(byte[] content) {
    if (content == null || content.length < 2) return null;
    if (content.length >= 4
        && content[0] == 'P'
        && content[1] == 'K'
        && (content[2] == 3 || content[2] == 5 || content[2] == 7)
        && (content[3] == 4 || content[3] == 6 || content[3] == 8)) {
      return ImportSourceType.XLSX;
    }
    if (content.length >= 8
        && (content[0] & 0xFF) == 0xD0
        && (content[1] & 0xFF) == 0xCF
        && (content[2] & 0xFF) == 0x11
        && (content[3] & 0xFF) == 0xE0
        && (content[4] & 0xFF) == 0xA1
        && (content[5] & 0xFF) == 0xB1
        && (content[6] & 0xFF) == 0x1A
        && (content[7] & 0xFF) == 0xE1) {
      return ImportSourceType.XLS;
    }
    return looksLikeDelimitedText(content) ? ImportSourceType.CSV : null;
  }

  /**
   * True when the bytes are plausibly delimited text: valid UTF-8 with no control characters, a
   * delimiter somewhere on the first line and a line break. This is what keeps a PNG or a PDF - any
   * binary whose magic bytes are not a spreadsheet - from being fed to the CSV reader as nonsense.
   */
  private static boolean looksLikeDelimitedText(byte[] content) {
    int check = Math.min(content.length, 4096);
    boolean delimiter = false;
    boolean newline = false;
    for (int i = 0; i < check; i++) {
      byte b = content[i];
      int value = b & 0xFF;
      if (value == 0) return false; // a NUL is a binary file, not text
      if (value < 0x09) return false; // control characters other than tab/newline/carriage return
      if (value == '\t' || value == ',' || value == ';' || value == '|') delimiter = true;
      if (value == '\n') newline = true;
    }
    return delimiter && newline;
  }

  // ----------------------------------------------------------------------------------------------
  // Validation
  // ----------------------------------------------------------------------------------------------

  private record Validation(List<String> errors, List<String> warnings, Map<String, String> mapped) {}

  private Validation validate(
      ImportEntityType entityType, Map<String, String> raw, List<String> header) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> mapped = new LinkedHashMap<>();

    List<String> canonical = COLUMNS.get(entityType);

    // A column the operator supplied that this import does not know about is worth saying, but it
    // does not make the row unwritable - the row is still complete for the columns that matter.
    for (String supplied : header) {
      if (supplied != null && !supplied.isBlank() && !canonical.contains(supplied)) {
        warnings.add("'" + supplied + "' is not a column this import recognises and was ignored");
      }
    }

    for (String column : canonical) {
      String value = raw.get(column);
      mapped.put(column, value == null || value.isBlank() ? null : value.trim());
    }

    for (String column : REQUIRED.get(entityType)) {
      if (mapped.get(column) == null) errors.add(column + " is required");
    }

    validateEmail(mapped, errors);
    validateMoney(
        mapped,
        List.of(
            "acquisitionValue", "currentValuation", "askingRent", "askingPrice", "minBudget",
            "maxBudget", "expectedValue", "rentAmount", "depositAmount", "amount"),
        errors);
    validateInteger(
        mapped,
        List.of("floorNumber", "bedrooms", "bathrooms", "balconies", "minBedrooms", "dueDay", "noticePeriodDays"),
        errors);

    switch (entityType) {
      case PROPERTY -> {
        enumOrNull(
            PropertyStatus.class,
            mapped.get("status"),
            "status",
            errors,
            mapped,
            PropertyStatus.AVAILABLE_ACTIVE);
        parseDate(mapped.get("valuationDate"), "valuationDate", errors, mapped);
      }
      case UNIT -> {
        enumOrNull(UnitStatus.class, mapped.get("status"), "status", errors, mapped, UnitStatus.AVAILABLE);
        enumOrNull(UnitFurnishing.class, mapped.get("furnishing"), "furnishing", errors, mapped, UnitFurnishing.UNFURNISHED);
        enumOrNull(UnitFacing.class, mapped.get("facing"), "facing", errors, mapped, null);
        enumOrBoolean(mapped, "leasable", errors);
      }
      case OWNER -> enumOrNull(OwnerType.class, mapped.get("ownerType"), "ownerType", errors, mapped, OwnerType.INDIVIDUAL);
      case TENANT -> enumOrNull(TenantType.class, mapped.get("tenantType"), "tenantType", errors, mapped, TenantType.INDIVIDUAL);
      case LEAD -> {
        enumOrNull(LeadType.class, mapped.get("leadType"), "leadType", errors, mapped, LeadType.RENTAL);
        enumOrNull(LeadPriority.class, mapped.get("priority"), "priority", errors, mapped, LeadPriority.MEDIUM);
      }
      case LEASE -> {
        LocalDate start = parseDate(mapped.get("startDate"), "startDate", errors, mapped);
        LocalDate end = parseDate(mapped.get("endDate"), "endDate", errors, mapped);
        if (start != null && end != null && !end.isAfter(start)) {
          errors.add("endDate must be after startDate");
        }
        enumOrNull(RentFrequency.class, mapped.get("rentFrequency"), "rentFrequency", errors, mapped, RentFrequency.MONTHLY);
      }
      case PAYMENT -> {
        parseDateTime(mapped.get("receivedAt"), "receivedAt", errors, mapped);
        enumOrNull(PaymentStatus.class, mapped.get("status"), "status", errors, mapped, PaymentStatus.PAID);
      }
    }

    return new Validation(errors, warnings, mapped);
  }

  /** Validate a true/false column, lower-casing it so the insert can read it plainly. */
  private static void enumOrBoolean(Map<String, String> mapped, String field, List<String> errors) {
    String value = mapped.get(field);
    if (value == null) return;
    if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
      mapped.put(field, value.toLowerCase(Locale.ROOT));
    } else {
      errors.add(field + " '" + value + "' is not true or false");
    }
  }

  private static void validateEmail(Map<String, String> mapped, List<String> errors) {
    String email = mapped.get("email");
    if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
      errors.add("email '" + email + "' is not a valid address");
    } else if (email != null) {
      mapped.put("email", email.toLowerCase(Locale.ROOT));
    }
  }

  private static void validateMoney(Map<String, String> mapped, List<String> fields, List<String> errors) {
    for (String field : fields) {
      String value = mapped.get(field);
      if (value == null) continue;
      try {
        BigDecimal amount = new BigDecimal(value.replace(",", ""));
        if (amount.signum() < 0) errors.add(field + " cannot be negative");
      } catch (NumberFormatException ex) {
        errors.add(field + " '" + value + "' is not a number");
      }
    }
  }

  private static void validateInteger(Map<String, String> mapped, List<String> fields, List<String> errors) {
    for (String field : fields) {
      String value = mapped.get(field);
      if (value == null) continue;
      try {
        Integer.parseInt(value);
      } catch (NumberFormatException ex) {
        errors.add(field + " '" + value + "' is not a whole number");
      }
    }
  }

  private static LocalDate parseDate(
      String value, String field, List<String> errors, Map<String, String> mapped) {
    if (value == null) return null;
    try {
      LocalDate date = LocalDate.parse(value);
      mapped.put(field, date.toString());
      return date;
    } catch (DateTimeParseException ex) {
      errors.add(field + " '" + value + "' is not a date in yyyy-MM-dd form");
      return null;
    }
  }

  private static LocalDateTime parseDateTime(
      String value, String field, List<String> errors, Map<String, String> mapped) {
    if (value == null) return null;
    try {
      LocalDateTime moment =
          value.contains("T") ? LocalDateTime.parse(value) : LocalDate.parse(value).atTime(LocalTime.NOON);
      mapped.put(field, moment.toString());
      return moment;
    } catch (DateTimeParseException ex) {
      errors.add(field + " '" + value + "' is not a date or date-time");
      return null;
    }
  }

  /** Validate an enum value, applying a default when absent and recording an error when wrong. */
  private static <E extends Enum<E>> boolean enumOrNull(
      Class<E> type,
      String value,
      String field,
      List<String> errors,
      Map<String, String> mapped,
      E defaultValue) {
    if (value == null) {
      mapped.put(field, defaultValue == null ? null : defaultValue.name());
      return true;
    }
    try {
      E parsed = Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
      mapped.put(field, parsed.name());
      return true;
    } catch (IllegalArgumentException ex) {
      errors.add(
          field
              + " '"
              + value
              + "' is not one of "
              + String.join(", ", java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
      return false;
    }
  }

  // ----------------------------------------------------------------------------------------------
  // Row insertion
  // ----------------------------------------------------------------------------------------------

  private record Outcome(ImportRowStatus status, UUID entityId, String entityLabel, String message) {
    static Outcome inserted(UUID id, String label) {
      return new Outcome(ImportRowStatus.INSERTED, id, label, null);
    }

    static Outcome skipped(String reason) {
      return new Outcome(ImportRowStatus.SKIPPED, null, null, reason);
    }

    static Outcome failed(String reason) {
      return new Outcome(ImportRowStatus.FAILED, null, null, reason);
    }
  }

  /**
   * Insert one validated row through the domain service that owns its entity type.
   *
   * <p>Nothing here writes to a repository directly. {@code PropertyService.create} and its
   * counterparts mint the code, stamp the owner, record the audit entry and enforce the create
   * path's own rules, so an imported record is one a person could have keyed in themselves.</p>
   */
  private Outcome insert(ImportEntityType type, Map<String, String> data) {
    return switch (type) {
      case PROPERTY -> insertProperty(data);
      case UNIT -> insertUnit(data);
      case LEAD -> insertLead(data);
      case LEASE -> insertLease(data);
      case PAYMENT -> insertPayment(data);
      // Not offered: this module carries no create service for these, and the import must not
      // bypass the services to write a repository row directly.
      case OWNER, TENANT -> Outcome.failed("importing " + type + " is not supported");
    };
  }

  private Outcome insertProperty(Map<String, String> data) {
    PropertyType type =
        propertyTypes
            .findByCode(data.get("propertyTypeCode"))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "property type '" + data.get("propertyTypeCode") + "' does not exist"));

    PropertyDtos.CreatePropertyRequest request =
        new PropertyDtos.CreatePropertyRequest(
            data.get("name"),
            type.getId(),
            data.get("status"),
            data.get("addressLine1"),
            data.get("addressLine2"),
            data.get("locality"),
            data.get("city"),
            data.get("state"),
            data.get("postalCode"),
            blankToNull(data.get("country")),
            null,
            null,
            null,
            null,
            null,
            null,
            moneyOrNull(data.get("acquisitionValue")),
            moneyOrNull(data.get("currentValuation")),
            dateOrNull(data.get("valuationDate")));

    PropertyDtos.PropertyDetail created = propertyService.create(request);
    return Outcome.inserted(created.id(), created.propertyCode());
  }

  private Outcome insertUnit(Map<String, String> data) {
    Property property =
        propertyRepository
            .findByPropertyCodeIgnoreCaseAndDeletedAtIsNull(data.get("propertyCode"))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no property with the code '" + data.get("propertyCode") + "'"));
    UnitType type =
        unitTypes
            .findByCode(data.get("unitTypeCode"))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "unit type '" + data.get("unitTypeCode") + "' does not exist"));

    UnitDtos.CreateUnitRequest request =
        new UnitDtos.CreateUnitRequest(
            property.getId(),
            null,
            type.getId(),
            data.get("unitNumber"),
            intOrNull(data.get("floorNumber")),
            blankToNull(data.get("floorLabel")),
            intOrNull(data.get("bedrooms")),
            intOrNull(data.get("bathrooms")),
            intOrNull(data.get("balconies")),
            null,
            null,
            null,
            data.get("furnishing"),
            data.get("facing"),
            data.get("status"),
            boolOrNull(data.get("leasable")),
            moneyOrNull(data.get("askingRent")),
            moneyOrNull(data.get("askingPrice")));

    UnitDtos.UnitDetail created = unitService.create(request);
    return Outcome.inserted(created.id(), created.unitCode());
  }

  private Outcome insertLead(Map<String, String> data) {
    LeadDtos.CreateLeadRequest request =
        new LeadDtos.CreateLeadRequest(
            data.get("leadType"),
            null,
            data.get("priority"),
            data.get("displayName"),
            blankToNull(data.get("companyName")),
            blankToNull(data.get("email")),
            blankToNull(data.get("phone")),
            blankToNull(data.get("alternatePhone")),
            null,
            blankToNull(data.get("preferredCity")),
            blankToNull(data.get("preferredLocality")),
            moneyOrNull(data.get("minBudget")),
            moneyOrNull(data.get("maxBudget")),
            intOrNull(data.get("minBedrooms")),
            blankToNull(data.get("requirementNotes")),
            null,
            null,
            null,
            null,
            blankToNull(data.get("nextAction")),
            null,
            moneyOrNull(data.get("expectedValue")));

    LeadDtos.LeadDetail created = leadService.create(request);
    return Outcome.inserted(created.id(), created.leadCode());
  }

  private Outcome insertLease(Map<String, String> data) {
    Unit unit =
        unitRepository
            .findByUnitCodeIgnoreCaseAndDeletedAtIsNull(data.get("unitCode"))
            .orElseThrow(
                () ->
                    new IllegalStateException("no unit with the code '" + data.get("unitCode") + "'"));
    UUID tenantId =
        tenants
            .findByTenantCodeIgnoreCaseAndDeletedAtIsNull(data.get("tenantCode"))
            .map(Tenant::getId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no tenant with the code '" + data.get("tenantCode") + "'"));
    UUID propertyId =
        blankToNull(data.get("propertyCode")) == null
            ? null
            : propertyRepository
                .findByPropertyCodeIgnoreCaseAndDeletedAtIsNull(data.get("propertyCode"))
                .map(Property::getId)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "no property with the code '" + data.get("propertyCode") + "'"));

    LocalDate start = LocalDate.parse(data.get("startDate"));
    LocalDate end = LocalDate.parse(data.get("endDate"));
    if (!end.isAfter(start)) {
      return Outcome.failed("the end date is not after the start date");
    }

    LeaseDtos.CreateLeaseRequest request =
        new LeaseDtos.CreateLeaseRequest(
            propertyId,
            unit.getId(),
            tenantId,
            null,
            start,
            end,
            moneyOrNull(data.get("rentAmount")),
            data.get("rentFrequency"),
            intOrNull(data.get("dueDay")),
            null,
            moneyOrNull(data.get("depositAmount")),
            intOrNull(data.get("noticePeriodDays")),
            blankToNull(data.get("agreementReference")));

    LeaseDtos.LeaseSummary created = leaseService.createLease(request);
    return Outcome.inserted(created.id(), created.leaseCode());
  }

  private Outcome insertPayment(Map<String, String> data) {
    Lease lease =
        leases
            .findByLeaseCodeIgnoreCaseAndDeletedAtIsNull(data.get("leaseCode"))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no lease with the code '" + data.get("leaseCode") + "'"));
    PaymentMethod method =
        paymentMethods
            .findByCode(data.get("methodCode"))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "payment method '" + data.get("methodCode") + "' does not exist"));

    LocalDateTime receivedAt =
        data.get("receivedAt").contains("T")
            ? LocalDateTime.parse(data.get("receivedAt"))
            : LocalDate.parse(data.get("receivedAt")).atTime(LocalTime.NOON);

    PaymentDtos.RecordPaymentRequest request =
        new PaymentDtos.RecordPaymentRequest(
            method.getId(),
            moneyOrNull(data.get("amount")),
            null,
            data.get("status"),
            blankToNull(data.get("currency")),
            lease.getTenantId(),
            null,
            lease.getId(),
            null,
            lease.getProperty() == null ? null : lease.getProperty().getId(),
            receivedAt,
            blankToNull(data.get("reference")),
            blankToNull(data.get("notes")));

    PaymentDtos.PaymentSummary created = paymentService.record(request);
    return Outcome.inserted(created.id(), created.receiptNumber());
  }

  // ----------------------------------------------------------------------------------------------
  // Small helpers
  // ----------------------------------------------------------------------------------------------

  private static <E extends Enum<E>> E statusOr(Class<E> type, String value, E fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return fallback;
    }
  }

  private static BigDecimal moneyOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    return new BigDecimal(value.replace(",", ""));
  }

  private static BigDecimal moneyOrZero(String value) {
    BigDecimal amount = moneyOrNull(value);
    return amount == null ? BigDecimal.ZERO : amount;
  }

  private static int intOr(String value, int fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static boolean boolOr(String value, boolean fallback) {
    if (value == null || value.isBlank()) return fallback;
    return value.equalsIgnoreCase("true");
  }

  /** A whole number, or null when the cell is absent - the shape a create request's Integer takes. */
  private static Integer intOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    return Integer.parseInt(value.trim());
  }

  /** A true/false cell as an object, or null when absent. */
  private static Boolean boolOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    return value.equalsIgnoreCase("true");
  }

  /** A {@code yyyy-MM-dd} cell as a date, or null when absent. */
  private static LocalDate dateOrNull(String value) {
    return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
  }

  private String json(Object value) {
    if (value == null) return null;
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("Could not serialise import data", ex);
    }
  }

  private Map<String, String> readJsonMap(String json) {
    if (json == null || json.isBlank()) return new LinkedHashMap<>();
    try {
      return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (Exception ex) {
      log.warn("Could not read import payload: {}", ex.getMessage());
      return new LinkedHashMap<>();
    }
  }

  private List<String> readJsonList(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (Exception ex) {
      return List.of();
    }
  }

  private static String normalise(String value) {
    if (value == null) return "";
    return value.trim().replace("\uFEFF", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
  }

  private static String label(ImportEntityType type) {
    return switch (type) {
      case PROPERTY -> "Properties";
      case UNIT -> "Units";
      case OWNER -> "Owners";
      case TENANT -> "Tenants";
      case LEAD -> "Leads";
      case LEASE -> "Leases";
      case PAYMENT -> "Receipts";
    };
  }

  private static String extension(ImportSourceType type) {
    return switch (type) {
      case CSV -> "csv";
      case XLSX -> "xlsx";
      case XLS -> "xls";
    };
  }

  private static String sha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content);
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception ex) {
      return null;
    }
  }

  private static String humanSize(long bytes) {
    if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
    if (bytes >= 1024) return (bytes / 1024) + " KB";
    return bytes + " bytes";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String rootMessage(Throwable ex) {
    Throwable current = ex;
    while (current.getCause() != null) current = current.getCause();
    String message = current.getMessage();
    if (message == null) return current.getClass().getSimpleName();
    return message.length() <= 400 ? message : message.substring(0, 397) + "...";
  }
}
