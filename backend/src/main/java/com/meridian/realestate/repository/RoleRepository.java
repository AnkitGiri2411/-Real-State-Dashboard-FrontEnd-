package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * The role bundles.
 *
 * <p>Reads stay on live roles: a role is archived rather than deleted, because the accounts that
 * held it and the audit entries that name it must stay coherent, but an archived role must never
 * appear in a picker where somebody could hand it to a colleague again.</p>
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

  /** A role by name. Names are unique in the schema, so this is an exact lookup. */
  Optional<Role> findByName(String name);

  /** Whether the name is already taken, ignoring case: "Admin" and "ADMIN" read as the same role. */
  boolean existsByNameIgnoreCase(String name);

  /** The live roles in the order the staff and role screens list them. */
  List<Role> findAllByOrderByNameAsc();

  /**
   * The live roles with their capability sets loaded in one round trip.
   *
   * <p>The role list renders what each role may do, so every row needs its grants and their
   * permission codes. Fetching them lazily would be one query per role plus one per grant - a page
   * of eleven seeded roles would cost hundreds of round trips to draw one screen. This is also the
   * query the role editor opens on, because editing a role without its current capabilities is an
   * invitation to widen it by accident.</p>
   */
  @Query(
      "select distinct r from Role r "
          + "left join fetch r.permissionAssignments rp "
          + "left join fetch rp.permission "
          + "where r.deletedAt is null "
          + "order by r.name asc")
  List<Role> findAllWithPermissions();
}
