package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Offer;
import com.meridian.realestate.domain.SalesOpportunity;
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
 * The sales API contracts, offers included.
 *
 * <p><b>Why the offers live here rather than in their own area.</b> An offer has no life outside the
 * sale it is made on: it is one step in one negotiation, and the sale is the record the desk works.
 * The endpoints share the {@code /offers} path because a caller revises an offer by its own id
 * without carrying the sale around, but the shapes are stated together so the sequence, the stage
 * and the outcome cannot drift apart in two files.</p>
 *
 * <p><b>The three commercial figures are three, not one.</b> {@code askingPrice} is what the asset
 * was marketed at, {@code expectedPrice} is what the deal is realistically expected to fetch and is
 * what the open-pipeline value sums, and {@code agreedPrice} exists only on a concluded sale - the
 * schema refuses one on any other stage. Collapsing them would lose the difference between what was
 * wanted and what is likely, which is the whole of pipeline reporting.</p>
 *
 * <p>{@link SaleDetail} publishes {@code allowedTransitions} and the offer sequence, so a screen can
 * offer exactly the moves that are legal and show the negotiation as it happened - which is the only
 * thing that distinguishes a deal that is moving from one that is stuck.</p>
 */
public final class SaleDtos {

  private SaleDtos() {}

  /** A row on the pipeline board. */
  public record SaleSummary(
      UUID id,
      String saleCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitCode,
      UUID buyerId,
      String buyerName,
      UUID leadId,
      String stage,
      BigDecimal askingPrice,
      BigDecimal expectedPrice,
      BigDecimal agreedPrice,
      LocalDate expectedCloseDate,
      LocalDateTime closedAt,
      LocalDateTime lostAt,
      String lostReason,
      UUID ownerAgentId,
      String ownerAgentName,
      UUID officeId,
      String officeName,
      /** True when the deal is still live and belongs in the pipeline figures. */
      boolean open,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    /** Must be called inside a transaction: the property, unit, buyer, agent and office are lazy. */
    public static SaleSummary from(SalesOpportunity sale) {
      return new SaleSummary(
          sale.getId(),
          sale.getSaleCode(),
          sale.getProperty() == null ? null : sale.getProperty().getId(),
          sale.getProperty() == null ? null : sale.getProperty().getPropertyCode(),
          sale.getProperty() == null ? null : sale.getProperty().getName(),
          sale.getUnit() == null ? null : sale.getUnit().getId(),
          sale.getUnit() == null ? null : sale.getUnit().getUnitCode(),
          sale.getBuyer() == null ? null : sale.getBuyer().getId(),
          sale.getBuyer() == null ? null : sale.getBuyer().getDisplayName(),
          sale.getLead() == null ? null : sale.getLead().getId(),
          sale.getStage() == null ? null : sale.getStage().name(),
          sale.getAskingPrice(),
          sale.getExpectedPrice(),
          sale.getAgreedPrice(),
          sale.getExpectedCloseDate(),
          sale.getClosedAt(),
          sale.getLostAt(),
          sale.getLostReason(),
          sale.getOwnerAgent() == null ? null : sale.getOwnerAgent().getId(),
          SaleDtos.agentName(sale),
          sale.getOffice() == null ? null : sale.getOffice().getId(),
          sale.getOffice() == null ? null : sale.getOffice().getName(),
          sale.isOpen(),
          sale.getCreatedAt(),
          sale.getUpdatedAt());
    }
  }

  /** Everything the deal screen shows for one sale: the negotiation, its steps and its trail. */
  public record SaleDetail(
      UUID id,
      String saleCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitCode,
      UUID buyerId,
      String buyerName,
      UUID leadId,
      String stage,
      BigDecimal askingPrice,
      BigDecimal expectedPrice,
      BigDecimal agreedPrice,
      LocalDate expectedCloseDate,
      LocalDateTime closedAt,
      LocalDateTime lostAt,
      String lostReason,
      UUID ownerAgentId,
      String ownerAgentName,
      UUID officeId,
      String officeName,
      String notes,
      boolean open,
      /** The accepted offer's id, or null while the negotiation is still open. */
      UUID acceptedOfferId,
      /** What has been put on the table across every live offer - not what was accepted. */
      BigDecimal totalOffered,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      /** The moves that are legal from the current stage, so the UI offers only those. */
      Set<String> allowedTransitions,
      List<OfferEntry> offers,
      List<StageHistoryEntry> stageHistory) {}

  /** One offer in a negotiation, as it was made. */
  public record OfferEntry(
      UUID id,
      String offerNumber,
      UUID saleId,
      BigDecimal amount,
      LocalDateTime offeredAt,
      String direction,
      String status,
      LocalDateTime expiresAt,
      LocalDateTime respondedAt,
      UUID respondedById,
      String respondedByName,
      String responseNotes,
      String conditions) {

    /** Must be called inside a transaction: the responder's account is lazy. */
    public static OfferEntry from(Offer offer) {
      return new OfferEntry(
          offer.getId(),
          offer.getOfferNumber(),
          offer.getSale() == null ? null : offer.getSale().getId(),
          offer.getAmount(),
          offer.getOfferedAt(),
          offer.getDirection() == null ? null : offer.getDirection().name(),
          offer.getStatus() == null ? null : offer.getStatus().name(),
          offer.getExpiresAt(),
          offer.getRespondedAt(),
          offer.getRespondedBy() == null ? null : offer.getRespondedBy().getId(),
          offer.getRespondedBy() == null ? null : offer.getRespondedBy().getFullName(),
          offer.getResponseNotes(),
          offer.getConditions());
    }
  }

  /** One recorded transition in a sale's stage. */
  public record StageHistoryEntry(
      UUID id,
      String fromStage,
      String toStage,
      String reason,
      UUID changedById,
      LocalDateTime changedAt) {}

  /** The pipeline board: how many deals sit in each stage and what the open pipeline is worth. */
  public record PipelineResponse(
      long totalSales,
      long openSales,
      BigDecimal openPipelineValue,
      LocalDateTime generatedAt,
      List<StageBucket> stages) {}

  /** One column of the board. The per-stage value is deliberately absent: see the class note. */
  public record StageBucket(String stage, long count, BigDecimal value, boolean open) {}

  /** The sales card: the pipeline, and what has actually completed. */
  public record SalesSummary(
      long totalSales,
      long openSales,
      long closedSales,
      long lostSales,
      BigDecimal openPipelineValue,
      BigDecimal closedAgreedValue,
      LocalDateTime generatedAt) {}

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /**
   * Open a sales opportunity.
   *
   * <p>No stage is supplied: every sale opens at {@code LEAD} and is moved on by the offer it
   * attracts or by an explicit stage change. A caller that could open a deal at
   * {@code UNDER_CONTRACT} could put a number into the pipeline that nothing supports.</p>
   */
  public record CreateSaleRequest(
      @NotNull UUID propertyId,
      UUID unitId,
      @NotNull UUID buyerId,
      UUID leadId,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal askingPrice,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal expectedPrice,
      LocalDate expectedCloseDate,
      UUID ownerAgentId,
      UUID officeId,
      String notes) {}

  /** Change a sale's commercial details. Concluded deals are refused. */
  public record UpdateSaleRequest(
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal askingPrice,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal expectedPrice,
      LocalDate expectedCloseDate,
      UUID ownerAgentId,
      UUID officeId,
      String notes) {}

  /**
   * Move a sale between stages.
   *
   * <p>{@code agreedPrice} is required to close a deal as won - an agreed sale needs a price, and the
   * schema refuses a {@code CLOSED} row without one - while {@code lostReason} is required to lose
   * one. Neither is accepted for any other stage, because the schema requires every other stage to
   * carry neither: a concluded outcome on a live deal is how a pipeline figure double-counts.</p>
   */
  public record SaleStageTransitionRequest(
      @NotBlank String stage,
      @Size(max = 500) String reason,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal agreedPrice,
      @Size(max = 500) String lostReason) {}

  /** Record an offer made on a sale. */
  public record RecordOfferRequest(
      @NotNull @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal amount,
      /** {@code BUYER} unless the firm is making the first move; defaults to the buyer's. */
      String direction,
      LocalDateTime expiresAt,
      String conditions) {}

  /**
   * Decide an offer.
   *
   * <p>{@code action} is one of {@code ACCEPT}, {@code COUNTER}, {@code REJECT} or {@code WITHDRAW};
   * a counter is a new offer in the other direction rather than an amendment of this one, which is
   * why it needs the amount the firm is putting forward and why the original is marked
   * {@code COUNTERED} rather than overwritten.</p>
   */
  public record OfferDecisionRequest(
      @NotBlank String action,
      /** Required by {@code COUNTER}: the amount the firm is offering. */
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal counterAmount,
      LocalDateTime expiresAt,
      @Size(max = 1000) String responseNotes) {}

  /** The name an agent reads back as, or null when the deal is unowned or has no account. */
  public static String agentName(SalesOpportunity sale) {
    if (sale == null || sale.getOwnerAgent() == null || sale.getOwnerAgent().getUser() == null) {
      return null;
    }
    return sale.getOwnerAgent().getUser().getFullName();
  }
}
