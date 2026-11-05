-- ============================================================================
-- V22 - Seed the Meridian Estates portfolio: offices, agents, assets and stock
-- ============================================================================
-- The structural half of the demonstration dataset: the firm, the agents, the
-- portfolios, the properties and their buildings and units, and who owns them.
-- The people they are let to and the money they produce are seeded by V23.
--
-- ----------------------------------------------------------------------------
-- THESE ARE DEMONSTRATION RECORDS, AND THE COORDINATES ARE REAL
-- ----------------------------------------------------------------------------
-- Meridian Estates is a fictional firm. The properties are fictional. Their
-- addresses and their latitude/longitude are real places in real cities, because
-- the portfolio map is a genuine projection over genuine coordinates - plotting
-- invented positions would make the map a drawing of nothing and would defeat the
-- one thing it exists to show: how the book is distributed.
--
-- Every figure is internally consistent. Valuations, unit counts, rents and
-- ownership percentages are chosen so that the derived numbers - occupancy,
-- yield, portfolio value, owner share - come out to plausible values rather than
-- to whatever the arithmetic happens to produce.
--
-- ----------------------------------------------------------------------------
-- WHY THE TRANSACTION HISTORY IS DATED RELATIVE TO CURRENT_DATE
-- ----------------------------------------------------------------------------
-- V23 dates every lease, invoice, receipt and viewing relative to `CURRENT_DATE`
-- rather than to a fixed calendar. A fixed date would mean the demonstration
-- dataset aged: within a year every lease would be expired and every chart empty,
-- and the "expiring soon" rail would be full of rows that expired long ago.
-- Relative dating keeps the dataset permanently current, which is why there is no
-- separate re-anchoring migration.
--
-- This migration does not use CURRENT_DATE at all: the assets themselves - when a
-- building was built, when it was acquired - are genuinely historical facts and
-- are fixed.
-- ============================================================================

DO $$
DECLARE
  v_property_count integer;
  v_unit_count integer;
  v_occupied_count integer;
  v_unit_seq integer := 0;
  rec record;
  v_property_id uuid;
  v_building_id uuid;
  v_unit_type_id uuid;
  v_status text;
  v_floor integer;
  v_floor_label text;
  v_unit_in_floor integer;
  v_i integer;
BEGIN

  -- ==========================================================================
  -- 1. Offices
  -- ==========================================================================
  INSERT INTO offices (id, office_code, name, address_line1, locality, city, state, postal_code,
                       country, phone, email, is_head_office, status, created_at, updated_at) VALUES
    (gen_random_uuid(), 'ME-HO', 'Meridian House', 'Meridian House, 14 Ballard Estate', 'Fort',
     'Mumbai', 'Maharashtra', '400001', 'India', '+91-22-6155-4400', 'advisory@meridianestates.example',
     true,  'ACTIVE', now(), now()),
    (gen_random_uuid(), 'ME-BLR','Bengaluru Office', '4th Floor, Orion Tech Square, Outer Ring Road',
     'Bellandur', 'Bengaluru', 'Karnataka', '560103', 'India', '+91-80-4712-8800',
     'bengaluru@meridianestates.example', false, 'ACTIVE', now(), now()),
    (gen_random_uuid(), 'ME-PNQ','Pune Office', 'Ground Floor, Riverstone Business Park, Hinjawadi',
     'Hinjawadi', 'Pune', 'Maharashtra', '411057', 'India', '+91-20-6712-3300',
     'pune@meridianestates.example', false, 'ACTIVE', now(), now());


  -- ==========================================================================
  -- 2. Agent profiles
  -- ==========================================================================
  -- One profile per staff account that carries stock. The accounts themselves
  -- were seeded by V2; here they gain a portfolio, an office and a licence.
  INSERT INTO agent_profiles (id, user_id, agent_code, office_id, manager_id, specialisation,
                              licence_number, joined_on, monthly_target, bio, status,
                              created_at, updated_at)
  SELECT gen_random_uuid(), u.id,
         'ME-AG-' || lpad((row_number() OVER (ORDER BY u.staff_code))::text, 3, '0'),
         o.id, NULL, v.specialisation, v.licence, v.joined_on, v.target, v.bio, 'ACTIVE', now(), now()
  FROM (VALUES
    ('manager@meridianestates.example',     'PROPERTY_MANAGEMENT', 'RERA/ME/AG/0114', DATE '2019-04-01', 0.00,
     'Heads the portfolio: lettings, sales, the rent roll and reporting.'),
    ('leasing@meridianestates.example',     'LETTING',             'RERA/ME/AG/0231', DATE '2021-02-15', 850000.00,
     'Senior letting agent for the residential book.'),
    ('leasing2@meridianestates.example',    'LETTING',             'RERA/ME/AG/0418', DATE '2023-07-03', 600000.00,
     'Letting agent covering commercial and retail stock.'),
    ('sales@meridianestates.example',       'SALES',               'RERA/ME/AG/0122', DATE '2020-09-01', 2500000.00,
     'Head of sales for the capital markets desk.'),
    ('sales2@meridianestates.example',      'SALES',               'RERA/ME/AG/0355', DATE '2022-11-21', 1800000.00,
     'Investment sales agent for the commercial book.')
  ) AS v (email, specialisation, licence, joined_on, target, bio)
  JOIN users u ON u.email = v.email
  CROSS JOIN LATERAL (SELECT id FROM offices WHERE office_code = 'ME-HO') AS o;

  UPDATE agent_profiles SET manager_id = (SELECT id FROM agent_profiles WHERE agent_code = 'ME-AG-001')
   WHERE agent_code <> 'ME-AG-001';


  -- ==========================================================================
  -- 3. Portfolios
  -- ==========================================================================
  INSERT INTO portfolios (id, portfolio_code, name, description, owner_id, manager_id, strategy,
                          target_yield_percent, inception_date, status, notes, created_at, updated_at)
  SELECT gen_random_uuid(), v.code, v.name, v.descr, NULL,
         (SELECT u.id FROM users u WHERE u.email = 'manager@meridianestates.example'),
         v.strategy, v.target, v.inception, 'ACTIVE', 'Seeded demonstration portfolio.', now(), now()
  FROM (VALUES
    ('ME-CORE', 'Meridian Core Income',
     'Stabilised residential assets held for long-term rental income.', 'INCOME', 6.50, DATE '2019-04-01'),
    ('ME-COMM', 'Meridian Commercial Growth',
     'Office, retail and mixed-use assets held for income and capital growth.', 'VALUE_ADD', 8.00, DATE '2020-09-01'),
    ('ME-DEV',  'Meridian Development Book',
     'Land and industrial assets held for development or repositioning.', 'DEVELOPMENT', NULL, DATE '2022-04-01')
  ) AS v (code, name, descr, strategy, target, inception);


  -- ==========================================================================
  -- 4. Properties
  -- ==========================================================================
  -- Fourteen assets across eight cities. Addresses and coordinates are real
  -- locations; see the header. `current_valuation` is the latest assessment and
  -- is what yield is measured against.
  INSERT INTO properties (id, property_code, name, property_type_id, status,
                          address_line1, address_line2, locality, city, state, postal_code, country,
                          latitude, longitude, description, year_built, land_area_sqft, built_area_sqft,
                          acquisition_value, current_valuation, valuation_date,
                          created_at, updated_at)
  SELECT gen_random_uuid(), v.code, v.name, pt.id, v.status,
         v.addr1, v.addr2, v.locality, v.city, v.state, v.postal, 'India',
         v.lat, v.lon, v.descr, v.built, v.land, v.area, v.acq, v.val, DATE '2026-03-31',
         now(), now()
  FROM (VALUES
    ('ME-P-00001','Meridian One','COMMERCIAL_OFFICE','AVAILABLE_ACTIVE',
     'Plot C-62, G Block, Bandra Kurla Complex','Levels 3-7','Bandra Kurla Complex','Mumbai','Maharashtra','400051',
     19.066200, 72.868500,
     'Grade-A office building of fourteen suites over five levels, fully fitted and let to financial-services tenants.',
     2016, 42000.00, 96500.00, 412000000.00, 638000000.00),

    ('ME-P-00002','Harbour Court','RESIDENTIAL_APARTMENT','AVAILABLE_ACTIVE',
     '22 Worli Sea Face Road',NULL,'Worli','Mumbai','Maharashtra','400018',
     19.017600, 72.815700,
     'Two towers of thirty-two sea-facing apartments, completed to a premium specification.',
     2012, 31000.00, 118000.00, 486000000.00, 792000000.00),

    ('ME-P-00003','Deccan Heights','RESIDENTIAL_APARTMENT','AVAILABLE_ACTIVE',
     'Survey 114, Baner-Pashan Link Road','Block A and B','Baner','Pune','Maharashtra','411045',
     18.559000, 73.786800,
     'Thirty apartments across two blocks, close to the Rajiv Gandhi Infotech Park.',
     2018, 26500.00, 74500.00, 138000000.00, 214000000.00),

    ('ME-P-00004','Riverstone Business Park','COMMERCIAL_OFFICE','AVAILABLE_ACTIVE',
     'Plot 7, Hinjawadi Phase II','Building 1 and 2','Hinjawadi','Pune','Maharashtra','411057',
     18.591300, 73.738900,
     'Two-building office park of ten suites serving the Hinjawadi IT corridor.',
     2015, 38000.00, 88000.00, 226000000.00, 341000000.00),

    ('ME-P-00005','Cedar Grove Villas','RESIDENTIAL_VILLA','AVAILABLE_ACTIVE',
     'Cedar Grove, Whitefield Main Road','Villa Cluster','Whitefield','Bengaluru','Karnataka','560066',
     12.969800, 77.750000,
     'Twelve detached villas on landscaped plots, each with a private garden.',
     2017, 68000.00, 54000.00, 205000000.00, 318000000.00),

    ('ME-P-00006','Orion Tech Square','COMMERCIAL_OFFICE','AVAILABLE_ACTIVE',
     'Outer Ring Road, Bellandur Junction','Block A','Bellandur','Bengaluru','Karnataka','560103',
     12.935200, 77.624500,
     'Twelve fitted office suites on the Outer Ring Road, let predominantly to technology tenants.',
     2019, 29500.00, 72000.00, 268000000.00, 402000000.00),

    ('ME-P-00007','Vaishnavi Retail Arcade','COMMERCIAL_RETAIL','AVAILABLE_ACTIVE',
     'Survey 42, Gachibowli-Miyapur Road','Arcade Wing','Gachibowli','Hyderabad','Telangana','500032',
     17.440100, 78.348900,
     'Street-front retail arcade of sixteen units anchored by a supermarket and a pharmacy.',
     2014, 34000.00, 46000.00, 112000000.00, 168000000.00),

    ('ME-P-00008','Golconda Residences','RESIDENTIAL_APARTMENT','AVAILABLE_ACTIVE',
     'Road No. 12, Banjara Hills','Tower 1 and 2','Banjara Hills','Hyderabad','Telangana','500034',
     17.412600, 78.439200,
     'Twenty-four apartments in two towers in the Banjara Hills residential district.',
     2013, 22000.00, 68000.00, 124000000.00, 196000000.00),

    ('ME-P-00009','Aravalli Logistics Hub','INDUSTRIAL_WAREHOUSE','AVAILABLE_ACTIVE',
     'Kherki Daula Industrial Area, Sector 18','Bay Block','Kherki Daula','Gurugram','Haryana','122001',
     28.496500, 77.034500,
     'Six warehouse bays with dock levellers and twenty-four-hour vehicular access.',
     2020, 92000.00, 128000.00, 168000000.00, 244000000.00),

    ('ME-P-00010','Marina Terrace','RESIDENTIAL_APARTMENT','AVAILABLE_ACTIVE',
     '18 Sardar Patel Road, Adyar','Tower A','Adyar','Chennai','Tamil Nadu','600020',
     13.006700, 80.257000,
     'Twenty apartments on a quiet residential stretch close to the Adyar estuary.',
     2015, 19500.00, 58000.00, 92000000.00, 142000000.00),

    ('ME-P-00011','Sabarmati Trade Centre','MIXED_USE','AVAILABLE_ACTIVE',
     'Sarkhej-Gandhinagar Highway, Bodakdev','Trade Tower','Bodakdev','Ahmedabad','Gujarat','380054',
     23.030000, 72.510000,
     'Twelve mixed units with retail at ground level and offices above.',
     2018, 24000.00, 52000.00, 78000000.00, 121000000.00),

    ('ME-P-00012','Salt Lake Chambers','COMMERCIAL_OFFICE','AVAILABLE_ACTIVE',
     'DN Block, Sector V, Salt Lake City','Block V','Salt Lake City','Kolkata','West Bengal','700091',
     22.572600, 88.431200,
     'Eight office suites in the Salt Lake electronics district.',
     2011, 18000.00, 40000.00, 62000000.00, 95000000.00),

    ('ME-P-00013','Emerald Enclave','RESIDENTIAL_APARTMENT','AVAILABLE_ACTIVE',
     'Hebbal Ring Road, Sahakar Nagar','Block A and B','Hebbal','Bengaluru','Karnataka','560092',
     13.035800, 77.597000,
     'Twenty-six apartments across two blocks with a clubhouse and a swimming pool.',
     2020, 27500.00, 79000.00, 146000000.00, 228000000.00),

    ('ME-P-00015','Panvel Land Parcel','LAND_PLOT','AVAILABLE_ACTIVE',
     'Survey 219, Panvel-Karjat Road','Adjacent to the expressway','Panvel','Mumbai','Maharashtra','410206',
     18.989400, 73.117500,
     'Nine acres of undeveloped land held for a future residential development.',
     NULL, 392000.00, NULL, 88000000.00, 156000000.00)
  ) AS v (code, name, type_code, status, addr1, addr2, locality, city, state, postal, lat, lon, descr, built, land, area, acq, val)
  JOIN property_types pt ON pt.code = v.type_code;

  SELECT count(*) INTO v_property_count FROM properties;
  IF v_property_count <> 14 THEN
    RAISE EXCEPTION 'Expected 14 seeded properties, found %', v_property_count;
  END IF;


  -- ==========================================================================
  -- 5. Buildings
  -- ==========================================================================
  INSERT INTO buildings (id, property_id, building_code, name, floors, year_built, description,
                         created_at, updated_at)
  SELECT gen_random_uuid(), p.id, v.bcode, v.bname, v.floors, v.built, v.descr, now(), now()
  FROM (VALUES
    ('ME-P-00001','TOWER','Meridian One Tower', 7, 2016, 'The single office tower.'),
    ('ME-P-00002','T-A','Harbour Court Tower A', 16, 2012, 'North tower, sea-facing.'),
    ('ME-P-00002','T-B','Harbour Court Tower B', 16, 2012, 'South tower, city-facing.'),
    ('ME-P-00003','BLK-A','Deccan Heights Block A', 8, 2018, 'Block A overlooking the internal garden.'),
    ('ME-P-00003','BLK-B','Deccan Heights Block B', 7, 2018, 'Block B facing the link road.'),
    ('ME-P-00004','B-1','Riverstone Building 1', 6, 2015, 'Larger of the two office buildings.'),
    ('ME-P-00004','B-2','Riverstone Building 2', 5, 2015, 'Smaller building, shell and core.'),
    ('ME-P-00005','VILLAS','Cedar Grove Cluster', 2, 2017, 'The twelve villas share one cluster entry.'),
    ('ME-P-00006','BLK-A','Orion Block A', 9, 2019, 'The office block.'),
    ('ME-P-00007','ARC','Vaishnavi Arcade Wing', 3, 2014, 'Retail wing over ground and two upper levels.'),
    ('ME-P-00008','T-1','Golconda Tower 1', 11, 2013, 'First residential tower.'),
    ('ME-P-00008','T-2','Golconda Tower 2', 11, 2013, 'Second residential tower.'),
    ('ME-P-00009','BAY','Aravalli Bay Block', 1, 2020, 'Single-storey warehouse bays.'),
    ('ME-P-00010','T-A','Marina Terrace Tower A', 10, 2015, 'The single residential tower.'),
    ('ME-P-00011','TT','Sabarmati Trade Tower', 6, 2018, 'Retail podium with offices above.'),
    ('ME-P-00012','BLK-V','Salt Lake Block V', 5, 2011, 'The office block.'),
    ('ME-P-00013','BLK-A','Emerald Enclave Block A', 9, 2020, 'Block A with the clubhouse.'),
    ('ME-P-00013','BLK-B','Emerald Enclave Block B', 9, 2020, 'Block B facing the park.')
  ) AS v (pcode, bcode, bname, floors, built, descr)
  JOIN properties p ON p.property_code = v.pcode;


  -- ==========================================================================
  -- 6. Property amenities
  -- ==========================================================================
  -- Linked from a per-type baseline plus a couple of type-specific additions, so
  -- the facilities on a warehouse are not the facilities on a villa.
  INSERT INTO property_amenities (property_id, amenity_id, notes)
  SELECT p.id, a.id, NULL
  FROM properties p
  JOIN property_types pt ON pt.id = p.property_type_id
  JOIN amenities a ON a.code::text = ANY (
    CASE pt.code
      WHEN 'RESIDENTIAL_APARTMENT' THEN ARRAY['POWER_BACKUP','DG_BACKUP','WATER_24X7','LIFT','SECURITY_24X7','CCTV',
                                               'FIRE_SAFETY','PARKING_BASEMENT','GYM','CLUBHOUSE','LANDSCAPED_GARDEN',
                                               'RAINWATER_HARVESTING']
      WHEN 'RESIDENTIAL_VILLA'     THEN ARRAY['POWER_BACKUP','DG_BACKUP','WATER_24X7','SECURITY_24X7','CCTV',
                                               'SOLAR_POWER','PARKING_OPEN','LANDSCAPED_GARDEN','RAINWATER_HARVESTING']
      WHEN 'COMMERCIAL_OFFICE'     THEN ARRAY['POWER_BACKUP','DG_BACKUP','WATER_24X7','LIFT','SECURITY_24X7','CCTV',
                                               'FIRE_SAFETY','PARKING_BASEMENT','LIFT']
      WHEN 'COMMERCIAL_RETAIL'     THEN ARRAY['POWER_BACKUP','DG_BACKUP','WATER_24X7','SECURITY_24X7','CCTV','FIRE_SAFETY']
      WHEN 'INDUSTRIAL_WAREHOUSE'  THEN ARRAY['POWER_BACKUP','DG_BACKUP','WATER_24X7','SECURITY_24X7','CCTV',
                                               'FIRE_SAFETY','LOADING_DOCK','FREIGHT_ELEVATOR','PARKING_OPEN']
      WHEN 'MIXED_USE'             THEN ARRAY['POWER_BACKUP','DG_BACKUP','WATER_24X7','LIFT','SECURITY_24X7','CCTV',
                                               'FIRE_SAFETY','PARKING_BASEMENT']
      ELSE ARRAY['SECURITY_24X7']
    END
  )
  ON CONFLICT DO NOTHING;


  -- ==========================================================================
  -- 7. Units
  -- ==========================================================================
  -- Generated from a per-property plan rather than listed by hand: 222 units
  -- written as literals would be unreadable and unmaintainable, and the shape of
  -- a block - n units per floor over however many floors - is exactly the kind of
  -- thing a loop expresses better than a list.
  CREATE TEMP TABLE seed_unit_plan (
    property_code text, unit_count integer, per_floor integer, unit_type_code text, bedrooms integer,
    bathrooms integer, carpet numeric, built_up numeric, rent numeric, price numeric
  ) ON COMMIT DROP;

  INSERT INTO seed_unit_plan VALUES
    ('ME-P-00001', 14,  3, 'OFFICE_SUITE',  0, 2, 1150.00, 1420.00,  385000.00, NULL),
    ('ME-P-00002', 32,  2, 'BHK_3',         3, 3, 1420.00, 1880.00,  265000.00, NULL),
    ('ME-P-00003', 30,  4, 'BHK_2',         2, 2,  880.00, 1180.00,   92000.00, NULL),
    ('ME-P-00004', 10,  2, 'OFFICE_SUITE',  0, 2, 1650.00, 2100.00,  310000.00, NULL),
    ('ME-P-00005', 12,  1, 'BHK_4_PLUS',    4, 4, 2450.00, 3200.00,  385000.00, NULL),
    ('ME-P-00006', 12,  2, 'OFFICE_SUITE',  0, 2, 1980.00, 2450.00,  420000.00, NULL),
    ('ME-P-00007', 16,  4, 'RETAIL_UNIT',   0, 1,  620.00,  760.00,  148000.00, NULL),
    ('ME-P-00008', 24,  3, 'BHK_2',         2, 2,  940.00, 1240.00,   98000.00, NULL),
    ('ME-P-00009',  6,  1, 'WAREHOUSE_BAY', 0, 1, 8400.00, 9600.00,  520000.00, NULL),
    ('ME-P-00010', 20,  2, 'BHK_2',         2, 2,  860.00, 1120.00,   84000.00, NULL),
    ('ME-P-00011', 12,  2, 'OFFICE_SUITE',  0, 2, 1080.00, 1360.00,  168000.00, NULL),
    ('ME-P-00012',  8,  2, 'OFFICE_SUITE',  0, 2,  980.00, 1240.00,  152000.00, NULL),
    ('ME-P-00013', 26,  3, 'BHK_3',         3, 2, 1280.00, 1680.00,  128000.00, NULL);

  FOR rec IN
    SELECT sp.*, p.id AS pid FROM seed_unit_plan sp
    JOIN properties p ON p.property_code = sp.property_code
    ORDER BY sp.property_code
  LOOP
    SELECT id INTO v_unit_type_id FROM unit_types WHERE code = rec.unit_type_code;

    FOR v_i IN 1..rec.unit_count LOOP
      v_unit_seq := v_unit_seq + 1;
      v_floor := (v_i - 1) / rec.per_floor;
      v_unit_in_floor := ((v_i - 1) % rec.per_floor) + 1;
      v_floor_label := CASE WHEN v_floor = 0 THEN 'G' ELSE v_floor::text END;

      -- Building: alternate between the property's buildings, so a two-block
      -- property genuinely has units in both rather than all of them in the first.
      SELECT b.id INTO v_building_id
      FROM buildings b
      WHERE b.property_id = rec.pid AND b.deleted_at IS NULL
      ORDER BY b.building_code
      OFFSET ((v_i - 1) % GREATEST(1, (SELECT count(*) FROM buildings WHERE property_id = rec.pid)))
      LIMIT 1;

      -- Status distribution, weighted so the portfolio reads as a working book:
      -- mostly let, a realistic tail of vacancy, and a handful of units in each
      -- operational state. V23 creates a lease for every OCCUPIED unit and for no
      -- other, so occupancy and the rent roll cannot disagree.
      --
      -- The distribution keys off v_unit_seq (the portfolio-wide 1..222 counter),
      -- NOT off v_i (the per-property counter). The largest property in the plan
      -- holds 32 units, so v_i never reaches 72: a v_i-based test sends every unit
      -- down the first branch and the book silently becomes 100% occupied, with no
      -- vacancy, no reserved stock, no maintenance state - and a lease against all
      -- 222 units. The guard below now fails the migration if that ever recurs.
      v_status := CASE
        WHEN (v_unit_seq % 100) < 72 THEN 'OCCUPIED'
        WHEN (v_unit_seq % 100) < 85 THEN 'AVAILABLE'
        WHEN (v_unit_seq % 100) < 91 THEN 'RESERVED'
        WHEN (v_unit_seq % 100) < 96 THEN 'UNDER_MAINTENANCE'
        WHEN (v_unit_seq % 100) < 98 THEN 'LISTED'
        ELSE 'UNDER_CONTRACT'
      END;

      INSERT INTO units (id, unit_code, property_id, building_id, unit_type_id, unit_number,
                         floor_number, floor_label, bedrooms, bathrooms, balconies,
                         carpet_area_sqft, built_up_area_sqft, parking_slots,
                         furnishing, facing, status, leasable, asking_rent, asking_price,
                         created_at, updated_at)
      VALUES (
        gen_random_uuid(), 'ME-U-' || lpad(v_unit_seq::text, 6, '0'), rec.pid, v_building_id, v_unit_type_id,
        v_floor_label || '-' || lpad(v_unit_in_floor::text, 2, '0'),
        v_floor, v_floor_label, rec.bedrooms, rec.bathrooms,
        CASE WHEN rec.bedrooms = 0 THEN 0 ELSE 1 END,
        rec.carpet, rec.built_up,
        CASE WHEN rec.bedrooms = 0 THEN 2 ELSE 1 END,
        CASE WHEN rec.bedrooms = 0 THEN 'FURNISHED' ELSE (ARRAY['UNFURNISHED','SEMI_FURNISHED','FURNISHED'])[1 + (v_i % 3)] END,
        (ARRAY['N','S','E','W','NE','NW','SE','SW'])[1 + (v_i % 8)],
        v_status, true, rec.rent, rec.price, now(), now()
      );
    END LOOP;
  END LOOP;

  SELECT count(*) INTO v_unit_count FROM units;
  IF v_unit_count <> 222 THEN
    RAISE EXCEPTION 'Expected 222 seeded units, found %', v_unit_count;
  END IF;

  -- The occupancy this book reads as has to be a measurement, not a foregone
  -- conclusion. The distribution above lands 166 of the 222 units as OCCUPIED
  -- (74.8%), which is what the whole portfolio story - vacancy, the rent roll,
  -- the arrears tail - is derived from. A collapse back to "every unit occupied"
  -- is caught here rather than being noticed on the dashboard.
  SELECT count(*) INTO v_occupied_count FROM units WHERE status = 'OCCUPIED';
  IF v_occupied_count < (v_unit_count * 60 / 100)
     OR v_occupied_count > (v_unit_count * 80 / 100) THEN
    RAISE EXCEPTION 'Occupancy out of band: % of % units are OCCUPIED',
      v_occupied_count, v_unit_count;
  END IF;

  -- And the operational states have to exist: a book with nothing available, nothing
  -- reserved and nothing under maintenance is not a working portfolio.
  IF (SELECT count(DISTINCT status) FROM units) < 4 THEN
    RAISE EXCEPTION 'Unit status distribution collapsed to % distinct states',
      (SELECT count(DISTINCT status) FROM units);
  END IF;


  -- ==========================================================================
  -- 8. Owners and holdings
  -- ==========================================================================
  INSERT INTO owners (id, owner_code, owner_type, display_name, primary_contact_name, email, phone,
                      tax_reference, city, state, country, status, created_at, updated_at)
  VALUES
    (gen_random_uuid(),'ME-O-00001','COMPANY','Meridian Holdings Private Limited','Aditya Raghunathan','accounts@meridianholdings.example','+91-22-6155-4410','AABCM1234H','Mumbai','Maharashtra','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00002','INDIVIDUAL','Rukmini Iyengar',NULL,'rukmini.iyengar@example.com','+91-98200-30002','ABCPI5678K','Mumbai','Maharashtra','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00003','TRUST','Saraswati Family Trust','Vikram Iyengar','trustee@saraswatitrust.example','+91-98200-30003','AABTS9012L','Mumbai','Maharashtra','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00004','COMPANY','Konkan Realty Developers Private Limited','Sunita Kamat','contact@konkanrealty.example','+91-22-6712-4455','AACCK3456M','Pune','Maharashtra','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00005','PARTNERSHIP','Deccan Builders & Co.','Mahesh Joshi','partner@deccanbuilders.example','+91-20-6712-3311','AADFD7890N','Pune','Maharashtra','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00006','INDIVIDUAL','Anand Gokhale',NULL,'anand.gokhale@example.com','+91-98220-30006','ABCPG2345P','Pune','Maharashtra','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00007','COMPANY','Cauvery Estates Private Limited','Lakshmi Narayan','finance@cauveryestates.example','+91-80-4712-8899','AABCC6789Q','Bengaluru','Karnataka','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00008','INDIVIDUAL','Shalini Rao',NULL,'shalini.rao@example.com','+91-98450-30008','ABCPR1234R','Bengaluru','Karnataka','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00009','JOINT_VENTURE','Orion Tech Developers LLP','Ravi Chandran','admin@oriontechdev.example','+91-80-4712-8877','AAJVO5678S','Bengaluru','Karnataka','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00010','COMPANY','Musi Holdings Private Limited','Faisal Ahmed','accounts@musiholdings.example','+91-40-4455-6677','AABCM9012T','Hyderabad','Telangana','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00011','INDIVIDUAL','Padma Reddy',NULL,'padma.reddy@example.com','+91-99490-30011','ABCPR3456U','Hyderabad','Telangana','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00012','COMPANY','Aravalli Logistics Parks Private Limited','Harpreet Singh','finance@aravallilogistics.example','+91-124-4455-6600','AABCA7890V','Gurugram','Haryana','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00013','INDIVIDUAL','Naresh Kumar',NULL,'naresh.kumar@example.com','+91-98110-30013','ABCPK2345W','Gurugram','Haryana','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00014','COMPANY','Marina Property Holdings Private Limited','Sridhar Krishnan','accounts@marinaproperty.example','+91-44-4455-6622','AABCM3456X','Chennai','Tamil Nadu','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00015','INDIVIDUAL','Kamala Subramanian',NULL,'kamala.subramanian@example.com','+91-98400-30015','ABCPS6789Y','Chennai','Tamil Nadu','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00016','COMPANY','Sabarmati Trade Estates Private Limited','Nikhil Desai','finance@sabarmatitrade.example','+91-79-4455-6633','AABCS9012Z','Ahmedabad','Gujarat','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00017','INDIVIDUAL','Bhavna Mehta',NULL,'bhavna.mehta@example.com','+91-99790-30017','ABCPM2345A','Ahmedabad','Gujarat','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00018','COMPANY','Hooghly Chambers Private Limited','Amitava Sen','accounts@hooghlychambers.example','+91-33-4455-6644','AABCH5678B','Kolkata','West Bengal','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00019','INDIVIDUAL','Sujata Chatterjee',NULL,'sujata.chatterjee@example.com','+91-98300-30019','ABCPC9012C','Kolkata','West Bengal','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00020','COMPANY','Panvel Land Holdings Private Limited','Dinesh Pawar','finance@panvelland.example','+91-22-6712-4488','AABCP3456D','Mumbai','Maharashtra','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00021','GOVERNMENT','Maharashtra Industrial Development Corporation','Estate Officer','estates@midc.example','+91-22-2202-0000',NULL,'Mumbai','Maharashtra','India','ACTIVE',now(),now()),
    (gen_random_uuid(),'ME-O-00022','INDIVIDUAL','Vikram Iyengar',NULL,'vikram.iyengar@example.com','+91-98200-30022','ABCPI4567E','Mumbai','Maharashtra','India','ACTIVE',now(),now());

  -- Holdings. Every property's open holdings sum to exactly 100 per cent; the
  -- service layer enforces the same rule on live data, and the guard at the foot
  -- of this migration proves the seed satisfies it.
  INSERT INTO property_ownerships (id, property_id, owner_id, ownership_percentage, relationship,
                                   effective_from, effective_to, notes, created_at, updated_at)
  SELECT gen_random_uuid(), p.id, o.id, v.pct, v.rel, v.from_date, NULL, NULL, now(), now()
  FROM (VALUES
    ('ME-P-00001','ME-O-00001',60.00,'MAJORITY_HOLDER',DATE '2016-06-01'),
    ('ME-P-00001','ME-O-00007',40.00,'MINORITY_HOLDER',DATE '2016-06-01'),
    ('ME-P-00002','ME-O-00002',45.00,'CO_OWNER',DATE '2012-09-01'),
    ('ME-P-00002','ME-O-00003',35.00,'BENEFICIARY',DATE '2012-09-01'),
    ('ME-P-00002','ME-O-00022',20.00,'CO_OWNER',DATE '2019-04-15'),
    ('ME-P-00003','ME-O-00004',100.00,'SOLE_OWNER',DATE '2018-03-01'),
    ('ME-P-00004','ME-O-00005',55.00,'MAJORITY_HOLDER',DATE '2015-08-01'),
    ('ME-P-00004','ME-O-00006',45.00,'MINORITY_HOLDER',DATE '2015-08-01'),
    ('ME-P-00005','ME-O-00007',100.00,'SOLE_OWNER',DATE '2017-05-01'),
    ('ME-P-00006','ME-O-00009',70.00,'DEVELOPMENT_PARTNER',DATE '2019-02-01'),
    ('ME-P-00006','ME-O-00008',30.00,'CO_OWNER',DATE '2019-02-01'),
    ('ME-P-00007','ME-O-00010',100.00,'SOLE_OWNER',DATE '2014-07-01'),
    ('ME-P-00008','ME-O-00010',65.00,'MAJORITY_HOLDER',DATE '2013-10-01'),
    ('ME-P-00008','ME-O-00011',35.00,'MINORITY_HOLDER',DATE '2013-10-01'),
    ('ME-P-00009','ME-O-00012',80.00,'MAJORITY_HOLDER',DATE '2020-01-15'),
    ('ME-P-00009','ME-O-00013',20.00,'MINORITY_HOLDER',DATE '2020-01-15'),
    ('ME-P-00010','ME-O-00014',100.00,'SOLE_OWNER',DATE '2015-11-01'),
    ('ME-P-00011','ME-O-00016',75.00,'MAJORITY_HOLDER',DATE '2018-06-01'),
    ('ME-P-00011','ME-O-00017',25.00,'MINORITY_HOLDER',DATE '2018-06-01'),
    ('ME-P-00012','ME-O-00018',100.00,'SOLE_OWNER',DATE '2011-12-01'),
    ('ME-P-00013','ME-O-00007',100.00,'SOLE_OWNER',DATE '2020-08-01'),
    ('ME-P-00015','ME-O-00020',85.00,'MAJORITY_HOLDER',DATE '2022-04-01'),
    ('ME-P-00015','ME-O-00021',15.00,'MINORITY_HOLDER',DATE '2022-04-01')
  ) AS v (pcode, ocode, pct, rel, from_date)
  JOIN properties p ON p.property_code = v.pcode
  JOIN owners o ON o.owner_code = v.ocode;


  -- ==========================================================================
  -- 9. Portfolio allocation
  -- ==========================================================================
  INSERT INTO portfolio_properties (id, portfolio_id, property_id, allocated_at, notes, created_at, updated_at)
  SELECT gen_random_uuid(), pf.id, p.id, v.alloc, NULL, now(), now()
  FROM (VALUES
    ('ME-CORE','ME-P-00002',DATE '2019-04-01'), ('ME-CORE','ME-P-00003',DATE '2019-04-01'),
    ('ME-CORE','ME-P-00005',DATE '2019-06-01'), ('ME-CORE','ME-P-00008',DATE '2019-04-01'),
    ('ME-CORE','ME-P-00010',DATE '2020-01-01'), ('ME-CORE','ME-P-00013',DATE '2021-01-01'),
    ('ME-COMM','ME-P-00001',DATE '2020-09-01'), ('ME-COMM','ME-P-00004',DATE '2020-09-01'),
    ('ME-COMM','ME-P-00006',DATE '2020-09-01'), ('ME-COMM','ME-P-00007',DATE '2021-03-01'),
    ('ME-COMM','ME-P-00011',DATE '2021-03-01'), ('ME-COMM','ME-P-00012',DATE '2020-09-01'),
    ('ME-DEV', 'ME-P-00009',DATE '2022-04-01'), ('ME-DEV', 'ME-P-00015',DATE '2022-04-01')
  ) AS v (pfcode, pcode, alloc)
  JOIN portfolios pf ON pf.portfolio_code = v.pfcode
  JOIN properties p ON p.property_code = v.pcode;


  -- ==========================================================================
  -- 10. Agent property assignments
  -- ==========================================================================
  -- The residential book to the two letting agents, the commercial and retail
  -- book to the second letting agent, and the development assets to the sales
  -- desk. Exactly one open PRIMARY row per property - which the V7 unique index
  -- enforces, so this insert would fail rather than silently double-assign.
  INSERT INTO agent_property_assignments (id, agent_id, property_id, role, assigned_from,
                                          assigned_to, created_at, updated_at)
  SELECT gen_random_uuid(), ap.id, p.id, 'PRIMARY', v.from_date, NULL, now(), now()
  FROM (VALUES
    ('ME-P-00002','ME-AG-002',DATE '2021-02-15'), ('ME-P-00003','ME-AG-003',DATE '2023-07-03'),
    ('ME-P-00005','ME-AG-002',DATE '2021-02-15'), ('ME-P-00008','ME-AG-003',DATE '2023-07-03'),
    ('ME-P-00010','ME-AG-002',DATE '2021-02-15'), ('ME-P-00013','ME-AG-003',DATE '2023-07-03'),
    ('ME-P-00001','ME-AG-003',DATE '2023-07-03'), ('ME-P-00004','ME-AG-003',DATE '2023-07-03'),
    ('ME-P-00006','ME-AG-002',DATE '2021-02-15'), ('ME-P-00007','ME-AG-003',DATE '2023-07-03'),
    ('ME-P-00011','ME-AG-002',DATE '2021-02-15'), ('ME-P-00012','ME-AG-003',DATE '2023-07-03'),
    ('ME-P-00009','ME-AG-004',DATE '2020-09-01'), ('ME-P-00015','ME-AG-004',DATE '2020-09-01')
  ) AS v (pcode, acode, from_date)
  JOIN properties p ON p.property_code = v.pcode
  JOIN agent_profiles ap ON ap.agent_code = v.acode;


  -- ==========================================================================
  -- 11. Advance the business-number series above the seeded range
  -- ==========================================================================
  -- The seed uses explicit codes so the demonstration data reads predictably.
  -- Without this the first live insert would mint ME-P-00001 a second time and
  -- collide with a seeded row.
  UPDATE business_number_sequences SET next_value = 16,  updated_at = now() WHERE code = 'PROPERTY';
  UPDATE business_number_sequences SET next_value = 223, updated_at = now() WHERE code = 'UNIT';
  UPDATE business_number_sequences SET next_value = 23,  updated_at = now() WHERE code = 'OWNER';


  -- ==========================================================================
  -- 12. Guards
  -- ==========================================================================
  -- These assert the properties the rest of the product depends on. An ownership
  -- book that does not sum to 100, or a property with no agent, would look fine
  -- until a report quietly produced a number that was wrong.

  -- Every property's open holdings sum to exactly 100 per cent.
  FOR rec IN
    SELECT p.property_code, sum(po.ownership_percentage) AS total
    FROM properties p
    JOIN property_ownerships po ON po.property_id = p.id AND po.effective_to IS NULL AND po.deleted_at IS NULL
    GROUP BY p.property_code
    HAVING sum(po.ownership_percentage) <> 100
  LOOP
    RAISE EXCEPTION 'Property % holdings sum to %, not 100', rec.property_code, rec.total;
  END LOOP;

  -- Every property has an owner at all.
  FOR rec IN
    SELECT p.property_code FROM properties p
    WHERE NOT EXISTS (
      SELECT 1 FROM property_ownerships po
       WHERE po.property_id = p.id AND po.deleted_at IS NULL
    )
  LOOP
    RAISE EXCEPTION 'Property % has no ownership record', rec.property_code;
  END LOOP;

  -- Every property has a current primary agent, so "who carries this property"
  -- always has an answer.
  FOR rec IN
    SELECT p.property_code FROM properties p
    WHERE NOT EXISTS (
      SELECT 1 FROM agent_property_assignments a
       WHERE a.property_id = p.id AND a.role = 'PRIMARY'
         AND a.assigned_to IS NULL AND a.deleted_at IS NULL
    )
  LOOP
    RAISE EXCEPTION 'Property % has no current primary agent', rec.property_code;
  END LOOP;

  -- Every property is valued, because portfolio value and yield are both
  -- meaningless without a valuation.
  FOR rec IN
    SELECT property_code FROM properties WHERE current_valuation IS NULL OR current_valuation <= 0
  LOOP
    RAISE EXCEPTION 'Property % has no usable valuation', rec.property_code;
  END LOOP;

  -- The map is a dashboard surface, so most of the book must be plottable.
  IF (SELECT count(*) FROM properties WHERE latitude IS NOT NULL) < 14 THEN
    RAISE EXCEPTION 'Fewer than 14 properties carry coordinates; the portfolio map would be mostly empty';
  END IF;

  RAISE NOTICE 'Meridian Estates portfolio seeded: % properties, % units, % owners.',
    (SELECT count(*) FROM properties), (SELECT count(*) FROM units), (SELECT count(*) FROM owners);

END $$;

ANALYZE properties;
ANALYZE units;
ANALYZE buildings;
ANALYZE owners;
ANALYZE property_ownerships;
ANALYZE agent_property_assignments;
ANALYZE portfolio_properties;
