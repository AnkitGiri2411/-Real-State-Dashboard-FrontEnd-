-- ============================================================================
-- V19 - Conversations, messages and notifications
-- ============================================================================
-- The correspondence with owners, tenants, prospects and buyers, kept against the
-- record it is about.
--
-- ----------------------------------------------------------------------------
-- MESSAGE STYLE IS DERIVED ONLY FROM STORED FIELDS
-- ----------------------------------------------------------------------------
-- `sender_type`, `message_type` and `is_internal` are real columns, set when the
-- message is written. The interface styles a message from these and from nothing
-- else. It does not read the body text to guess whether a message is urgent,
-- whether it is about money, or what it concerns.
--
-- That is a hard rule rather than a preference. Inferring a category from free
-- text produces a screen that is confidently wrong in a way nobody can debug: a
-- tenant writing "I will not pay for the lift again" would be styled as a payment
-- message, while a genuinely overdue account whose email says "please see
-- attached" would not. Anything the interface needs to distinguish is a column,
-- or it is not distinguished.
--
-- `sender_name` is stored alongside `sender_user_id` because a message can arrive
-- from someone who has no account - a tenant replying to a listing - and a null
-- sender would otherwise render as nothing.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Conversations
-- ----------------------------------------------------------------------------

CREATE TABLE conversations (
  id                  uuid          PRIMARY KEY,
  conversation_code   varchar(30)   NOT NULL UNIQUE,
  subject             varchar(255)  NOT NULL,
  channel             varchar(30)   NOT NULL DEFAULT 'EMAIL',
  status              varchar(20)   NOT NULL DEFAULT 'OPEN',
  priority            varchar(20)   NOT NULL DEFAULT 'NORMAL',

  -- What the conversation is about. Several may be set: a thread about a lease
  -- arrears concerns the tenant, the lease and the unit at once.
  property_id         uuid          REFERENCES properties (id),
  unit_id             uuid          REFERENCES units (id),
  lease_id            uuid          REFERENCES leases (id),
  listing_id          uuid          REFERENCES listings (id),
  lead_id             uuid          REFERENCES leads (id),
  tenant_id           uuid          REFERENCES tenants (id),
  owner_id            uuid          REFERENCES owners (id),
  buyer_id            uuid          REFERENCES buyers (id),

  -- The counterparty's name, for a contact who is not yet one of the above.
  counterparty_name   varchar(200),
  counterparty_email  varchar(255),
  counterparty_phone  varchar(30),

  -- Who at the firm owns the thread.
  assigned_to         uuid          REFERENCES users (id),

  -- Denormalised read state. The console renders a list of threads with their
  -- latest message and a count without opening the message table per row; these
  -- two columns are maintained by the service inside the same transaction that
  -- writes a message, so they cannot drift from it.
  last_message_at     timestamp,
  last_message_preview varchar(255),

  opened_at           timestamp     NOT NULL,
  closed_at           timestamp,
  closed_by           uuid          REFERENCES users (id),
  close_reason        varchar(500),

  created_at          timestamp     NOT NULL,
  updated_at          timestamp     NOT NULL,
  created_by          uuid,
  updated_by          uuid,
  deleted_at          timestamp,

  CONSTRAINT ck_conversations_channel CHECK (channel IN (
    'EMAIL', 'PHONE', 'MESSAGE', 'PORTAL', 'IN_PERSON', 'LETTER', 'INTERNAL'
  )),
  CONSTRAINT ck_conversations_status CHECK (status IN ('OPEN', 'WAITING', 'RESOLVED', 'CLOSED')),
  CONSTRAINT ck_conversations_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
  CONSTRAINT ck_conversations_counterparty_email_lower CHECK (
    counterparty_email IS NULL OR counterparty_email = lower(counterparty_email)
  ),
  -- A conversation concerns something, or names who it is with.
  CONSTRAINT ck_conversations_subject_matter CHECK (
    property_id IS NOT NULL OR unit_id IS NOT NULL OR lease_id IS NOT NULL
    OR listing_id IS NOT NULL OR lead_id IS NOT NULL OR tenant_id IS NOT NULL
    OR owner_id IS NOT NULL OR buyer_id IS NOT NULL
    OR counterparty_name IS NOT NULL
  ),
  -- A closed conversation records when and why.
  CONSTRAINT ck_conversations_closed CHECK (
    (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL AND close_reason IS NOT NULL)
    OR (status <> 'CLOSED' AND closed_at IS NULL)
  )
);

CREATE INDEX ix_conversations_status ON conversations (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_conversations_assigned ON conversations (assigned_to)
  WHERE assigned_to IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_conversations_property ON conversations (property_id)
  WHERE property_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_conversations_lease ON conversations (lease_id)
  WHERE lease_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_conversations_tenant ON conversations (tenant_id)
  WHERE tenant_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_conversations_lead ON conversations (lead_id)
  WHERE lead_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_conversations_owner ON conversations (owner_id)
  WHERE owner_id IS NOT NULL AND deleted_at IS NULL;
-- The console lists threads newest-first on the left rail.
CREATE INDEX ix_conversations_recent ON conversations (last_message_at DESC NULLS LAST)
  WHERE deleted_at IS NULL;
-- The "needs a reply" count reads only threads that are still live.
CREATE INDEX ix_conversations_live ON conversations (status, last_message_at DESC)
  WHERE status IN ('OPEN', 'WAITING') AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Messages
-- ----------------------------------------------------------------------------

CREATE TABLE conversation_messages (
  id               uuid         PRIMARY KEY,
  conversation_id  uuid         NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,

  -- The stored fields the interface styles from. See the header.
  sender_type      varchar(20)  NOT NULL,
  message_type     varchar(20)  NOT NULL DEFAULT 'TEXT',
  is_internal      boolean      NOT NULL DEFAULT false,

  sender_user_id   uuid         REFERENCES users (id),
  sender_name      varchar(200),
  body             text         NOT NULL,

  sent_at          timestamp    NOT NULL,
  read_at          timestamp,

  -- Set when the message is the record of something the system did - a lease
  -- activated, a receipt posted - so the thread carries the business event
  -- alongside the correspondence without pretending it was written by a person.
  system_event     varchar(80),

  created_at       timestamp    NOT NULL,
  updated_at       timestamp    NOT NULL,
  created_by       uuid,
  updated_by       uuid,
  deleted_at       timestamp,

  CONSTRAINT ck_conversation_messages_sender CHECK (sender_type IN ('CLIENT', 'FIRM', 'SYSTEM')),
  CONSTRAINT ck_conversation_messages_type CHECK (message_type IN (
    'TEXT', 'NOTE', 'SYSTEM_EVENT', 'ATTACHMENT_REFERENCE'
  )),
  -- A message from the firm carries the colleague who wrote it; a system message
  -- carries the event that produced it.
  CONSTRAINT ck_conversation_messages_firm_sender CHECK (
    (sender_type = 'FIRM' AND sender_user_id IS NOT NULL)
    OR sender_type <> 'FIRM'
  ),
  CONSTRAINT ck_conversation_messages_system CHECK (
    (sender_type = 'SYSTEM' AND system_event IS NOT NULL)
    OR (sender_type <> 'SYSTEM' AND system_event IS NULL)
  ),
  -- Only a firm-side message is internal. A client cannot write a note to the firm
  -- that the firm cannot see.
  CONSTRAINT ck_conversation_messages_internal CHECK (
    is_internal = false OR sender_type = 'FIRM'
  ),
  CONSTRAINT ck_conversation_messages_body CHECK (length(btrim(body)) > 0)
);

CREATE INDEX ix_conversation_messages_conversation ON conversation_messages (conversation_id, sent_at);
CREATE INDEX ix_conversation_messages_unread ON conversation_messages (conversation_id)
  WHERE read_at IS NULL AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Notifications
-- ----------------------------------------------------------------------------
-- In-app only. This product does not send email or messages: a notification is a
-- row a signed-in user reads, and claiming delivery through a channel that is not
-- configured would be a promise the product cannot keep.

CREATE TABLE notifications (
  id           uuid         PRIMARY KEY,
  user_id      uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,

  type         varchar(60)  NOT NULL,
  severity     varchar(20)  NOT NULL DEFAULT 'INFO',
  title        varchar(255) NOT NULL,
  body         varchar(1000),

  -- What the notification is about, so acting on it can open the record.
  entity_type  varchar(60),
  entity_id    uuid,
  link         varchar(500),

  is_read      boolean      NOT NULL DEFAULT false,
  read_at      timestamp,
  created_at   timestamp    NOT NULL,

  CONSTRAINT ck_notifications_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  -- Read state is all-or-nothing, so an unread notification can never carry a
  -- read timestamp that would hide it from the badge.
  CONSTRAINT ck_notifications_read CHECK (
    (is_read = true AND read_at IS NOT NULL) OR (is_read = false AND read_at IS NULL)
  )
);

CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);
-- The bell's badge counts unread rows for one user; the partial index is exactly
-- that set.
CREATE INDEX ix_notifications_unread ON notifications (user_id)
  WHERE is_read = false;
