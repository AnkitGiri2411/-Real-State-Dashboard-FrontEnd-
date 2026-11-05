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
 * The assignment of one role to one staff account.
 *
 * <p>A person may hold several roles - a property manager who also covers the letting desk is
 * ordinary in a firm this size - so an account's authority is the union of the permissions of every
 * assignment here. That union is the whole point of the table: it is what lets the sign-in path
 * build a token's authorities from one join instead of a role-name switch that would have to be
 * edited every time the firm renames a role.</p>
 *
 * <p>There are no audit columns to map. The assignment carries no state beyond its existence, and
 * the account and the role it joins are both {@link Auditable}, so the trail of who could do what
 * is assembled from rows that already keep their own timestamps.</p>
 */
@Entity
@Table(name = "user_roles")
public class UserRole {

  @EmbeddedId
  @AttributeOverrides({
    @AttributeOverride(name = "userId", column = @Column(name = "user_id", nullable = false)),
    @AttributeOverride(name = "roleId", column = @Column(name = "role_id", nullable = false))
  })
  private UserRoleId id;

  @MapsId("userId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @MapsId("roleId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "role_id", nullable = false)
  private Role role;

  public UserRole() {}

  /**
   * The assignment of {@code role} to {@code user}.
   *
   * <p>The key is deliberately not built here: {@code @MapsId} derives it from the two associations
   * at flush time, and writing it by hand as well would only give a second place for the pair to
   * disagree with the foreign keys.</p>
   */
  public UserRole(User user, Role role) {
    this.user = user;
    this.role = role;
  }

  public UserRoleId getId() {
    return id;
  }

  public void setId(UserRoleId id) {
    this.id = id;
  }

  public User getUser() {
    return user;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public Role getRole() {
    return role;
  }

  public void setRole(Role role) {
    this.role = role;
  }
}
