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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A person or company that has expressed interest in a property, a unit or a type of stock.
 *
 * <p>The stage records how far that interest has progressed and is enforced by the service's state
 * machine, not by free assignment.</p>
 *
 * <p>Contact details live on the lead rather than pointing at a tenant or a buyer, because most
 * leads are neither yet - deciding that is exactly what qualification does.</p>
 *
 * <p>{@code ownerAgent} holds the current holder of the lead; the history of who held it before lives
 * in {@link LeadAssignment}. That is a deliberate denormalisation: the pipeline board reads the
 * current holder on every row and must not join a history table to do it.</p>
 */
@Entity
@Table(name = "leads")
public class Lead extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "lead_code", nullable = false, length = 30, unique = true)
  private String leadCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "lead_type", nullable = false, length = 20)
  private LeadType leadType = LeadType.RENTAL;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_id")
  private LeadSource source;

  @Enumerated(EnumType.STRING)
  @Column(name = "stage", nullable = false, length = 30)
  private LeadStage stage = LeadStage.NEW;

  @Enumerated(EnumType.STRING)
  @Column(name = "priority", nullable = false, length = 20)
  private LeadPriority priority = LeadPriority.MEDIUM;

  @Column(name = "display_name", nullable = false, length = 200)
  private String displayName;

  @Column(name = "company_name", length = 200)
  private String companyName;

  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "phone", length = 30)
  private String phone;

  @Column(name = "alternate_phone", length = 30)
  private String alternatePhone;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "property_type_id")
  private PropertyType propertyType;

  @Column(name = "preferred_city", length = 120)
  private String preferredCity;

  @Column(name = "preferred_locality", length = 120)
  private String preferredLocality;

  @Column(name = "min_budget", precision = 14, scale = 2)
  private BigDecimal minBudget;

  @Column(name = "max_budget", precision = 14, scale = 2)
  private BigDecimal maxBudget;

  @Column(name = "min_bedrooms")
  private Integer minBedrooms;

  @Column(name = "requirement_notes", columnDefinition = "text")
  private String requirementNotes;

  /** The specific property they enquired about, when there is one. Nullable: a walk-in may name none. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "property_id")
  private Property property;

  /** The specific unit they enquired about, when there is one. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "unit_id")
  private Unit unit;

  /** The current holder of the lead; see the class note. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_agent_id")
  private AgentProfile ownerAgent;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "office_id")
  private Office office;

  @Column(name = "next_action", length = 255)
  private String nextAction;

  @Column(name = "next_action_at")
  private LocalDateTime nextActionAt;

  /** What the lead is worth if it converts - never the budget maximum, which is a wish. */
  @Column(name = "expected_value", precision = 14, scale = 2)
  private BigDecimal expectedValue;

  @Column(name = "won_at")
  private LocalDateTime wonAt;

  @Column(name = "lost_at")
  private LocalDateTime lostAt;

  @Column(name = "lost_reason", length = 500)
  private String lostReason;

  /** The tenancy a converted lead produced, if it became one. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "converted_tenant_id")
  private Tenant convertedTenant;

  /** The buyer a converted lead produced, if it became one. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "converted_buyer_id")
  private Buyer convertedBuyer;

  @Column(name = "converted_at")
  private LocalDateTime convertedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getLeadCode() {
    return leadCode;
  }

  public void setLeadCode(String leadCode) {
    this.leadCode = leadCode;
  }

  public LeadType getLeadType() {
    return leadType;
  }

  public void setLeadType(LeadType leadType) {
    this.leadType = leadType;
  }

  public LeadSource getSource() {
    return source;
  }

  public void setSource(LeadSource source) {
    this.source = source;
  }

  public LeadStage getStage() {
    return stage;
  }

  public void setStage(LeadStage stage) {
    this.stage = stage;
  }

  public LeadPriority getPriority() {
    return priority;
  }

  public void setPriority(LeadPriority priority) {
    this.priority = priority;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getCompanyName() {
    return companyName;
  }

  public void setCompanyName(String companyName) {
    this.companyName = companyName;
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

  public String getAlternatePhone() {
    return alternatePhone;
  }

  public void setAlternatePhone(String alternatePhone) {
    this.alternatePhone = alternatePhone;
  }

  public PropertyType getPropertyType() {
    return propertyType;
  }

  public void setPropertyType(PropertyType propertyType) {
    this.propertyType = propertyType;
  }

  public String getPreferredCity() {
    return preferredCity;
  }

  public void setPreferredCity(String preferredCity) {
    this.preferredCity = preferredCity;
  }

  public String getPreferredLocality() {
    return preferredLocality;
  }

  public void setPreferredLocality(String preferredLocality) {
    this.preferredLocality = preferredLocality;
  }

  public BigDecimal getMinBudget() {
    return minBudget;
  }

  public void setMinBudget(BigDecimal minBudget) {
    this.minBudget = minBudget;
  }

  public BigDecimal getMaxBudget() {
    return maxBudget;
  }

  public void setMaxBudget(BigDecimal maxBudget) {
    this.maxBudget = maxBudget;
  }

  public Integer getMinBedrooms() {
    return minBedrooms;
  }

  public void setMinBedrooms(Integer minBedrooms) {
    this.minBedrooms = minBedrooms;
  }

  public String getRequirementNotes() {
    return requirementNotes;
  }

  public void setRequirementNotes(String requirementNotes) {
    this.requirementNotes = requirementNotes;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public Unit getUnit() {
    return unit;
  }

  public void setUnit(Unit unit) {
    this.unit = unit;
  }

  public AgentProfile getOwnerAgent() {
    return ownerAgent;
  }

  public void setOwnerAgent(AgentProfile ownerAgent) {
    this.ownerAgent = ownerAgent;
  }

  public Office getOffice() {
    return office;
  }

  public void setOffice(Office office) {
    this.office = office;
  }

  public String getNextAction() {
    return nextAction;
  }

  public void setNextAction(String nextAction) {
    this.nextAction = nextAction;
  }

  public LocalDateTime getNextActionAt() {
    return nextActionAt;
  }

  public void setNextActionAt(LocalDateTime nextActionAt) {
    this.nextActionAt = nextActionAt;
  }

  public BigDecimal getExpectedValue() {
    return expectedValue;
  }

  public void setExpectedValue(BigDecimal expectedValue) {
    this.expectedValue = expectedValue;
  }

  public LocalDateTime getWonAt() {
    return wonAt;
  }

  public void setWonAt(LocalDateTime wonAt) {
    this.wonAt = wonAt;
  }

  public LocalDateTime getLostAt() {
    return lostAt;
  }

  public void setLostAt(LocalDateTime lostAt) {
    this.lostAt = lostAt;
  }

  public String getLostReason() {
    return lostReason;
  }

  public void setLostReason(String lostReason) {
    this.lostReason = lostReason;
  }

  public Tenant getConvertedTenant() {
    return convertedTenant;
  }

  public void setConvertedTenant(Tenant convertedTenant) {
    this.convertedTenant = convertedTenant;
  }

  public Buyer getConvertedBuyer() {
    return convertedBuyer;
  }

  public void setConvertedBuyer(Buyer convertedBuyer) {
    this.convertedBuyer = convertedBuyer;
  }

  public LocalDateTime getConvertedAt() {
    return convertedAt;
  }

  public void setConvertedAt(LocalDateTime convertedAt) {
    this.convertedAt = convertedAt;
  }
}
