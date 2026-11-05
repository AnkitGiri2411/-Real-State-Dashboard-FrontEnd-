package com.meridian.realestate.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A named bundle of permissions handed to staff accounts.
 *
 * <p>Roles exist so "what may this person do" is answered once, in one place, instead of being
 * re-argued at every account. Their power is bounded on purpose: a role carries permission codes,
 * never endpoints, so no role can reach anything the permission set does not already describe.</p>
 *
 * <p>{@code systemRole} marks a role the schema seeded and the product's own navigation, guides and
 * sign-in expectations refer to by name. Those may be re-permissioned but never deleted through the
 * API - removing one would leave seeded accounts holding a role that no longer exists.</p>
 *
 * <p>A role is archived rather than removed, for the same reason as every other business record:
 * the accounts that held it and the audit entries that name it must stay readable, and "who could
 * do this last quarter" has to remain answerable.</p>
 */
@Entity
@Table(name = "roles")
public class Role extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 50, unique = true)
  private String name;

  @Column(name = "description", length = 255)
  private String description;

  @Column(name = "system_role", nullable = false)
  private boolean systemRole = false;

  @OneToMany(
      mappedBy = "role",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  private Set<RolePermission> permissionAssignments = new HashSet<>();

  /** Grants one capability to this role, keeping both sides of the pair in step. */
  public void addPermissionAssignment(RolePermission assignment) {
    assignment.setRole(this);
    permissionAssignments.add(assignment);
  }

  /** Withdraws a capability from this role. */
  public void removePermissionAssignment(RolePermission assignment) {
    permissionAssignments.remove(assignment);
    assignment.setRole(null);
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public boolean isSystemRole() {
    return systemRole;
  }

  public void setSystemRole(boolean systemRole) {
    this.systemRole = systemRole;
  }

  public Set<RolePermission> getPermissionAssignments() {
    return permissionAssignments;
  }

  public void setPermissionAssignments(Set<RolePermission> permissionAssignments) {
    this.permissionAssignments = permissionAssignments;
  }
}
