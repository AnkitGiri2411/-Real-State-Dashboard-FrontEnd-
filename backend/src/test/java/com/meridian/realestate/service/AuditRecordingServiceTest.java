package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.realestate.domain.AuditLog;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.repository.AuditLogRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.security.AccessScope;
import java.util.Optional;
import java.util.UUID;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The audit recorder's builder, exercised through the shape Spring actually injects.
 *
 * <p><b>Why this test exists.</b> {@code AuditRecordingService} is CGLIB-proxied for its
 * transactional boundaries, and a CGLIB proxy is instantiated <em>without running the
 * constructor</em>: only its method calls are intercepted and forwarded to the target, so any
 * <em>field</em> read through the proxy sees the proxy's own uninitialised fields. The builder's
 * {@code asCurrentUser()} read {@code service.accessScope} that way and threw a
 * {@link NullPointerException} on every import commit - the one and only caller of that method in
 * the product, which is why nothing caught it.</p>
 *
 * <p><b>Why a plain mock cannot stand in for this.</b> A Mockito mock of the service, or the
 * service constructed normally, has its fields set - so the defect is invisible through either.
 * The test therefore builds a real {@link ProxyFactory} proxy over a fully-constructed target,
 * which is precisely the topology Spring creates, and drives the entry through the proxy.</p>
 */
@DisplayName("AuditRecordingService's builder works through a proxied instance")
class AuditRecordingServiceTest {

  private static final UUID ACTOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID JOB_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");

  private final AuditLogRepository auditLogs = mock(AuditLogRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final AccessScope accessScope = mock(AccessScope.class);
  @SuppressWarnings("unchecked")
  private final ObjectProvider<AuditRecordingService> self = mock(ObjectProvider.class);

  @Test
  @DisplayName("an entry built on a CGLIB proxy still names the signed-in account")
  void aProxiedInstanceNamesTheActorFromItsOwnScope() {
    User actor = mock(User.class);
    when(actor.getId()).thenReturn(ACTOR_ID);
    when(actor.getFullName()).thenReturn("Aditya Raghunathan");
    when(accessScope.currentUser()).thenReturn(Optional.of(actor));
    when(users.getReferenceById(ACTOR_ID)).thenReturn(actor);

    AuditRecordingService target =
        new AuditRecordingService(auditLogs, users, accessScope, new ObjectMapper(), self);

    // The proxy is created here rather than by a Spring context so the test can assert the exact
    // topology without a database: real method bodies on the target, uninitialised fields on the
    // proxy, every call forwarded through the advice chain.
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
    AuditRecordingService proxied = (AuditRecordingService) factory.getProxy();

    // The service hands the builder its own instance, as the @Bean wiring does.
    when(self.getObject()).thenReturn(proxied);

    proxied
        .entry()
        .asCurrentUser()
        .module(AuditActions.IMPORT)
        .action(AuditActions.IMPORT_COMMITTED)
        .description("Committed import ME-IMP-TEST: 2 inserted, 0 skipped, 1 failed")
        .entity("IMPORT", JOB_ID, "ME-IMP-TEST")
        .record();

    ArgumentCaptor<AuditLog> written = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogs).save(written.capture());

    // The actor is the whole point: an entry that could not name who acted is not evidence.
    assertThat(written.getValue().getActorName()).isEqualTo("Aditya Raghunathan");
    assertThat(written.getValue().getActor()).isSameAs(actor);
    assertThat(written.getValue().getModule()).isEqualTo(AuditActions.IMPORT);
  }

  @Test
  @DisplayName("an unauthenticated caller leaves the actor empty rather than failing the write")
  void anUnauthenticatedCallerStillWritesTheEntry() {
    when(accessScope.currentUser()).thenReturn(Optional.empty());

    AuditRecordingService target =
        new AuditRecordingService(auditLogs, users, accessScope, new ObjectMapper(), self);

    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
    AuditRecordingService proxied = (AuditRecordingService) factory.getProxy();
    when(self.getObject()).thenReturn(proxied);

    proxied
        .entry()
        .asCurrentUser()
        .module(AuditActions.IMPORT)
        .action(AuditActions.IMPORT_COMMITTED)
        .description("Nothing to attribute")
        .entity("IMPORT", JOB_ID, "ME-IMP-TEST")
        .record();

    ArgumentCaptor<AuditLog> written = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogs).save(written.capture());
    assertThat(written.getValue().getActor()).isNull();
    assertThat(written.getValue().getActorName()).isNull();
  }
}
