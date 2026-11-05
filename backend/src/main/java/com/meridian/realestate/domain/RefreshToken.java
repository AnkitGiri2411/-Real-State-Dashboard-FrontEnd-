package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One issued session, stored as a hash and rotated on every use.
 *
 * <p>Only the SHA-256 hash of the refresh token is kept ({@code tokenHash}), so a database
 * disclosure does not hand over usable sessions. {@code tokenHash} must therefore never be exposed
 * by a DTO or a response body: publishing it would hand out exactly the session the hash exists to
 * protect.</p>
 *
 * <p>{@code replacedByHash} records the rotation, and that is what makes a stolen token detectable:
 * whichever client refreshes second presents a token that has already been replaced and is refused,
 * instead of both silently carrying on with the same session.</p>
 *
 * <p>There is no base class here, deliberately. The table has no {@code updated_at} and no
 * {@code deleted_at}: a session is not archived, it is revoked or it expires, and revoking is a
 * timestamp rather than a delete so the session stays visible in the account's own session list
 * where "was that me?" can actually be answered.</p>
 *
 * <p>{@code createdByIp} is the address the session was opened from. It is not an audit
 * {@code created_by} - the audit trail of a sign-in lives in {@code audit_logs}, and a person
 * cannot be compelled to answer for an address they shared. It is a {@code varchar} and not a
 * foreign key precisely because it is a fact about the network, not about a record in this
 * database.</p>
 *
 * <p>{@code createdAt} is declared here rather than inherited, with a {@code @PrePersist} that
 * stamps it, because there is no {@code Auditable} shape to borrow: the session is written once and
 * never edited.</p>
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "token_hash", nullable = false, length = 128, unique = true)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  @Column(name = "replaced_by_hash", length = 128)
  private String replacedByHash;

  @Column(name = "created_by_ip", length = 60)
  private String createdByIp;

  @Column(name = "user_agent", length = 255)
  private String userAgent;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = LocalDateTime.now();
  }

  /**
   * True when this session may still be exchanged for an access token.
   *
   * <p>Expiry and revocation are both checked here so no caller has to remember that a revoked
   * session that happens to be unexpired is still dead.</p>
   */
  public boolean isLive(LocalDateTime at) {
    return revokedAt == null && expiresAt.isAfter(at);
  }

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

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public LocalDateTime getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(LocalDateTime revokedAt) {
    this.revokedAt = revokedAt;
  }

  public String getReplacedByHash() {
    return replacedByHash;
  }

  public void setReplacedByHash(String replacedByHash) {
    this.replacedByHash = replacedByHash;
  }

  public String getCreatedByIp() {
    return createdByIp;
  }

  public void setCreatedByIp(String createdByIp) {
    this.createdByIp = createdByIp;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
