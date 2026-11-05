package com.meridian.realestate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.realestate.domain.AuditLog;
import com.meridian.realestate.domain.AuditSeverity;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.repository.AuditLogRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.security.AccessScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The single place an audit row is written.
 *
 * <p><b>Why this class exists.</b> "Who changed this, and from what to what" is a question this
 * product has to answer months later, for a lease, a receipt or a permission. That answer is only
 * trustworthy if every entry is written the same way: with the acting account resolved identically,
 * the module and action drawn from {@link AuditActions} rather than typed, the before and after
 * values captured, the request's origin recorded, and the entity references pointing at the record
 * the entry is about. One entry point means one set of rules, and a rule that holds in one place
 * cannot be forgotten in another.</p>
 *
 * <p><b>The rule it protects.</b> The trail must be complete and it must be honest. Complete, because
 * an audit row is written inside the caller's transaction ({@code Propagation.MANDATORY}: an audit
 * entry can never be committed without the business change it describes, and a change that rolls
 * back takes its entry with it) - so a service that forgets to record leaves a trail with a hole
 * rather than a wrong entry, and the hole is visible to the reviewer. Honest, because the entry
 * records what was <em>actually</em> submitted: the values as stored, not as a caller hoped.</p>
 *
 * <p><b>Never a credential.</b> Neither {@code description} nor {@code metadata} - nor
 * {@code previousValue} / {@code newValue} - may ever carry a password, a password hash, a token, a
 * key or any other credential, in whole or in part. A sign-in entry records <em>that</em> somebody
 * signed in and from where, never how. The trail is readable by anyone holding the audit permission
 * and it is exported for auditors, so a secret written here is a secret disclosed; the columns are
 * also only {@code varchar(500)}, and a truncated hash is still a disclosure. This is a rule on the
 * caller as much as on this class, because this class cannot inspect the meaning of the strings it is
 * handed.</p>
 *
 * <p><b>Where it is allowed to run.</b> The primary {@code record(...)} overloads are
 * {@code MANDATORY}: they join the caller's transaction and refuse to run without one, which is what
 * guarantees the entry and the change are one unit of work. {@link #recordAsCurrentUser} is the
 * deliberate exception - {@code REQUIRES_NEW} - because it exists for events that have no business
 * transaction of their own: a sign-in, a sign-out, a document download. Those are identity and read
 * events, they must be recorded whether or not a transaction happens to be open, and there is no
 * business change for the entry to be consistent with. Writing one of them inside another
 * transaction would make the trail's record of a sign-in depend on unrelated work committing.</p>
 *
 * <p><b>The entity-reference columns are deliberately not foreign keys.</b> {@code property_id},
 * {@code unit_id}, {@code owner_id}, {@code tenant_id}, {@code lead_id}, {@code listing_id},
 * {@code lease_id}, {@code payment_id} and {@code maintenance_request_id} are plain identifiers, so
 * an audit entry survives the record it describes being archived. A constraint here would quietly
 * turn the trail into a description of whatever still exists, which is the opposite of its purpose.
 * They are UUID columns rather than text so "show me everything that happened to this property" is an
 * index lookup rather than a parse of a description.</p>
 *
 * <p><b>How the metadata is stored.</b> {@code metadata} is a {@code jsonb} column, and the entity
 * maps it the same way it already does - a JSON <em>string</em> on a {@code String} field annotated
 * {@code @JdbcTypeCode(SqlTypes.JSON)}, so PostgreSQL stores it as jsonb and the operational summary
 * (the field that changed, the state transition, the row counts of an import) stays queryable. This
 * class serialises that string with the Spring-managed {@link ObjectMapper} rather than with a
 * mapper of its own, so the application's own Jackson configuration applies. An empty map is stored
 * as null: an absent summary and an empty one are the same fact.</p>
 */
@Service
public class AuditRecordingService {

  /** {@code audit_logs.description}, {@code previous_value} and {@code new_value} are varchar(500). */
  private static final int VALUE_MAX = 500;

  /** {@code audit_logs.actor_name} and {@code .entity_label} are varchar(200). */
  private static final int LABEL_MAX = 200;

  /** {@code audit_logs.entity_type} is varchar(60). */
  private static final int TYPE_MAX = 60;

  /** {@code audit_logs.ip_address} is varchar(60). */
  private static final int IP_MAX = 60;

  /** {@code audit_logs.user_agent} is varchar(255). */
  private static final int USER_AGENT_MAX = 255;

  private final AuditLogRepository auditLogs;
  private final UserRepository users;
  private final AccessScope accessScope;
  private final ObjectMapper objectMapper;

  /**
   * This bean, as the container's proxy.
   *
   * <p>Held so {@link Builder#record()} can take the same transactional path a direct caller takes.
   * A nested class calling back into its own service is a self-invocation, which the proxy never sees
   * - and the propagation rule that makes an audit entry inseparable from its business change would
   * then hold on one path and not the other.</p>
   */
  private final ObjectProvider<AuditRecordingService> self;

  public AuditRecordingService(
      AuditLogRepository auditLogs,
      UserRepository users,
      AccessScope accessScope,
      ObjectMapper objectMapper,
      ObjectProvider<AuditRecordingService> self) {
    this.auditLogs = auditLogs;
    this.users = users;
    this.accessScope = accessScope;
    this.objectMapper = objectMapper;
    this.self = self;
  }

  // ---------------------------------------------------------------------------
  // The entry points
  // ---------------------------------------------------------------------------

  /**
   * Records an event about one record, with the generic entity reference and no before/after values.
   *
   * <p>The common case: something happened to a named record and the description says what.</p>
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      UUID actorId,
      String actorName,
      String module,
      String action,
      String description,
      String entityType,
      UUID entityId,
      String entityLabel) {
    write(
        new Entry(
            actorId,
            actorName,
            module,
            action,
            AuditSeverity.INFO,
            description,
            entityType,
            entityId,
            entityLabel,
            null,
            null,
            null,
            EntityReferences.none()));
  }

  /** Records an event with nothing more than its module, action and description. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      UUID actorId, String actorName, String module, String action, String description) {
    write(
        new Entry(
            actorId,
            actorName,
            module,
            action,
            AuditSeverity.INFO,
            description,
            null,
            null,
            null,
            null,
            null,
            null,
            EntityReferences.none()));
  }

  /**
   * Records a change to a record, with the values it moved between and an operational summary.
   *
   * <p>The shape the detail screens' activity tabs read: what the field was, what it became, and the
   * machine-readable summary a filter or a report can aggregate.</p>
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      UUID actorId,
      String actorName,
      String module,
      String action,
      String description,
      String entityType,
      UUID entityId,
      String entityLabel,
      String previousValue,
      String newValue,
      Map<String, Object> metadata) {
    write(
        new Entry(
            actorId,
            actorName,
            module,
            action,
            AuditSeverity.INFO,
            description,
            entityType,
            entityId,
            entityLabel,
            previousValue,
            newValue,
            metadata,
            EntityReferences.none()));
  }

  /**
   * The same, at a chosen severity.
   *
   * <p>Severity is what puts an entry in the attention rail rather than only in the trail, so it is
   * passed by the caller who knows whether the event is ordinary noise or something a colleague has
   * to see.</p>
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      UUID actorId,
      String actorName,
      String module,
      String action,
      AuditSeverity severity,
      String description,
      String entityType,
      UUID entityId,
      String entityLabel,
      String previousValue,
      String newValue,
      Map<String, Object> metadata) {
    write(
        new Entry(
            actorId,
            actorName,
            module,
            action,
            severity,
            description,
            entityType,
            entityId,
            entityLabel,
            previousValue,
            newValue,
            metadata,
            EntityReferences.none()));
  }

  /**
   * Records a change to a record that also carries the named entity-reference columns.
   *
   * <p>Used where the entry has to be findable from a property, a lease, a receipt or a maintenance
   * request as well as from its generic entity: those columns are what the detail screens' activity
   * tabs query by.</p>
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordEntityChange(
      UUID actorId,
      String actorName,
      String module,
      String action,
      String description,
      String entityType,
      UUID entityId,
      String entityLabel,
      String previousValue,
      String newValue,
      EntityReferences references) {
    write(
        new Entry(
            actorId,
            actorName,
            module,
            action,
            AuditSeverity.INFO,
            description,
            entityType,
            entityId,
            entityLabel,
            previousValue,
            newValue,
            null,
            references == null ? EntityReferences.none() : references));
  }

  /**
   * Records an entry for whoever is signed in, in a transaction of its own.
   *
   * <p>{@code REQUIRES_NEW} rather than {@code MANDATORY} is the whole point of this method: it is
   * called for events that have no business transaction behind them - a sign-in, a sign-out, a
   * report run against data that is not being changed - and those must be recorded whether or not a
   * transaction happens to be open. Given its own transaction, it also commits independently of the
   * caller, so a read that later fails still leaves the record that somebody attempted it. Use the
   * {@code record(...)} overloads for anything that describes a business change; a change must never
   * be recorded by a method that can outlive it.</p>
   *
   * <p>The actor comes from {@link AccessScope}, and may be empty - an unauthenticated caller, a
   * scheduled job - in which case the entry is written with no actor rather than with an invented
   * one. {@code actor_id} is nullable for exactly that reason: a false attribution is worse than an
   * anonymous entry.</p>
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordAsCurrentUser(
      String module,
      String action,
      String description,
      String entityType,
      UUID entityId,
      String entityLabel) {
    recordAsCurrentUser(
        module, action, AuditSeverity.INFO, description, entityType, entityId, entityLabel);
  }

  /**
   * The same, at a chosen severity.
   *
   * <p>A successful sign-in against an unrecognised address, or a refused permission change, belongs
   * in the attention rail rather than only in the trail.</p>
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordAsCurrentUser(
      String module,
      String action,
      AuditSeverity severity,
      String description,
      String entityType,
      UUID entityId,
      String entityLabel) {
    User actor = accessScope.currentUser().orElse(null);
    self.getObject()
        .record(
            new Entry(
                actor == null ? null : actor.getId(),
                actor == null ? null : actor.getFullName(),
                module,
                action,
                severity,
                description,
                entityType,
                entityId,
                entityLabel,
                null,
                null,
                null,
                EntityReferences.none()));
  }

  /**
   * The one write path.
   *
   * <p>Every overload above and {@link Builder#record()} arrive here, so the field rules - the
   * lengths, the null handling, the JSON, the request origin - are applied in exactly one place. It
   * is public so the {@link Builder} can reach it through the bean's proxy rather than by
   * self-invocation, keeping the {@code MANDATORY} rule true on that path too.</p>
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Entry entry) {
    write(entry);
  }

  // ---------------------------------------------------------------------------
  // The write
  // ---------------------------------------------------------------------------

  private void write(Entry entry) {
    if (entry.module() == null || entry.module().isBlank()) {
      throw new IllegalArgumentException(
          "An audit entry must name its module (see AuditActions); an entry filed under no module "
              + "is an entry nobody can find.");
    }
    if (entry.action() == null || entry.action().isBlank()) {
      throw new IllegalArgumentException(
          "An audit entry must name its action (see AuditActions); 'something happened' is not a "
              + "trail anybody can filter.");
    }

    EntityReferences references =
        entry.references() == null ? EntityReferences.none() : entry.references();

    AuditLog row = new AuditLog();
    // A reference, not a load: the row's foreign key is written without a select, because an audit
    // entry needs the actor's identity and not its profile.
    row.setActor(entry.actorId() == null ? null : users.getReferenceById(entry.actorId()));
    row.setActorName(trim(entry.actorName(), LABEL_MAX));
    row.setModule(entry.module());
    row.setAction(entry.action());
    row.setSeverity(entry.severity() == null ? AuditSeverity.INFO : entry.severity());
    row.setDescription(trim(entry.description(), VALUE_MAX));
    row.setEntityType(trim(entry.entityType(), TYPE_MAX));
    row.setEntityId(entry.entityId());
    row.setEntityLabel(trim(entry.entityLabel(), LABEL_MAX));
    row.setPropertyId(references.propertyId());
    row.setUnitId(references.unitId());
    row.setOwnerId(references.ownerId());
    row.setTenantId(references.tenantId());
    row.setLeadId(references.leadId());
    row.setListingId(references.listingId());
    row.setLeaseId(references.leaseId());
    row.setPaymentId(references.paymentId());
    row.setMaintenanceRequestId(references.maintenanceRequestId());
    row.setPreviousValue(trim(entry.previousValue(), VALUE_MAX));
    row.setNewValue(trim(entry.newValue(), VALUE_MAX));
    row.setMetadata(toJson(entry.metadata()));
    row.setIpAddress(trim(currentIpAddress(), IP_MAX));
    row.setUserAgent(trim(currentUserAgent(), USER_AGENT_MAX));

    auditLogs.save(row);
  }

  /**
   * Trims a value to its column, rather than letting the write fail.
   *
   * <p>An import's description or a long before/after value can legitimately exceed
   * {@code varchar(500)}. Failing the insert would roll back the business change the entry
   * describes - an import refused because its own summary was too long - which is a far worse
   * outcome than a truncated tail on one sentence. Losing the end of a description loses some
   * detail; losing the entry, or the work, loses the fact.</p>
   */
  private static String trim(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }

  /**
   * The metadata as the JSON string the entity's jsonb mapping expects.
   *
   * <p>An empty map is stored as null. A map that cannot be serialised is a defect in the caller's
   * summary, and it is raised rather than swallowed: an entry that silently lost the operational
   * detail it was written for is worse than a loud refusal at the call site that produced it.</p>
   */
  private String toJson(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) return null;
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "The audit metadata could not be serialised. The summary must hold JSON-serialisable "
              + "values - never a credential.",
          ex);
    }
  }

  /**
   * The caller's address, or null.
   *
   * <p>Null is the correct answer outside a request: a scheduled job or a test has no client, and
   * inventing {@code 0.0.0.0} would put a false fact in the trail. The container's remote address is
   * recorded as it stands - this product does not trust a forwarding header, because a client can
   * set one, and an audit trail that can be lied to is not evidence.</p>
   */
  private static String currentIpAddress() {
    HttpServletRequest request = currentRequest();
    return request == null ? null : request.getRemoteAddr();
  }

  /** The caller's user agent, or null when there is no request. */
  private static String currentUserAgent() {
    HttpServletRequest request = currentRequest();
    return request == null ? null : request.getHeader("User-Agent");
  }

  private static HttpServletRequest currentRequest() {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes)) {
      return null;
    }
    return attributes.getRequest();
  }

  // ---------------------------------------------------------------------------
  // Entity references, and the builder that assembles them
  // ---------------------------------------------------------------------------

  /**
   * The named entity-reference columns {@code audit_logs} carries, as one value.
   *
   * <p>Every one is optional: an entry about a permission change references nobody, and an entry
   * about a receipt references the receipt, its lease and the tenant - whichever the screens filter
   * by. They are passed as a set rather than as nine positional arguments so a call site cannot put
   * the tenant where the lease belongs and stay readable while doing it.</p>
   */
  public record EntityReferences(
      UUID propertyId,
      UUID unitId,
      UUID ownerId,
      UUID tenantId,
      UUID leadId,
      UUID listingId,
      UUID leaseId,
      UUID paymentId,
      UUID maintenanceRequestId) {

    /** No entity references at all - the generic {@code entity_type}/{@code entity_id} case. */
    public static EntityReferences none() {
      return new EntityReferences(null, null, null, null, null, null, null, null, null);
    }
  }

  /**
   * One fully assembled audit entry.
   *
   * <p>Exists so there is a single write path: the public {@code record(...)} overloads construct one
   * and hand it over, and so does the {@link Builder}. Thirteen components is more than a call site
   * should spell out, which is exactly why the named overloads and the builder exist on top of it.</p>
   */
  public record Entry(
      UUID actorId,
      String actorName,
      String module,
      String action,
      AuditSeverity severity,
      String description,
      String entityType,
      UUID entityId,
      String entityLabel,
      String previousValue,
      String newValue,
      Map<String, Object> metadata,
      EntityReferences references) {}

  /**
   * A fluent way to assemble an entry that carries entity references.
   *
   * <p>Exists for the cases the fixed overloads cannot express readably: an entry about a lease that
   * must also be findable from its property, its unit and its tenant. The chain reads as the sentence
   * the trail will hold - who, what happened where, from what to what - and the terminal
   * {@link #record()} passes through the bean's own proxy so the {@code MANDATORY} rule holds.</p>
   *
   * <p>Nothing is written until {@code record()} is called, so a builder that is abandoned on an
   * early return leaves no entry behind. The same rule applies throughout: the actor comes from
   * {@link #asCurrentUser()} or is given explicitly, and no credential ever goes into a description
   * or the metadata.</p>
   */
  public static final class Builder {

    private final AuditRecordingService service;

    /**
     * The scope the acting account is resolved from.
     *
     * <p>Held here rather than reached through {@link AuditRecordingService#accessScope} on
     * {@link #service}, and that is not a style choice. {@code AuditRecordingService} is
     * CGLIB-proxied for its transactional boundaries, and a CGLIB proxy is instantiated
     * <em>without running the constructor</em> - only its method calls are intercepted and
     * forwarded to the target. A field read on the proxy therefore sees the proxy's own
     * uninitialised fields and is always null. Reading {@code service.accessScope} threw a
     * NullPointerException on every import commit, which was the one and only caller of
     * {@link #asCurrentUser()} in this product; every other entry names its actor explicitly.</p>
     *
     * <p>{@link AuditRecordingService#entry()} is itself a method on the target, so the
     * {@code accessScope} handed to this builder is the target's own properly-injected one.</p>
     */
    private final AccessScope accessScope;

    private UUID actorId;
    private String actorName;
    private String module;
    private String action;
    private AuditSeverity severity = AuditSeverity.INFO;
    private String description;
    private String entityType;
    private UUID entityId;
    private String entityLabel;
    private String previousValue;
    private String newValue;
    private Map<String, Object> metadata;

    private UUID propertyId;
    private UUID unitId;
    private UUID ownerId;
    private UUID tenantId;
    private UUID leadId;
    private UUID listingId;
    private UUID leaseId;
    private UUID paymentId;
    private UUID maintenanceRequestId;

    private Builder(AuditRecordingService service, AccessScope accessScope) {
      this.service = service;
      this.accessScope = accessScope;
    }

    /** The account that acted, named as it will read in the trail. */
    public Builder actor(UUID actorId, String actorName) {
      this.actorId = actorId;
      this.actorName = actorName;
      return this;
    }

    /** The account that acted, taken from {@link AccessScope}. */
    public Builder asCurrentUser() {
      User actor = accessScope.currentUser().orElse(null);
      this.actorId = actor == null ? null : actor.getId();
      this.actorName = actor == null ? null : actor.getFullName();
      return this;
    }

    /** The product area, one of {@link AuditActions}' module constants. */
    public Builder module(String module) {
      this.module = module;
      return this;
    }

    /** What happened, one of {@link AuditActions}' action constants. */
    public Builder action(String action) {
      this.action = action;
      return this;
    }

    /** How loudly the entry should be read. INFO unless something else is meant. */
    public Builder severity(AuditSeverity severity) {
      this.severity = severity;
      return this;
    }

    /** What happened, in a sentence the desk can read. Never a credential. */
    public Builder description(String description) {
      this.description = description;
      return this;
    }

    /** The record the entry is about. */
    public Builder entity(String entityType, UUID entityId, String entityLabel) {
      this.entityType = entityType;
      this.entityId = entityId;
      this.entityLabel = entityLabel;
      return this;
    }

    /** The record the entry is about, without a label to show for it. */
    public Builder entity(String entityType, UUID entityId) {
      return entity(entityType, entityId, null);
    }

    /** The value the record moved away from, and the one it moved to. */
    public Builder change(String previousValue, String newValue) {
      this.previousValue = previousValue;
      this.newValue = newValue;
      return this;
    }

    /** The operational summary, stored as jsonb. Never a credential. */
    public Builder metadata(Map<String, Object> metadata) {
      this.metadata = metadata;
      return this;
    }

    /** The property the entry is findable from. */
    public Builder property(UUID propertyId) {
      this.propertyId = propertyId;
      return this;
    }

    /** The unit the entry is findable from. */
    public Builder unit(UUID unitId) {
      this.unitId = unitId;
      return this;
    }

    /** The owner the entry is findable from. */
    public Builder owner(UUID ownerId) {
      this.ownerId = ownerId;
      return this;
    }

    /** The tenant the entry is findable from. */
    public Builder tenant(UUID tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    /** The lead the entry is findable from. */
    public Builder lead(UUID leadId) {
      this.leadId = leadId;
      return this;
    }

    /** The listing the entry is findable from. */
    public Builder listing(UUID listingId) {
      this.listingId = listingId;
      return this;
    }

    /** The lease the entry is findable from. */
    public Builder lease(UUID leaseId) {
      this.leaseId = leaseId;
      return this;
    }

    /** The receipt the entry is findable from. */
    public Builder payment(UUID paymentId) {
      this.paymentId = paymentId;
      return this;
    }

    /** The maintenance request the entry is findable from. */
    public Builder maintenanceRequest(UUID maintenanceRequestId) {
      this.maintenanceRequestId = maintenanceRequestId;
      return this;
    }

    /** Writes the entry, in the caller's transaction. */
    public void record() {
      service.record(
          new Entry(
              actorId,
              actorName,
              module,
              action,
              severity,
              description,
              entityType,
              entityId,
              entityLabel,
              previousValue,
              newValue,
              metadata,
              new EntityReferences(
                  propertyId,
                  unitId,
                  ownerId,
                  tenantId,
                  leadId,
                  listingId,
                  leaseId,
                  paymentId,
                  maintenanceRequestId)));
    }
  }

  /** Starts an entry that needs entity references, or more than a fixed overload can say. */
  public Builder entry() {
    return new Builder(self.getObject(), accessScope);
  }
}
