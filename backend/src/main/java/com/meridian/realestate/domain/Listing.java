package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The offer of a specific property or unit to the market.
 *
 * <p>A listing points at a property or at a unit, or both: a whole building offered to let is listed
 * at the property level, a single flat at the unit level. The schema allows either but not neither.
 * The asking terms are separate from the property's valuation, because what the firm is currently
 * asking is a commercial decision that changes while the valuation does not.</p>
 */
@Entity
@Table(name = "listings")
public class Listing extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "listing_code", nullable = false, length = 30, unique = true)
  private String listingCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  /** The unit offered, when the listing is at unit level. Nullable: a building-level listing names none. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "unit_id")
  private Unit unit;

  @Enumerated(EnumType.STRING)
  @Column(name = "listing_type", nullable = false, length = 30)
  private ListingType listingType = ListingType.RENT;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "channel_id")
  private ListingChannel channel;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private ListingStatus status = ListingStatus.DRAFT;

  @Column(name = "headline", nullable = false, length = 255)
  private String headline;

  @Column(name = "description", columnDefinition = "text")
  private String description;

  @Column(name = "asking_rent", precision = 12, scale = 2)
  private BigDecimal askingRent;

  @Column(name = "asking_price", precision = 14, scale = 2)
  private BigDecimal askingPrice;

  /** True when the firm holds the sole mandate; see the schema note on the fee and stock. */
  @Column(name = "exclusive", nullable = false)
  private boolean exclusive = false;

  @Column(name = "negotiable", nullable = false)
  private boolean negotiable = true;

  @Column(name = "listing_start", nullable = false)
  private LocalDate listingStart;

  @Column(name = "listing_end")
  private LocalDate listingEnd;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  @Column(name = "closed_at")
  private LocalDateTime closedAt;

  @Column(name = "closed_reason", length = 500)
  private String closedReason;

  /** The agent running the listing. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "listed_by")
  private AgentProfile listedBy;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "office_id")
  private Office office;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getListingCode() {
    return listingCode;
  }

  public void setListingCode(String listingCode) {
    this.listingCode = listingCode;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public Unit getUnit() {
    return unit;
  }

  public void setUnit(Unit unit) {
    this.unit = unit;
  }

  public ListingType getListingType() {
    return listingType;
  }

  public void setListingType(ListingType listingType) {
    this.listingType = listingType;
  }

  public ListingChannel getChannel() {
    return channel;
  }

  public void setChannel(ListingChannel channel) {
    this.channel = channel;
  }

  public ListingStatus getStatus() {
    return status;
  }

  public void setStatus(ListingStatus status) {
    this.status = status;
  }

  public String getHeadline() {
    return headline;
  }

  public void setHeadline(String headline) {
    this.headline = headline;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public BigDecimal getAskingRent() {
    return askingRent;
  }

  public void setAskingRent(BigDecimal askingRent) {
    this.askingRent = askingRent;
  }

  public BigDecimal getAskingPrice() {
    return askingPrice;
  }

  public void setAskingPrice(BigDecimal askingPrice) {
    this.askingPrice = askingPrice;
  }

  public boolean isExclusive() {
    return exclusive;
  }

  public void setExclusive(boolean exclusive) {
    this.exclusive = exclusive;
  }

  public boolean isNegotiable() {
    return negotiable;
  }

  public void setNegotiable(boolean negotiable) {
    this.negotiable = negotiable;
  }

  public LocalDate getListingStart() {
    return listingStart;
  }

  public void setListingStart(LocalDate listingStart) {
    this.listingStart = listingStart;
  }

  public LocalDate getListingEnd() {
    return listingEnd;
  }

  public void setListingEnd(LocalDate listingEnd) {
    this.listingEnd = listingEnd;
  }

  public LocalDateTime getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(LocalDateTime publishedAt) {
    this.publishedAt = publishedAt;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(LocalDateTime closedAt) {
    this.closedAt = closedAt;
  }

  public String getClosedReason() {
    return closedReason;
  }

  public void setClosedReason(String closedReason) {
    this.closedReason = closedReason;
  }

  public AgentProfile getListedBy() {
    return listedBy;
  }

  public void setListedBy(AgentProfile listedBy) {
    this.listedBy = listedBy;
  }

  public Office getOffice() {
    return office;
  }

  public void setOffice(Office office) {
    this.office = office;
  }
}
