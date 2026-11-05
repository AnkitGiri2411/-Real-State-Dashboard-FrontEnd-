package com.meridian.realestate.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * The grant of one capability to one role.
 *
 * <p>This is the edge that makes a role's capability set, and it is mapped as its own entity rather
 * than as a bare many-to-many collection because the administration screen changes it one grant at
 * a time - adding "may archive a property" to a role, taking it away again - and the row is what
 * that change names.</p>
 *
 * <p>There are no audit columns to map: the pair carries no state of its own beyond its existence,
 * and the role and the permission it joins both carry their own trail. The grant is the strongest
 * statement available about access, so the schema keeps it as narrow as it can be.</p>
 */
@Entity
@Table(name = "role_permissions")
public class RolePermission {

  @EmbeddedId
  @AttributeOverrides({
    @AttributeOverride(name = "roleId", column = @Column(name = "role_id", nullable = false)),
    @AttributeOverride(
        name = "permissionId",
        column = @Column(name = "permission_id", nullable = false))
  })
  private RolePermissionId id;

  @MapsId("roleId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "role_id", nullable = false)
  private Role role;

  @MapsId("permissionId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "permission_id", nullable = false)
  private Permission permission;

  public RolePermission() {}

  /**
   * A grant of {@code permission} to {@code role}.
   *
   * <p>The key is deliberately not built here: {@code @MapsId} derives it from the two
   * associations at flush time, and writing it by hand as well would only give a second place for
   * the pair to disagree with the foreign keys.</p>
   */
  public RolePermission(Role role, Permission permission) {
    this.role = role;
    this.permission = permission;
  }

  public RolePermissionId getId() {
    return id;
  }

  public void setId(RolePermissionId id) {
    this.id = id;
  }

  public Role getRole() {
    return role;
  }

  public void setRole(Role role) {
    this.role = role;
  }

  public Permission getPermission() {
    return permission;
  }

  public void setPermission(Permission permission) {
    this.permission = permission;
  }
}
