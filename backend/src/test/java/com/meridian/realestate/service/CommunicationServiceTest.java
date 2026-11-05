package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.Conversation;
import com.meridian.realestate.domain.ConversationChannel;
import com.meridian.realestate.domain.ConversationMessage;
import com.meridian.realestate.domain.ConversationPriority;
import com.meridian.realestate.domain.ConversationStatus;
import com.meridian.realestate.domain.MessageSenderType;
import com.meridian.realestate.domain.MessageType;
import com.meridian.realestate.dto.CommunicationDtos;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The communication console's rules, asserted without a database.
 *
 * <p>Two promises are pinned here, and both are about what a caller sees by default. An internal note
 * never reaches the client-facing thread read - not merely hidden in rendering, but filtered at the
 * query and again in the service - while the internal-only read is a separate call a caller must make.
 * And a conversation with no messages sorts last: the thread list orders on {@code last_message_at}
 * descending with nulls last, so a thread nobody has written is below one that has been.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunicationService: internal notes and thread ordering")
class CommunicationServiceTest {

  @Mock private ConversationRepository conversations;
  @Mock private ConversationMessageRepository messages;
  @Mock private UserRepository users;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private LeaseRepository leases;
  @Mock private ListingRepository listings;
  @Mock private LeadRepository leads;
  @Mock private TenantRepository tenants;
  @Mock private OwnerRepository owners;
  @Mock private BuyerRepository buyers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private CommunicationService service;

  @BeforeEach
  void setUp() {
    service =
        new CommunicationService(
            conversations,
            messages,
            users,
            properties,
            units,
            leases,
            listings,
            leads,
            tenants,
            owners,
            buyers,
            audit,
            accessScope);
  }

  private static Conversation conversation(UUID id, LocalDateTime lastMessageAt) {
    Conversation conversation = new Conversation();
    conversation.setId(id);
    conversation.setConversationCode("ME-C-00001");
    conversation.setSubject("Rent receipt not received");
    conversation.setChannel(ConversationChannel.EMAIL);
    conversation.setStatus(ConversationStatus.OPEN);
    conversation.setPriority(ConversationPriority.NORMAL);
    conversation.setOpenedAt(LocalDateTime.now().minusDays(2));
    conversation.setLastMessageAt(lastMessageAt);
    return conversation;
  }

  private static ConversationMessage message(
      MessageSenderType senderType, boolean internal, String body) {
    ConversationMessage message = new ConversationMessage();
    message.setId(UUID.randomUUID());
    message.setSenderType(senderType);
    message.setMessageType(internal ? MessageType.NOTE : MessageType.TEXT);
    message.setInternal(internal);
    message.setBody(body);
    message.setSentAt(LocalDateTime.now());
    return message;
  }

  @Test
  @DisplayName("an internal note is excluded from the client-facing message list")
  void anInternalNoteIsExcludedFromTheClientFacingList() {
    UUID id = UUID.randomUUID();
    ConversationMessage clientVisible = message(MessageSenderType.CLIENT, false, "Please confirm receipt.");
    ConversationMessage internalNote = message(MessageSenderType.FIRM, true, "Chase the arrears before reply.");

    when(conversations.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(conversation(id, LocalDateTime.now())));
    // The finder is made to (wrongly) return the internal note too, to prove the service filters it.
    when(messages.findAllByConversationIdAndInternalFalseAndDeletedAtIsNullOrderBySentAtAsc(id))
        .thenReturn(List.of(clientVisible, internalNote));
    when(messages.countByConversationIdAndDeletedAtIsNull(id)).thenReturn(2L);
    when(messages.countByConversationIdAndReadAtIsNullAndDeletedAtIsNull(id)).thenReturn(0L);

    CommunicationDtos.ConversationDetail detail = service.detail(id);

    assertThat(detail.includesInternalNotes()).isFalse();
    assertThat(detail.messages()).hasSize(1);
    assertThat(detail.messages().get(0).internal()).isFalse();
    assertThat(detail.messages().get(0).body()).isEqualTo("Please confirm receipt.");
  }

  @Test
  @DisplayName("a conversation with no messages sorts last")
  void aConversationWithNoMessagesSortsLast() {
    UUID recentId = UUID.randomUUID();
    UUID emptyId = UUID.randomUUID();
    Conversation recent = conversation(recentId, LocalDateTime.now());
    Conversation empty = conversation(emptyId, null);

    // The repository returns them in the wrong order on purpose; the service's ordering must fix it.
    when(conversations.findAllOrderedByRecentActivity()).thenReturn(List.of(empty, recent));
    when(messages.countByConversationIdAndDeletedAtIsNull(any())).thenReturn(0L);
    when(messages.countByConversationIdAndReadAtIsNullAndDeletedAtIsNull(any())).thenReturn(0L);

    Page<CommunicationDtos.ConversationSummary> page =
        service.list(
            null, null, null, null, null, null, null, null, null, null, Pageable.unpaged());

    List<CommunicationDtos.ConversationSummary> items = page.getContent();
    assertThat(items).hasSize(2);
    assertThat(items.get(0).id()).isEqualTo(recentId);
    assertThat(items.get(0).lastMessageAt()).isNotNull();
    assertThat(items.get(1).id()).isEqualTo(emptyId);
    assertThat(items.get(1).lastMessageAt()).isNull();
  }
}
