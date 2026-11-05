package com.meridian.realestate.service;

import com.meridian.realestate.domain.Buyer;
import com.meridian.realestate.domain.Conversation;
import com.meridian.realestate.domain.ConversationChannel;
import com.meridian.realestate.domain.ConversationMessage;
import com.meridian.realestate.domain.ConversationPriority;
import com.meridian.realestate.domain.ConversationStatus;
import com.meridian.realestate.domain.Lead;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.Listing;
import com.meridian.realestate.domain.MessageSenderType;
import com.meridian.realestate.domain.MessageType;
import com.meridian.realestate.domain.Owner;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.CommunicationDtos;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.BuyerRepository;
import com.meridian.realestate.repository.ConversationMessageRepository;
import com.meridian.realestate.repository.ConversationRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.ListingRepository;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The correspondence with owners, tenants, prospects and buyers, kept against the record it is about.
 *
 * <p><b>Why this class exists.</b> A thread, the messages inside it, and the read and ownership state
 * the console shows all describe one question - "what has been said about this, and who is dealing
 * with it" - and splitting them across several callers is how a thread list and a thread view end up
 * disagreeing about whether anything is unread. The reads and the writes that move a thread between
 * states belong in one place.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>An internal note is never client-facing.</b> A message whose {@code internal} flag is set
 *       was written by the firm about the counterparty, often candid, and leaking it through a portal,
 *       an export or a shared print view is a disclosure rather than a cosmetic bug. The default
 *       {@link #detail} therefore reads the thread through the repository finder that <em>cannot</em>
 *       return an internal note, and filters again here so a row that slipped the query still cannot
 *       reach a client-facing surface. The internal-only read is a separate, explicitly named endpoint
 *       - {@link #internalMessages} - so returning internal notes is something a caller must ask for,
 *       never something they get by default.</li>
 *   <li><b>A message is styled from stored fields, never from its text.</b> {@code senderType},
 *       {@code messageType} and {@code internal} are set when the message is written; nothing reads
 *       the body to decide what a message is. There is no classifier here and there will not be one.</li>
 *   <li><b>A thread with no messages sorts last.</b> The console's left rail orders on
 *       {@code last_message_at} descending with nulls last, which the schema indexes exactly. A thread
 *       opened before anybody has written has no activity to place in time, so it is last by design -
 *       inventing a timestamp for it would put a thread nobody has written above one that has been.</li>
 *   <li><b>Read state is written once, in the same transaction as the message it describes.</b> The
 *       denormalised {@code last_message_at} and {@code last_message_preview} are updated inside the
 *       transaction that writes a message, so they cannot drift from the messages they summarise.</li>
 *   <li><b>Only a firm-side message may be internal.</b> The schema enforces it; the service states it
 *       as a 400 so the caller gets a sentence - a client cannot write a note to the firm that the firm
 *       cannot see.</li>
 * </ul>
 *
 * <p><b>A note on numbering.</b> {@code V2} seeds no conversation series, so a thread's code is minted
 * from the record's own identity rather than from a counter. It is reported rather than added, because
 * the migration chain is not this task's to change.</p>
 *
 * <p><b>A note on the unread badge and the read-only filter.</b> The console needs a per-thread unread
 * count and an unread-only filter, and the message repository exposes a per-conversation count but not
 * one across all of a user's threads. Both are therefore assembled here by reading each thread's count;
 * the missing cross-thread aggregate is reported rather than added, because the repository layer is not
 * this task's to change.</p>
 */
@Service
public class CommunicationService {

  private static final int PREVIEW_MAX = 255;

  private final ConversationRepository conversations;
  private final ConversationMessageRepository messages;
  private final UserRepository users;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final LeaseRepository leases;
  private final ListingRepository listings;
  private final LeadRepository leads;
  private final TenantRepository tenants;
  private final OwnerRepository owners;
  private final BuyerRepository buyers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public CommunicationService(
      ConversationRepository conversations,
      ConversationMessageRepository messages,
      UserRepository users,
      PropertyRepository properties,
      UnitRepository units,
      LeaseRepository leases,
      ListingRepository listings,
      LeadRepository leads,
      TenantRepository tenants,
      OwnerRepository owners,
      BuyerRepository buyers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.conversations = conversations;
    this.messages = messages;
    this.users = users;
    this.properties = properties;
    this.units = units;
    this.leases = leases;
    this.listings = listings;
    this.leads = leads;
    this.tenants = tenants;
    this.owners = owners;
    this.buyers = buyers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live thread behind an id, or a 404. An archived thread is not addressable. */
  @Transactional(readOnly = true)
  public Conversation require(UUID conversationId) {
    return conversations
        .findByIdAndDeletedAtIsNull(conversationId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("CONVERSATION_NOT_FOUND")));
  }

  /**
   * The thread list, filtered, searched, sorted and paged.
   *
   * <p>The ordering is recency - {@code lastMessageAt} descending with nulls last - and that is the
   * list's default as well as the schema's own order, so a thread with no messages sorts last by
   * design. See the class note.</p>
   */
  @Transactional(readOnly = true)
  public Page<CommunicationDtos.ConversationSummary> list(
      String status,
      String channel,
      String priority,
      UUID assignedToId,
      UUID propertyId,
      UUID tenantId,
      UUID leadId,
      UUID buyerId,
      Boolean unreadOnly,
      String search,
      Pageable pageable) {

    ConversationStatus statusFilter = parseStatus(status, null);
    ConversationChannel channelFilter = parseChannel(channel, null);
    ConversationPriority priorityFilter = parsePriority(priority, null);
    String term = blankToNull(search);
    boolean onlyUnread = Boolean.TRUE.equals(unreadOnly);

    List<CommunicationDtos.ConversationSummary> matches =
        sortConversations(conversations.findAllOrderedByRecentActivity(), pageable.getSort()).stream()
            .filter(conversation -> statusFilter == null || conversation.getStatus() == statusFilter)
            .filter(conversation -> channelFilter == null || conversation.getChannel() == channelFilter)
            .filter(conversation -> priorityFilter == null || conversation.getPriority() == priorityFilter)
            .filter(
                conversation ->
                    assignedToId == null
                        || (conversation.getAssignedTo() != null
                            && assignedToId.equals(conversation.getAssignedTo().getId())))
            .filter(conversation -> propertyId == null || idOf(conversation.getProperty()) != null
                && propertyId.equals(idOf(conversation.getProperty())))
            .filter(conversation -> tenantId == null || idOf(conversation.getTenant()) != null
                && tenantId.equals(idOf(conversation.getTenant())))
            .filter(conversation -> leadId == null || idOf(conversation.getLead()) != null
                && leadId.equals(idOf(conversation.getLead())))
            .filter(conversation -> buyerId == null || idOf(conversation.getBuyer()) != null
                && buyerId.equals(idOf(conversation.getBuyer())))
            .filter(conversation -> term == null || matchesSearch(conversation, term))
            .map(this::summarise)
            .filter(summary -> !onlyUnread || summary.unreadCount() > 0)
            .toList();

    return pageOf(matches, pageable);
  }

  /**
   * One thread and the messages a client could see.
   *
   * <p>The default read. Internal notes are excluded at the query and again here, so a portal, an
   * export or a print view built on this cannot disclose one. See the class note.</p>
   */
  @Transactional(readOnly = true)
  public CommunicationDtos.ConversationDetail detail(UUID conversationId) {
    Conversation conversation = require(conversationId);
    List<CommunicationDtos.MessageSummary> clientFacing =
        messages
            .findAllByConversationIdAndInternalFalseAndDeletedAtIsNullOrderBySentAtAsc(conversationId)
            .stream()
            // Belt and braces: even if a finder returned one, an internal note never reaches here.
            .filter(message -> !message.isInternalNote())
            .map(CommunicationDtos.MessageSummary::from)
            .toList();
    return new CommunicationDtos.ConversationDetail(summarise(conversation), clientFacing, false);
  }

  /**
   * The internal-only read: the whole thread, internal notes included.
   *
   * <p>Separate and explicitly named, because returning an internal note is something a caller must
   * ask for and never something they get by default. See the class note on internal notes.</p>
   */
  @Transactional(readOnly = true)
  public List<CommunicationDtos.MessageSummary> internalMessages(UUID conversationId) {
    require(conversationId);
    return messages.findAllByConversationIdAndDeletedAtIsNullOrderBySentAtAsc(conversationId).stream()
        .map(CommunicationDtos.MessageSummary::from)
        .toList();
  }

  /**
   * The header badge's number: the unread messages across the live threads.
   *
   * <p>Assembled thread by thread because the message repository exposes no cross-thread unread
   * aggregate; see the class note. It counts only rows whose {@code read_at} is null, which is the set
   * the schema's partial index is built over.</p>
   */
  @Transactional(readOnly = true)
  public long unreadCount() {
    long unread = 0;
    for (Conversation conversation : conversations.findAllByDeletedAtIsNull()) {
      unread += messages.countByConversationIdAndReadAtIsNullAndDeletedAtIsNull(conversation.getId());
    }
    return unread;
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** Open a thread. Its code is minted; it carries no message until one is posted. */
  @Transactional
  public CommunicationDtos.ConversationDetail create(CommunicationDtos.CreateConversationRequest request) {
    User actor = accessScope.requireUser();

    Conversation conversation = new Conversation();
    conversation.setConversationCode(mintCode());
    conversation.setSubject(request.subject().trim());
    conversation.setChannel(parseChannel(request.channel(), ConversationChannel.EMAIL));
    conversation.setPriority(parsePriority(request.priority(), ConversationPriority.NORMAL));
    conversation.setStatus(parseStatus(request.status(), ConversationStatus.OPEN));
    conversation.setProperty(request.propertyId() == null ? null : requireProperty(request.propertyId()));
    conversation.setUnit(request.unitId() == null ? null : requireUnit(request.unitId()));
    conversation.setLease(request.leaseId() == null ? null : requireLease(request.leaseId()));
    conversation.setListing(request.listingId() == null ? null : requireListing(request.listingId()));
    conversation.setLead(request.leadId() == null ? null : requireLead(request.leadId()));
    conversation.setTenant(request.tenantId() == null ? null : requireTenant(request.tenantId()));
    conversation.setOwner(request.ownerId() == null ? null : requireOwner(request.ownerId()));
    conversation.setBuyer(request.buyerId() == null ? null : requireBuyer(request.buyerId()));
    conversation.setCounterpartyName(trim(request.counterpartyName()));
    conversation.setCounterpartyEmail(lower(request.counterpartyEmail()));
    conversation.setCounterpartyPhone(trim(request.counterpartyPhone()));
    conversation.setAssignedTo(request.assignedToId() == null ? null : requireStaff(request.assignedToId()));
    conversation.setOpenedAt(LocalDateTime.now());
    // A non-closed thread carries no closure, which the schema's ck_conversations_closed requires.
    conversation.setClosedAt(null);
    conversation.setClosedBy(null);
    conversation.setCloseReason(null);
    assertSubjectMatter(conversation);
    conversation.setCreatedBy(actor.getId());
    conversation.setUpdatedBy(actor.getId());

    Conversation saved = conversations.save(conversation);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.COMMUNICATION,
        AuditActions.CREATE,
        "Opened conversation " + saved.getConversationCode() + " (" + saved.getSubject() + ")",
        "CONVERSATION",
        saved.getId(),
        saved.getConversationCode());

    return detail(saved.getId());
  }

  /**
   * Post a message to a thread.
   *
   * <p>The message's stored fields decide how it is styled, and the thread's denormalised recency and
   * preview are updated in the same transaction, so the thread list cannot drift from the messages it
   * summarises. See the class note.</p>
   */
  @Transactional
  public CommunicationDtos.MessageSummary postMessage(UUID conversationId, CommunicationDtos.PostMessageRequest request) {
    User actor = accessScope.requireUser();
    Conversation conversation = require(conversationId);

    if (conversation.isClosed()) {
      throw ApiException.rule(
          "CONVERSATION_CLOSED", "This conversation is closed. Reopen it before adding a message");
    }

    MessageSenderType senderType = parseSender(request.senderType(), MessageSenderType.FIRM);
    MessageType messageType = parseMessageType(request.messageType(), MessageType.TEXT);
    boolean internal = Boolean.TRUE.equals(request.internal());

    // The schema's rules, stated as sentences the caller can act on.
    if (internal && senderType != MessageSenderType.FIRM) {
      throw ApiException.badRequest("Only a firm-side message can be an internal note");
    }
    if (senderType == MessageSenderType.SYSTEM
        && (request.systemEvent() == null || request.systemEvent().isBlank())) {
      throw ApiException.badRequest("A system message must name the event that produced it");
    }

    ConversationMessage message = new ConversationMessage();
    message.setConversation(conversation);
    message.setSenderType(senderType);
    message.setMessageType(messageType);
    message.setInternal(internal);
    message.setSenderUser(senderType == MessageSenderType.FIRM ? actor : null);
    message.setSenderName(resolveSenderName(request.senderName(), senderType, conversation, actor));
    message.setBody(request.body().trim());
    message.setSentAt(LocalDateTime.now());
    message.setSystemEvent(
        senderType == MessageSenderType.SYSTEM ? trim(request.systemEvent()) : null);
    message.setCreatedBy(actor.getId());
    message.setUpdatedBy(actor.getId());

    ConversationMessage saved = messages.save(message);

    // The denormalised recency and preview, written in this same transaction so they cannot drift.
    conversation.setLastMessageAt(saved.getSentAt());
    conversation.setLastMessagePreview(preview(saved.getBody()));
    conversation.setUpdatedBy(actor.getId());
    conversations.save(conversation);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.COMMUNICATION,
        AuditActions.UPDATE,
        "Message added to conversation " + conversation.getConversationCode(),
        "CONVERSATION",
        conversation.getId(),
        conversation.getConversationCode());

    return CommunicationDtos.MessageSummary.from(saved);
  }

  /** Mark every message on a thread read, stamping the moment the firm saw it. */
  @Transactional
  public CommunicationDtos.ConversationSummary markRead(UUID conversationId) {
    User actor = accessScope.requireUser();
    Conversation conversation = require(conversationId);
    LocalDateTime now = LocalDateTime.now();
    int changed = 0;
    for (ConversationMessage message :
        messages.findAllByConversationIdAndDeletedAtIsNullOrderBySentAtAsc(conversationId)) {
      if (message.getReadAt() == null) {
        message.setReadAt(now);
        message.setUpdatedBy(actor.getId());
        messages.save(message);
        changed++;
      }
    }
    if (changed > 0) {
      audit.record(
          actor.getId(),
          actor.getFullName(),
          AuditActions.COMMUNICATION,
          AuditActions.UPDATE,
          "Marked conversation " + conversation.getConversationCode() + " read",
          "CONVERSATION",
          conversation.getId(),
          conversation.getConversationCode());
    }
    return summarise(conversation);
  }

  /** Mark every message on a thread unread again. */
  @Transactional
  public CommunicationDtos.ConversationSummary markUnread(UUID conversationId) {
    User actor = accessScope.requireUser();
    Conversation conversation = require(conversationId);
    int changed = 0;
    for (ConversationMessage message :
        messages.findAllByConversationIdAndDeletedAtIsNullOrderBySentAtAsc(conversationId)) {
      if (message.getReadAt() != null) {
        message.setReadAt(null);
        message.setUpdatedBy(actor.getId());
        messages.save(message);
        changed++;
      }
    }
    if (changed > 0) {
      audit.record(
          actor.getId(),
          actor.getFullName(),
          AuditActions.COMMUNICATION,
          AuditActions.UPDATE,
          "Marked conversation " + conversation.getConversationCode() + " unread",
          "CONVERSATION",
          conversation.getId(),
          conversation.getConversationCode());
    }
    return summarise(conversation);
  }

  /** Assign or reassign the colleague who owns a thread. A null id unassigns it. */
  @Transactional
  public CommunicationDtos.ConversationSummary assign(UUID conversationId, UUID assignedToId) {
    User actor = accessScope.requireUser();
    Conversation conversation = require(conversationId);

    User assignee = assignedToId == null ? null : requireStaff(assignedToId);
    conversation.setAssignedTo(assignee);
    conversation.setUpdatedBy(actor.getId());
    conversations.save(conversation);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.COMMUNICATION,
        AuditActions.ASSIGNMENT,
        (assignee == null ? "Unassigned " : "Assigned ")
            + conversation.getConversationCode()
            + (assignee == null ? "" : " to " + assignee.getFullName()),
        "CONVERSATION",
        conversation.getId(),
        conversation.getConversationCode());

    return summarise(conversation);
  }

  /** Close a thread, recording when, by whom and why - all three the schema requires. */
  @Transactional
  public CommunicationDtos.ConversationSummary close(UUID conversationId, String reason) {
    User actor = accessScope.requireUser();
    Conversation conversation = require(conversationId);
    if (conversation.getStatus() == ConversationStatus.CLOSED) {
      throw ApiException.rule("CONVERSATION_ALREADY_CLOSED", ErrorMessages.friendly("CONVERSATION_ALREADY_CLOSED"));
    }

    conversation.setStatus(ConversationStatus.CLOSED);
    conversation.setClosedAt(LocalDateTime.now());
    conversation.setClosedBy(actor);
    conversation.setCloseReason(reason);
    conversation.setUpdatedBy(actor.getId());
    conversations.save(conversation);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.COMMUNICATION,
        AuditActions.STATUS_CHANGE,
        "Closed conversation " + conversation.getConversationCode(),
        "CONVERSATION",
        conversation.getId(),
        conversation.getConversationCode());

    return summarise(conversation);
  }

  /** Reopen a closed thread, clearing its closure - time, actor and reason together. */
  @Transactional
  public CommunicationDtos.ConversationSummary reopen(UUID conversationId) {
    User actor = accessScope.requireUser();
    Conversation conversation = require(conversationId);
    if (conversation.getStatus() != ConversationStatus.CLOSED) {
      throw ApiException.rule("CONVERSATION_NOT_CLOSED", "This conversation is not closed");
    }

    conversation.setStatus(ConversationStatus.OPEN);
    conversation.setClosedAt(null);
    conversation.setClosedBy(null);
    conversation.setCloseReason(null);
    conversation.setUpdatedBy(actor.getId());
    conversations.save(conversation);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.COMMUNICATION,
        AuditActions.STATUS_CHANGE,
        "Reopened conversation " + conversation.getConversationCode(),
        "CONVERSATION",
        conversation.getId(),
        conversation.getConversationCode());

    return summarise(conversation);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private CommunicationDtos.ConversationSummary summarise(Conversation conversation) {
    UUID id = conversation.getId();
    long messageCount = messages.countByConversationIdAndDeletedAtIsNull(id);
    long unreadCount = messages.countByConversationIdAndReadAtIsNullAndDeletedAtIsNull(id);
    return new CommunicationDtos.ConversationSummary(
        id,
        conversation.getConversationCode(),
        conversation.getSubject(),
        conversation.getChannel() == null ? null : conversation.getChannel().name(),
        conversation.getStatus() == null ? null : conversation.getStatus().name(),
        conversation.getPriority() == null ? null : conversation.getPriority().name(),
        idOf(conversation.getProperty()),
        conversation.getProperty() == null ? null : conversation.getProperty().getPropertyCode(),
        conversation.getProperty() == null ? null : conversation.getProperty().getName(),
        idOf(conversation.getUnit()),
        idOf(conversation.getLease()),
        idOf(conversation.getListing()),
        idOf(conversation.getLead()),
        idOf(conversation.getTenant()),
        conversation.getTenant() == null ? null : conversation.getTenant().getTenantCode(),
        idOf(conversation.getOwner()),
        idOf(conversation.getBuyer()),
        conversation.getCounterpartyName(),
        conversation.getCounterpartyEmail(),
        conversation.getCounterpartyPhone(),
        conversation.getAssignedTo() == null ? null : conversation.getAssignedTo().getId(),
        conversation.getAssignedTo() == null ? null : conversation.getAssignedTo().getFullName(),
        conversation.getLastMessageAt(),
        conversation.getLastMessagePreview(),
        conversation.getOpenedAt(),
        conversation.getClosedAt(),
        conversation.getCloseReason(),
        messageCount,
        unreadCount,
        conversation.getCreatedAt(),
        conversation.getUpdatedAt());
  }

  /** Refuses a thread filed against nothing - the schema's own rule, said in the product's words. */
  private static void assertSubjectMatter(Conversation conversation) {
    boolean about =
        conversation.getProperty() != null
            || conversation.getUnit() != null
            || conversation.getLease() != null
            || conversation.getListing() != null
            || conversation.getLead() != null
            || conversation.getTenant() != null
            || conversation.getOwner() != null
            || conversation.getBuyer() != null
            || (conversation.getCounterpartyName() != null
                && !conversation.getCounterpartyName().isBlank());
    if (!about) {
      throw ApiException.badRequest(
          "A conversation must concern a property, unit, lease, listing, lead, tenant, owner or buyer, "
              + "or name the counterparty it is with");
    }
  }

  private static String resolveSenderName(
      String requested, MessageSenderType senderType, Conversation conversation, User actor) {
    if (requested != null && !requested.isBlank()) {
      return requested.trim();
    }
    return switch (senderType) {
      case FIRM -> actor.getFullName();
      case CLIENT -> conversation.getCounterpartyName();
      case SYSTEM -> null;
    };
  }

  private static String preview(String body) {
    if (body == null) return null;
    String clean = body.strip();
    return clean.length() <= PREVIEW_MAX ? clean : clean.substring(0, PREVIEW_MAX);
  }

  private static String mintCode() {
    return "ME-C-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private Property requireProperty(UUID id) {
    return properties
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));
  }

  private Unit requireUnit(UUID id) {
    return units
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("UNIT_NOT_FOUND")));
  }

  private Lease requireLease(UUID id) {
    return leases
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("LEASE_NOT_FOUND")));
  }

  private Listing requireListing(UUID id) {
    return listings
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("LISTING_NOT_FOUND")));
  }

  private Lead requireLead(UUID id) {
    return leads
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("LEAD_NOT_FOUND")));
  }

  private Tenant requireTenant(UUID id) {
    return tenants
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("TENANT_NOT_FOUND")));
  }

  private Owner requireOwner(UUID id) {
    return owners
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OWNER_NOT_FOUND")));
  }

  private Buyer requireBuyer(UUID id) {
    return buyers
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("BUYER_NOT_FOUND")));
  }

  private User requireStaff(UUID id) {
    return users
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("That member of staff does not exist"));
  }

  private static UUID idOf(Property value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Unit value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Lease value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Listing value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Lead value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Tenant value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Owner value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Buyer value) {
    return value == null ? null : value.getId();
  }

  private static boolean matchesSearch(Conversation conversation, String term) {
    return contains(conversation.getSubject(), term)
        || contains(conversation.getConversationCode(), term)
        || contains(conversation.getCounterpartyName(), term)
        || contains(conversation.getCounterpartyEmail(), term);
  }

  private static boolean contains(String value, String term) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(term);
  }

  private static String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String lower(String value) {
    return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  private static ConversationStatus parseStatus(String value, ConversationStatus fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return ConversationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest(
          "'" + value + "' is not a conversation status", CommunicationDtos.statusValues());
    }
  }

  private static ConversationChannel parseChannel(String value, ConversationChannel fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return ConversationChannel.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest(
          "'" + value + "' is not a conversation channel", CommunicationDtos.channelValues());
    }
  }

  private static ConversationPriority parsePriority(String value, ConversationPriority fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return ConversationPriority.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a conversation priority");
    }
  }

  private static MessageSenderType parseSender(String value, MessageSenderType fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return MessageSenderType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a message sender type");
    }
  }

  private static MessageType parseMessageType(String value, MessageType fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return MessageType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a message type");
    }
  }

  // --------------------------------------------------------------------------
  // Paging and sorting, applied here because the combined repository query does
  // not exist (see the class note on paging).
  // --------------------------------------------------------------------------

  private static List<Conversation> sortConversations(List<Conversation> items, Sort sort) {
    Comparator<Conversation> comparator =
        sort == null || sort.isUnsorted()
            ? conversationComparator("lastMessageAt", true)
            : buildComparator(sort);
    List<Conversation> sorted = new ArrayList<>(items);
    sorted.sort(comparator.thenComparing(conversation -> conversation.getId(), Comparator.nullsLast(Comparator.naturalOrder())));
    return sorted;
  }

  private static Comparator<Conversation> buildComparator(Sort sort) {
    Comparator<Conversation> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Conversation> next = conversationComparator(order.getProperty(), order.isDescending());
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    return comparator == null ? conversationComparator("lastMessageAt", true) : comparator;
  }

  /**
   * The comparator for one field, with nulls always last in either direction.
   *
   * <p>Nulls last when descending is the whole point of the thread list's ordering: a conversation
   * with no messages has no activity to place in time and must not be pushed to the top as if it were
   * the most recent. See the class note.</p>
   */
  private static Comparator<Conversation> conversationComparator(String property, boolean descending) {
    return switch (property == null ? "" : property) {
      case "subject" -> comparingNullsLast(conversation -> lower(conversation.getSubject()), descending);
      case "conversationCode" ->
          comparingNullsLast(conversation -> lower(conversation.getConversationCode()), descending);
      case "channel" ->
          comparingNullsLast(
              conversation -> conversation.getChannel() == null ? null : conversation.getChannel().name(),
              descending);
      case "status" ->
          comparingNullsLast(
              conversation -> conversation.getStatus() == null ? null : conversation.getStatus().name(),
              descending);
      case "priority" ->
          comparingNullsLast(
              conversation -> conversation.getPriority() == null ? null : conversation.getPriority().name(),
              descending);
      case "openedAt" -> comparingNullsLast(Conversation::getOpenedAt, descending);
      case "createdAt" -> comparingNullsLast(Conversation::getCreatedAt, descending);
      case "updatedAt" -> comparingNullsLast(Conversation::getUpdatedAt, descending);
      default -> comparingNullsLast(Conversation::getLastMessageAt, descending);
    };
  }

  private static <T extends Comparable<? super T>> Comparator<Conversation> comparingNullsLast(
      Function<Conversation, T> key, boolean descending) {
    Comparator<T> order =
        descending
            ? Comparator.nullsLast(Comparator.<T>reverseOrder())
            : Comparator.nullsLast(Comparator.<T>naturalOrder());
    return Comparator.comparing(key, order);
  }

  private static <T> Page<T> pageOf(List<T> all, Pageable pageable) {
    int total = all.size();
    int size = pageable.isPaged() ? pageable.getPageSize() : total;
    int start = pageable.isPaged() ? (int) Math.min(pageable.getOffset(), (long) total) : 0;
    int end = Math.min(start + size, total);
    return new PageImpl<>(new ArrayList<>(all.subList(start, end)), pageable, total);
  }
}
