-- ============================================================================
-- V21 - Hardening: composite indexes for the queries the product actually runs
-- ============================================================================
-- The per-table indexes created alongside each table cover the lookups a service
-- makes in isolation. This migration adds the composites that the dashboard,
-- the unit board, the rent roll and the reports need, where the real query
-- filters on one column and orders by another and a per-column index would leave
-- a sort behind.
--
-- Every index here is justified by a specific read. An index that no query uses
-- is a write cost with no benefit, so none is added speculatively.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- The portfolio register: filter by status, order by value.
-- The dashboard's property ranking and the register's default sort both do this.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_properties_status_valuation ON properties (status, current_valuation DESC NULLS LAST)
  WHERE deleted_at IS NULL;

-- The register's "newest first" ordering on the properties page.
CREATE INDEX ix_properties_created ON properties (created_at DESC) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- The unit board: filter by property, group by building and floor, read status.
-- This is the single busiest read in the product - it renders every unit of a
-- property on one screen.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_units_board ON units (property_id, building_id, floor_number, unit_number)
  WHERE deleted_at IS NULL;

-- Occupancy by property: count the lettable stock in each state, per property.
CREATE INDEX ix_units_property_leasable_status ON units (property_id, status)
  WHERE leasable = true AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- The rent roll: leases by tenant and state, and the expiring report.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_leases_tenant_status ON leases (tenant_id, status) WHERE deleted_at IS NULL;

-- The expiring-lease timeline reads running leases ordered by end date, which the
-- existing partial index covers; this one adds the tenant join key so the report
-- does not fall back to a per-row lookup.
CREATE INDEX ix_leases_expiry_report ON leases (end_date, tenant_id, unit_id)
  WHERE status IN ('ACTIVE', 'EXPIRING') AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- Collections: invoices by lease and due date, and the overdue calculation.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_rent_invoices_lease_due ON rent_invoices (lease_id, due_date)
  WHERE status = 'ISSUED' AND deleted_at IS NULL;

-- The collections trend aggregates issued invoices by period across the whole
-- book, ordered by the period start.
CREATE INDEX ix_rent_invoices_period ON rent_invoices (period_start, status)
  WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- Allocations: the settlement of one invoice, which is recomputed on every
-- invoice read. Without this the invoice list is N+1.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_payment_allocations_invoice_live ON payment_allocations (invoice_id, payment_id)
  WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- The lead pipeline: group by stage, order by value; and the follow-up diary.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_leads_stage_value ON leads (stage, expected_value DESC NULLS LAST)
  WHERE deleted_at IS NULL;

CREATE INDEX ix_leads_agent_stage ON leads (owner_agent_id, stage) WHERE deleted_at IS NULL;

-- The funnel's period comparison reads leads created within a window.
CREATE INDEX ix_leads_created_stage ON leads (created_at, stage) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- The viewing diary: one agent's schedule over a date range.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_viewings_agent_schedule ON viewings (agent_id, scheduled_at)
  WHERE deleted_at IS NULL;

-- Viewings for one property, newest first - the property detail's viewings tab.
CREATE INDEX ix_viewings_property_schedule ON viewings (property_id, scheduled_at DESC)
  WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- Maintenance: open work per property, which is what the dashboard counts.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_maintenance_requests_property_status ON maintenance_requests (property_id, status)
  WHERE deleted_at IS NULL;

-- The urgent queue: open work ordered by how long it has been waiting.
CREATE INDEX ix_maintenance_requests_urgent ON maintenance_requests (reported_at)
  WHERE status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS') AND priority IN ('HIGH', 'URGENT')
    AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- The sales pipeline, and offers per opportunity.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_sales_opportunities_pipeline_value ON sales_opportunities (stage, expected_price DESC NULLS LAST)
  WHERE stage NOT IN ('CLOSED', 'LOST') AND deleted_at IS NULL;

CREATE INDEX ix_offers_sale_status ON offers (sale_id, status) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- The document expiry report and the property detail's document tab.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_documents_property_type ON documents (property_id, document_type)
  WHERE property_id IS NOT NULL AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- Communication: the console's rail and the per-record thread list.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_conversations_property_recent ON conversations (property_id, last_message_at DESC NULLS LAST)
  WHERE property_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX ix_conversations_tenant_recent ON conversations (tenant_id, last_message_at DESC NULLS LAST)
  WHERE tenant_id IS NOT NULL AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- Audit: the per-record trails the detail screens show.
-- ----------------------------------------------------------------------------
CREATE INDEX ix_audit_logs_lease_created ON audit_logs (lease_id, created_at DESC)
  WHERE lease_id IS NOT NULL;

CREATE INDEX ix_audit_logs_actor_created ON audit_logs (actor_id, created_at DESC)
  WHERE actor_id IS NOT NULL;


-- ----------------------------------------------------------------------------
-- ANALYZE
-- ----------------------------------------------------------------------------
-- The demonstration data is committed by the next two migrations. Analysing now
-- gives the planner statistics for the empty tables, so the first queries after
-- the seed do not run against a plan built from nothing; the seed migrations run
-- ANALYZE again when they have finished.
ANALYZE;
