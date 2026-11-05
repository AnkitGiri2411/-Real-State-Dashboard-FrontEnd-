package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ApplicationScreening;
import com.meridian.realestate.domain.ScreeningStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The screening checks run on an application. */
public interface ApplicationScreeningRepository extends JpaRepository<ApplicationScreening, UUID> {

  /** Every check recorded against one application. */
  List<ApplicationScreening> findAllByApplicationIdAndDeletedAtIsNull(UUID applicationId);

  long countByApplicationIdAndDeletedAtIsNull(UUID applicationId);

  /**
   * How many checks on an application have not passed yet. The service refuses a decision while this
   * is greater than zero, so an application cannot be approved on incomplete screening evidence.
   *
   * <p>The caller passes the passed status ({@code PASSED}); the count is everything else that is
   * still live. Note that a {@code WAIVED} check is a resolved check - a caller that treats a waiver
   * as "not passed" would refuse a decision that the firm has already decided to allow.</p>
   */
  @Query(
      "select count(s) from ApplicationScreening s "
          + "where s.deletedAt is null and s.application.id = :applicationId and s.status <> :passedStatus")
  long countOutstandingChecks(
      @Param("applicationId") UUID applicationId, @Param("passedStatus") ScreeningStatus passedStatus);
}
