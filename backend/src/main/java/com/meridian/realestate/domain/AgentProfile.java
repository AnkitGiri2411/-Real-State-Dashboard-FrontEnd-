package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An agent: a staff account that carries a portfolio.
 *
 * <p>The account and the profile are separate because they answer different questions. The staff
 * account is who may sign in and what they may do; this is who carries which stock, under which
 * licence, out of which office. Not every staff account is an agent - accounts, administration and
 * facilities hold permissions but no portfolio - so a profile is a 1:1 extension that only the
 * letting and sales sides have. That is why work is assigned to an agent profile rather than to a
 * staff account: "who is responsible for this property" has to have an answer.</p>
 *
 * <p>{@link #user} is that extension: a one-to-one to the staff account, backed by the UNIQUE
 * constraint on {@code user_id}, so a person has at most one agent profile. It is {@code Optional =
 * false} because a profile without an account would be a portfolio nobody is answerable for, and
 * the account is deliberately not cascaded from here - deleting a profile must never remove the
 * identity the audit trail, leases and approvals name.</p>
 *
 * <p>{@link #manager} is self-referencing: an agent reports to another agent, or to nobody. The
 * schema refuses a row that manages itself, because such a row would turn any upward traversal into
 * a cycle.</p>
 */
@Entity
@Table(name = "agent_profiles")
public class AgentProfile extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The staff account this profile extends - one profile per account. */
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  private User user;

  @Column(name = "agent_code", nullable = false, length = 30, unique = true)
  private String agentCode;

  /** Nullable: an agent between offices, or one attached to no branch, is still an agent. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "office_id")
  private Office office;

  /** Self-referencing: this agent's manager, who is also an agent. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "manager_id")
  private AgentProfile manager;

  @Enumerated(EnumType.STRING)
  @Column(name = "specialisation", nullable = false, length = 30)
  private AgentSpecialisation specialisation = AgentSpecialisation.BOTH;

  @Column(name = "licence_number", length = 60)
  private String licenceNumber;

  /** Used by the performance report to pro-rate a target for a new joiner. */
  @Column(name = "joined_on")
  private LocalDate joinedOn;

  @Column(name = "monthly_target", precision = 14, scale = 2)
  private BigDecimal monthlyTarget;

  @Column(name = "bio", length = 1000)
  private String bio;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private AgentStatus status = AgentStatus.ACTIVE;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public User getUser() {
    return user;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public String getAgentCode() {
    return agentCode;
  }

  public void setAgentCode(String agentCode) {
    this.agentCode = agentCode;
  }

  public Office getOffice() {
    return office;
  }

  public void setOffice(Office office) {
    this.office = office;
  }

  public AgentProfile getManager() {
    return manager;
  }

  public void setManager(AgentProfile manager) {
    this.manager = manager;
  }

  public AgentSpecialisation getSpecialisation() {
    return specialisation;
  }

  public void setSpecialisation(AgentSpecialisation specialisation) {
    this.specialisation = specialisation;
  }

  public String getLicenceNumber() {
    return licenceNumber;
  }

  public void setLicenceNumber(String licenceNumber) {
    this.licenceNumber = licenceNumber;
  }

  public LocalDate getJoinedOn() {
    return joinedOn;
  }

  public void setJoinedOn(LocalDate joinedOn) {
    this.joinedOn = joinedOn;
  }

  public BigDecimal getMonthlyTarget() {
    return monthlyTarget;
  }

  public void setMonthlyTarget(BigDecimal monthlyTarget) {
    this.monthlyTarget = monthlyTarget;
  }

  public String getBio() {
    return bio;
  }

  public void setBio(String bio) {
    this.bio = bio;
  }

  public AgentStatus getStatus() {
    return status;
  }

  public void setStatus(AgentStatus status) {
    this.status = status;
  }
}
