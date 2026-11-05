package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Office;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The office register's API contract.
 *
 * <p><b>Why these records exist.</b> An {@code Office} is a real place - viewings are booked from it
 * and correspondence is addressed to it - rather than a label on a staff record, so it has its own
 * register, its own create and change shapes, and a detail that carries the headcount of the agents
 * who sit in it. Returning the entity would leak the housekeeping columns; these records are the
 * deliberate subset a branch screen prints.</p>
 *
 * <p><b>The rule these records protect.</b> There is exactly one head office. The schema enforces it
 * with the partial unique index {@code uq_offices_single_head} over
 * {@code (is_head_office) WHERE is_head_office = true AND deleted_at IS NULL}, because two rows
 * claiming to be the registered office would silently double-count in office-level reporting. The
 * service refuses a second head office first, as a clear {@code 409}, rather than letting the index
 * report it as an opaque integrity error - see {@link com.meridian.realestate.service.OfficeService}.</p>
 */
public final class OfficeDtos {

  private OfficeDtos() {}

  /** The closed set of statuses the schema's {@code ck_offices_status} allows. */
  private static final String OFFICE_STATUS_SET = "ACTIVE|INACTIVE";

  /** One row of the office register. */
  public record OfficeSummary(
      UUID id,
      String officeCode,
      String name,
      String city,
      String state,
      String phone,
      String email,
      boolean headOffice,
      String status,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static OfficeSummary from(Office office) {
      return new OfficeSummary(
          office.getId(),
          office.getOfficeCode(),
          office.getName(),
          office.getCity(),
          office.getState(),
          office.getPhone(),
          office.getEmail(),
          office.isHeadOffice(),
          statusName(office.getStatus()),
          office.getCreatedAt(),
          office.getUpdatedAt());
    }
  }

  /** One office's full record, with the number of agents who sit in it. */
  public record OfficeDetail(
      UUID id,
      String officeCode,
      String name,
      String addressLine1,
      String addressLine2,
      String locality,
      String city,
      String state,
      String postalCode,
      String country,
      String phone,
      String email,
      boolean headOffice,
      String status,
      long agentCount,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** Add a branch. The office code is supplied by the caller (there is no seeded series for it). */
  public record CreateOfficeRequest(
      @NotBlank @Size(max = 30) String officeCode,
      @NotBlank @Size(max = 150) String name,
      @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @NotBlank @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      @Size(max = 30) String phone,
      @Email @Size(max = 255) String email,
      Boolean headOffice,
      @Pattern(regexp = OFFICE_STATUS_SET, message = "must be an office status") String status) {}

  /** Change a branch. Every field is optional; only the ones supplied are applied. */
  public record UpdateOfficeRequest(
      @Size(max = 150) String name,
      @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      @Size(max = 30) String phone,
      @Email @Size(max = 255) String email,
      Boolean headOffice,
      @Pattern(regexp = OFFICE_STATUS_SET, message = "must be an office status") String status) {}

  private static String statusName(Enum<?> value) {
    return value == null ? null : value.name();
  }
}
