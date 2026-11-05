package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A heading an expense is recorded under - plumbing repairs, property tax, a lift replacement.
 *
 * <p>Reference data, not a business record: seeded by the V15 migration because every installation
 * needs the same headings, and never created through the API. It carries the two timestamps of
 * {@link ReferenceData} and no audit trail and no soft delete, because nobody archives "Property
 * tax".</p>
 *
 * <p><b>{@code isCapital} is the flag that keeps net income honest.</b> A replacement lift is
 * capital: it improves the asset and is not a cost of the period's letting. A lift repair is
 * operating: it is. Adding them together would overstate cost and understate the portfolio's running
 * result, and the error would be invisible because both are real expenses that happened. This column
 * is therefore the input to the operating-versus-capital split the service performs, and it is what
 * {@code expense_categories} exists to hold rather than repeating the distinction on every expense
 * row. There is no separate {@code type} column: the distinction is this boolean, and the reporting
 * bucket is {@link ExpenseGroup}.</p>
 *
 * <p>{@code typicallyRecoverable} says whether this kind of cost is normally rechargeable under a
 * lease. It is a default offered when an expense is recorded, not an entitlement: what matters on a
 * particular cost is {@link PropertyExpense#isRecoverable()}, because a lease may or may not allow
 * the recharge on the day. Keeping the two apart is what stops a report from claiming recovery the
 * firm has no right to.</p>
 *
 * <p>{@code displayOrder} is the order the picker offers the headings in, so an accountant sees the
 * categories in the order costs are actually thought about rather than alphabetically.</p>
 */
@Entity
@Table(name = "expense_categories")
public class ExpenseCategory extends ReferenceData {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 40, unique = true)
  private String code;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  /** Named {@code groupName} in Java: {@code group} is a reserved word in JPQL. */
  @Enumerated(EnumType.STRING)
  @Column(name = "group_name", nullable = false, length = 30)
  private ExpenseGroup groupName;

  /** True for capital expenditure, which net operating income excludes. See the class note. */
  @Column(name = "is_capital", nullable = false)
  private boolean capital = false;

  @Column(name = "typically_recoverable", nullable = false)
  private boolean typicallyRecoverable = false;

  @Column(name = "description", length = 255)
  private String description;

  @Column(name = "display_order", nullable = false)
  private int displayOrder = 0;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public ExpenseGroup getGroupName() {
    return groupName;
  }

  public void setGroupName(ExpenseGroup groupName) {
    this.groupName = groupName;
  }

  public boolean isCapital() {
    return capital;
  }

  public void setCapital(boolean capital) {
    this.capital = capital;
  }

  public boolean isTypicallyRecoverable() {
    return typicallyRecoverable;
  }

  public void setTypicallyRecoverable(boolean typicallyRecoverable) {
    this.typicallyRecoverable = typicallyRecoverable;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public void setDisplayOrder(int displayOrder) {
    this.displayOrder = displayOrder;
  }
}
