package com.meridian.realestate.dto;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.Listing;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The listing API contracts.
 *
 * <p>A listing carries no endpoint that writes an arbitrary status. It moves through the named
 * operations - publish, unpublish, expire - and a generic status move, each validated against the
 * service's listing lifecycle. {@link ListingDetail} publishes {@code allowedTransitions} so a screen
 * offers only the moves that will succeed.</p>
 *
 * <p>{@code stale} is a derived flag rather than a stored column: a listing is stale when it has been
 * on the market longer than the installation's configured window, which is a policy threshold rather
 * than a fact about the row.</p>
 */
public final class ListingDtos {

  private ListingDtos() {}

  static String agentName(AgentProfile agent) {
    if (agent == null || agent.getUser() == null) return null;
    return agent.getUser().getFullName();
  }

  /** A row in the listing board. */
  public record ListingSummary(
      UUID id,
      String listingCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitNumber,
      String listingType,
      String channelCode,
      String channelName,
      String status,
      boolean live,
      String headline,
      BigDecimal askingRent,
      BigDecimal askingPrice,
      boolean exclusive,
      boolean negotiable,
      LocalDate listingStart,
      LocalDate listingEnd,
      UUID listedById,
      String listedByName,
      UUID officeId,
      LocalDateTime publishedAt,
      LocalDateTime closedAt,
      /** True when the listing has been on the market past the configured stale window. */
      boolean stale,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    /** Must be called inside a transaction: the property, unit, channel and agent are lazy. */
    public static ListingSummary from(Listing listing, boolean stale) {
      return new ListingSummary(
          listing.getId(),
          listing.getListingCode(),
          listing.getProperty() == null ? null : listing.getProperty().getId(),
          listing.getProperty() == null ? null : listing.getProperty().getPropertyCode(),
          listing.getProperty() == null ? null : listing.getProperty().getName(),
          listing.getUnit() == null ? null : listing.getUnit().getId(),
          listing.getUnit() == null ? null : listing.getUnit().getUnitNumber(),
          listing.getListingType() == null ? null : listing.getListingType().name(),
          listing.getChannel() == null ? null : listing.getChannel().getCode(),
          listing.getChannel() == null ? null : listing.getChannel().getName(),
          listing.getStatus() == null ? null : listing.getStatus().name(),
          listing.getStatus() != null && listing.getStatus().isLive(),
          listing.getHeadline(),
          listing.getAskingRent(),
          listing.getAskingPrice(),
          listing.isExclusive(),
          listing.isNegotiable(),
          listing.getListingStart(),
          listing.getListingEnd(),
          listing.getListedBy() == null ? null : listing.getListedBy().getId(),
          agentName(listing.getListedBy()),
          listing.getOffice() == null ? null : listing.getOffice().getId(),
          listing.getPublishedAt(),
          listing.getClosedAt(),
          stale,
          listing.getCreatedAt(),
          listing.getUpdatedAt());
    }
  }

  /** Everything the listing screen shows for one listing. */
  public record ListingDetail(
      UUID id,
      String listingCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitNumber,
      String listingType,
      String channelCode,
      String channelName,
      String status,
      String headline,
      String description,
      BigDecimal askingRent,
      BigDecimal askingPrice,
      boolean exclusive,
      boolean negotiable,
      LocalDate listingStart,
      LocalDate listingEnd,
      LocalDateTime publishedAt,
      LocalDateTime closedAt,
      String closedReason,
      UUID listedById,
      String listedByName,
      UUID officeId,
      boolean stale,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      Set<String> allowedTransitions,
      List<ListingStatusHistoryEntry> statusHistory,
      List<ViewingDtos.ViewingSummary> viewings,
      List<ApplicationDtos.ApplicationSummary> applications) {}

  /** One transition in a listing's publication state. */
  public record ListingStatusHistoryEntry(
      UUID id,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedById,
      LocalDateTime changedAt) {

    public static ListingStatusHistoryEntry from(
        com.meridian.realestate.domain.ListingStatusHistory entry) {
      return new ListingStatusHistoryEntry(
          entry.getId(),
          entry.getFromStatus() == null ? null : entry.getFromStatus().name(),
          entry.getToStatus() == null ? null : entry.getToStatus().name(),
          entry.getReason(),
          entry.getChangedBy() == null ? null : entry.getChangedBy().getId(),
          entry.getChangedAt());
    }
  }

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /** Create a listing. It starts as a DRAFT; publishing is a separate, guarded act. */
  public record CreateListingRequest(
      @NotNull UUID propertyId,
      UUID unitId,
      String listingType,
      String channelCode,
      @NotBlank @Size(max = 255) String headline,
      String description,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal askingRent,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal askingPrice,
      Boolean exclusive,
      Boolean negotiable,
      LocalDate listingStart,
      LocalDate listingEnd,
      UUID listedById,
      UUID officeId) {}

  /** Change a listing's details. A null field is left unchanged. */
  public record UpdateListingRequest(
      String channelCode,
      String listingType,
      @Size(max = 255) String headline,
      String description,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal askingRent,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal askingPrice,
      Boolean exclusive,
      Boolean negotiable,
      LocalDate listingStart,
      LocalDate listingEnd,
      UUID listedById,
      UUID officeId) {}

  /** Move a listing between publication states. */
  public record ListingStatusChangeRequest(
      @NotBlank String status, @Size(max = 500) String reason) {}

  /** Publish or withdraw a listing, with a reason. */
  public record ListingActionRequest(@Size(max = 500) String reason) {}
}
