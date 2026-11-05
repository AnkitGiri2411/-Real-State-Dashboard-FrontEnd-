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
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A thread of correspondence with an owner, tenant, prospect or buyer, kept against the record it
 * is about.
 *
 * <p>Several subject links may be set at once, because a thread about lease arrears concerns the
 * tenant, the lease and the unit together, and picking one of them would lose the other two. The
 * schema requires that at least one of them, or a named counterparty, is present, so a thread
 * cannot be filed against nothing.</p>
 *
 * <p><strong>{@code lastMessageAt} is nullable, and the thread list orders on it.</strong> The
 * console's left rail lists threads newest-first by this column, and the schema indexes it
 * {@code DESC NULLS LAST}. A conversation that has been opened but has no messages yet therefore
 * sorts last by design: it has no activity to place in time, and inventing a timestamp for it would
 * put a thread nobody has written in above one that has been. {@code lastMessagePreview} is the
 * same denormalisation in prose - both are maintained in the same transaction that writes a
 * message, so they cannot drift from the messages they summarise.</p>
 */
@Entity
@Table(name = "conversations")
public class Conversation extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "conversation_code", nullable = false, length = 30, unique = true)
  private String conversationCode;

  @Column(name = "subject", nullable = false, length = 255)
  private String subject;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false, length = 30)
  private ConversationChannel channel = ConversationChannel.EMAIL;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ConversationStatus status = ConversationStatus.OPEN;

  @Enumerated(EnumType.STRING)
  @Column(name = "priority", nullable = false, length = 20)
  private ConversationPriority priority = ConversationPriority.NORMAL;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "property_id")
  private Property property;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "unit_id")
  private Unit unit;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lease_id")
  private Lease lease;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "listing_id")
  private Listing listing;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lead_id")
  private Lead lead;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "tenant_id")
  private Tenant tenant;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id")
  private Owner owner;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "buyer_id")
  private Buyer buyer;

  /** A counterparty who is not yet one of the linked records, so a thread can still name them. */
  @Column(name = "counterparty_name", length = 200)
  private String counterpartyName;

  @Column(name = "counterparty_email", length = 255)
  private String counterpartyEmail;

  @Column(name = "counterparty_phone", length = 30)
  private String counterpartyPhone;

  /** Who at the firm owns the thread. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "assigned_to")
  private User assignedTo;

  /** The ordering key of the thread list. Null until the first message is written. */
  @Column(name = "last_message_at")
  private LocalDateTime lastMessageAt;

  @Column(name = "last_message_preview", length = 255)
  private String lastMessagePreview;

  @Column(name = "opened_at", nullable = false)
  private LocalDateTime openedAt;

  @Column(name = "closed_at")
  private LocalDateTime closedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "closed_by")
  private User closedBy;

  @Column(name = "close_reason", length = 500)
  private String closeReason;

  /** True when the thread is no longer live, so it is out of the "needs a reply" count. */
  public boolean isClosed() {
    return status == ConversationStatus.CLOSED;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getConversationCode() {
    return conversationCode;
  }

  public void setConversationCode(String conversationCode) {
    this.conversationCode = conversationCode;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public ConversationChannel getChannel() {
    return channel;
  }

  public void setChannel(ConversationChannel channel) {
    this.channel = channel;
  }

  public ConversationStatus getStatus() {
    return status;
  }

  public void setStatus(ConversationStatus status) {
    this.status = status;
  }

  public ConversationPriority getPriority() {
    return priority;
  }

  public void setPriority(ConversationPriority priority) {
    this.priority = priority;
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

  public Lease getLease() {
    return lease;
  }

  public void setLease(Lease lease) {
    this.lease = lease;
  }

  public Listing getListing() {
    return listing;
  }

  public void setListing(Listing listing) {
    this.listing = listing;
  }

  public Lead getLead() {
    return lead;
  }

  public void setLead(Lead lead) {
    this.lead = lead;
  }

  public Tenant getTenant() {
    return tenant;
  }

  public void setTenant(Tenant tenant) {
    this.tenant = tenant;
  }

  public Owner getOwner() {
    return owner;
  }

  public void setOwner(Owner owner) {
    this.owner = owner;
  }

  public Buyer getBuyer() {
    return buyer;
  }

  public void setBuyer(Buyer buyer) {
    this.buyer = buyer;
  }

  public String getCounterpartyName() {
    return counterpartyName;
  }

  public void setCounterpartyName(String counterpartyName) {
    this.counterpartyName = counterpartyName;
  }

  public String getCounterpartyEmail() {
    return counterpartyEmail;
  }

  public void setCounterpartyEmail(String counterpartyEmail) {
    this.counterpartyEmail = counterpartyEmail;
  }

  public String getCounterpartyPhone() {
    return counterpartyPhone;
  }

  public void setCounterpartyPhone(String counterpartyPhone) {
    this.counterpartyPhone = counterpartyPhone;
  }

  public User getAssignedTo() {
    return assignedTo;
  }

  public void setAssignedTo(User assignedTo) {
    this.assignedTo = assignedTo;
  }

  public LocalDateTime getLastMessageAt() {
    return lastMessageAt;
  }

  public void setLastMessageAt(LocalDateTime lastMessageAt) {
    this.lastMessageAt = lastMessageAt;
  }

  public String getLastMessagePreview() {
    return lastMessagePreview;
  }

  public void setLastMessagePreview(String lastMessagePreview) {
    this.lastMessagePreview = lastMessagePreview;
  }

  public LocalDateTime getOpenedAt() {
    return openedAt;
  }

  public void setOpenedAt(LocalDateTime openedAt) {
    this.openedAt = openedAt;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(LocalDateTime closedAt) {
    this.closedAt = closedAt;
  }

  public User getClosedBy() {
    return closedBy;
  }

  public void setClosedBy(User closedBy) {
    this.closedBy = closedBy;
  }

  public String getCloseReason() {
    return closeReason;
  }

  public void setCloseReason(String closeReason) {
    this.closeReason = closeReason;
  }
}
