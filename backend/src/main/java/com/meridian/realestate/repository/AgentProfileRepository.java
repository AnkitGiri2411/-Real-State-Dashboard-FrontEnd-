package com.meridian.realestate.repository;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.AgentSpecialisation;
import com.meridian.realestate.domain.AgentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Agent profiles: the staff accounts that carry a portfolio.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A profile is archived rather than removed,
 * because the assignments, leads and performance history that reference it must stay coherent - and
 * in any case an assignment is closed by dating it, not by deleting it.</p>
 *
 * <p>{@code user_id} is a UNIQUE foreign key to {@code users}, modelled as the one-to-one
 * {@link AgentProfile#getUser()} that it is: a staff account is a person, and a person has one
 * portfolio. Reads by account go through {@link #findByUserIdAndDeletedAtIsNull(UUID)}, and the
 * register queries fetch the account with the office, because the agent register shows a person's
 * name and branch - reading both lazily would cost two extra queries per row.</p>
 */
public interface AgentProfileRepository extends JpaRepository<AgentProfile, UUID> {

  Optional<AgentProfile> findByIdAndDeletedAtIsNull(UUID id);

  /**
   * The profile behind a signed-in staff account.
   *
   * <p>A staff account without a profile is not an agent at all - accounts, administration and
   * facilities hold permissions but no portfolio - so this answers "is this account an agent, and
   * which profile is it", and an empty answer is a real one rather than an error.</p>
   */
  Optional<AgentProfile> findByUserIdAndDeletedAtIsNull(UUID userId);

  boolean existsByUserIdAndDeletedAtIsNull(UUID userId);

  Optional<AgentProfile> findByAgentCodeIgnoreCaseAndDeletedAtIsNull(String agentCode);

  boolean existsByAgentCodeIgnoreCaseAndDeletedAtIsNull(String agentCode);

  List<AgentProfile> findAllByDeletedAtIsNull();

  Page<AgentProfile> findAllByDeletedAtIsNull(Pageable pageable);

  long countByDeletedAtIsNull();

  long countByStatusAndDeletedAtIsNull(AgentStatus status);

  List<AgentProfile> findAllByStatusAndDeletedAtIsNull(AgentStatus status);

  List<AgentProfile> findAllByOfficeIdAndDeletedAtIsNull(UUID officeId);

  /** The agents reporting to this manager. */
  List<AgentProfile> findAllByManagerIdAndDeletedAtIsNull(UUID managerId);

  /** The agents who work a given desk, for lead routing. */
  List<AgentProfile> findAllBySpecialisationAndDeletedAtIsNull(AgentSpecialisation specialisation);

  /**
   * The agent register, with the staff account, the office and the manager fetched.
   *
   * <p>The register shows each agent's name, their office and their reports-to line, so all three
   * are fetched here: read one row at a time and a hundred-agent register becomes two to three
   * hundred extra queries. The office and the manager are left joins because both are nullable -
   * an agent attached to no branch still has to appear on the register - while the staff account is
   * an inner join, since a profile without an account cannot exist.</p>
   *
   * <p>Deliberately not paged, so that a fixed order is safe to state; the paged reads above take
   * their order from the caller's {@link Pageable}.</p>
   */
  @Query(
      "select a from AgentProfile a "
          + "join fetch a.user "
          + "left join fetch a.office "
          + "left join fetch a.manager "
          + "where a.deletedAt is null "
          + "order by a.agentCode asc")
  List<AgentProfile> findAllWithUserAndOffice();

  /**
   * The agents of one office, account and office fetched, for the office roster.
   *
   * <p>Same reasoning as the register query: the roster is read as a list of people with their
   * reporting line, so it is fetched in one statement.</p>
   */
  @Query(
      "select a from AgentProfile a "
          + "join fetch a.user "
          + "left join fetch a.office "
          + "left join fetch a.manager "
          + "where a.deletedAt is null and a.office.id = :officeId "
          + "order by a.agentCode asc")
  List<AgentProfile> findAllWithUserAndOfficeByOffice(@Param("officeId") UUID officeId);
}
