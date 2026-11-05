-- ============================================================================
-- V23 - Seed the letting, ledger and operational history
-- ============================================================================
-- The transactional half of the demonstration dataset: the tenants, the leads,
-- the viewings and applications that filled the units, the leases those produced,
-- fourteen months of rent invoices and the receipts against them, the maintenance
-- and expenses they cost, the sales pipeline, the document register and the
-- correspondence.
--
-- ----------------------------------------------------------------------------
-- EVERYTHING HERE IS DATED RELATIVE TO CURRENT_DATE
-- ----------------------------------------------------------------------------
-- Nothing in this file names a fixed calendar date. Leases start relative to
-- today, invoices fall in the last fourteen months, viewings are scheduled around
-- this week. That is what keeps the demonstration dataset permanently current: a
-- fixed date would mean that within a year every lease had expired, every chart
-- was empty, and the "expiring soon" rail was full of rows that expired long ago.
--
-- It also removes the need for a separate re-anchoring migration.
--
-- ----------------------------------------------------------------------------
-- THE LEDGER IS INTERNALLY CONSISTENT, AND THAT IS THE POINT
-- ----------------------------------------------------------------------------
-- The invoice, receipt and allocation rows here are generated so that the derived
-- figures come out to real values rather than to whatever the arithmetic happens
-- to produce:
--
--   * every OCCUPIED unit has exactly one running lease and no other unit does,
--     so occupancy computed from units and occupancy computed from leases agree;
--   * every invoice's total is its subtotal plus its tax, and its lines sum to
--     its subtotal - both enforced by checks on the tables themselves;
--   * a receipt is allocated against the invoice it settles, and never beyond
--     either the invoice's total or the receipt's own amount;
--   * older invoices are mostly settled, recent ones are partly outstanding, and a
--     realistic minority is genuinely overdue - so the collections chart, the
--     arrears figure and the "needs attention" rail all have something real to
--     say;
--   * deposits are held on the lease and appear in no income figure.
--
-- ----------------------------------------------------------------------------
-- A NOTE ON THE COUNTERS
-- ----------------------------------------------------------------------------
-- Each entity is numbered from its own counter rather than from one shared
-- variable. A shared counter is what produced duplicate invoice numbers in an
-- earlier draft of this migration: the lease loop had advanced it, the invoice
-- loop never did, and every lease billed under the same fourteen numbers. The
-- unique indexes on those columns are what caught it, which is the point of
-- having them.
-- ============================================================================

DO $$
DECLARE
  rec record;
  c_tenant integer := 0;
  c_buyer integer := 0;
  c_lead integer := 0;
  c_lease integer := 0;
  c_invoice integer := 0;
  c_payment integer := 0;
  c_application integer := 0;
  c_listing integer := 0;
  c_viewing integer := 0;
  c_maint integer := 0;
  c_expense integer := 0;
  c_sale integer := 0;
  c_offer integer := 0;
  c_doc integer := 0;
  c_conv integer := 0;
  i integer;
  m integer;
  v_months constant integer := 14;

  v_lease_id uuid;
  v_invoice_id uuid;
  v_payment_id uuid;
  v_tenant_id uuid;
  v_rent numeric(12,2);
  v_deposit numeric(12,2);
  v_tax_rate numeric(5,2);
  v_subtotal numeric(14,2);
  v_tax numeric(14,2);
  v_month_rent numeric(14,2);
  v_pay numeric(14,2);
  v_period_start date;
  v_lease_start date;
  v_lease_end date;
  v_settlement text;
  v_occupied integer;
  v_running_leases integer;
  v_view_status text;
BEGIN

  -- ==========================================================================
  -- 1. Name pools
  -- ==========================================================================
  -- The index column is named `n`, not `i`, on purpose. These tables are joined inside
  -- PL/pgSQL loops whose counter is called `i`, and PostgreSQL resolves a bare name that
  -- matches both a variable and a column as an error rather than a guess - so a column
  -- named `i` here makes every `(i * 7)` in the same query ambiguous and the migration
  -- fails outright. The counter keeps the natural name; the lookup column does not need it.
  CREATE TEMP TABLE seed_first (n integer, name text) ON COMMIT DROP;
  INSERT INTO seed_first VALUES
    (1,'Aarav'),(2,'Ishita'),(3,'Rohan'),(4,'Meera'),(5,'Kabir'),(6,'Ananya'),(7,'Vivaan'),(8,'Diya'),
    (9,'Arjun'),(10,'Saanvi'),(11,'Aditya'),(12,'Nisha'),(13,'Rehan'),(14,'Pooja'),(15,'Karan'),(16,'Tanvi'),
    (17,'Siddharth'),(18,'Riya'),(19,'Nikhil'),(20,'Aisha'),(21,'Manish'),(22,'Shreya'),(23,'Varun'),(24,'Kavya'),
    (25,'Harsh'),(26,'Priya'),(27,'Devansh'),(28,'Neha'),(29,'Raghav'),(30,'Simran');

  CREATE TEMP TABLE seed_last (n integer, name text) ON COMMIT DROP;
  INSERT INTO seed_last VALUES
    (1,'Sharma'),(2,'Verma'),(3,'Iyer'),(4,'Patel'),(5,'Reddy'),(6,'Nair'),(7,'Kulkarni'),(8,'Bose'),
    (9,'Menon'),(10,'Gupta'),(11,'Desai'),(12,'Rao'),(13,'Joshi'),(14,'Pillai'),(15,'Chatterjee'),(16,'Sinha'),
    (17,'Bhatt'),(18,'Naidu'),(19,'Shetty'),(20,'Mishra'),(21,'Kapoor'),(22,'Banerjee'),(23,'Chauhan'),(24,'Agarwal'),
    (25,'Fernandes'),(26,'Dutta'),(27,'Saxena'),(28,'Krishnan'),(29,'Malhotra'),(30,'Sethi');

  CREATE TEMP TABLE seed_company (n integer, name text) ON COMMIT DROP;
  INSERT INTO seed_company VALUES
    (1,'Trident Software Services Private Limited'), (2,'Bluewave Analytics India Private Limited'),
    (3,'Kestrel Consulting LLP'), (4,'Northstar Financial Advisory Private Limited'),
    (5,'Vantage Retail Ventures Private Limited'), (6,'Helios Engineering Works Private Limited'),
    (7,'Sundial Media Networks Private Limited'), (8,'Cobalt Health Technologies Private Limited'),
    (9,'Ironwood Supply Chain Private Limited'), (10,'Lumina Education Services Private Limited');


  -- ==========================================================================
  -- 2. Tenants
  -- ==========================================================================
  -- Every fifth tenant is a company. A real book lets to both, and the tenant
  -- type distributions in the reports are only meaningful if both exist.
  FOR i IN 1..60 LOOP
    c_tenant := c_tenant + 1;

    IF i % 5 = 0 THEN
      INSERT INTO tenants (id, tenant_code, tenant_type, display_name, primary_contact_name, email, phone,
                           tax_reference, registration_number, address_line1, locality, city, state,
                           postal_code, country, status, created_at, updated_at)
      SELECT gen_random_uuid(), 'ME-T-' || lpad(c_tenant::text, 5, '0'), 'COMPANY', c.name,
             f.name || ' ' || l.name,
             'accounts+' || c_tenant || '@tenant.example',
             '+91-98200-' || lpad((40000 + c_tenant)::text, 5, '0'),
             'AABCT' || lpad((1000000 + c_tenant)::text, 7, '0'),
             'U72900MH' || (2000 + (i % 20))::text || 'PTC' || lpad(c_tenant::text, 5, '0'),
             (100 + c_tenant)::text || ' Corporate Park', 'Andheri East', 'Mumbai', 'Maharashtra',
             '400093', 'India', 'ACTIVE', now(), now()
      FROM seed_first f, seed_last l, seed_company c
      WHERE f.n = ((i * 7) % 30) + 1 AND l.n = ((i * 11) % 30) + 1 AND c.n = (i % 10) + 1;
    ELSE
      INSERT INTO tenants (id, tenant_code, tenant_type, display_name, email, phone, id_reference,
                           occupation, employer, annual_income, address_line1, locality, city, state,
                           postal_code, country, status, created_at, updated_at)
      SELECT gen_random_uuid(), 'ME-T-' || lpad(c_tenant::text, 5, '0'), 'INDIVIDUAL',
             f.name || ' ' || l.name,
             'tenant' || c_tenant || '@example.com',
             '+91-98200-' || lpad((40000 + c_tenant)::text, 5, '0'),
             'AADHAAR-X' || lpad((4000 + c_tenant)::text, 4, '0'),
             (ARRAY['Software Engineer','Chartered Accountant','Doctor','Architect','Teacher',
                    'Business Owner','Consultant','Banker','Designer','Civil Engineer'])[1 + (i % 10)],
             (ARRAY['Trident Software Services','Bluewave Analytics','Kestrel Consulting',
                    'Northstar Financial','Sundial Media','Cobalt Health','Self-employed',
                    'Lumina Education','Helios Engineering','Ironwood Supply Chain'])[1 + ((i * 3) % 10)],
             (600000 + ((i * 137000) % 2400000))::numeric,
             (10 + c_tenant)::text || ' Residency Road', 'Koramangala', 'Bengaluru', 'Karnataka',
             '560034', 'India', 'ACTIVE', now(), now()
      FROM seed_first f, seed_last l
      WHERE f.n = ((i * 7) % 30) + 1 AND l.n = ((i * 11) % 30) + 1;
    END IF;
  END LOOP;


  -- ==========================================================================
  -- 3. Buyers and their briefs
  -- ==========================================================================
  FOR i IN 1..25 LOOP
    c_buyer := c_buyer + 1;

    INSERT INTO buyers (id, buyer_code, buyer_type, display_name, primary_contact_name, email, phone,
                        tax_reference, city, state, country, status, created_at, updated_at)
    SELECT gen_random_uuid(), 'ME-B-' || lpad(c_buyer::text, 4, '0'),
           CASE WHEN i % 6 = 0 THEN 'NRI' WHEN i % 4 = 0 THEN 'COMPANY' ELSE 'INDIVIDUAL' END,
           CASE WHEN i % 4 = 0 THEN c.name ELSE f.name || ' ' || l.name END,
           f.name || ' ' || l.name,
           'buyer' || c_buyer || '@example.com',
           '+91-98200-' || lpad((50000 + c_buyer)::text, 5, '0'),
           'ABCPB' || lpad((2000000 + c_buyer)::text, 7, '0'),
           (ARRAY['Mumbai','Pune','Bengaluru','Hyderabad','Gurugram','Chennai'])[1 + (i % 6)],
           'India', 'India', 'ACTIVE', now(), now()
    FROM seed_first f, seed_last l, seed_company c
    WHERE f.n = ((i * 13) % 30) + 1 AND l.n = ((i * 17) % 30) + 1 AND c.n = (i % 10) + 1;
  END LOOP;

  -- One active brief each. A company briefs for commercial space, an individual
  -- for a home, so the requirements are genuinely different rows rather than the
  -- same row with different numbers.
  INSERT INTO buyer_requirements (id, buyer_id, property_type_id, preferred_city, min_budget, max_budget,
                                  min_bedrooms, min_area_sqft, purpose, is_active, notes, created_at, updated_at)
  SELECT gen_random_uuid(), b.id, pt.id, b.city,
         (5000000 + ((row_number() OVER (ORDER BY b.buyer_code) * 1750000) % 25000000))::numeric(14,2),
         (20000000 + ((row_number() OVER (ORDER BY b.buyer_code) * 3100000) % 60000000))::numeric(14,2),
         CASE WHEN b.buyer_type = 'COMPANY' THEN NULL ELSE 2 + (length(b.buyer_code) % 3) END,
         CASE WHEN b.buyer_type = 'COMPANY' THEN 4000 ELSE 900 END,
         CASE WHEN b.buyer_type = 'COMPANY' THEN 'INVESTMENT' ELSE 'OWN_OCCUPANCY' END,
         true, NULL, now(), now()
  FROM buyers b
  JOIN property_types pt ON pt.code = CASE WHEN b.buyer_type = 'COMPANY'
                                           THEN 'COMMERCIAL_OFFICE' ELSE 'RESIDENTIAL_APARTMENT' END
  WHERE b.deleted_at IS NULL;


  -- ==========================================================================
  -- 4. Vendors
  -- ==========================================================================
  INSERT INTO vendors (id, vendor_code, name, category, contact_name, phone, email, tax_reference,
                       city, state, country, is_empanelled, rating, payment_terms_days, status,
                       created_at, updated_at) VALUES
    (gen_random_uuid(),'ME-V-001','Ashwin Plumbing Services','PLUMBING','Ashwin Kale','+91-98200-70001','contact@ashwinplumbing.example','AABCA1234P','Mumbai','Maharashtra','India',true,4,30,'ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-V-002','Voltasafe Electricals','ELECTRICAL','Prakash Rane','+91-98200-70002','service@voltasafe.example','AABCV2345E','Mumbai','Maharashtra','India',true,5,30,'ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-V-003','Coolair HVAC Solutions','HVAC','Imtiaz Baig','+91-98200-70003','support@coolair.example','AABCC3456H','Pune','Maharashtra','India',true,4,45,'ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-V-004','Sterling Civil Works','CIVIL','Ramesh Yadav','+91-98200-70004','office@sterlingcivil.example','AABCS4567C','Bengaluru','Karnataka','India',true,3,45,'ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-V-005','ColourCraft Painters','PAINTING','Suresh Pillai','+91-98200-70005','hello@colourcraft.example','AABCC5678T','Bengaluru','Karnataka','India',true,4,15,'ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-V-006','PestGuard India','PEST_CONTROL','Anita Rao','+91-98200-70006','care@pestguard.example','AABCP6789G','Hyderabad','Telangana','India',true,5,15,'ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-V-007','Elevate Lift Services','LIFT','Girish Nambiar','+91-98200-70007','service@elevatelifts.example','AABCE7890L','Mumbai','Maharashtra','India',true,5,30,'ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-V-008','Sentinel Fire Systems','FIRE_SAFETY','Deepak Joshi','+91-98200-70008','compliance@sentinelfire.example','AABCS8901F','Gurugram','Haryana','India',true,4,30,'ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-V-009','GreenScape Landscaping','LANDSCAPING','Meenakshi Iyer','+91-98200-70009','info@greenscape.example','AABCG9012K','Chennai','Tamil Nadu','India',true,3,15,'ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-V-010','Omni Facility Services','GENERAL','Bharat Solanki','+91-98200-70010','ops@omnifacility.example','AABCO0123M','Ahmedabad','Gujarat','India',true,4,30,'ACTIVE',now(),now());


  -- ==========================================================================
  -- 5. Leads, their assignments, activities and follow-ups
  -- ==========================================================================
  FOR i IN 1..30 LOOP
    DECLARE
      v_stage text := (ARRAY['NEW','CONTACTED','QUALIFIED','VIEWING','NEGOTIATION','WON','LOST'])[1 + ((i * 7) % 7)];
      v_agent uuid;
      v_lead_id uuid;
      v_won timestamptz;
      v_lost timestamptz;
    BEGIN
      c_lead := c_lead + 1;

      SELECT ap.id INTO v_agent FROM agent_profiles ap
       WHERE ap.agent_code = CASE WHEN i % 3 = 0 THEN 'ME-AG-004' ELSE 'ME-AG-002' END;

      v_won := CASE WHEN v_stage = 'WON' THEN now() - ((i % 45) + 5) * INTERVAL '1 day' END;
      v_lost := CASE WHEN v_stage = 'LOST' THEN now() - ((i % 45) + 5) * INTERVAL '1 day' END;

      INSERT INTO leads (id, lead_code, lead_type, source_id, stage, priority, display_name, company_name,
                         email, phone, property_type_id, preferred_city, min_budget, max_budget, min_bedrooms,
                         property_id, owner_agent_id, office_id, next_action, next_action_at, expected_value,
                         won_at, lost_at, lost_reason, created_at, updated_at)
      SELECT gen_random_uuid(), 'ME-LD-' || lpad(c_lead::text, 6, '0'),
             CASE WHEN i % 4 = 0 THEN 'SALE' ELSE 'RENTAL' END,
             (SELECT id FROM lead_sources WHERE code = (ARRAY['PORTAL_99ACRES','PORTAL_MAGICBRICKS','WEBSITE',
                     'REFERRAL_CLIENT','BROKER','WALK_IN','CAMPAIGN','EXISTING_TENANT'])[1 + (i % 8)]),
             v_stage,
             CASE WHEN i % 9 = 0 THEN 'URGENT' WHEN i % 3 = 0 THEN 'HIGH'
                  WHEN i % 7 = 0 THEN 'LOW' ELSE 'MEDIUM' END,
             f.name || ' ' || l.name,
             CASE WHEN i % 6 = 0 THEN c.name END,
             'lead' || c_lead || '@example.com',
             '+91-98200-' || lpad((60000 + c_lead)::text, 5, '0'),
             (SELECT id FROM property_types WHERE code = CASE WHEN i % 4 = 0
                     THEN 'COMMERCIAL_OFFICE' ELSE 'RESIDENTIAL_APARTMENT' END),
             (ARRAY['Mumbai','Pune','Bengaluru','Hyderabad','Gurugram','Chennai'])[1 + (i % 6)],
             40000, 180000, 2,
             (SELECT id FROM properties WHERE property_code = (ARRAY['ME-P-00002','ME-P-00003','ME-P-00008',
                     'ME-P-00010','ME-P-00013','ME-P-00006','ME-P-00007'])[1 + (i % 7)]),
             v_agent,
             (SELECT id FROM offices WHERE office_code = 'ME-HO'),
             CASE v_stage WHEN 'NEW' THEN 'Make first contact'
                          WHEN 'CONTACTED' THEN 'Qualify requirement'
                          WHEN 'QUALIFIED' THEN 'Arrange a viewing'
                          WHEN 'VIEWING' THEN 'Collect viewing feedback'
                          WHEN 'NEGOTIATION' THEN 'Agree terms' END,
             CASE WHEN v_stage IN ('WON','LOST') THEN NULL
                  ELSE now() + ((i % 14) - 4) * INTERVAL '1 day' END,
             (45000 + ((i * 8500) % 180000))::numeric(14,2),
             v_won, v_lost,
             CASE WHEN v_stage = 'LOST' THEN (ARRAY['Budget mismatch','Chose a competing property',
                     'Requirement withdrawn','Failed to qualify','Timeline slipped'])[1 + (i % 5)] END,
             now() - ((i % 90) + 10) * INTERVAL '1 day',
             now() - (i % 30) * INTERVAL '1 day'
      FROM seed_first f, seed_last l, seed_company c
      WHERE f.n = ((i * 5) % 30) + 1 AND l.n = ((i * 19) % 30) + 1 AND c.n = (i % 10) + 1;

      SELECT id INTO v_lead_id FROM leads WHERE lead_code = 'ME-LD-' || lpad(c_lead::text, 6, '0');

      -- The assignment history, plus the open row that agrees with
      -- leads.owner_agent_id - the two must not disagree, and the partial unique
      -- index on the history table enforces that there is only one open row.
      INSERT INTO lead_assignments (id, lead_id, agent_id, assigned_at, assigned_by, unassigned_at,
                                    reason, created_at, updated_at)
      VALUES (gen_random_uuid(), v_lead_id, v_agent,
              now() - ((i % 60) + 12) * INTERVAL '1 day',
              (SELECT id FROM users WHERE email = 'manager@meridianestates.example'),
              NULL, 'Seeded assignment.', now(), now());

      -- Two or three activities, so a lead's timeline has real content.
      IF v_stage <> 'NEW' THEN
        INSERT INTO lead_activities (id, lead_id, activity_type, subject, notes, occurred_at,
                                     performed_by, created_at, updated_at)
        SELECT gen_random_uuid(), v_lead_id,
               (ARRAY['CALL','EMAIL','MEETING','MESSAGE','SITE_VISIT'])[1 + ((i + g) % 5)],
               (ARRAY['Initial enquiry logged','Requirement discussed','Sent matching options',
                      'Followed up on requirements','Site visit arranged'])[1 + ((i + g) % 5)],
               NULL,
               now() - ((i % 50) + (g * 6)) * INTERVAL '1 day',
               (SELECT id FROM users WHERE email = CASE WHEN i % 3 = 0
                  THEN 'sales@meridianestates.example' ELSE 'leasing@meridianestates.example' END),
               now(), now()
        FROM generate_series(1, 3) g;
      END IF;

      -- Follow-ups: some pending, some done, some missed. A diary where every
      -- entry is green is a diary nobody needs.
      IF v_stage NOT IN ('WON','LOST') THEN
        INSERT INTO lead_followups (id, lead_id, due_at, channel, notes, status, assigned_to,
                                    completed_at, outcome, created_at, updated_at)
        SELECT gen_random_uuid(), v_lead_id,
               now() + (((i + g) % 12) - 6) * INTERVAL '1 day',
               (ARRAY['CALL','EMAIL','MEETING','SITE_VISIT'])[1 + ((i + g) % 4)],
               'Seeded follow-up.',
               v_fu.status, v_agent,
               CASE WHEN v_fu.status = 'DONE' THEN now() - ((i % 8) + 1) * INTERVAL '1 day' END,
               CASE WHEN v_fu.status = 'DONE' THEN 'Discussed and closed out.' END,
               now(), now()
        FROM generate_series(1, 2) g
        CROSS JOIN LATERAL (
          SELECT (ARRAY['PENDING','DONE','PENDING','MISSED'])[1 + ((i + g) % 4)] AS status
        ) AS v_fu;
      END IF;
    END;
  END LOOP;


  -- ==========================================================================
  -- 6. Leases — one per occupied unit, and for no other unit
  -- ==========================================================================
  -- Occupancy is read from the unit's own state, so a lease exists exactly where
  -- a unit says it is let. There is no second, independent decision about which
  -- units are occupied, which is what makes the two occupancy figures agree.
  FOR rec IN
    SELECT u.id AS unit_id, u.property_id, u.asking_rent, pt.code AS type_code
    FROM units u
    JOIN properties p ON p.id = u.property_id
    JOIN property_types pt ON pt.id = p.property_type_id
    WHERE u.status = 'OCCUPIED' AND u.deleted_at IS NULL
    ORDER BY u.unit_code
  LOOP
    c_lease := c_lease + 1;
    v_rent := COALESCE(rec.asking_rent, 100000);
    v_deposit := round(v_rent * 2, 2);

    -- A tenancy that started between one month and roughly three years ago with a
    -- standard eleven-month term, so some are close to expiry - which is what
    -- gives the expiry report and the renewal diary real content.
    v_lease_start := CURRENT_DATE - (((c_lease * 37) % 1000) + 30);
    v_lease_end := v_lease_start + INTERVAL '11 months';

    SELECT id INTO v_tenant_id FROM tenants
     WHERE tenant_code = 'ME-T-' || lpad(((c_lease % 60) + 1)::text, 5, '0');

    INSERT INTO leases (id, lease_code, property_id, unit_id, tenant_id, primary_owner_id, status,
                        start_date, end_date, rent_amount, rent_frequency, due_day, escalation_percent,
                        deposit_amount, deposit_received_at, notice_period_days, agreement_reference,
                        signed_at, activated_at, created_at, updated_at)
    SELECT gen_random_uuid(), 'ME-L-' || lpad(c_lease::text, 5, '0'), rec.property_id, rec.unit_id,
           v_tenant_id,
           (SELECT po.owner_id FROM property_ownerships po
             WHERE po.property_id = rec.property_id AND po.effective_to IS NULL AND po.deleted_at IS NULL
             ORDER BY po.ownership_percentage DESC LIMIT 1),
           'ACTIVE', v_lease_start, v_lease_end, v_rent, 'MONTHLY', 5, 5.00,
           v_deposit, (v_lease_start - 3)::timestamp, 30,
           'Lease deed dated ' || to_char(v_lease_start - 5, 'DD Mon YYYY') || '.',
           (v_lease_start - 5)::timestamp, (v_lease_start - 2)::timestamp, now(), now();

    SELECT id INTO v_lease_id FROM leases WHERE lease_code = 'ME-L-' || lpad(c_lease::text, 5, '0');

    -- The rent schedule. One open-ended term at the rent the lease records, so
    -- "the rent in force today" and the lease's own figure cannot disagree.
    INSERT INTO lease_terms (id, lease_id, term_number, effective_from, effective_to, rent_amount,
                             escalation_percent, notes, created_at, updated_at)
    VALUES (gen_random_uuid(), v_lease_id, 1, v_lease_start, NULL, v_rent, 5.00,
            'Initial term.', now(), now());

    INSERT INTO lease_status_history (id, lease_id, from_status, to_status, reason, changed_by, changed_at)
    VALUES (gen_random_uuid(), v_lease_id, NULL, 'DRAFT', 'Lease drafted.', NULL, (v_lease_start - 5)::timestamp),
           (gen_random_uuid(), v_lease_id, 'DRAFT', 'PENDING', 'Sent for signature.', NULL, (v_lease_start - 3)::timestamp),
           (gen_random_uuid(), v_lease_id, 'PENDING', 'ACTIVE', 'Lease executed and activated.', NULL, (v_lease_start - 2)::timestamp);

    INSERT INTO unit_status_history (id, unit_id, from_status, to_status, reason, changed_by, changed_at)
    VALUES (gen_random_uuid(), rec.unit_id, 'AVAILABLE', 'OCCUPIED', 'Lease activated.', NULL,
            (v_lease_start - 2)::timestamp);
  END LOOP;


  -- ==========================================================================
  -- 7. Rent invoices and their lines
  -- ==========================================================================
  -- Fourteen months of billing per running lease. Commercial lettings carry tax,
  -- so the tax column and the subtotal-plus-tax invariant are exercised by real
  -- data rather than sitting at zero everywhere.
  FOR rec IN
    SELECT l.id AS lease_id, l.tenant_id, l.property_id, l.unit_id, l.rent_amount, l.due_day,
           l.start_date, pt.code AS type_code
    FROM leases l
    JOIN units u ON u.id = l.unit_id
    JOIN properties p ON p.id = l.property_id
    JOIN property_types pt ON pt.id = p.property_type_id
    WHERE l.status = 'ACTIVE' AND l.deleted_at IS NULL
    ORDER BY l.lease_code
  LOOP
    v_tax_rate := CASE WHEN rec.type_code LIKE 'COMMERCIAL%' THEN 18.00 ELSE 0.00 END;

    FOR m IN REVERSE (v_months - 1)..0 LOOP
      v_period_start := (date_trunc('month', CURRENT_DATE) - (m || ' months')::interval)::date;

      -- A lease that started after the period began was not billed for it.
      CONTINUE WHEN v_period_start < rec.start_date;

      c_invoice := c_invoice + 1;

      v_month_rent := rec.rent_amount;
      v_subtotal := round(v_month_rent, 2);
      v_tax := round(v_subtotal * v_tax_rate / 100, 2);

      INSERT INTO rent_invoices (id, invoice_number, lease_id, tenant_id, property_id, unit_id,
                                 period_start, period_end, due_date, subtotal, tax_amount, total_amount,
                                 credited_amount, status, issued_at, notes, created_at, updated_at)
      VALUES (gen_random_uuid(),
              'ME-R-' || lpad(c_invoice::text, 6, '0'),
              rec.lease_id, rec.tenant_id, rec.property_id, rec.unit_id,
              v_period_start,
              (v_period_start + INTERVAL '1 month - 1 day')::date,
              v_period_start + (rec.due_day - 1),
              v_subtotal, v_tax, v_subtotal + v_tax, 0, 'ISSUED',
              (v_period_start + INTERVAL '1 day')::timestamp,
              NULL, now(), now());

      v_invoice_id := (SELECT id FROM rent_invoices
                        WHERE invoice_number = 'ME-R-' || lpad(c_invoice::text, 6, '0'));

      INSERT INTO rent_invoice_lines (id, invoice_id, line_type, description, quantity, unit_amount,
                                      line_total, tax_rate_percent, tax_amount, display_order,
                                      created_at, updated_at)
      VALUES (gen_random_uuid(), v_invoice_id, 'RENT',
              'Rent for ' || to_char(v_period_start, 'Mon YYYY'),
              1, v_month_rent, v_subtotal, v_tax_rate, v_tax, 1, now(), now());

      -- A monthly maintenance charge on every third letting, so an invoice can
      -- genuinely have more than one line and the subtotal is a sum rather than a
      -- copy of the rent. The UPDATE reads the old values, which is why the three
      -- expressions are written against the pre-update subtotal.
      IF c_invoice % 3 = 0 THEN
        UPDATE rent_invoices
           SET subtotal = subtotal + 2500.00,
               tax_amount = round((subtotal + 2500.00) * v_tax_rate / 100, 2),
               total_amount = (subtotal + 2500.00)
                              + round((subtotal + 2500.00) * v_tax_rate / 100, 2)
         WHERE id = v_invoice_id;

        INSERT INTO rent_invoice_lines (id, invoice_id, line_type, description, quantity, unit_amount,
                                        line_total, tax_rate_percent, tax_amount, display_order,
                                        created_at, updated_at)
        VALUES (gen_random_uuid(), v_invoice_id, 'MAINTENANCE', 'Common area maintenance',
                1, 2500.00, 2500.00, v_tax_rate, round(2500.00 * v_tax_rate / 100, 2), 2, now(), now());
      END IF;
    END LOOP;
  END LOOP;


  -- ==========================================================================
  -- 8. Receipts and allocations
  -- ==========================================================================
  -- Older invoices are mostly settled, recent ones are partly outstanding, and a
  -- minority is genuinely overdue. An allocation never exceeds either the invoice
  -- or the receipt, which the service layer also enforces on live data.
  FOR rec IN
    SELECT i.id AS invoice_id, i.lease_id, i.tenant_id, i.total_amount, i.due_date,
           i.period_start, i.property_id,
           row_number() OVER (ORDER BY i.invoice_number) AS rn
    FROM rent_invoices i
    WHERE i.status = 'ISSUED' AND i.deleted_at IS NULL
    ORDER BY i.invoice_number
  LOOP
    -- How settled an invoice is depends on how long ago it fell due.
    v_settlement := CASE
      WHEN rec.period_start < (date_trunc('month', CURRENT_DATE) - INTERVAL '2 months') THEN
        CASE WHEN rec.rn % 25 = 0 THEN 'UNPAID'
             WHEN rec.rn % 17 = 0 THEN 'PARTIAL'
             ELSE 'PAID' END
      WHEN rec.period_start < date_trunc('month', CURRENT_DATE) THEN
        CASE WHEN rec.rn % 9 = 0 THEN 'UNPAID'
             WHEN rec.rn % 5 = 0 THEN 'PARTIAL'
             ELSE 'PAID' END
      ELSE
        CASE WHEN rec.rn % 4 = 0 THEN 'UNPAID'
             WHEN rec.rn % 3 = 0 THEN 'PARTIAL'
             ELSE 'PAID' END
    END;

    CONTINUE WHEN v_settlement = 'UNPAID';

    c_payment := c_payment + 1;

    v_pay := CASE WHEN v_settlement = 'PAID' THEN rec.total_amount
                  ELSE round(rec.total_amount * (0.3 + ((rec.rn % 4) * 0.15)), 2) END;

    INSERT INTO payments (id, receipt_number, direction, payment_method_id, tenant_id, lease_id,
                          property_id, amount, currency, received_at, status, reference,
                          recorded_by, created_at, updated_at)
    VALUES (gen_random_uuid(),
            'ME-PAY-' || lpad(c_payment::text, 6, '0'),
            'INBOUND',
            (SELECT id FROM payment_methods WHERE code =
              (ARRAY['NEFT','UPI','CHEQUE','IMPS','AUTO_DEBIT','CASH'])[1 + (rec.rn % 6)]),
            rec.tenant_id, rec.lease_id, rec.property_id, v_pay, 'INR',
            (rec.due_date - (((rec.rn % 5) - 2)::integer))::timestamp,  -- rn is bigint: date - bigint has no operator
            'PAID',
            CASE WHEN rec.rn % 3 = 0
                 THEN 'UTR' || lpad((900000000 + c_payment)::text, 9, '0') END,
            (SELECT id FROM users WHERE email = 'accounts@meridianestates.example'),
            now(), now());

    v_payment_id := (SELECT id FROM payments
                      WHERE receipt_number = 'ME-PAY-' || lpad(c_payment::text, 6, '0'));

    INSERT INTO payment_allocations (id, payment_id, invoice_id, amount, allocated_at, allocated_by,
                                     created_at, updated_at)
    VALUES (gen_random_uuid(), v_payment_id, rec.invoice_id, v_pay,
            (rec.due_date - (((rec.rn % 5) - 2)::integer))::timestamp,  -- rn is bigint: date - bigint has no operator
            (SELECT id FROM users WHERE email = 'accounts@meridianestates.example'), now(), now());
  END LOOP;


  -- ==========================================================================
  -- 9. Applications and their screening results
  -- ==========================================================================
  -- The applications behind the first forty current tenancies, so the funnel and
  -- the screening history have real records rather than stand-alone examples.
  FOR rec IN
    SELECT l.id AS lease_id, l.property_id, l.unit_id, l.start_date, l.rent_amount,
           t.id AS tenant_id, t.tenant_type, t.display_name, t.email, t.phone
    FROM (
      SELECT * FROM leases
       WHERE status = 'ACTIVE' AND deleted_at IS NULL
       ORDER BY lease_code LIMIT 40
    ) l
    JOIN tenants t ON t.id = l.tenant_id
    ORDER BY l.lease_code
  LOOP
    c_application := c_application + 1;

    INSERT INTO rental_applications (id, application_code, property_id, unit_id, tenant_id,
                                     applicant_type, applicant_name, applicant_email, applicant_phone,
                                     status, proposed_start_date, proposed_end_date, proposed_rent,
                                     proposed_deposit, occupancy_count, has_pets, employment_type,
                                     annual_income, submitted_at, decided_at, decided_by, decision_notes,
                                     created_at, updated_at)
    VALUES (gen_random_uuid(), 'ME-A-' || lpad(c_application::text, 5, '0'),
            rec.property_id, rec.unit_id, rec.tenant_id,
            rec.tenant_type, rec.display_name, rec.email, rec.phone,
            'APPROVED', rec.start_date, rec.start_date + INTERVAL '11 months', rec.rent_amount,
            round(rec.rent_amount * 2, 2), 2, false, 'SALARIED', 1200000,
            (rec.start_date - 12)::timestamp,
            (rec.start_date - 6)::timestamp,
            (SELECT id FROM users WHERE email = 'manager@meridianestates.example'),
            'Approved after screening.', now(), now());

    -- Screening results. The landlord reference is waived for a first-time renter
    -- with an employer guarantee, which is a real decision recorded as one rather
    -- than an absent check.
    INSERT INTO application_screenings (id, application_id, check_type, status, score, notes,
                                        evidence_reference, performed_by, performed_at,
                                        created_at, updated_at)
    SELECT gen_random_uuid(), a.id, s.check_type, s.status, s.score, NULL, s.evidence,
           (SELECT id FROM users WHERE email = 'manager@meridianestates.example'),
           (rec.start_date - 8)::timestamp, now(), now()
    FROM rental_applications a
    CROSS JOIN (VALUES
      ('IDENTITY',           'PASSED', 900, 'Identity verified against the original document.'),
      ('EMPLOYMENT',         'PASSED', 850, 'Employment letter on file.'),
      ('INCOME',             'PASSED', 800, 'Three months of bank statements reviewed.'),
      ('REFERENCE_LANDLORD', 'WAIVED', NULL, 'Waived: first-time renter with an employer guarantee.')
    ) AS s (check_type, status, score, evidence)
    WHERE a.application_code = 'ME-A-' || lpad(c_application::text, 5, '0');
  END LOOP;


  -- ==========================================================================
  -- 10. Listings for the vacant and marketed stock
  -- ==========================================================================
  FOR rec IN
    SELECT u.id AS unit_id, u.property_id, u.status AS unit_status, u.asking_rent,
           row_number() OVER (ORDER BY u.unit_code) AS rn
    FROM units u
    WHERE u.status IN ('AVAILABLE', 'LISTED', 'UNDER_CONTRACT', 'RESERVED')
      AND u.leasable = true AND u.deleted_at IS NULL
    ORDER BY u.unit_code
  LOOP
    c_listing := c_listing + 1;

    INSERT INTO listings (id, listing_code, property_id, unit_id, listing_type, channel_id, status,
                          headline, description, asking_rent, asking_price, exclusive, negotiable,
                          listing_start, listing_end, published_at, closed_at, listed_by, office_id,
                          created_at, updated_at)
    SELECT gen_random_uuid(), 'ME-LS-' || lpad(c_listing::text, 6, '0'),
           rec.property_id, rec.unit_id, 'RENT',
           (SELECT id FROM listing_channels WHERE code =
             (ARRAY['PORTAL_99ACRES','PORTAL_MAGICBRICKS','WEBSITE','BROKER_NETWORK','PORTAL_HOUSING'])[1 + (rec.rn % 5)]),
           CASE rec.unit_status
             WHEN 'LISTED' THEN 'PUBLISHED'
             WHEN 'RESERVED' THEN 'UNDER_OFFER'
             ELSE 'ACTIVE' END,
           'Available to let — ' || pt.name || ' in ' || p.city,
           'Marketing copy for the seeded listing.',
           rec.asking_rent, NULL,
           (rec.rn % 3 = 0), true,
           CURRENT_DATE - (((rec.rn % 120) + 5)::integer),  -- rn is bigint
           CURRENT_DATE + 90,
           (CURRENT_DATE - (((rec.rn % 120) + 5)::integer))::timestamp,  -- rn is bigint
           NULL,
           (SELECT id FROM agent_profiles WHERE agent_code =
             CASE WHEN rec.rn % 2 = 0 THEN 'ME-AG-002' ELSE 'ME-AG-003' END),
           (SELECT id FROM offices WHERE office_code = 'ME-HO'),
           now(), now()
    FROM properties p
    JOIN property_types pt ON pt.id = p.property_type_id
    WHERE p.id = rec.property_id;
  END LOOP;


  -- ==========================================================================
  -- 11. Viewings
  -- ==========================================================================
  -- The status and its required outcome fields are computed together and written
  -- in one insert. Writing the row first and patching the outcome afterwards is
  -- not possible here, because the outcome check on the table would refuse the
  -- intermediate state - which is the constraint doing its job.
  FOR i IN 1..60 LOOP
    INSERT INTO viewings (id, viewing_code, property_id, unit_id, lead_id, prospect_name, prospect_phone,
                          agent_id, scheduled_at, duration_minutes, status, feedback, rating, interested,
                          cancelled_reason, completed_at, created_at, updated_at)
    SELECT gen_random_uuid(), 'ME-V-' || lpad(i::text, 6, '0'),
           l.property_id, l.unit_id, l.id, l.display_name, l.phone, l.owner_agent_id,
           t.scheduled_at, 30, v.status,
           CASE WHEN v.status = 'COMPLETED' THEN
             (ARRAY['Interested, asked about the deposit terms.','Liked the layout but the rent is a stretch.',
                    'Very positive, will discuss with family.','Wanted more parking than is available.',
                    'Interested subject to a shorter notice period.'])[1 + (i % 5)] END,
           CASE WHEN v.status = 'COMPLETED' THEN 3 + (i % 3) END,
           CASE WHEN v.status = 'COMPLETED' THEN (i % 4 <> 0) END,
           CASE WHEN v.status IN ('CANCELLED','NO_SHOW') THEN
             (ARRAY['Prospect rescheduled.','Prospect did not attend.',
                    'Unit withdrawn before the appointment.',
                    'Access not available at the requested time.'])[1 + (i % 4)] END,
           CASE WHEN v.status = 'COMPLETED' THEN t.scheduled_at + INTERVAL '30 minutes' END,
           now(), now()
    FROM leads l
    CROSS JOIN LATERAL (
      SELECT (now() + ((i % 30) - 12) * INTERVAL '1 day'
                    + ((i % 8) + 9) * INTERVAL '1 hour') AS scheduled_at
    ) AS t
    CROSS JOIN LATERAL (
      SELECT CASE
        WHEN ((i % 30) - 12) < 0 THEN 'COMPLETED'
        WHEN i % 17 = 0 THEN 'CANCELLED'
        WHEN i % 23 = 0 THEN 'NO_SHOW'
        WHEN i % 3 = 0 THEN 'CONFIRMED'
        ELSE 'SCHEDULED' END AS status
    ) AS v
    WHERE l.lead_code = 'ME-LD-' || lpad((((i * 7) % 30) + 1)::text, 6, '0')
      AND l.property_id IS NOT NULL;
  END LOOP;


  -- ==========================================================================
  -- 12. Maintenance requests
  -- ==========================================================================
  -- Status and its required fields are computed together, for the same reason as
  -- the viewings above: the table refuses a request that claims to be resolved
  -- with nothing recorded about the resolution.
  FOR i IN 1..45 LOOP
    c_maint := c_maint + 1;

    INSERT INTO maintenance_requests (id, request_code, property_id, unit_id, category, priority, status,
                                      title, description, reported_by, reporter_name,
                                      assigned_vendor_id, assigned_to,
                                      reported_at, target_date, assigned_at, started_at, resolved_at,
                                      closed_at, estimated_cost, actual_cost, resolution_notes,
                                      chargeable_to_tenant, created_at, updated_at)
    SELECT gen_random_uuid(), 'ME-M-' || lpad(c_maint::text, 5, '0'),
           p.id,
           (SELECT u.id FROM units u WHERE u.property_id = p.id AND u.deleted_at IS NULL
             ORDER BY u.unit_code OFFSET (i % 5) LIMIT 1),
           v.category, v.priority, v.status, v.title,
           'Recorded by the demonstration dataset.',
           (SELECT id FROM users WHERE email = 'maintenance@meridianestates.example'),
           'Facilities desk',
           (SELECT id FROM vendors WHERE vendor_code = v.vendor),
           CASE WHEN v.status IN ('ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED')
                THEN (SELECT id FROM users WHERE email = 'maintenance@meridianestates.example') END,
           v.reported_at,
           (v.reported_at::date + 3),
           CASE WHEN v.status IN ('ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED')
                THEN v.reported_at + INTERVAL '1 day' END,
           CASE WHEN v.status IN ('IN_PROGRESS','RESOLVED','CLOSED')
                THEN v.reported_at + INTERVAL '2 days' END,
           CASE WHEN v.status IN ('RESOLVED','CLOSED')
                THEN v.reported_at + INTERVAL '4 days' END,
           CASE WHEN v.status = 'CLOSED' THEN v.reported_at + INTERVAL '6 days' END,
           v.estimated_cost,
           CASE WHEN v.status IN ('RESOLVED','CLOSED') THEN v.actual_cost END,
           CASE WHEN v.status IN ('RESOLVED','CLOSED')
                THEN 'Attended, repaired and confirmed working.' END,
           (i % 7 = 0),
           now(), now()
    FROM properties p
    JOIN LATERAL (
      SELECT (ARRAY['ME-P-00001','ME-P-00002','ME-P-00003','ME-P-00004','ME-P-00005','ME-P-00006',
                    'ME-P-00007','ME-P-00008','ME-P-00009','ME-P-00010','ME-P-00011','ME-P-00012',
                    'ME-P-00013'])[1 + (i % 13)] AS pcode
    ) AS pk ON true
    CROSS JOIN LATERAL (
      SELECT
        (ARRAY['PLUMBING','ELECTRICAL','HVAC','CIVIL','PAINTING','PEST_CONTROL','LIFT',
               'FIRE_SAFETY','LANDSCAPING','GENERAL'])[1 + (i % 10)] AS category,
        (ARRAY['LOW','MEDIUM','HIGH','URGENT'])[1 + (i % 4)] AS priority,
        (ARRAY['OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED'])[1 + (i % 5)] AS status,
        (ARRAY['ME-V-001','ME-V-002','ME-V-003','ME-V-004','ME-V-005','ME-V-006','ME-V-007',
               'ME-V-008','ME-V-009','ME-V-010'])[1 + (i % 10)] AS vendor,
        (ARRAY['Leaking kitchen tap','Lift door sensor fault','Air-conditioning not cooling',
               'Damp patch on the ceiling','Common area repainting','Pest treatment required',
               'Fire alarm panel beeping','Garden irrigation blocked','Electrical socket dead',
               'Generator servicing due'])[1 + (i % 10)] AS title,
        (now() - ((i % 120) + 5) * INTERVAL '1 day') AS reported_at,
        (1500 + (i % 20) * 950)::numeric(12,2) AS estimated_cost,
        (1800 + (i % 20) * 1100)::numeric(12,2) AS actual_cost
    ) AS v
    WHERE p.property_code = pk.pcode;
  END LOOP;

  INSERT INTO maintenance_status_history (id, request_id, from_status, to_status, reason,
                                          changed_by, changed_at)
  SELECT gen_random_uuid(), m.id, NULL, 'OPEN', 'Request raised.', NULL, m.reported_at
  FROM maintenance_requests m;


  -- ==========================================================================
  -- 13. Property expenses
  -- ==========================================================================
  -- Fourteen months of running cost plus capital spend, so the income-versus-
  -- expense chart and the net income figure both have real content - and so the
  -- capital/operating split is exercised rather than assumed.
  FOR i IN 1..220 LOOP
    c_expense := c_expense + 1;

    INSERT INTO property_expenses (id, expense_code, property_id, unit_id, category_id,
                                   maintenance_request_id, vendor_id, expense_date, amount, tax_amount,
                                   total_amount, description, invoice_reference, is_recoverable,
                                   recovered_amount, recorded_by, created_at, updated_at)
    SELECT gen_random_uuid(), 'ME-E-' || lpad(c_expense::text, 6, '0'),
           p.id, NULL, ec.id,
           CASE WHEN ec.group_name = 'REPAIR' THEN
             (SELECT m.id FROM maintenance_requests m WHERE m.property_id = p.id
               ORDER BY m.request_code OFFSET (i % 5) LIMIT 1) END,
           CASE WHEN ec.group_name = 'REPAIR' THEN
             (SELECT id FROM vendors WHERE vendor_code = 'ME-V-00' || (1 + (i % 9))) END,
           ((date_trunc('month', CURRENT_DATE) - ((i % 14) || ' months')::interval)::date
             + ((i % 25) + 1)),
           v.amount, round(v.amount * 0.18, 2), v.amount + round(v.amount * 0.18, 2),
           ec.name || ' — ' || p.name,
           'INV/' || to_char((date_trunc('month', CURRENT_DATE)
                              - ((i % 14) || ' months')::interval)::date, 'YYYYMM')
             || '/' || lpad(c_expense::text, 5, '0'),
           ec.typically_recoverable,
           CASE WHEN ec.typically_recoverable THEN round(v.amount * 0.6, 2) ELSE 0 END,
           (SELECT id FROM users WHERE email = 'accounts@meridianestates.example'),
           now(), now()
    FROM properties p
    JOIN LATERAL (
      SELECT (ARRAY['ME-P-00001','ME-P-00002','ME-P-00003','ME-P-00004','ME-P-00005','ME-P-00006',
                    'ME-P-00007','ME-P-00008','ME-P-00009','ME-P-00010','ME-P-00011','ME-P-00012',
                    'ME-P-00013'])[1 + (i % 13)] AS pcode
    ) AS pk ON true
    JOIN LATERAL (
      SELECT c.id, c.group_name, c.name, c.typically_recoverable
      FROM expense_categories c
      WHERE c.code = (ARRAY['REPAIRS_PLUMBING','REPAIRS_ELECTRICAL','REPAIRS_HVAC','UTILITY_ELECTRICITY',
                            'UTILITY_WATER','HOUSEKEEPING','SECURITY','PROPERTY_TAX','INSURANCE',
                            'MANAGEMENT_FEE','MARKETING','CAPITAL_IMPROVEMENT','CAPITAL_REPLACEMENT',
                            'PEST_CONTROL','LEGAL_PROFESSIONAL'])[1 + (i % 15)]
    ) AS ec ON true
    CROSS JOIN LATERAL (
      SELECT (20000 + ((i * 3700) % 260000))::numeric(14,2) AS amount
    ) AS v
    WHERE p.property_code = pk.pcode;
  END LOOP;


  -- ==========================================================================
  -- 14. Sales pipeline and offers
  -- ==========================================================================
  FOR i IN 1..18 LOOP
    c_sale := c_sale + 1;

    INSERT INTO sales_opportunities (id, sale_code, property_id, unit_id, buyer_id, lead_id, stage,
                                     asking_price, expected_price, agreed_price, expected_close_date,
                                     closed_at, lost_at, lost_reason, owner_agent_id, office_id, notes,
                                     created_at, updated_at)
    SELECT gen_random_uuid(), 'ME-S-' || lpad(c_sale::text, 5, '0'),
           p.id, NULL,
           (SELECT id FROM buyers WHERE buyer_code = 'ME-B-' || lpad((((i * 3) % 25) + 1)::text, 4, '0')),
           (SELECT id FROM leads WHERE lead_code = 'ME-LD-' || lpad((((i * 3) % 30) + 1)::text, 6, '0')),
           v.stage, v.asking,
           round(v.asking * 0.96, 2),
           CASE WHEN v.stage = 'CLOSED' THEN round(v.asking * 0.94, 2) END,
           CURRENT_DATE + 45,
           CASE WHEN v.stage = 'CLOSED' THEN now() - ((i % 30) + 3) * INTERVAL '1 day' END,
           CASE WHEN v.stage = 'LOST' THEN now() - ((i % 30) + 3) * INTERVAL '1 day' END,
           CASE WHEN v.stage = 'LOST' THEN (ARRAY['Price not agreed','Buyer withdrew',
                     'Due diligence failed','Seller withdrew from the market'])[1 + (i % 4)] END,
           (SELECT id FROM agent_profiles WHERE agent_code =
             CASE WHEN i % 2 = 0 THEN 'ME-AG-004' ELSE 'ME-AG-005' END),
           (SELECT id FROM offices WHERE office_code = 'ME-HO'),
           'Seeded sales opportunity.',
           now() - ((i % 120) + 10) * INTERVAL '1 day', now()
    FROM properties p
    JOIN LATERAL (
      SELECT (ARRAY['ME-P-00001','ME-P-00004','ME-P-00006','ME-P-00007','ME-P-00009','ME-P-00011',
                    'ME-P-00012','ME-P-00015'])[1 + (i % 8)] AS pcode
    ) AS pk ON true
    CROSS JOIN LATERAL (
      SELECT (ARRAY['LEAD','OFFER','NEGOTIATION','UNDER_CONTRACT','CLOSED','LOST'])[1 + (i % 6)] AS stage,
             (20000000 + ((i * 7300000) % 140000000))::numeric(14,2) AS asking
    ) AS v
    WHERE p.property_code = pk.pcode;

    -- Two offers on anything past the lead stage. Only the first can be ACCEPTED:
    -- the unique index on the table allows one accepted offer per sale, because
    -- acceptance is what ends the negotiation and an asset cannot be promised
    -- twice.
    IF (ARRAY['LEAD','OFFER','NEGOTIATION','UNDER_CONTRACT','CLOSED','LOST'])[1 + (i % 6)] <> 'LEAD' THEN
      INSERT INTO offers (id, offer_number, sale_id, amount, offered_at, direction, status,
                          expires_at, responded_at, responded_by, response_notes, created_at, updated_at)
      SELECT gen_random_uuid(), 'ME-OF-' || lpad(c_sale::text, 5, '0') || '-' || g,
             so.id,
             round(so.asking_price * (0.90 + (g * 0.02)), 2),
             now() - ((i % 60) + (5 - g) * 2) * INTERVAL '1 day',
             CASE WHEN g % 2 = 0 THEN 'BUYER' ELSE 'FIRM' END,
             CASE
               -- An accepted or countered offer has been answered, and the table
               -- requires a response moment for exactly those states. An offer
               -- still on the table is SUBMITTED and carries no response - which
               -- is why the non-terminal stages cannot use COUNTERED here.
               WHEN so.stage IN ('UNDER_CONTRACT','CLOSED') AND g = 1 THEN 'ACCEPTED'
               WHEN so.stage IN ('UNDER_CONTRACT','CLOSED') AND g > 1 THEN 'COUNTERED'
               WHEN so.stage = 'LOST' THEN 'REJECTED'
               ELSE 'SUBMITTED'
             END,
             now() + (30 - (i % 20)) * INTERVAL '1 day',
             CASE WHEN so.stage IN ('UNDER_CONTRACT','CLOSED','LOST')
                  THEN now() - ((i % 40) + 2) * INTERVAL '1 day' END,
             CASE WHEN so.stage IN ('UNDER_CONTRACT','CLOSED','LOST')
                  THEN (SELECT id FROM users WHERE email = 'sales@meridianestates.example') END,
             CASE WHEN so.stage IN ('UNDER_CONTRACT','CLOSED')
                  THEN 'Terms agreed subject to contract.' END,
             now(), now()
      FROM sales_opportunities so
      CROSS JOIN generate_series(1, 2) g
      WHERE so.sale_code = 'ME-S-' || lpad(c_sale::text, 5, '0');
    END IF;
  END LOOP;


  -- ==========================================================================
  -- 15. Document register
  -- ==========================================================================
  FOR rec IN
    SELECT l.id AS lease_id, l.property_id, l.tenant_id, l.primary_owner_id, l.lease_code,
           l.start_date, l.end_date
    FROM leases l WHERE l.deleted_at IS NULL ORDER BY l.lease_code
  LOOP
    c_doc := c_doc + 1;
    INSERT INTO documents (id, document_code, title, document_type, status, property_id, lease_id,
                           tenant_id, owner_id, file_reference, mime_type, byte_size, page_count,
                           version, is_confidential, issued_date, expiry_date, uploaded_by,
                           created_at, updated_at)
    VALUES (gen_random_uuid(), 'ME-D-' || lpad(c_doc::text, 6, '0'),
            'Lease agreement — ' || rec.lease_code, 'LEASE_AGREEMENT', 'ACTIVE',
            rec.property_id, rec.lease_id, rec.tenant_id, rec.primary_owner_id,
            '/documents/leases/' || rec.lease_code || '.pdf', 'application/pdf',
            240000 + (c_doc * 1300), 18, 1, true, rec.start_date, rec.end_date,
            (SELECT id FROM users WHERE email = 'manager@meridianestates.example'), now(), now());
  END LOOP;

  FOR rec IN SELECT id, name, property_code, created_at FROM properties ORDER BY property_code LOOP
    c_doc := c_doc + 1;
    INSERT INTO documents (id, document_code, title, document_type, status, property_id, owner_id,
                           file_reference, mime_type, byte_size, page_count, version, is_confidential,
                           issued_date, expiry_date, uploaded_by, created_at, updated_at)
    VALUES (gen_random_uuid(), 'ME-D-' || lpad(c_doc::text, 6, '0'),
            'Title deed — ' || rec.name, 'TITLE_DEED', 'ACTIVE', rec.id,
            (SELECT po.owner_id FROM property_ownerships po WHERE po.property_id = rec.id
              ORDER BY po.ownership_percentage DESC LIMIT 1),
            '/documents/titles/' || rec.property_code || '.pdf', 'application/pdf',
            1450000, 42, 1, true, rec.created_at::date, NULL,
            (SELECT id FROM users WHERE email = 'admin@meridianestates.example'), now(), now());

    c_doc := c_doc + 1;
    -- Two of the policies are deliberately expired, so the expiry report has
    -- something to report and the "needs attention" rail is not uniformly clean.
    INSERT INTO documents (id, document_code, title, document_type, status, property_id, owner_id,
                           file_reference, mime_type, byte_size, page_count, version, is_confidential,
                           issued_date, expiry_date, verified_by, verified_at, uploaded_by,
                           created_at, updated_at)
    VALUES (gen_random_uuid(), 'ME-D-' || lpad(c_doc::text, 6, '0'),
            'Building insurance — ' || rec.name, 'INSURANCE_POLICY',
            CASE WHEN rec.property_code IN ('ME-P-00003','ME-P-00008') THEN 'EXPIRED' ELSE 'ACTIVE' END,
            rec.id,
            (SELECT po.owner_id FROM property_ownerships po WHERE po.property_id = rec.id
              ORDER BY po.ownership_percentage DESC LIMIT 1),
            '/documents/insurance/' || rec.property_code || '.pdf', 'application/pdf',
            320000, 12, 1, false,
            CURRENT_DATE - INTERVAL '1 year',
            CASE WHEN rec.property_code IN ('ME-P-00003','ME-P-00008')
                 THEN CURRENT_DATE - INTERVAL '20 days' ELSE CURRENT_DATE + INTERVAL '6 months' END,
            (SELECT id FROM users WHERE email = 'admin@meridianestates.example'),
            now() - INTERVAL '30 days',
            (SELECT id FROM users WHERE email = 'admin@meridianestates.example'), now(), now());
  END LOOP;


  -- ==========================================================================
  -- 16. Conversations and messages
  -- ==========================================================================
  FOR i IN 1..28 LOOP
    DECLARE
      v_conv_id uuid;
      v_property_id uuid;
      v_unit_id uuid;
      v_lease_id uuid;
      v_tenant_id uuid;
      v_owner_id uuid;
      v_status text := (ARRAY['OPEN','WAITING','RESOLVED','CLOSED'])[1 + (i % 4)];
      v_opened timestamptz := now() - ((i % 40) + 1) * INTERVAL '1 day';
      v_cp_name text;
    BEGIN
      c_conv := c_conv + 1;

      v_cp_name := (ARRAY['Aarav Sharma','Ishita Verma','Rohan Iyer','Meera Patel','Kabir Reddy',
                          'Ananya Nair'])[1 + (i % 6)];

      SELECT p.id INTO v_property_id FROM properties p
       WHERE p.property_code = (ARRAY['ME-P-00002','ME-P-00003','ME-P-00005','ME-P-00008','ME-P-00010',
             'ME-P-00013','ME-P-00001','ME-P-00004','ME-P-00006','ME-P-00007','ME-P-00011',
             'ME-P-00012'])[1 + (i % 12)];

      SELECT u.id INTO v_unit_id FROM units u WHERE u.property_id = v_property_id
       ORDER BY u.unit_code OFFSET (i % 4) LIMIT 1;

      -- A thread may or may not have a lease behind it: an early enquiry has a
      -- unit but no lease yet, and both are real states.
      SELECT l.id INTO v_lease_id FROM leases l WHERE l.unit_id = v_unit_id LIMIT 1;

      SELECT t.id INTO v_tenant_id FROM tenants t
       WHERE t.tenant_code = 'ME-T-' || lpad((((i * 5) % 60) + 1)::text, 5, '0');

      SELECT o.id INTO v_owner_id FROM owners o
       WHERE o.owner_code = 'ME-O-' || lpad((((i * 7) % 22) + 1)::text, 5, '0');

      INSERT INTO conversations (id, conversation_code, subject, channel, status, priority,
                                 property_id, unit_id, lease_id, tenant_id, owner_id,
                                 counterparty_name, counterparty_email, assigned_to,
                                 last_message_at, last_message_preview, opened_at, closed_at,
                                 closed_by, close_reason, created_at, updated_at)
      VALUES (gen_random_uuid(), 'ME-C-' || lpad(c_conv::text, 5, '0'),
              (ARRAY['Rent receipt not received','Request for a minor repair',
                     'Enquiry about the available unit','Lease renewal terms',
                     'Parking allocation query','Service charge statement',
                     'Notice period clarification','Move-out inspection booking'])[1 + (i % 8)],
              (ARRAY['EMAIL','PHONE','MESSAGE','PORTAL','IN_PERSON'])[1 + (i % 5)],
              v_status,
              (ARRAY['LOW','NORMAL','HIGH','URGENT'])[1 + (i % 4)],
              v_property_id, v_unit_id, v_lease_id, v_tenant_id, v_owner_id,
              v_cp_name, 'contact' || c_conv || '@example.com',
              (SELECT id FROM users WHERE email = CASE WHEN i % 3 = 0
                 THEN 'accounts@meridianestates.example' ELSE 'leasing@meridianestates.example' END),
              v_opened + INTERVAL '18 hours',
              (ARRAY['Following up on my earlier message.','Please confirm the receipt of payment.',
                     'Could someone look at this today?','Thank you for the quick response.',
                     'I would like to discuss the renewal.'])[1 + (i % 5)],
              v_opened,
              CASE WHEN v_status = 'CLOSED' THEN v_opened + INTERVAL '2 days' END,
              CASE WHEN v_status = 'CLOSED'
                   THEN (SELECT id FROM users WHERE email = 'manager@meridianestates.example') END,
              CASE WHEN v_status = 'CLOSED' THEN 'Resolved with the counterparty.' END,
              now(), now())
      RETURNING id INTO v_conv_id;

      -- Four messages per thread, alternating sides, so a thread reads as a
      -- conversation rather than a log.
      INSERT INTO conversation_messages (id, conversation_id, sender_type, message_type, is_internal,
                                         sender_user_id, sender_name, body, sent_at, read_at,
                                         created_at, updated_at)
      SELECT gen_random_uuid(), v_conv_id,
             CASE WHEN g % 2 = 1 THEN 'CLIENT' ELSE 'FIRM' END,
             'TEXT', false,
             CASE WHEN g % 2 = 0
                  THEN (SELECT assigned_to FROM conversations WHERE id = v_conv_id) END,
             CASE WHEN g % 2 = 1 THEN v_cp_name ELSE 'Meridian Estates' END,
             CASE WHEN g % 2 = 1
               THEN (ARRAY['Could you confirm whether the maintenance charge is included in the monthly rent?',
                           'I have transferred this month''s rent — please confirm receipt.',
                           'The tap in the second bathroom is still dripping. Could someone attend this week?',
                           'Thank you for arranging the viewing. We would like to proceed.',
                           'Has the renewal been prepared yet?'])[1 + ((i + g) % 5)]
               ELSE (ARRAY['Included — the maintenance charge is billed separately on the invoice.',
                           'Received and receipted, thank you. The receipt is in your portal.',
                           'A technician has been assigned and will attend within two working days.',
                           'Noted — I will prepare the paperwork and send it across.',
                           'The renewal is with us and you will have it this week.'])[1 + ((i + g) % 5)]
             END,
             v_opened + (g * INTERVAL '6 hours'),
             CASE WHEN v_status IN ('RESOLVED','CLOSED') OR g % 2 = 0
                  THEN v_opened + (g * INTERVAL '6 hours') + INTERVAL '1 hour' END,
             now(), now()
      FROM generate_series(1, 4) g;
    END;
  END LOOP;


  -- ==========================================================================
  -- 17. Notifications
  -- ==========================================================================
  INSERT INTO notifications (id, user_id, type, severity, title, body, entity_type, entity_id,
                             link, is_read, read_at, created_at)
  SELECT gen_random_uuid(), u.id, v.type, v.severity, v.title, v.body, v.entity_type, NULL,
         v.link, v.is_read,
         CASE WHEN v.is_read THEN now() - INTERVAL '2 hours' END,
         now() - (v.age_hours || ' hours')::interval
  FROM (VALUES
    ('manager@meridianestates.example'),
    ('leasing@meridianestates.example'),
    ('accounts@meridianestates.example'),
    ('maintenance@meridianestates.example')
  ) AS e (email)
  JOIN users u ON u.email = e.email
  CROSS JOIN (VALUES
    ('LEASE_EXPIRING','WARNING','Leases expiring within 30 days',
     'Several leases reach their end date in the next month.','LEASE','/leases',false,3),
    ('RENT_OVERDUE','CRITICAL','Rent overdue',
     'Invoices are past their due date and remain outstanding.','RENT_INVOICE','/rent',false,5),
    ('MAINTENANCE_URGENT','WARNING','Urgent maintenance open',
     'An urgent maintenance request is still open.','MAINTENANCE','/maintenance',false,8),
    ('LEAD_ASSIGNED','INFO','Lead assigned to you',
     'A new lead has been assigned to your desk.','LEAD','/leads',true,26),
    ('LISTING_STALE','WARNING','Listings without activity',
     'Listings have passed the stale threshold.','LISTING','/listings',false,12),
    ('PAYMENT_RECEIVED','INFO','Receipt recorded',
     'A receipt has been posted against a lease.','PAYMENT','/payments',true,30)
  ) AS v (type, severity, title, body, entity_type, link, is_read, age_hours);


  -- ==========================================================================
  -- 18. Audit trail
  -- ==========================================================================
  -- The events the detail screens show come from here, and they are events the
  -- seed actually performed rather than decorative noise.
  INSERT INTO audit_logs (id, module, action, severity, description, actor_id, actor_name,
                          entity_type, entity_label, previous_value, new_value, ip_address, created_at)
  SELECT gen_random_uuid(), v.module, v.action, v.severity, v.description, u.id,
         u.first_name || ' ' || u.last_name, v.entity_type, v.entity_label, v.previous_value,
         v.new_value, '127.0.0.1', now() - (v.age_hours || ' hours')::interval
  FROM (VALUES
    ('LEASES','LEASE_ACTIVATED','INFO','Lease activated.','manager@meridianestates.example',
     'LEASE','Seeded lease','PENDING','ACTIVE',10),
    ('PAYMENTS','PAYMENT_RECORDED','INFO','Receipt recorded against a lease.',
     'accounts@meridianestates.example','PAYMENT','Seeded receipt',NULL,'PAID',9),
    ('MAINTENANCE','MAINTENANCE_ASSIGNED','INFO','Maintenance request assigned to a vendor.',
     'maintenance@meridianestates.example','MAINTENANCE','Seeded request','OPEN','ASSIGNED',8),
    ('LEADS','LEAD_STATUS_CHANGED','INFO','Lead moved to a new stage.',
     'leasing@meridianestates.example','LEAD','Seeded lead','QUALIFIED','NEGOTIATION',7),
    ('PROPERTIES','PROPERTY_CREATED','INFO','Property added to the register.',
     'manager@meridianestates.example','PROPERTY','Seeded property',NULL,'AVAILABLE_ACTIVE',40),
    ('LISTINGS','LISTING_PUBLISHED','INFO','Listing published to a channel.',
     'leasing@meridianestates.example','LISTING','Seeded listing','DRAFT','PUBLISHED',6),
    ('VIEWINGS','VIEWING_SCHEDULED','INFO','Viewing scheduled.',
     'leasing@meridianestates.example','VIEWING','Seeded viewing',NULL,'SCHEDULED',5),
    ('APPLICATIONS','APPLICATION_APPROVED','INFO','Rental application approved.',
     'manager@meridianestates.example','APPLICATION','Seeded application','SCREENING','APPROVED',20),
    ('SALES','SALE_STAGE_CHANGED','INFO','Sale moved to a new stage.',
     'sales@meridianestates.example','SALE','Seeded sale','NEGOTIATION','UNDER_CONTRACT',4),
    ('IMPORTS','IMPORT_COMMITTED','INFO','Data import committed.',
     'admin@meridianestates.example','IMPORT','Seeded import','VALIDATED','COMMITTED',15)
  ) AS v (module, action, severity, description, email, entity_type, entity_label,
          previous_value, new_value, age_hours)
  JOIN users u ON u.email = v.email;

  -- A per-property valuation trail, so a property's Activity tab has something
  -- specific to show rather than only firm-wide events.
  INSERT INTO audit_logs (id, module, action, severity, description, actor_id, actor_name,
                          entity_type, entity_id, entity_label, property_id,
                          previous_value, new_value, ip_address, created_at)
  SELECT gen_random_uuid(), 'PROPERTIES', 'PROPERTY_VALUATION_REVISED', 'INFO',
         'Valuation revised on the property record.',
         (SELECT id FROM users WHERE email = 'manager@meridianestates.example'),
         'Rohan Malhotra', 'PROPERTY', p.id, p.name, p.id,
         to_char(p.acquisition_value, 'FM999,999,999,999.00'),
         to_char(p.current_valuation, 'FM999,999,999,999.00'),
         '127.0.0.1',
         now() - (row_number() OVER (ORDER BY p.property_code) * 7 || ' hours')::interval
  FROM properties p;


  -- ==========================================================================
  -- 19. Advance the remaining business-number series
  -- ==========================================================================
  -- The seed uses explicit codes so the demonstration data reads predictably.
  -- Without this the first live insert would mint a code that already exists.
  UPDATE business_number_sequences SET next_value = 61,      updated_at = now() WHERE code = 'TENANT';
  UPDATE business_number_sequences SET next_value = 31,      updated_at = now() WHERE code = 'LEAD';
  UPDATE business_number_sequences SET next_value = 2000,    updated_at = now() WHERE code = 'LISTING';
  UPDATE business_number_sequences SET next_value = 61,      updated_at = now() WHERE code = 'VIEWING';
  UPDATE business_number_sequences SET next_value = 41,      updated_at = now() WHERE code = 'APPLICATION';
  UPDATE business_number_sequences SET next_value = 1000,    updated_at = now() WHERE code = 'LEASE';
  UPDATE business_number_sequences SET next_value = 999999,  updated_at = now() WHERE code = 'INVOICE';
  UPDATE business_number_sequences SET next_value = 999998,  updated_at = now() WHERE code = 'RECEIPT';
  UPDATE business_number_sequences SET next_value = 1000,    updated_at = now() WHERE code = 'MAINTENANCE';
  UPDATE business_number_sequences SET next_value = 100,     updated_at = now() WHERE code = 'SALE';


  -- ==========================================================================
  -- 20. Guards
  -- ==========================================================================
  -- The invariants the dashboard, the rent roll and the collections report all
  -- depend on. Each of these has been wrong in a real system somewhere.

  -- Occupancy read from units and occupancy read from leases agree.
  SELECT count(*) INTO v_occupied FROM units WHERE status = 'OCCUPIED' AND deleted_at IS NULL;
  SELECT count(*) INTO v_running_leases FROM leases WHERE status = 'ACTIVE' AND deleted_at IS NULL;
  IF v_occupied <> v_running_leases THEN
    RAISE EXCEPTION 'Unit and lease occupancy disagree: % occupied units but % running leases',
      v_occupied, v_running_leases;
  END IF;
  IF v_occupied = 0 THEN
    RAISE EXCEPTION 'No occupied units were seeded; the rent roll would be empty';
  END IF;

  -- Every lease in force has a rent term, so "the rent today" always resolves.
  FOR rec IN
    SELECT l.lease_code FROM leases l
    WHERE l.status = 'ACTIVE' AND l.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM lease_terms t WHERE t.lease_id = l.id AND t.deleted_at IS NULL)
  LOOP
    RAISE EXCEPTION 'Lease % has no rent term', rec.lease_code;
  END LOOP;

  -- No invoice's allocations exceed what it billed. This is the invariant whose
  -- absence produces a negative outstanding balance.
  FOR rec IN
    SELECT i.invoice_number, i.total_amount, sum(a.amount) AS allocated
    FROM rent_invoices i
    JOIN payment_allocations a ON a.invoice_id = i.id AND a.deleted_at IS NULL
    GROUP BY i.invoice_number, i.total_amount
    HAVING sum(a.amount) > i.total_amount
  LOOP
    RAISE EXCEPTION 'Invoice % allocated % against a total of %',
      rec.invoice_number, rec.allocated, rec.total_amount;
  END LOOP;

  -- No receipt is allocated beyond its own amount.
  FOR rec IN
    SELECT p.receipt_number, p.amount, sum(a.amount) AS allocated
    FROM payments p
    JOIN payment_allocations a ON a.payment_id = p.id AND a.deleted_at IS NULL
    GROUP BY p.receipt_number, p.amount
    HAVING sum(a.amount) > p.amount
  LOOP
    RAISE EXCEPTION 'Receipt % allocated % against an amount of %',
      rec.receipt_number, rec.allocated, rec.amount;
  END LOOP;

  -- The book is genuinely mixed, so the collections charts are not uniformly
  -- green and the arrears figures are not all zero.
  IF NOT EXISTS (
    SELECT 1 FROM rent_invoices i
     WHERE i.status = 'ISSUED' AND i.due_date < CURRENT_DATE - INTERVAL '5 days'
       AND i.total_amount > COALESCE((
             SELECT sum(a.amount) FROM payment_allocations a
             JOIN payments p ON p.id = a.payment_id
            WHERE a.invoice_id = i.id AND a.deleted_at IS NULL AND p.status = 'PAID'), 0)
  ) THEN
    RAISE EXCEPTION 'No overdue invoice was seeded; the arrears figures would all be zero';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM payments WHERE status = 'PAID') THEN
    RAISE EXCEPTION 'No settled receipt was seeded; collections would be zero';
  END IF;

  IF (SELECT count(*) FROM property_expenses WHERE deleted_at IS NULL) < 100 THEN
    RAISE EXCEPTION 'Too few expenses seeded for a meaningful expense report';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM property_expenses e JOIN expense_categories c ON c.id = e.category_id
     WHERE c.is_capital = true
  ) THEN
    RAISE EXCEPTION 'No capital expense seeded; the capital/operating split would be untested';
  END IF;

  RAISE NOTICE 'Meridian Estates history seeded: % tenants, % leases, % invoices, % receipts.',
    (SELECT count(*) FROM tenants), (SELECT count(*) FROM leases),
    (SELECT count(*) FROM rent_invoices), (SELECT count(*) FROM payments);

END $$;

ANALYZE tenants;
ANALYZE buyers;
ANALYZE buyer_requirements;
ANALYZE leads;
ANALYZE lead_activities;
ANALYZE lead_assignments;
ANALYZE lead_followups;
ANALYZE vendors;
ANALYZE leases;
ANALYZE lease_terms;
ANALYZE rent_invoices;
ANALYZE rent_invoice_lines;
ANALYZE payments;
ANALYZE payment_allocations;
ANALYZE rental_applications;
ANALYZE application_screenings;
ANALYZE listings;
ANALYZE viewings;
ANALYZE maintenance_requests;
ANALYZE property_expenses;
ANALYZE sales_opportunities;
ANALYZE offers;
ANALYZE documents;
ANALYZE conversations;
ANALYZE conversation_messages;
ANALYZE notifications;
ANALYZE audit_logs;
