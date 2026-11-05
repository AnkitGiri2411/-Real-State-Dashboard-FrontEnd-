package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Permission;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The capability catalogue.
 *
 * <p>The permission set is seeded by the migration chain and read by the whole product: the
 * administration screen shows it grouped by module, the role editor offers it, and the sign-in path
 * resolves it into a token's authorities. Nothing here filters or deletes, because a capability that
 * an endpoint no longer needs is withdrawn from the roles that hold it rather than removed from the
 * catalogue - the code a token carried has to stay explainable after the fact.</p>
 */
public interface PermissionRepository extends JpaRepository<Permission, UUID> {

  /**
   * The capability behind one code.
   *
   * <p>{@code code} is unique in the schema, so this is an exact lookup rather than a search: the
   * same string is what {@code @PreAuthorize} checks and what a token carries, and a fuzzy match
   * here would be a way to grant something nobody asked for.</p>
   */
  Optional<Permission> findByCode(String code);

  boolean existsByCode(String code);

  /** The whole catalogue in the order the role editor presents it: grouped by module, then by code. */
  List<Permission> findAllByOrderByModuleAscCodeAsc();

  /** The capabilities of one module - one tab of the role editor. */
  List<Permission> findAllByModule(String module);
}
