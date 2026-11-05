package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One capability, gated on one endpoint.
 *
 * <p>This is the real control in the product. Every secured endpoint declares the permission code
 * it needs, and a role is nothing more than a bundle of these codes - which is why adding, renaming
 * or widening a role can never widen access by itself, and why withdrawing a capability is a single
 * change in one place rather than an audit of every endpoint that used to imply it.</p>
 *
 * <p>There is deliberately no base class here. {@link ReferenceData} would require
 * {@code created_at} / {@code updated_at} columns that this table does not carry, and the permission
 * set is written by the migration chain rather than edited through the API, so the usual audit
 * columns would never hold a value. Hibernate validates the model against the Flyway schema, so
 * inventing those columns would fail the boot rather than be quietly tolerated.</p>
 */
@Entity
@Table(name = "permissions")
public class Permission {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 80, unique = true)
  private String code;

  @Column(name = "module", nullable = false, length = 60)
  private String module;

  @Column(name = "description", length = 255)
  private String description;

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

  public String getModule() {
    return module;
  }

  public void setModule(String module) {
    this.module = module;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}
