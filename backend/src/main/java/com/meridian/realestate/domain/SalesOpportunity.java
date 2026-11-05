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
 * A negotiation with one buyer about one asset - the capital side of the business.
 *
 * <p>A sale is a separate record from the listing it may have come through, because a sale is a
 * negotiation with one buyer about one asset while a listing is an offer to the market. One listing
 * can produce several offers from several buyers, and only one of them becomes the sale. That is why
 * the offers live in their own table ({@link Offer}) rather than in an {@code offer_amount} column
 * here, and why this row is the negotiation rather than the advertisement.</p>
 *
 * <p>The opportunity walks {@link SaleStage} from {@code LEAD} to {@code CLOSED}, or to
 * {@code LOST} from any point before contract. The history of those moves is kept in
 * {@link SaleStageHistory} rather than inferred from the row.</p>
 *
 * <p><b>The outcome constraint - stated exactly as the schema has it.</b> {@code
 * ck_sales_opportunities_outcome} allows three shapes and nothing else:</p>
 * <ul>
 *   <li>{@code stage = 'CLOSED'} requires {@code agreed_price IS NOT NULL} and
 *       {@code closed_at IS NOT NULL}, and requires {@code lost_at} and {@code lost_reason} to be
 *       null;</li>
 *   <li>{@code stage = 'LOST'} requires {@code lost_at IS NOT NULL} and
 *       {@code lost_reason IS NOT NULL}, and requires {@code closed_at} and {@code agreed_price} to
 *       be null;</li>
 *   <li>every other stage requires {@code closed_at} and {@code lost_at} to be null <em>and</em>
 *       {@code agreed_price IS NULL}.</li>
 * </ul>
 * <p>So a completed sale must carry an agreed price, and the other stages must not be forced to:
 * a deal in negotiation has an {@code expectedPrice} and no agreed one, and the constraint is what
 * stops a concluded deal being read as a live one. Concretely, a caller moving a sale into
 * {@code CLOSED} must set {@code agreedPrice} and {@code closedAt} in the same statement, and a
 * caller moving it into {@code LOST} must set {@code lostAt} and {@code lostReason} - a half-applied
 * outcome is refused by the database, which is why the service sets the whole outcome rather than
 * one column.</p>
 *
 * <p>Two further rules the schema carries and the service must respect. The partial unique index
 * {@code uq_sales_opportunities_open} allows only one live opportunity per buyer per property-and-unit,
 * because a buyer cannot be negotiating the same asset twice and two open rows would double-count the
 * pipeline. And the partial index {@code ix_sales_opportunities_pipeline} covers only the stages that
 * are <em>not</em> {@code CLOSED} or {@code LOST}: those two are concluded, so every pipeline value
 * and every funnel figure is computed over the rest. See {@link SaleStage#isConcluded()}.</p>
 *
 * <p>The commercial figures are three, not one. {@code askingPrice} is what the asset was marketed at,
 * {@code expectedPrice} is what the deal is realistically expected to fetch and is what the pipeline
 * value sums, and {@code agreedPrice} is what it actually closed at. Collapsing them would lose the
 * difference between what was wanted and what is likely, which is the whole of pipeline reporting.</p>
 */
@Entity
@Table(name = "sales_opportunities")
public class SalesOpportunity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "sale_code", nullable = false, length = 30, unique = true)
  private String saleCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  /** Set when the sale is of a specific unit rather than of the whole asset. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "unit_id")
  private Unit unit;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "buyer_id", nullable = false)
  private Buyer buyer;

  /** The enquiry this grew out of, when it grew out of one. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lead_id")
  private Lead lead;

  @Enumerated(EnumType.STRING)
  @Column(name = "stage", nullable = false, length = 20)
  private SaleStage stage = SaleStage.LEAD;

  @Column(name = "asking_price", precision = 14, scale = 2)
  private BigDecimal askingPrice;

  /** What the deal is expected to fetch; this is what the open-pipeline value sums. */
  @Column(name = "expected_price", precision = 14, scale = 2)
  private BigDecimal expectedPrice;

  /** What it actually closed at. Only a CLOSED sale has one; see the class note. */
  @Column(name = "agreed_price", precision = 14, scale = 2)
  private BigDecimal agreedPrice;

  @Column(name = "expected_close_date")
  private LocalDate expectedCloseDate;

  /** The closure moment. Non-null if and only if the stage is CLOSED. */
  @Column(name = "closed_at")
  private LocalDateTime closedAt;

  /** The loss moment. Non-null if and only if the stage is LOST. */
  @Column(name = "lost_at")
  private LocalDateTime lostAt;

  @Column(name = "lost_reason", length = 500)
  private String lostReason;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_agent_id")
  private AgentProfile ownerAgent;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "office_id")
  private Office office;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  /** True when the deal is still live and belongs in the pipeline figures. */
  public boolean isOpen() {
    return stage != null && !stage.isConcluded();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getSaleCode() {
    return saleCode;
  }

  public void setSaleCode(String saleCode) {
    this.saleCode = saleCode;
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

  public Buyer getBuyer() {
    return buyer;
  }

  public void setBuyer(Buyer buyer) {
    this.buyer = buyer;
  }

  public Lead getLead() {
    return lead;
  }

  public void setLead(Lead lead) {
    this.lead = lead;
  }

  public SaleStage getStage() {
    return stage;
  }

  public void setStage(SaleStage stage) {
    this.stage = stage;
  }

  public BigDecimal getAskingPrice() {
    return askingPrice;
  }

  public void setAskingPrice(BigDecimal askingPrice) {
    this.askingPrice = askingPrice;
  }

  public BigDecimal getExpectedPrice() {
    return expectedPrice;
  }

  public void setExpectedPrice(BigDecimal expectedPrice) {
    this.expectedPrice = expectedPrice;
  }

  public BigDecimal getAgreedPrice() {
    return agreedPrice;
  }

  public void setAgreedPrice(BigDecimal agreedPrice) {
    this.agreedPrice = agreedPrice;
  }

  public LocalDate getExpectedCloseDate() {
    return expectedCloseDate;
  }

  public void setExpectedCloseDate(LocalDate expectedCloseDate) {
    this.expectedCloseDate = expectedCloseDate;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(LocalDateTime closedAt) {
    this.closedAt = closedAt;
  }

  public LocalDateTime getLostAt() {
    return lostAt;
  }

  public void setLostAt(LocalDateTime lostAt) {
    this.lostAt = lostAt;
  }

  public String getLostReason() {
    return lostReason;
  }

  public void setLostReason(String lostReason) {
    this.lostReason = lostReason;
  }

  public AgentProfile getOwnerAgent() {
    return ownerAgent;
  }

  public void setOwnerAgent(AgentProfile ownerAgent) {
    this.ownerAgent = ownerAgent;
  }

  public Office getOffice() {
    return office;
  }

  public void setOffice(Office office) {
    this.office = office;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
