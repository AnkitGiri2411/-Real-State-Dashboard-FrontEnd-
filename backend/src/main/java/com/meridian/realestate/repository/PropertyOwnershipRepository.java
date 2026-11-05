package com.meridian.realestate.repository;

import com.meridian.realestate.domain.PropertyOwnership;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The holdings: who owns what, in what share, for how long.
 *
 * <p>The methods below are the ones a service needs to enforce the rule the schema cannot. The
 * database constrains a single row to {@code (0, 100]} and to a period that runs forwards, but it
 * cannot say that the holdings in force for one property must add up to exactly 100% - that spans
 * rows. The check is therefore made in the service, inside one transaction, using
 * {@link #sumOpenPercentageForProperty(UUID)} (or {@link #sumPercentageForProperty(UUID)} when the
 * whole history is being rewritten) together with {@link #findOpenForProperty(UUID)}.</p>
 *
 * <p>Owner and property are {@code join fetch}ed wherever the caller is going to read them: an
 * ownership row without its owner is not a useful answer, and loading one row at a time would turn
 * a portfolio report into N+1 queries.</p>
 *
 * <p>Everything reads through {@code deleted_at is null}. A holding that has ended is dated
 * ({@code effective_to}), not deleted, so a past ownership stays reportable.</p>
 */
public interface PropertyOwnershipRepository extends JpaRepository<PropertyOwnership, UUID> {

  Optional<PropertyOwnership> findByIdAndDeletedAtIsNull(UUID id);

  Page<PropertyOwnership> findAllByDeletedAtIsNull(Pageable pageable);

  List<PropertyOwnership> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  long countByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  long countByOwnerIdAndDeletedAtIsNull(UUID ownerId);

  boolean existsByPropertyIdAndOwnerIdAndDeletedAtIsNull(UUID propertyId, UUID ownerId);

  /**
   * Every holding ever recorded on a property, most recent first, with the owner fetched.
   *
   * <p>Ordered by the holding's own dates rather than by insertion, because that is the order an
   * ownership history is read in. This query is not paged, so a fixed order here is deliberate
   * rather than in conflict with a caller-supplied one.</p>
   */
  @Query(
      "select po from PropertyOwnership po "
          + "join fetch po.owner "
          + "where po.deletedAt is null and po.property.id = :propertyId "
          + "order by po.effectiveFrom desc, po.id asc")
  List<PropertyOwnership> findAllForProperty(@Param("propertyId") UUID propertyId);

  /**
   * The holdings still in force on a property, largest share first.
   *
   * <p>This is what the 100% check is measured over, and what the owner report reads: a closed
   * holding is history and must not be counted towards the current total.</p>
   */
  @Query(
      "select po from PropertyOwnership po "
          + "join fetch po.owner "
          + "where po.deletedAt is null and po.property.id = :propertyId and po.effectiveTo is null "
          + "order by po.ownershipPercentage desc, po.id asc")
  List<PropertyOwnership> findOpenForProperty(@Param("propertyId") UUID propertyId);

  /** Everything one owner holds, across the portfolio, with the property fetched. */
  @Query(
      "select po from PropertyOwnership po "
          + "join fetch po.property "
          + "where po.deletedAt is null and po.owner.id = :ownerId "
          + "order by po.effectiveFrom desc, po.id asc")
  List<PropertyOwnership> findAllForOwner(@Param("ownerId") UUID ownerId);

  /** What one owner holds today, with the property fetched. */
  @Query(
      "select po from PropertyOwnership po "
          + "join fetch po.property "
          + "where po.deletedAt is null and po.owner.id = :ownerId and po.effectiveTo is null "
          + "order by po.effectiveFrom desc, po.id asc")
  List<PropertyOwnership> findOpenForOwner(@Param("ownerId") UUID ownerId);

  /**
   * The sum of the shares still in force on a property - the number that must be exactly 100.
   *
   * <p>Coalesced to zero so a property with no holdings answers {@code 0} rather than null, which
   * is a defensible answer to check against rather than a null the caller has to special-case.</p>
   */
  @Query(
      "select coalesce(sum(po.ownershipPercentage), 0) from PropertyOwnership po "
          + "where po.deletedAt is null and po.property.id = :propertyId and po.effectiveTo is null")
  BigDecimal sumOpenPercentageForProperty(@Param("propertyId") UUID propertyId);

  /**
   * The sum of every holding ever recorded on a property, open or closed.
   *
   * <p>Only meaningful while a history is being rewritten in one transaction: over the whole
   * history of a property the total routinely exceeds 100, because holdings that have ended were
   * still real when they ran.</p>
   */
  @Query(
      "select coalesce(sum(po.ownershipPercentage), 0) from PropertyOwnership po "
          + "where po.deletedAt is null and po.property.id = :propertyId")
  BigDecimal sumPercentageForProperty(@Param("propertyId") UUID propertyId);

  /**
   * The holdings in force on a property on a given date, with the owner fetched.
   *
   * <p>A holding runs from {@code effectiveFrom} to {@code effectiveTo} inclusive, and a null end
   * date means still held. Asking for the past is a first-class question here - "who owned this when
   * the invoice was raised" - which is why nothing filters on the current date implicitly.</p>
   */
  @Query(
      "select po from PropertyOwnership po "
          + "join fetch po.owner "
          + "where po.deletedAt is null and po.property.id = :propertyId "
          + "  and po.effectiveFrom <= :asOf "
          + "  and (po.effectiveTo is null or po.effectiveTo >= :asOf) "
          + "order by po.ownershipPercentage desc, po.id asc")
  List<PropertyOwnership> findEffectiveForPropertyOn(
      @Param("propertyId") UUID propertyId, @Param("asOf") LocalDate asOf);

  /** What one owner held on a given date, across the portfolio, with the property fetched. */
  @Query(
      "select po from PropertyOwnership po "
          + "join fetch po.property "
          + "where po.deletedAt is null and po.owner.id = :ownerId "
          + "  and po.effectiveFrom <= :asOf "
          + "  and (po.effectiveTo is null or po.effectiveTo >= :asOf) "
          + "order by po.effectiveFrom desc, po.id asc")
  List<PropertyOwnership> findEffectiveForOwnerOn(
      @Param("ownerId") UUID ownerId, @Param("asOf") LocalDate asOf);

  /**
   * The properties an owner held on a given date, for the owner statement.
   *
   * <p>Returns property ids rather than rows: the caller is going to read whole properties next,
   * and fetching them here would load an entity graph nobody asked for.</p>
   */
  @Query(
      "select distinct po.property.id from PropertyOwnership po "
          + "where po.deletedAt is null and po.owner.id = :ownerId "
          + "  and po.effectiveFrom <= :asOf "
          + "  and (po.effectiveTo is null or po.effectiveTo >= :asOf)")
  List<UUID> findPropertyIdsHeldByOwnerOn(
      @Param("ownerId") UUID ownerId, @Param("asOf") LocalDate asOf);
}
