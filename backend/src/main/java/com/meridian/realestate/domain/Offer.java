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
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One offer made on a sale, in sequence.
 *
 * <p>A negotiation is a sequence, and keeping the sequence is what shows whether a deal is moving or
 * stuck - a fact a single "current offer" column cannot express. Every offer is therefore a row, in
 * the order it was made, carrying {@code direction} ({@link OfferDirection}) so a run of {@code FIRM}
 * entries after a {@code BUYER} entry reads as the firm countering.</p>
 *
 * <p><b>Many offers may be submitted on a sale, but only one may be accepted.</b> The schema enforces
 * that with the partial unique index {@code uq_offers_accepted_per_sale ON offers (sale_id) WHERE
 * status = 'ACCEPTED' AND deleted_at IS NULL}: acceptance is what ends the negotiation, so two accepted
 * offers on one sale would mean the asset was promised twice. The index only sees rows already marked
 * {@code ACCEPTED}, which means the database refuses the second one after the fact and does so as an
 * opaque constraint violation. <b>The service must therefore check for an existing accepted offer
 * before accepting another and fail with a clear 409,</b> so the caller is told the sale is already
 * under offer rather than being handed a driver-level error it cannot act on. The repository's
 * {@code findAcceptedForSale} is the read that check is made with.</p>
 *
 * <p>Two further constraints the schema carries. {@code ck_offers_amount_positive} requires
 * {@code amount > 0} - an offer of nothing is not an offer. And {@code ck_offers_response} requires a
 * response moment exactly when the offer has been answered: {@code SUBMITTED} and {@code WITHDRAWN}
 * must have {@code respondedAt} null, the other four statuses must have it set. That is what keeps
 * "decided" and "waiting" from being conflated - see {@link OfferStatus#isAnswered()}. An expiry, when
 * set, must fall after the offer was made ({@code ck_offers_expiry}).</p>
 */
@Entity
@Table(name = "offers")
public class Offer extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "offer_number", nullable = false, length = 30, unique = true)
  private String offerNumber;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sale_id", nullable = false)
  private SalesOpportunity sale;

  @Column(name = "amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(name = "offered_at", nullable = false)
  private LocalDateTime offeredAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "direction", nullable = false, length = 20)
  private OfferDirection direction = OfferDirection.BUYER;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OfferStatus status = OfferStatus.SUBMITTED;

  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  /** Set exactly when the offer has been answered; see the class note. */
  @Column(name = "responded_at")
  private LocalDateTime respondedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "responded_by")
  private User respondedBy;

  @Column(name = "response_notes", length = 1000)
  private String responseNotes;

  @Column(name = "conditions", columnDefinition = "text")
  private String conditions;

  /** True when this offer is the accepted one for its sale - there can be at most one. */
  public boolean isAccepted() {
    return status == OfferStatus.ACCEPTED;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getOfferNumber() {
    return offerNumber;
  }

  public void setOfferNumber(String offerNumber) {
    this.offerNumber = offerNumber;
  }

  public SalesOpportunity getSale() {
    return sale;
  }

  public void setSale(SalesOpportunity sale) {
    this.sale = sale;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public LocalDateTime getOfferedAt() {
    return offeredAt;
  }

  public void setOfferedAt(LocalDateTime offeredAt) {
    this.offeredAt = offeredAt;
  }

  public OfferDirection getDirection() {
    return direction;
  }

  public void setDirection(OfferDirection direction) {
    this.direction = direction;
  }

  public OfferStatus getStatus() {
    return status;
  }

  public void setStatus(OfferStatus status) {
    this.status = status;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public LocalDateTime getRespondedAt() {
    return respondedAt;
  }

  public void setRespondedAt(LocalDateTime respondedAt) {
    this.respondedAt = respondedAt;
  }

  public User getRespondedBy() {
    return respondedBy;
  }

  public void setRespondedBy(User respondedBy) {
    this.respondedBy = respondedBy;
  }

  public String getResponseNotes() {
    return responseNotes;
  }

  public void setResponseNotes(String responseNotes) {
    this.responseNotes = responseNotes;
  }

  public String getConditions() {
    return conditions;
  }

  public void setConditions(String conditions) {
    this.conditions = conditions;
  }
}
