-- ============================================================================
-- V2 - Seed permissions, property roles and staff accounts
-- ============================================================================
-- The permission set is the real control in this product; the roles below are
-- only convenient bundles of it. No endpoint ever checks a role name.
--
-- Role design for a property firm:
--   OWNER             the firm that bought the product. Everything, and cannot
--                     be locked out or have its permissions reduced.
--   ADMIN             the firm's administration: staff, records, settings.
--   PROPERTY_MANAGER  the portfolio manager: the whole operation, the rent roll
--                     and the numbers. Cannot change role definitions or
--                     deactivate a colleague - those are the owner's and the
--                     administrator's calls.
--   LEASING_AGENT     the letting side: leads, listings, viewings,
--                     applications, tenancies and the leases that follow.
--   SALES_AGENT       the sales side: buyers, the offer and sale pipeline, and
--                     the listings that carry them.
--   ACCOUNTING        the money: the rent roll, receipts, voids and refunds,
--                     expenses, and a read-only view of the audit trail.
--   MAINTENANCE       the stock's condition: faults on a property or unit, their
--                     assignment and resolution, and the cost they carry.
--   VIEWER            read-only across the portfolio. A board member, an auditor
--                     or a client with reporting access.
--
-- The documented demonstration password for every seeded account is 'testing'. It
-- is a published demo credential on purpose (the install documentation states it
-- and tells the operator to change it); it is not a secret and never protects
-- real data.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Permissions
-- ----------------------------------------------------------------------------
INSERT INTO permissions (id, code, module, description) VALUES
  (gen_random_uuid(), 'PROPERTY_VIEW',          'PROPERTIES',    'See properties and the portfolio register'),
  (gen_random_uuid(), 'PROPERTY_CREATE',        'PROPERTIES',    'Add a property'),
  (gen_random_uuid(), 'PROPERTY_UPDATE',        'PROPERTIES',    'Change a property''s details'),
  (gen_random_uuid(), 'PROPERTY_DELETE',        'PROPERTIES',    'Archive a property'),
  (gen_random_uuid(), 'PROPERTY_STATUS_CHANGE', 'PROPERTIES',    'Move a property between lifecycle states'),

  (gen_random_uuid(), 'BUILDING_VIEW',          'BUILDINGS',     'See buildings and blocks'),
  (gen_random_uuid(), 'BUILDING_CREATE',        'BUILDINGS',     'Add a building'),
  (gen_random_uuid(), 'BUILDING_UPDATE',        'BUILDINGS',     'Change a building'),
  (gen_random_uuid(), 'BUILDING_DELETE',        'BUILDINGS',     'Archive a building'),

  (gen_random_uuid(), 'UNIT_VIEW',              'UNITS',         'See the unit board and unit records'),
  (gen_random_uuid(), 'UNIT_CREATE',            'UNITS',         'Add a unit to the stock'),
  (gen_random_uuid(), 'UNIT_UPDATE',            'UNITS',         'Change a unit''s details'),
  (gen_random_uuid(), 'UNIT_DELETE',            'UNITS',         'Archive a unit'),
  (gen_random_uuid(), 'UNIT_STATUS_CHANGE',     'UNITS',         'Move a unit between lifecycle states'),

  (gen_random_uuid(), 'OWNER_VIEW',             'OWNERS',        'See owners and their holdings'),
  (gen_random_uuid(), 'OWNER_CREATE',           'OWNERS',        'Add an owner'),
  (gen_random_uuid(), 'OWNER_UPDATE',           'OWNERS',        'Change an owner or a holding'),
  (gen_random_uuid(), 'OWNER_DELETE',           'OWNERS',        'Archive an owner'),

  (gen_random_uuid(), 'TENANT_VIEW',            'TENANTS',       'See tenant profiles'),
  (gen_random_uuid(), 'TENANT_CREATE',          'TENANTS',       'Create a tenant profile'),
  (gen_random_uuid(), 'TENANT_UPDATE',          'TENANTS',       'Change a tenant profile'),
  (gen_random_uuid(), 'TENANT_DELETE',          'TENANTS',       'Archive a tenant profile'),

  (gen_random_uuid(), 'BUYER_VIEW',             'BUYERS',        'See buyers and their requirements'),
  (gen_random_uuid(), 'BUYER_CREATE',           'BUYERS',        'Add a buyer'),
  (gen_random_uuid(), 'BUYER_UPDATE',           'BUYERS',        'Change a buyer or their requirements'),
  (gen_random_uuid(), 'BUYER_DELETE',           'BUYERS',        'Archive a buyer'),

  (gen_random_uuid(), 'AGENT_VIEW',             'AGENTS',        'See agents and their portfolios'),
  (gen_random_uuid(), 'AGENT_CREATE',           'AGENTS',        'Add an agent profile'),
  (gen_random_uuid(), 'AGENT_UPDATE',           'AGENTS',        'Change an agent profile or assignment'),
  (gen_random_uuid(), 'AGENT_DEACTIVATE',       'AGENTS',        'Deactivate an agent profile'),

  (gen_random_uuid(), 'LEAD_VIEW',              'LEADS',         'See leads'),
  (gen_random_uuid(), 'LEAD_CREATE',            'LEADS',         'Log a lead'),
  (gen_random_uuid(), 'LEAD_UPDATE',            'LEADS',         'Change a lead or move its stage'),
  (gen_random_uuid(), 'LEAD_DELETE',            'LEADS',         'Archive a lead'),
  (gen_random_uuid(), 'LEAD_ASSIGN',            'LEADS',         'Assign a lead to an agent'),
  (gen_random_uuid(), 'LEAD_CONVERT',           'LEADS',         'Convert a lead into a tenant, buyer or applicant'),

  (gen_random_uuid(), 'LISTING_VIEW',           'LISTINGS',      'See listings'),
  (gen_random_uuid(), 'LISTING_CREATE',         'LISTINGS',      'Create a listing'),
  (gen_random_uuid(), 'LISTING_UPDATE',         'LISTINGS',      'Change a listing or its asking price'),
  (gen_random_uuid(), 'LISTING_DELETE',         'LISTINGS',      'Archive a listing'),
  (gen_random_uuid(), 'LISTING_PUBLISH',        'LISTINGS',      'Move a listing between publication states'),

  (gen_random_uuid(), 'VIEWING_VIEW',           'VIEWINGS',      'See the viewing diary'),
  (gen_random_uuid(), 'VIEWING_CREATE',         'VIEWINGS',      'Schedule a viewing'),
  (gen_random_uuid(), 'VIEWING_UPDATE',         'VIEWINGS',      'Confirm, complete or mark a viewing'),
  (gen_random_uuid(), 'VIEWING_CANCEL',         'VIEWINGS',      'Cancel a viewing'),

  (gen_random_uuid(), 'APPLICATION_VIEW',       'APPLICATIONS',  'See rental applications'),
  (gen_random_uuid(), 'APPLICATION_CREATE',     'APPLICATIONS',  'Take a rental application'),
  (gen_random_uuid(), 'APPLICATION_SCREEN',     'APPLICATIONS',  'Record screening on an application'),
  (gen_random_uuid(), 'APPLICATION_DECIDE',     'APPLICATIONS',  'Approve or reject an application'),

  (gen_random_uuid(), 'LEASE_VIEW',             'LEASES',        'See leases'),
  (gen_random_uuid(), 'LEASE_CREATE',           'LEASES',        'Draft a lease'),
  (gen_random_uuid(), 'LEASE_UPDATE',           'LEASES',        'Change a lease''s terms'),
  (gen_random_uuid(), 'LEASE_ACTIVATE',         'LEASES',        'Activate a lease'),
  (gen_random_uuid(), 'LEASE_RENEW',            'LEASES',        'Renew a lease'),
  (gen_random_uuid(), 'LEASE_TERMINATE',        'LEASES',        'Terminate a lease'),

  (gen_random_uuid(), 'RENT_VIEW',              'RENT',          'See the rent roll and its invoices'),
  (gen_random_uuid(), 'RENT_GENERATE',          'RENT',          'Raise rent invoices for a period'),
  (gen_random_uuid(), 'RENT_UPDATE',            'RENT',          'Change an invoice or write it off'),

  (gen_random_uuid(), 'PAYMENT_VIEW',           'PAYMENTS',      'See receipts'),
  (gen_random_uuid(), 'PAYMENT_RECORD',         'PAYMENTS',      'Record a receipt'),
  (gen_random_uuid(), 'PAYMENT_VOID',           'PAYMENTS',      'Void a receipt'),
  (gen_random_uuid(), 'PAYMENT_REFUND',         'PAYMENTS',      'Refund a receipt'),

  (gen_random_uuid(), 'MAINTENANCE_VIEW',       'MAINTENANCE',   'See maintenance requests'),
  (gen_random_uuid(), 'MAINTENANCE_CREATE',     'MAINTENANCE',   'Raise a maintenance request'),
  (gen_random_uuid(), 'MAINTENANCE_ASSIGN',     'MAINTENANCE',   'Assign a request to a vendor or colleague'),
  (gen_random_uuid(), 'MAINTENANCE_UPDATE',     'MAINTENANCE',   'Work on a maintenance request'),
  (gen_random_uuid(), 'MAINTENANCE_RESOLVE',    'MAINTENANCE',   'Resolve or close a request'),

  (gen_random_uuid(), 'EXPENSE_VIEW',           'EXPENSES',      'See property expenses'),
  (gen_random_uuid(), 'EXPENSE_CREATE',         'EXPENSES',      'Record a property expense'),
  (gen_random_uuid(), 'EXPENSE_UPDATE',         'EXPENSES',      'Change a property expense'),
  (gen_random_uuid(), 'EXPENSE_DELETE',         'EXPENSES',      'Archive a property expense'),

  (gen_random_uuid(), 'SALE_VIEW',              'SALES',         'See the sales pipeline'),
  (gen_random_uuid(), 'SALE_CREATE',            'SALES',         'Open a sales opportunity'),
  (gen_random_uuid(), 'SALE_UPDATE',            'SALES',         'Change a sales opportunity or its stage'),
  (gen_random_uuid(), 'SALE_OFFER',             'SALES',         'Record or revise an offer'),
  (gen_random_uuid(), 'SALE_CLOSE',             'SALES',         'Close a sale as won or lost'),

  (gen_random_uuid(), 'DOCUMENT_VIEW',          'DOCUMENTS',     'See document records'),
  (gen_random_uuid(), 'DOCUMENT_CREATE',        'DOCUMENTS',     'Register a document'),
  (gen_random_uuid(), 'DOCUMENT_DELETE',        'DOCUMENTS',     'Archive a document record'),

  (gen_random_uuid(), 'PORTFOLIO_VIEW',         'PORTFOLIOS',    'See portfolios and their allocations'),
  (gen_random_uuid(), 'PORTFOLIO_CREATE',       'PORTFOLIOS',    'Create a portfolio'),
  (gen_random_uuid(), 'PORTFOLIO_UPDATE',       'PORTFOLIOS',    'Change a portfolio or its allocation'),
  (gen_random_uuid(), 'PORTFOLIO_DELETE',       'PORTFOLIOS',    'Archive a portfolio'),

  (gen_random_uuid(), 'CONVERSATION_VIEW',      'COMMUNICATION', 'See owner, tenant and prospect conversations'),
  (gen_random_uuid(), 'CONVERSATION_CREATE',    'COMMUNICATION', 'Log a conversation'),
  (gen_random_uuid(), 'CONVERSATION_REPLY',     'COMMUNICATION', 'Reply on a conversation'),
  (gen_random_uuid(), 'CONVERSATION_CLOSE',     'COMMUNICATION', 'Close or reopen a conversation'),

  (gen_random_uuid(), 'NOTIFICATION_VIEW',      'NOTIFICATIONS', 'See notifications'),

  (gen_random_uuid(), 'REPORT_VIEW',            'REPORTS',       'Run reports'),
  (gen_random_uuid(), 'DATA_IMPORT',            'DATA',          'Import properties, units, owners, tenants, leads, leases and receipts'),
  (gen_random_uuid(), 'AUDIT_VIEW',             'AUDIT',         'Read the audit trail'),

  (gen_random_uuid(), 'USER_VIEW',              'STAFF',         'See staff accounts'),
  (gen_random_uuid(), 'USER_CREATE',            'STAFF',         'Add a staff account'),
  (gen_random_uuid(), 'USER_UPDATE',            'STAFF',         'Change a staff account'),
  (gen_random_uuid(), 'USER_DEACTIVATE',        'STAFF',         'Deactivate a staff account'),

  (gen_random_uuid(), 'ROLE_VIEW',              'ROLES',         'See roles and their permissions'),
  (gen_random_uuid(), 'ROLE_UPDATE',            'ROLES',         'Change what a role may do'),

  (gen_random_uuid(), 'SETTINGS_VIEW',          'SETTINGS',      'See firm settings'),
  (gen_random_uuid(), 'SETTINGS_UPDATE',        'SETTINGS',      'Change firm settings');


-- ----------------------------------------------------------------------------
-- 2. Roles
-- ----------------------------------------------------------------------------
INSERT INTO roles (id, name, description, system_role, created_at, updated_at) VALUES
  (gen_random_uuid(), 'OWNER',            'The firm''s owner. Full access; cannot be locked out.', true, now(), now()),
  (gen_random_uuid(), 'ADMIN',            'Firm administration: staff, records and settings.', true, now(), now()),
  (gen_random_uuid(), 'PROPERTY_MANAGER', 'Portfolio manager: the whole operation, the rent roll and the numbers.', true, now(), now()),
  (gen_random_uuid(), 'LEASING_AGENT',    'Letting: leads, listings, viewings, applicants and tenancies.', true, now(), now()),
  (gen_random_uuid(), 'SALES_AGENT',      'Sales: buyers, the offer pipeline and the listings that carry them.', true, now(), now()),
  (gen_random_uuid(), 'ACCOUNTING',       'Accounts: the rent roll, receipts, refunds, expenses and a read-only audit trail.', true, now(), now()),
  (gen_random_uuid(), 'MAINTENANCE',      'The stock''s condition: faults, their assignment, resolution and cost.', true, now(), now()),
  (gen_random_uuid(), 'VIEWER',           'Read-only across the portfolio, for a board member, auditor or client.', true, now(), now());


-- ----------------------------------------------------------------------------
-- 3. What each role may do
-- ----------------------------------------------------------------------------
-- OWNER and ADMIN hold every permission. They differ in what can be done *to*
-- them: the last active OWNER cannot be deactivated, deleted, or have
-- permissions reduced.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('OWNER', 'ADMIN');

-- PROPERTY_MANAGER: everything except changing role definitions and
-- deactivating a colleague.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'PROPERTY_MANAGER'
  AND p.code <> ALL (ARRAY['ROLE_UPDATE', 'USER_DEACTIVATE']);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'LEASING_AGENT'
  AND p.code = ANY (ARRAY[
    'PROPERTY_VIEW', 'BUILDING_VIEW', 'UNIT_VIEW', 'UNIT_STATUS_CHANGE',
    'OWNER_VIEW', 'TENANT_VIEW', 'TENANT_CREATE', 'TENANT_UPDATE',
    'BUYER_VIEW', 'BUYER_CREATE', 'BUYER_UPDATE',
    'LEAD_VIEW', 'LEAD_CREATE', 'LEAD_UPDATE', 'LEAD_ASSIGN', 'LEAD_CONVERT',
    'LISTING_VIEW', 'LISTING_CREATE', 'LISTING_UPDATE', 'LISTING_PUBLISH',
    'VIEWING_VIEW', 'VIEWING_CREATE', 'VIEWING_UPDATE', 'VIEWING_CANCEL',
    'APPLICATION_VIEW', 'APPLICATION_CREATE', 'APPLICATION_SCREEN', 'APPLICATION_DECIDE',
    'LEASE_VIEW', 'LEASE_CREATE', 'LEASE_UPDATE', 'LEASE_ACTIVATE', 'LEASE_RENEW',
    'RENT_VIEW', 'PAYMENT_VIEW', 'PAYMENT_RECORD',
    'MAINTENANCE_VIEW', 'MAINTENANCE_CREATE',
    'DOCUMENT_VIEW', 'DOCUMENT_CREATE', 'PORTFOLIO_VIEW',
    'CONVERSATION_VIEW', 'CONVERSATION_CREATE', 'CONVERSATION_REPLY',
    'NOTIFICATION_VIEW', 'REPORT_VIEW']);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'SALES_AGENT'
  AND p.code = ANY (ARRAY[
    'PROPERTY_VIEW', 'BUILDING_VIEW', 'UNIT_VIEW',
    'OWNER_VIEW', 'BUYER_VIEW', 'BUYER_CREATE', 'BUYER_UPDATE',
    'LEAD_VIEW', 'LEAD_CREATE', 'LEAD_UPDATE', 'LEAD_ASSIGN', 'LEAD_CONVERT',
    'LISTING_VIEW', 'LISTING_CREATE', 'LISTING_UPDATE', 'LISTING_PUBLISH',
    'VIEWING_VIEW', 'VIEWING_CREATE', 'VIEWING_UPDATE', 'VIEWING_CANCEL',
    'SALE_VIEW', 'SALE_CREATE', 'SALE_UPDATE', 'SALE_OFFER', 'SALE_CLOSE',
    'DOCUMENT_VIEW', 'DOCUMENT_CREATE', 'PORTFOLIO_VIEW',
    'CONVERSATION_VIEW', 'CONVERSATION_CREATE', 'CONVERSATION_REPLY',
    'NOTIFICATION_VIEW', 'REPORT_VIEW']);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ACCOUNTING'
  AND p.code = ANY (ARRAY[
    'PROPERTY_VIEW', 'BUILDING_VIEW', 'UNIT_VIEW', 'OWNER_VIEW', 'TENANT_VIEW',
    'LEASE_VIEW', 'RENT_VIEW', 'RENT_GENERATE', 'RENT_UPDATE',
    'PAYMENT_VIEW', 'PAYMENT_RECORD', 'PAYMENT_VOID', 'PAYMENT_REFUND',
    'EXPENSE_VIEW', 'EXPENSE_CREATE', 'EXPENSE_UPDATE', 'EXPENSE_DELETE',
    'MAINTENANCE_VIEW', 'SALE_VIEW',
    'DOCUMENT_VIEW', 'DOCUMENT_CREATE', 'PORTFOLIO_VIEW',
    'CONVERSATION_VIEW', 'NOTIFICATION_VIEW', 'REPORT_VIEW', 'AUDIT_VIEW']);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'MAINTENANCE'
  AND p.code = ANY (ARRAY[
    'PROPERTY_VIEW', 'BUILDING_VIEW', 'UNIT_VIEW', 'UNIT_STATUS_CHANGE',
    'TENANT_VIEW', 'LEASE_VIEW', 'OWNER_VIEW',
    'MAINTENANCE_VIEW', 'MAINTENANCE_CREATE', 'MAINTENANCE_ASSIGN',
    'MAINTENANCE_UPDATE', 'MAINTENANCE_RESOLVE',
    'EXPENSE_VIEW', 'EXPENSE_CREATE',
    'DOCUMENT_VIEW', 'DOCUMENT_CREATE', 'PORTFOLIO_VIEW',
    'CONVERSATION_VIEW', 'NOTIFICATION_VIEW', 'REPORT_VIEW']);

-- VIEWER: every read, and nothing that writes. Built from the permission table
-- rather than listed by hand, so a permission added for a new module is granted
-- to the read-only role with the same `_VIEW` suffix automatically - and so a
-- write can never be granted here by a typo.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'VIEWER'
  AND (p.code LIKE '%\_VIEW' ESCAPE '\' OR p.code = 'NOTIFICATION_VIEW');


-- ----------------------------------------------------------------------------
-- 4. Staff accounts
-- ----------------------------------------------------------------------------
-- Ten accounts so every role in the matrix can be signed in and verified, and so
-- the demonstration portfolio has real people attached to its leases, viewings,
-- receipts and maintenance rather than a single anonymous actor.

INSERT INTO users (id, staff_code, first_name, last_name, email, phone, password_hash,
                   department, designation, status, force_password_reset, created_at, updated_at) VALUES
  (gen_random_uuid(), 'ME-0001', 'Aditya',  'Raghunathan', 'owner@meridianestates.example',       '+91-98200-20001',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Executive',      'Managing Partner',        'ACTIVE', false, now(), now()),
  (gen_random_uuid(), 'ME-0002', 'Priyanka', 'Sethi',       'admin@meridianestates.example',       '+91-98200-20002',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Administration', 'Firm Administrator',      'ACTIVE', false, now(), now()),
  (gen_random_uuid(), 'ME-0003', 'Rohan',    'Malhotra',    'manager@meridianestates.example',     '+91-98200-20003',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Portfolio',      'Head of Portfolio',       'ACTIVE', false, now(), now()),
  (gen_random_uuid(), 'ME-0004', 'Sneha',    'Kulkarni',    'leasing@meridianestates.example',     '+91-98200-20004',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Letting',        'Senior Leasing Agent',    'ACTIVE', false, now(), now()),
  (gen_random_uuid(), 'ME-0005', 'Imran',    'Sheikh',      'leasing2@meridianestates.example',    '+91-98200-20005',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Letting',        'Leasing Agent',           'ACTIVE', false, now(), now()),
  (gen_random_uuid(), 'ME-0006', 'Kavya',    'Reddy',       'sales@meridianestates.example',       '+91-98200-20006',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Capital Markets','Head of Sales',           'ACTIVE', false, now(), now()),
  (gen_random_uuid(), 'ME-0007', 'Farhan',   'Qureshi',     'sales2@meridianestates.example',      '+91-98200-20007',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Capital Markets','Investment Sales Agent',  'ACTIVE', false, now(), now()),
  (gen_random_uuid(), 'ME-0008', 'Deepa',    'Venkatesan',  'accounts@meridianestates.example',    '+91-98200-20008',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Finance',        'Financial Controller',    'ACTIVE', false, now(), now()),
  (gen_random_uuid(), 'ME-0009', 'Ganesh',   'Patil',       'maintenance@meridianestates.example', '+91-98200-20009',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Facilities',     'Head of Facilities',      'ACTIVE', false, now(), now()),
  (gen_random_uuid(), 'ME-0010', 'Lata',     'Deshmukh',    'viewer@meridianestates.example',      '+91-98200-20010',
   '$2a$10$ZadqoLiE3QyLOcza5KmjKO0tU6Rg3AGAWfvgD65DLCP47DUpcem6y', 'Advisory',       'Client Reporting Lead',   'ACTIVE', false, now(), now());

-- The VALUES list is the driving table here: a joined table is only in scope for
-- ON clauses that come after it, so users and roles are joined onto it in turn
-- rather than referring to it from a join that precedes it.
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM (VALUES
  ('owner@meridianestates.example',       'OWNER'),
  ('admin@meridianestates.example',       'ADMIN'),
  ('manager@meridianestates.example',     'PROPERTY_MANAGER'),
  ('leasing@meridianestates.example',     'LEASING_AGENT'),
  ('leasing2@meridianestates.example',    'LEASING_AGENT'),
  ('sales@meridianestates.example',       'SALES_AGENT'),
  ('sales2@meridianestates.example',      'SALES_AGENT'),
  ('accounts@meridianestates.example',    'ACCOUNTING'),
  ('maintenance@meridianestates.example', 'MAINTENANCE'),
  ('viewer@meridianestates.example',      'VIEWER')
) AS v (email, role_name)
JOIN users u ON u.email = v.email
JOIN roles r ON r.name = v.role_name;


-- ----------------------------------------------------------------------------
-- 5. Business number series
-- ----------------------------------------------------------------------------
INSERT INTO business_number_sequences (id, code, prefix, next_value, padding, updated_at) VALUES
  (gen_random_uuid(), 'PROPERTY',    'ME-P-',   1, 5, now()),
  (gen_random_uuid(), 'UNIT',        'ME-U-',   1, 6, now()),
  (gen_random_uuid(), 'OWNER',       'ME-O-',   1, 5, now()),
  (gen_random_uuid(), 'TENANT',      'ME-T-',   1, 5, now()),
  (gen_random_uuid(), 'LEAD',        'ME-LD-',  1, 6, now()),
  (gen_random_uuid(), 'LISTING',     'ME-LS-',  1, 6, now()),
  (gen_random_uuid(), 'VIEWING',     'ME-V-',   1, 6, now()),
  (gen_random_uuid(), 'APPLICATION', 'ME-A-',   1, 5, now()),
  (gen_random_uuid(), 'LEASE',       'ME-L-',   1, 5, now()),
  (gen_random_uuid(), 'INVOICE',     'ME-R-',   1, 6, now()),
  (gen_random_uuid(), 'RECEIPT',     'ME-PAY-', 1, 6, now()),
  (gen_random_uuid(), 'MAINTENANCE', 'ME-M-',   1, 5, now()),
  (gen_random_uuid(), 'SALE',        'ME-S-',   1, 5, now());


-- ----------------------------------------------------------------------------
-- 6. Guard: the identity seed must be complete before anything else is built on it
-- ----------------------------------------------------------------------------
-- A partial seed would leave a role without its permissions or an account without
-- a role - a state that would look fine until someone tried to sign in or was
-- unexpectedly refused an operation. Failing the migration here is the honest
-- outcome.
DO $$
DECLARE
  permission_count integer;
  role_count       integer;
  staff_count      integer;
  unlinked_roles   integer;
  unlinked_users   integer;
  viewer_writes    integer;
BEGIN
  SELECT count(*) INTO permission_count FROM permissions;
  SELECT count(*) INTO role_count FROM roles;
  SELECT count(*) INTO staff_count FROM users;

  IF permission_count < 95 THEN
    RAISE EXCEPTION 'Expected at least 95 permissions, found %', permission_count;
  END IF;
  IF role_count <> 8 THEN
    RAISE EXCEPTION 'Expected 8 roles, found %', role_count;
  END IF;
  IF staff_count <> 10 THEN
    RAISE EXCEPTION 'Expected 10 staff accounts, found %', staff_count;
  END IF;

  SELECT count(*) INTO unlinked_roles FROM roles r
   WHERE NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id);
  IF unlinked_roles > 0 THEN
    RAISE EXCEPTION '% role(s) have no permissions', unlinked_roles;
  END IF;

  SELECT count(*) INTO unlinked_users FROM users u
   WHERE NOT EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id);
  IF unlinked_users > 0 THEN
    RAISE EXCEPTION '% staff account(s) have no role', unlinked_users;
  END IF;

  -- The read-only role must not hold a single writing capability.
  SELECT count(*) INTO viewer_writes
    FROM role_permissions rp
    JOIN roles r ON r.id = rp.role_id
    JOIN permissions p ON p.id = rp.permission_id
   WHERE r.name = 'VIEWER' AND p.code NOT LIKE '%\_VIEW' ESCAPE '\';
  IF viewer_writes > 0 THEN
    RAISE EXCEPTION 'The VIEWER role holds % non-view permission(s)', viewer_writes;
  END IF;
END $$;
