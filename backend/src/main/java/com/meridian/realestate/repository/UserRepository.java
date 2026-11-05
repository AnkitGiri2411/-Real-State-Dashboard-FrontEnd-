package com.meridian.realestate.repository;

import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Staff accounts.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist, so
 * the caller's requested order is the one that is actually applied.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. An account is archived rather than removed,
 * because the leases, audit entries and approvals that name it must stay coherent - and because
 * reusing a departed colleague's address for a new hire would silently reattribute everything the
 * first person did.</p>
 */
public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * A live or archived account by address.
   *
   * <p>The caller lower-cases the address first. The database holds nothing else
   * ({@code ck_users_email_lower}), so matching the stored form exactly keeps this an index lookup
   * instead of a scan with a function call on the column - and it means a caller who forgets to
   * lower-case gets no match, loudly, rather than a second account nobody can sign in to.</p>
   *
   * <p>Note that this deliberately does not filter the soft delete: the sign-in path has to be able
   * to resolve an archived address in order to refuse it after the password has been checked.</p>
   */
  Optional<User> findByEmail(String email);

  Optional<User> findByIdAndDeletedAtIsNull(UUID id);

  /** A live account by address - the lookup for everything except the sign-in path itself. */
  Optional<User> findByEmailAndDeletedAtIsNull(String email);

  boolean existsByEmailIgnoreCase(String email);

  boolean existsByStaffCodeIgnoreCase(String staffCode);

  Page<User> findAllByDeletedAtIsNull(Pageable pageable);

  List<User> findAllByDeletedAtIsNull();

  /** The live accounts in one state, for the staff directory's status filter. */
  List<User> findAllByStatusAndDeletedAtIsNull(UserStatus status);

  /** The same filter, paged - the staff list itself. */
  Page<User> findAllByStatusAndDeletedAtIsNull(UserStatus status, Pageable pageable);

  long countByDeletedAtIsNull();

  /** Headcount by state, for the team summary the dashboard shows. */
  long countByStatusAndDeletedAtIsNull(UserStatus status);

  /**
   * One account with its roles and their capabilities loaded.
   *
   * <p>This is the query the sign-in and refresh paths use to build a token's authorities. Doing it
   * lazily would be an N+1 on the hottest path in the product - one query per role, then one per
   * grant - and a token is minted on every sign-in and every rotation. Everything is fetched
   * eagerly here precisely so the authority list can be assembled with no further reads.</p>
   *
   * <p>The permissions of a role are fetched through {@link
   * com.meridian.realestate.domain.RolePermission} because that is the row that actually holds the
   * grant; the capability code a token carries comes from the permission it points at.</p>
   */
  @Query(
      "select distinct u from User u "
          + "left join fetch u.roleAssignments ur "
          + "left join fetch ur.role r "
          + "left join fetch r.permissionAssignments rp "
          + "left join fetch rp.permission "
          + "where u.id = :id and u.deletedAt is null")
  Optional<User> findByIdWithAuthorities(@Param("id") UUID id);
}
