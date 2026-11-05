package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Offer;
import com.meridian.realestate.domain.OfferStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The offers made on a sale, in sequence.
 *
 * <p>Every read filters on {@code deleted_at is null}. An offer is archived rather than removed,
 * because withdrawing it from the record is not the same as it never having been made.</p>
 *
 * <p><b>{@link #findAcceptedForSale} and {@link #countBySaleIdAndStatusAndDeletedAtIsNull} are how
 * the one-accepted-offer rule is honoured in the service.</b> The schema's partial unique index
 * {@code uq_offers_accepted_per_sale} refuses a second accepted offer, but it refuses it as an opaque
 * constraint violation at flush time. The service reads the sale first and returns a clear conflict
 * instead, so the caller learns the asset is already under offer rather than being handed a
 * driver-level error. See {@link Offer}.</p>
 */
public interface OfferRepository extends JpaRepository<Offer, UUID> {

  Optional<Offer> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Offer> findByOfferNumberIgnoreCaseAndDeletedAtIsNull(String offerNumber);

  boolean existsByOfferNumberIgnoreCaseAndDeletedAtIsNull(String offerNumber);

  /** Every offer on a sale, in the order it was made - the negotiation as it happened. */
  List<Offer> findAllBySaleIdAndDeletedAtIsNull(UUID saleId);

  /** Offers in one status on a sale. */
  List<Offer> findAllBySaleIdAndStatusAndDeletedAtIsNull(UUID saleId, OfferStatus status);

  /**
   * The accepted offer for a sale, if there is one.
   *
   * <p>Returns an {@code Optional} because "no offer has been accepted" is the ordinary state of a
   * live negotiation rather than an error. The status is fixed inside the query so a caller cannot
   * accidentally ask for the wrong one.</p>
   */
  @Query(
      "select o from Offer o "
          + "where o.deletedAt is null and o.sale.id = :saleId "
          + "and o.status = com.meridian.realestate.domain.OfferStatus.ACCEPTED")
  Optional<Offer> findAcceptedForSale(@Param("saleId") UUID saleId);

  /**
   * How many accepted offers a sale has. Zero for a sale nobody has accepted yet; anything else means
   * the deal is already promised and a second acceptance must be refused with a conflict.
   */
  long countBySaleIdAndStatusAndDeletedAtIsNull(UUID saleId, OfferStatus status);

  /** The negotiation summary: how many offers on this sale carry each status. */
  @Query(
      "select o.status as status, count(o) as total from Offer o "
          + "where o.deletedAt is null and o.sale.id = :saleId group by o.status")
  List<StatusCount> countGroupedByStatusForSale(@Param("saleId") UUID saleId);

  /**
   * The total offered on a sale.
   *
   * <p>Summed over every live offer regardless of status, because the question it answers is "what has
   * been put on the table" - which is how a negotiation is summarised. What was <em>accepted</em> is a
   * different figure, and it is the accepted offer's own amount, not this sum.</p>
   */
  @Query(
      "select coalesce(sum(o.amount), 0) from Offer o "
          + "where o.deletedAt is null and o.sale.id = :saleId")
  BigDecimal sumOfferedAmountForSale(@Param("saleId") UUID saleId);

  /** One status and how many of a sale's offers are in it. */
  interface StatusCount {

    OfferStatus getStatus();

    long getTotal();
  }
}
