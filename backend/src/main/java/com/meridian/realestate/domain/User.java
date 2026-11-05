package com.meridian.realestate.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A staff account: the identity that signs in to the dashboard.
 *
 * <p>{@code email} is stored lower-cased and the database enforces it
 * ({@code ck_users_email_lower}). Addresses are matched lower-cased, so a mixed-case row would be a
 * second account that nobody could sign in to predictably; the rule is a constraint rather than a
 * convention because it has to hold against a future migration or import that forgets it. The
 * caller lower-cases before storing, so a lookup by address is an exact match rather than a scan
 * with a function call on the column.</p>
 *
 * <p>{@code status} is checked <em>after</em> the password, never before. Refusing a deactivated
 * account earlier would tell an unauthenticated caller that the address exists, which the sign-in
 * path otherwise refuses to reveal.</p>
 *
 * <p>{@code passwordHash} must never leave the persistence layer. No DTO, response body, log line or
 * audit entry may carry it - the entity is the only place it is allowed to exist, and a sign-in
 * audit entry records that somebody signed in, never how.</p>
 *
 * <p>Accounts are archived rather than deleted: the leases they negotiated, the approvals they gave
 * and the audit entries that name them all reference the row, and "who did this" has to stay
 * answerable after they leave the firm.</p>
 */
@Entity
@Table(name = "users")
public class User extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "staff_code", nullable = false, length = 30, unique = true)
  private String staffCode;

  @Column(name = "first_name", nullable = false, length = 100)
  private String firstName;

  @Column(name = "last_name", nullable = false, length = 100)
  private String lastName;

  @Column(name = "email", nullable = false, length = 255, unique = true)
  private String email;

  @Column(name = "phone", length = 30)
  private String phone;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "department", length = 80)
  private String department;

  @Column(name = "designation", length = 80)
  private String designation;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private UserStatus status = UserStatus.ACTIVE;

  @Column(name = "force_password_reset", nullable = false)
  private boolean forcePasswordReset = false;

  @Column(name = "last_login_at")
  private LocalDateTime lastLoginAt;

  @OneToMany(
      mappedBy = "user",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  private Set<UserRole> roleAssignments = new HashSet<>();

  /** Assigns a role to this account, keeping both sides of the pair in step. */
  public void addRoleAssignment(UserRole assignment) {
    assignment.setUser(this);
    roleAssignments.add(assignment);
  }

  /** Withdraws a role from this account. */
  public void removeRoleAssignment(UserRole assignment) {
    roleAssignments.remove(assignment);
    assignment.setUser(null);
  }

  /** The name the staff list and audit entries show. */
  public String getFullName() {
    return firstName + " " + lastName;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getStaffCode() {
    return staffCode;
  }

  public void setStaffCode(String staffCode) {
    this.staffCode = staffCode;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public String getDepartment() {
    return department;
  }

  public void setDepartment(String department) {
    this.department = department;
  }

  public String getDesignation() {
    return designation;
  }

  public void setDesignation(String designation) {
    this.designation = designation;
  }

  public UserStatus getStatus() {
    return status;
  }

  public void setStatus(UserStatus status) {
    this.status = status;
  }

  public boolean isForcePasswordReset() {
    return forcePasswordReset;
  }

  public void setForcePasswordReset(boolean forcePasswordReset) {
    this.forcePasswordReset = forcePasswordReset;
  }

  public LocalDateTime getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(LocalDateTime lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
  }

  public Set<UserRole> getRoleAssignments() {
    return roleAssignments;
  }

  public void setRoleAssignments(Set<UserRole> roleAssignments) {
    this.roleAssignments = roleAssignments;
  }
}
