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
 * How money is received - Cash, Cheque, NEFT / RTGS, UPI and the rest.
 *
 * <p>Tables {@code payment_methods}.</p>
 *
 * <p><b>Reference data, not business records</b> - which is why this entity extends
 * {@link ReferenceData} rather than {@link Auditable}. The table has exactly {@code created_at} and
 * {@code updated_at}: no audit trail and no soft delete, because nobody archives "Cheque". It also
 * has no {@code deleted_at}, so every finder here is unrestricted by a soft-delete filter. It is
 * seeded by the migration, because every installation needs the same methods, and is never created
 * through the API.</p>
 *
 * <p>{@link #category} is {@link PaymentMethodCategory}, the values of
 * {@code ck_payment_methods_category}. {@link #requiresReference} is a real rule rather than a
 * label: a method flagged true must carry an instrument reference for the receipt to be traceable -
 * a UPI transfer without a reference cannot be reconciled against a statement.</p>
 */
@Entity
@Table(name = "payment_methods")
public class PaymentMethod extends ReferenceData {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 40, unique = true)
  private String code;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 30)
  private PaymentMethodCategory category;

  /** True where the method must carry a bank or instrument reference to be traceable. */
  @Column(name = "requires_reference", nullable = false)
  private boolean requiresReference = false;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

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

  public PaymentMethodCategory getCategory() {
    return category;
  }

  public void setCategory(PaymentMethodCategory category) {
    this.category = category;
  }

  public boolean isRequiresReference() {
    return requiresReference;
  }

  public void setRequiresReference(boolean requiresReference) {
    this.requiresReference = requiresReference;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public void setDisplayOrder(int displayOrder) {
    this.displayOrder = displayOrder;
  }
}
