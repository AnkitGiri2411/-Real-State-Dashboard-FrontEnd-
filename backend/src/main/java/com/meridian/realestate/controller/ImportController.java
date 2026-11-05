package com.meridian.realestate.controller;

import com.meridian.realestate.domain.ImportEntityType;
import com.meridian.realestate.domain.ImportJobStatus;
import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.ImportDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.security.AccessScope;
import com.meridian.realestate.service.ImportService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Data import.
 *
 * <p>Two steps and no shortcut. {@code POST /preview} stages a file: it parses and validates it and
 * records what it found without writing a single property, unit, owner, tenant, lead, lease or
 * receipt. {@code POST /{jobCode}/confirm} is the only thing that writes, and it carries the
 * idempotency key that makes a double-click safe.</p>
 *
 * <p><b>Insert-only.</b> There is no endpoint here that edits or deletes an imported record, and no
 * endpoint that edits a committed job. A correction is another import, and a committed import is a
 * historical record of what was staged and what it produced.</p>
 *
 * <p>Guarded by {@code DATA_IMPORT}, the code {@code V2} seeds for the administrative roles.</p>
 */
@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("jobCode", "jobCode")
          .allow("entityType", "entityType")
          .allow("status", "status")
          .allow("rowCount", "rowCount")
          .allow("createdAt", "createdAt")
          .build();

  private final ImportService importService;
  private final AccessScope accessScope;

  public ImportController(ImportService importService, AccessScope accessScope) {
    this.importService = importService;
    this.accessScope = accessScope;
  }

  /** The entity types this installation actually imports, so the screen offers only those. */
  @GetMapping("/supported")
  @PreAuthorize("hasAuthority('DATA_IMPORT')")
  public ApiResponse<List<ImportDtos.SupportedImport>> supported() {
    return ApiResponse.ok(importService.supportedImports());
  }

  /** The import history, newest first. */
  @GetMapping
  @PreAuthorize("hasAuthority('DATA_IMPORT')")
  public ApiResponse<PageResponse<ImportDtos.JobSummary>> list(
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Page<ImportDtos.JobSummary> jobs =
        importService.history(
            parseEntityTypeOrNull(entityType),
            parseStatusOrNull(status),
            PAGES.toPageable(page, size, sort));
    return ApiResponse.ok(PageResponse.from(jobs));
  }

  /** One staged or committed job with its rows. */
  @GetMapping("/{jobCode}")
  @PreAuthorize("hasAuthority('DATA_IMPORT')")
  public ApiResponse<ImportDtos.PreviewResult> detail(@PathVariable String jobCode) {
    return ApiResponse.ok(importService.detail(jobCode));
  }

  /**
   * Stage an uploaded file.
   *
   * <p>The file is read into memory and parsed there; it is never written to disk, so there is no
   * temporary file to clean up and no path to traverse. Its format is decided from its own bytes,
   * not from the extension or the browser's MIME type.</p>
   */
  @PostMapping("/preview")
  @PreAuthorize("hasAuthority('DATA_IMPORT')")
  public ApiResponse<ImportDtos.PreviewResult> preview(
      @RequestParam String entityType, @RequestPart("file") MultipartFile file) throws IOException {

    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("IMPORT_FILE_EMPTY: choose a file to import");
    }
    return ApiResponse.ok(
        importService.preview(
            parseEntityType(entityType),
            file.getOriginalFilename(),
            file.getBytes(),
            accessScope.requireUserId()));
  }

  /**
   * Confirm a staged import.
   *
   * <p>Idempotent on the key: the same key returns the first run's result and writes nothing, so a
   * retried or double-clicked confirm cannot duplicate data; a different key against a committed job
   * is refused with {@code IMPORT_IDEMPOTENCY_CONFLICT}.</p>
   */
  @PostMapping("/{jobCode}/confirm")
  @PreAuthorize("hasAuthority('DATA_IMPORT')")
  public ApiResponse<ImportDtos.ConfirmResult> confirm(
      @PathVariable String jobCode,
      @Valid @RequestBody(required = false) ImportDtos.ConfirmRequest request) {

    ImportDtos.ConfirmResult result =
        importService.confirm(
            jobCode,
            request == null ? null : request.idempotencyKey(),
            accessScope.requireUserId());
    return ApiResponse.ok(
        result,
        result.idempotentReplay()
            ? "That import had already been committed; nothing was written again"
            : "Import committed");
  }

  private static ImportEntityType parseEntityType(String value) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("An import entity type is required");
    }
    try {
      return ImportEntityType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest(
          "'" + value + "' is not an entity type this installation imports");
    }
  }

  private static ImportEntityType parseEntityTypeOrNull(String value) {
    return value == null || value.isBlank() ? null : parseEntityType(value);
  }

  private static ImportJobStatus parseStatusOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return ImportJobStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an import job status");
    }
  }
}
