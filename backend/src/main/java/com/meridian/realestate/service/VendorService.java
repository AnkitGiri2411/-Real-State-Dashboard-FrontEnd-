package com.meridian.realestate.service;

import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.Vendor;
import com.meridian.realestate.domain.VendorCategory;
import com.meridian.realestate.domain.VendorStatus;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.VendorDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.VendorRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vendor register: the empanelled trades the firm calls out to do work.
 *
 * <p><b>Why this class exists.</b> A maintenance request is handed to a vendor, and the two facts
 * that decide whether a hand-over is legitimate - whether the trade is on the firm's approved list,
 * and whether it has been stopped - live on this record and nowhere else. Stating those rules here
 * means the assignment screen, the register and any future work-order path ask one question and get
 * one answer, instead of each deciding for itself what "approved" means.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>Approval is per trade.</b> {@code is_empanelled} says the vendor is on the approved list;
 *       {@code category} says for what. A plumber who is empanelled is not thereby approved for
 *       electrical work, so {@link #isAvailableFor} requires both. This is exactly the rule the
 *       maintenance service asks before handing work over, which is why it lives here as a method
 *       rather than as a condition written at the call site.</li>
 *   <li><b>Approval is not the same as being available.</b> A {@code SUSPENDED} vendor is on the
 *       approved list and must still not be given new work; see {@link Vendor#isAssignable()}. The
 *       register therefore publishes both, because a screen that showed only one of them would
 *       either offer work to a stopped trade or hide a suspended one from the audit of its history.</li>
 *   <li><b>Archiving is soft, and once.</b> A vendor is archived rather than removed, because the
 *       work orders, expenses and status history that name it must stay coherent. A second archive is
 *       refused rather than silently re-stamped.</li>
 *   <li><b>The code is minted, not supplied.</b> {@code vendor_code} is unique, and a caller-invented
 *       code is how two vendors end up sharing the number a purchase ledger already has on paper.</li>
 *   <li><b>The e-mail is stored lower-cased</b>, as {@code ck_vendors_email_lower} requires: the desk
 *       matches on the address, and a mixed-case row is a vendor no search can find.</li>
 * </ul>
 *
 * <p><b>A note on paging, and on a gap that is reported rather than patched.</b>
 * {@code VendorRepository} offers a paged read and one read per filter, but not one combined query
 * for the register's four filters (category, empanelment, status, search). Rather than put a literal
 * {@code order by} in a query - which this product forbids, because it silently overrides the order
 * the caller asked for - the register is filtered, sorted and sliced here so the page the caller
 * asked for is the page they get, with correct totals. Separately, the migration chain seeds no
 * business-number series for vendors: {@code business_number_sequences} has rows for {@code PROPERTY}
 * through {@code SALE} and none for this table, so a vendor code is minted from the {@code
 * MAINTENANCE} series, the series of the module {@code V14} files vendors under. That is a gap in the
 * seed rather than a decision, and it is reported rather than worked around.</p>
 *
 * <p><b>Scope.</b> The register is firm-wide and protected by permission alone - a firm does not hide
 * its own trades from its own staff - so {@link AccessScope} is used here to resolve the acting
 * account for a change and its audit entry, not to narrow whose vendors a reader may see.</p>
 */
@Service
public class VendorService {

  /** The API sort fields, mapped to the entity paths they order by. */
  private static final Map<String, Comparator<Vendor>> SORTS =
      Map.of(
          "createdAt", Comparator.comparing(Vendor::getCreatedAt, nullSafe()),
          "updatedAt", Comparator.comparing(Vendor::getUpdatedAt, nullSafe()),
          "vendorCode", Comparator.comparing(Vendor::getVendorCode, nullSafe()),
          "name", Comparator.comparing(Vendor::getName, nullSafe()),
          "category", Comparator.comparing((Vendor v) -> enumName(v.getCategory()), nullSafe()),
          "status", Comparator.comparing((Vendor v) -> enumName(v.getStatus()), nullSafe()),
          "city", Comparator.comparing(Vendor::getCity, nullSafe()),
          "rating",
              Comparator.comparing(
                  Vendor::getRating, Comparator.nullsLast(Comparator.<Integer>naturalOrder())));

  private final VendorRepository vendors;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public VendorService(
      VendorRepository vendors,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.vendors = vendors;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  // ---------------------------------------------------------------------------
  // The rule the maintenance service asks
  // ---------------------------------------------------------------------------

  /**
   * True when this vendor may be handed work of this category.
   *
   * <p>Both halves are required, and they are not the same fact. {@code status} says the firm may
   * still work with the vendor; {@code is_empanelled} and {@code category} together say the firm has
   * approved this trade for this kind of fault. A vendor that fails either test is refused, and the
   * refusal names {@code VENDOR_NOT_EMPANELLED} - the schema's word for the approved list - rather
   * than inventing a second vocabulary for the same gate.</p>
   */
  public static boolean isAvailableFor(Vendor vendor, Enum<?> category) {
    if (vendor == null || category == null) return false;
    if (vendor.getCategory() == null) return false;
    return vendor.isEmpanelled()
        && vendor.isAssignable()
        && vendor.getCategory().name().equals(category.name());
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** The live vendor behind an id, or a 404. Archived vendors are not addressable. */
  @Transactional(readOnly = true)
  public Vendor require(UUID vendorId) {
    return vendors
        .findByIdAndDeletedAtIsNull(vendorId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("VENDOR_NOT_FOUND")));
  }

  /** The register, filtered, searched, sorted and paged. */
  @Transactional(readOnly = true)
  public PageResponse<VendorDtos.VendorSummary> list(
      String category,
      Boolean empanelled,
      String status,
      String search,
      Pageable pageable) {

    VendorCategory categoryFilter =
        category == null || category.isBlank() ? null : parseCategory(category);
    VendorStatus statusFilter = status == null || status.isBlank() ? null : parseStatus(status);
    String term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);

    List<VendorDtos.VendorSummary> rows =
        vendors.findAllByDeletedAtIsNull().stream()
            .filter(vendor -> categoryFilter == null || categoryFilter == vendor.getCategory())
            .filter(vendor -> empanelled == null || empanelled == vendor.isEmpanelled())
            .filter(vendor -> statusFilter == null || statusFilter == vendor.getStatus())
            .filter(vendor -> term == null || matchesSearch(vendor, term))
            .sorted(comparator(pageable))
            .map(VendorDtos.VendorSummary::from)
            .toList();

    return paginate(rows, pageable);
  }

  /** One vendor's full record. */
  @Transactional(readOnly = true)
  public VendorDtos.VendorDetail detail(UUID vendorId) {
    return VendorDtos.VendorDetail.from(require(vendorId));
  }

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /** Add a trade to the register, minting its code. */
  @Transactional
  public VendorDtos.VendorDetail create(VendorDtos.CreateVendorRequest request) {
    User actor = accessScope.requireUser();

    Vendor vendor = new Vendor();
    vendor.setVendorCode(numbers.next(BusinessNumberService.MAINTENANCE));
    vendor.setName(request.name().trim());
    vendor.setCategory(parseCategory(request.category()));
    applyContact(vendor, request);
    vendor.setEmpanelled(request.empanelled() == null || request.empanelled());
    vendor.setRating(request.rating());
    vendor.setPaymentTermsDays(request.paymentTermsDays());
    vendor.setStatus(request.status() == null || request.status().isBlank()
        ? VendorStatus.ACTIVE
        : parseStatus(request.status()));
    vendor.setNotes(request.notes());
    vendor.setCreatedBy(actor.getId());
    vendor.setUpdatedBy(actor.getId());

    Vendor saved = vendors.save(vendor);
    recordAudit(actor, AuditActions.CREATE, "Added vendor " + saved.getVendorCode()
        + " (" + saved.getName() + ")", saved);
    return VendorDtos.VendorDetail.from(saved);
  }

  /** Change a vendor's details. Only the fields supplied are applied. */
  @Transactional
  public VendorDtos.VendorDetail update(UUID vendorId, VendorDtos.UpdateVendorRequest request) {
    User actor = accessScope.requireUser();
    Vendor vendor = require(vendorId);

    if (request.name() != null && !request.name().isBlank()) vendor.setName(request.name().trim());
    if (request.category() != null && !request.category().isBlank()) {
      vendor.setCategory(parseCategory(request.category()));
    }
    applyContact(vendor, request);
    if (request.empanelled() != null) vendor.setEmpanelled(request.empanelled());
    if (request.rating() != null) vendor.setRating(request.rating());
    if (request.paymentTermsDays() != null) {
      vendor.setPaymentTermsDays(request.paymentTermsDays());
    }
    if (request.status() != null && !request.status().isBlank()) {
      vendor.setStatus(parseStatus(request.status()));
    }
    if (request.notes() != null) vendor.setNotes(request.notes());
    vendor.setUpdatedBy(actor.getId());

    Vendor saved = vendors.save(vendor);
    recordAudit(actor, AuditActions.UPDATE, "Changed vendor " + saved.getVendorCode(), saved);
    return VendorDtos.VendorDetail.from(saved);
  }

  /**
   * Archive a vendor - soft delete, once.
   *
   * <p>Refused when the vendor has already been archived, so the operation is not silently repeated;
   * the work it already did stays readable, which is the whole reason this is an archive rather than a
   * delete.</p>
   */
  @Transactional
  public VendorDtos.VendorDetail archive(UUID vendorId) {
    User actor = accessScope.requireUser();

    // Loaded including archived rows so "already archived" is distinguished from "never existed".
    Vendor vendor =
        vendors
            .findById(vendorId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("VENDOR_NOT_FOUND")));

    if (vendor.isDeleted()) {
      throw ApiException.rule("VENDOR_ALREADY_ARCHIVED", "This vendor has already been archived");
    }

    vendor.setDeletedAt(LocalDateTime.now());
    vendor.setUpdatedBy(actor.getId());
    Vendor saved = vendors.save(vendor);

    recordAudit(
        actor,
        AuditActions.DELETE,
        "Archived vendor " + saved.getVendorCode() + " (" + saved.getName() + ")",
        saved);
    return VendorDtos.VendorDetail.from(saved);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private static void applyContact(Vendor vendor, VendorDtos.CreateVendorRequest request) {
    vendor.setContactName(trim(request.contactName()));
    vendor.setPhone(trim(request.phone()));
    vendor.setAlternatePhone(trim(request.alternatePhone()));
    vendor.setEmail(lower(request.email()));
    vendor.setTaxReference(trim(request.taxReference()));
    vendor.setAddressLine1(trim(request.addressLine1()));
    vendor.setAddressLine2(trim(request.addressLine2()));
    vendor.setLocality(trim(request.locality()));
    vendor.setCity(trim(request.city()));
    vendor.setState(trim(request.state()));
    vendor.setPostalCode(trim(request.postalCode()));
    vendor.setCountry(
        request.country() == null || request.country().isBlank() ? "India" : request.country().trim());
  }

  private static void applyContact(Vendor vendor, VendorDtos.UpdateVendorRequest request) {
    if (request.contactName() != null) vendor.setContactName(trim(request.contactName()));
    if (request.phone() != null) vendor.setPhone(trim(request.phone()));
    if (request.alternatePhone() != null) vendor.setAlternatePhone(trim(request.alternatePhone()));
    if (request.email() != null) vendor.setEmail(lower(request.email()));
    if (request.taxReference() != null) vendor.setTaxReference(trim(request.taxReference()));
    if (request.addressLine1() != null) vendor.setAddressLine1(trim(request.addressLine1()));
    if (request.addressLine2() != null) vendor.setAddressLine2(trim(request.addressLine2()));
    if (request.locality() != null) vendor.setLocality(trim(request.locality()));
    if (request.city() != null) vendor.setCity(trim(request.city()));
    if (request.state() != null) vendor.setState(trim(request.state()));
    if (request.postalCode() != null) vendor.setPostalCode(trim(request.postalCode()));
    if (request.country() != null) {
      vendor.setCountry(
          request.country().isBlank() ? "India" : trim(request.country()));
    }
  }

  private void recordAudit(User actor, String action, String description, Vendor vendor) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.MAINTENANCE,
        action,
        description,
        "VENDOR",
        vendor.getId(),
        vendor.getVendorCode());
  }

  private static boolean matchesSearch(Vendor vendor, String term) {
    return contains(vendor.getName(), term)
        || contains(vendor.getVendorCode(), term)
        || contains(vendor.getContactName(), term)
        || contains(vendor.getEmail(), term)
        || contains(vendor.getPhone(), term)
        || contains(vendor.getCity(), term)
        || contains(vendor.getTaxReference(), term);
  }

  private static boolean contains(String value, String term) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(term);
  }

  private static VendorCategory parseCategory(String value) {
    try {
      return VendorCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a vendor category");
    }
  }

  private static VendorStatus parseStatus(String value) {
    try {
      return VendorStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a vendor status");
    }
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static String lower(String value) {
    return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  private static <T extends Comparable<? super T>> Comparator<T> nullSafe() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }

  private static Comparator<Vendor> comparator(Pageable pageable) {
    Comparator<Vendor> tieBreak =
        Comparator.comparing(Vendor::getCreatedAt, nullSafe()).thenComparing(Vendor::getId, nullSafe());
    Comparator<Vendor> chosen = null;
    Sort sort = pageable == null ? Sort.unsorted() : pageable.getSort();
    for (Sort.Order order : sort) {
      Comparator<Vendor> by = SORTS.get(order.getProperty());
      if (by == null) continue;
      if (order.isDescending()) by = by.reversed();
      chosen = chosen == null ? by : chosen.thenComparing(by);
    }
    return chosen == null ? tieBreak.reversed() : chosen.thenComparing(tieBreak);
  }

  private static <T> PageResponse<T> paginate(List<T> items, Pageable pageable) {
    int size = pageable == null || pageable.getPageSize() < 1 ? 20 : pageable.getPageSize();
    int page = pageable == null ? 0 : pageable.getPageNumber();
    int from = Math.min(page * size, items.size());
    int to = Math.min(from + size, items.size());
    return PageResponse.of(items.subList(from, to), page, size, items.size());
  }
}
