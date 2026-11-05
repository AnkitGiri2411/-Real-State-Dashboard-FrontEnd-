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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Something a signed-in user should know about.
 *
 * <p>In-app only. This product does not send email or messages, so a notification is a row a
 * signed-in user reads and nothing more; claiming delivery through a channel that is not configured
 * would be a promise the product cannot keep.</p>
 *
 * <p><strong>{@code entityType} and {@code entityId} are a generic reference pair, not foreign
 * keys.</strong> They let a notification point at "whatever this is about" without the schema
 * knowing every entity type that might raise one - a lease, a payment, a maintenance request that
 * has no entity of its own yet. They are a string and a UUID rather than prose so the record can be
 * opened by identifier, and their lack of a constraint is what keeps the notification table from
 * having to change every time the product grows a new subject.</p>
 *
 * <p>There is no base class here: the table carries neither {@code updated_at} nor the
 * who-changed-it columns, and no {@code deleted_at}, because a notification is written once, read
 * once and removed with its user by {@code ON DELETE CASCADE}. It is not a business record, so the
 * audit block would only ever be empty - and a column that is always null invites a reader to
 * believe it means something.</p>
 */
@Entity
@Table(name = "notifications")
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  /**
   * The vocabulary of the notification, owned by whoever raises it.
   *
   * <p>Deliberately a {@code String} and not an enum: the column carries no CHECK constraint, so the
   * database does not define a closed set and neither does the model. Inventing constants here would
   * claim a constraint that does not exist.</p>
   */
  @Column(name = "type", nullable = false, length = 60)
  private String type;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 20)
  private NotificationSeverity severity = NotificationSeverity.INFO;

  @Column(name = "title", nullable = false, length = 255)
  private String title;

  @Column(name = "body", length = 1000)
  private String body;

  /** The kind of record this notification is about. See the class note. */
  @Column(name = "entity_type", length = 60)
  private String entityType;

  /** The identifier of the record this notification is about, with no foreign key. */
  @Column(name = "entity_id")
  private UUID entityId;

  @Column(name = "link", length = 500)
  private String link;

  /** Maps {@code is_read}: read state is all-or-nothing with {@code readAt}. */
  @Column(name = "is_read", nullable = false)
  private boolean read = false;

  @Column(name = "read_at")
  private LocalDateTime readAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = LocalDateTime.now();
  }

  /** True when this row still counts towards the bell's badge. */
  public boolean isUnread() {
    return !read;
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

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public NotificationSeverity getSeverity() {
    return severity;
  }

  public void setSeverity(NotificationSeverity severity) {
    this.severity = severity;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public void setEntityId(UUID entityId) {
    this.entityId = entityId;
  }

  public String getLink() {
    return link;
  }

  public void setLink(String link) {
    this.link = link;
  }

  public boolean isRead() {
    return read;
  }

  public void setRead(boolean read) {
    this.read = read;
  }

  public LocalDateTime getReadAt() {
    return readAt;
  }

  public void setReadAt(LocalDateTime readAt) {
    this.readAt = readAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
