package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.DocumentDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.DocumentService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The document register.
 *
 * <p><b>Why this controller exists.</b> The register is one resource with one sub-resource that reads
 * it differently for a different purpose: the expiry watchlist, which answers "what needs renewing"
 * rather than "what do we hold". They live under one base path because a caller reaches both by
 * document, and because the watchlist is the register filtered to a horizon rather than a table of
 * its own.</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. An entry filed against nothing, a
 * document that names itself as its own replacement, a superseded document replaced twice, a file
 * reference that is not a real storage path - all of those are decided in {@link DocumentService} and
 * raised as {@link com.meridian.realestate.exception.ApiException}, which {@code
 * GlobalExceptionHandler} turns into the product's one error envelope. This class only routes,
 * validates the shape of the request body and wraps the answer in {@link ApiResponse}. No entity and
 * no repository ever appears here.</p>
 *
 * <p><b>There is no upload endpoint.</b> See {@link DocumentDtos}: the register records that a
 * document exists and where it lives. It does not accept the file, and it never will through this
 * class - the product has no object storage.</p>
 *
 * <p><b>Permissions.</b> Every endpoint names a code that exists in the {@code V2} seed. The document
 * module declares {@code DOCUMENT_VIEW}, {@code DOCUMENT_CREATE} and {@code DOCUMENT_DELETE} and no
 * update capability, so a change to a register entry is gated by {@code DOCUMENT_CREATE}: registering
 * a document and changing the record of one are the same capability, and a caller who may file may
 * amend. No role name appears anywhere in this class, so changing what a role may do is a data change
 * and never a code change.</p>
 *
 * <p>Sorting is honoured through {@link PageRequestFactory}: a caller may sort by the fields the
 * register offers and by nothing else, and an unsupported field is answered with a 400 that names the
 * accepted ones rather than a 500.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .allow("title", "title")
          .allow("documentCode", "documentCode")
          .allow("documentType", "documentType")
          .allow("status", "status")
          .allow("expiryDate", "expiryDate")
          .allow("issuedDate", "issuedDate")
          .build();

  private final DocumentService documentService;

  public DocumentController(DocumentService documentService) {
    this.documentService = documentService;
  }

  /** The register, filtered by filing target, type, status and expiry horizon, with search. */
  @GetMapping
  @PreAuthorize("hasAuthority('DOCUMENT_VIEW')")
  public ApiResponse<PageResponse<DocumentDtos.DocumentSummary>> list(
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID unitId,
      @RequestParam(required = false) UUID leaseId,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) UUID buyerId,
      @RequestParam(required = false) UUID saleId,
      @RequestParam(required = false) UUID maintenanceRequestId,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer expiringWithinDays,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            documentService.list(
                propertyId,
                unitId,
                leaseId,
                tenantId,
                ownerId,
                buyerId,
                saleId,
                maintenanceRequestId,
                type,
                status,
                expiringWithinDays,
                search,
                PAGES.toPageable(page, size, sort))));
  }

  /**
   * The expiry watchlist.
   *
   * <p>Live documents that lapse within the window, soonest first. The window defaults to the firm's
   * configured warning period when the caller gives none.</p>
   */
  @GetMapping("/expiring")
  @PreAuthorize("hasAuthority('DOCUMENT_VIEW')")
  public ApiResponse<List<DocumentDtos.ExpiryEntry>> expiring(
      @RequestParam(required = false) Integer withinDays) {
    return ApiResponse.ok(documentService.expiring(withinDays));
  }

  /** One register entry's full record. */
  @GetMapping("/{documentId}")
  @PreAuthorize("hasAuthority('DOCUMENT_VIEW')")
  public ApiResponse<DocumentDtos.DocumentDetail> detail(@PathVariable UUID documentId) {
    return ApiResponse.ok(documentService.detail(documentId));
  }

  /** File a document. The metadata is recorded; no file is uploaded. */
  @PostMapping
  @PreAuthorize("hasAuthority('DOCUMENT_CREATE')")
  public ApiResponse<DocumentDtos.DocumentDetail> create(
      @Valid @RequestBody DocumentDtos.CreateDocumentRequest request) {
    return ApiResponse.ok(documentService.create(request), "Document registered");
  }

  /** Change a register entry. */
  @PutMapping("/{documentId}")
  @PreAuthorize("hasAuthority('DOCUMENT_CREATE')")
  public ApiResponse<DocumentDtos.DocumentDetail> update(
      @PathVariable UUID documentId,
      @Valid @RequestBody DocumentDtos.UpdateDocumentRequest request) {
    return ApiResponse.ok(documentService.update(documentId, request));
  }

  /** Supersede a document with a later version. */
  @PostMapping("/{documentId}/supersede")
  @PreAuthorize("hasAuthority('DOCUMENT_CREATE')")
  public ApiResponse<DocumentDtos.DocumentDetail> supersede(
      @PathVariable UUID documentId,
      @Valid @RequestBody DocumentDtos.SupersedeDocumentRequest request) {
    return ApiResponse.ok(documentService.supersede(documentId, request.supersededById()), "Document superseded");
  }

  /** Withdraw a register entry: a soft delete, refused when it has already been archived. */
  @PostMapping("/{documentId}/archive")
  @PreAuthorize("hasAuthority('DOCUMENT_DELETE')")
  public ApiResponse<DocumentDtos.DocumentDetail> archive(@PathVariable UUID documentId) {
    return ApiResponse.ok(documentService.archive(documentId), "Document archived");
  }
}
