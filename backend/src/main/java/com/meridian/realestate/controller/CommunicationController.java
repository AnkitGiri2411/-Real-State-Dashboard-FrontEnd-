package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.CommunicationDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.CommunicationService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The correspondence console: the threads, their messages and their ownership.
 *
 * <p><b>Why this controller exists.</b> The console reaches one resource several ways for different
 * questions: the thread list (newest activity first), a single thread read as a client would see it,
 * the same thread read with the firm's internal notes, and the actions that move a thread between
 * states and owners. They live under one base path because they all read the thread.</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. A thread filed against nothing, an
 * internal note on a client message, a closed thread taking new messages, a closure with no reason -
 * all of those are decided in {@link CommunicationService} and raised as
 * {@link com.meridian.realestate.exception.ApiException}, which {@code GlobalExceptionHandler} turns
 * into the product's one error envelope. This class only routes, validates the shape of the request
 * body and wraps the answer in {@link ApiResponse}. No entity and no repository ever appears here.</p>
 *
 * <p><b>An internal note never reaches a default surface.</b> {@code GET /{id}} returns the thread as a
 * client could see it - internal notes are not merely omitted, they are excluded at the query and
 * again in the service. The whole thread, internal notes included, is reached only through the
 * explicitly named {@code GET /{id}/messages/internal}.</p>
 *
 * <p><b>Permissions.</b> Every endpoint names a code that exists in the {@code V2} seed -
 * {@code CONVERSATION_VIEW}, {@code CONVERSATION_CREATE}, {@code CONVERSATION_REPLY} and
 * {@code CONVERSATION_CLOSE}. The seed declares no assignment capability, so assigning a thread is
 * gated by {@code CONVERSATION_CREATE}, the capability that logs and owns a thread; marking a thread
 * read is gated by {@code CONVERSATION_REPLY}, the capability for working a thread. No role name
 * appears anywhere in this class, so changing what a role may do is a data change and never a code
 * change.</p>
 */
@RestController
@RequestMapping("/api/v1/communications")
public class CommunicationController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("lastMessageAt,desc")
          .allow("lastMessageAt", "lastMessageAt")
          .allow("subject", "subject")
          .allow("conversationCode", "conversationCode")
          .allow("channel", "channel")
          .allow("status", "status")
          .allow("priority", "priority")
          .allow("openedAt", "openedAt")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .build();

  private final CommunicationService communicationService;

  public CommunicationController(CommunicationService communicationService) {
    this.communicationService = communicationService;
  }

  /**
   * The thread list, newest activity first.
   *
   * <p>A thread with no messages sorts last by design; see {@link CommunicationService}.</p>
   */
  @GetMapping
  @PreAuthorize("hasAuthority('CONVERSATION_VIEW')")
  public ApiResponse<PageResponse<CommunicationDtos.ConversationSummary>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) UUID assignedToId,
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) UUID leadId,
      @RequestParam(required = false) UUID buyerId,
      @RequestParam(required = false) Boolean unreadOnly,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            communicationService.list(
                status,
                channel,
                priority,
                assignedToId,
                propertyId,
                tenantId,
                leadId,
                buyerId,
                unreadOnly,
                search,
                PAGES.toPageable(page, size, sort))));
  }

  /** The header badge's number. */
  @GetMapping("/unread-count")
  @PreAuthorize("hasAuthority('CONVERSATION_VIEW')")
  public ApiResponse<CommunicationDtos.UnreadCount> unreadCount() {
    return ApiResponse.ok(new CommunicationDtos.UnreadCount(communicationService.unreadCount()));
  }

  /**
   * One thread as a client could see it: internal notes are excluded.
   *
   * <p>This is the read a portal, an export or a print view must use.</p>
   */
  @GetMapping("/{conversationId}")
  @PreAuthorize("hasAuthority('CONVERSATION_VIEW')")
  public ApiResponse<CommunicationDtos.ConversationDetail> detail(@PathVariable UUID conversationId) {
    return ApiResponse.ok(communicationService.detail(conversationId));
  }

  /** The whole thread, internal notes included - asked for explicitly, never returned by default. */
  @GetMapping("/{conversationId}/messages/internal")
  @PreAuthorize("hasAuthority('CONVERSATION_VIEW')")
  public ApiResponse<List<CommunicationDtos.MessageSummary>> internalMessages(
      @PathVariable UUID conversationId) {
    return ApiResponse.ok(communicationService.internalMessages(conversationId));
  }

  /** Open a thread. */
  @PostMapping
  @PreAuthorize("hasAuthority('CONVERSATION_CREATE')")
  public ApiResponse<CommunicationDtos.ConversationDetail> create(
      @Valid @RequestBody CommunicationDtos.CreateConversationRequest request) {
    return ApiResponse.ok(communicationService.create(request), "Conversation opened");
  }

  /** Post a message to a thread. */
  @PostMapping("/{conversationId}/messages")
  @PreAuthorize("hasAuthority('CONVERSATION_REPLY')")
  public ApiResponse<CommunicationDtos.MessageSummary> postMessage(
      @PathVariable UUID conversationId,
      @Valid @RequestBody CommunicationDtos.PostMessageRequest request) {
    return ApiResponse.ok(communicationService.postMessage(conversationId, request), "Message posted");
  }

  /** Mark every message on a thread read. */
  @PostMapping("/{conversationId}/read")
  @PreAuthorize("hasAuthority('CONVERSATION_REPLY')")
  public ApiResponse<CommunicationDtos.ConversationSummary> markRead(@PathVariable UUID conversationId) {
    return ApiResponse.ok(communicationService.markRead(conversationId), "Conversation marked read");
  }

  /** Mark every message on a thread unread again. */
  @PostMapping("/{conversationId}/unread")
  @PreAuthorize("hasAuthority('CONVERSATION_REPLY')")
  public ApiResponse<CommunicationDtos.ConversationSummary> markUnread(@PathVariable UUID conversationId) {
    return ApiResponse.ok(communicationService.markUnread(conversationId), "Conversation marked unread");
  }

  /** Assign or reassign the colleague who owns a thread. A null id unassigns it. */
  @PostMapping("/{conversationId}/assign")
  @PreAuthorize("hasAuthority('CONVERSATION_CREATE')")
  public ApiResponse<CommunicationDtos.ConversationSummary> assign(
      @PathVariable UUID conversationId,
      @Valid @RequestBody CommunicationDtos.AssignConversationRequest request) {
    return ApiResponse.ok(communicationService.assign(conversationId, request.assignedToId()), "Conversation assigned");
  }

  /** Close a thread. */
  @PostMapping("/{conversationId}/close")
  @PreAuthorize("hasAuthority('CONVERSATION_CLOSE')")
  public ApiResponse<CommunicationDtos.ConversationSummary> close(
      @PathVariable UUID conversationId,
      @RequestBody(required = false) CommunicationDtos.CloseConversationRequest request) {
    return ApiResponse.ok(
        communicationService.close(conversationId, request == null ? null : request.reason()),
        "Conversation closed");
  }

  /** Reopen a closed thread. */
  @PostMapping("/{conversationId}/reopen")
  @PreAuthorize("hasAuthority('CONVERSATION_CLOSE')")
  public ApiResponse<CommunicationDtos.ConversationSummary> reopen(@PathVariable UUID conversationId) {
    return ApiResponse.ok(communicationService.reopen(conversationId), "Conversation reopened");
  }
}
