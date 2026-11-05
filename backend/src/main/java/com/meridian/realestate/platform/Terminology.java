package com.meridian.realestate.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The portfolio's vocabulary.
 *
 * <p>The product speaks real estate: the thing the firm owns is a property, what a tenant occupies
 * is a unit, the contract that makes a unit occupied is a lease, and the money a tenant owes is
 * rent. The frontend renders these strings rather than its own literals, so a deployment can adopt
 * a different word - "block" for "building", "flat" for "unit", "tenancy" for "lease" - without a
 * code change, and no screen invents its own copy of a noun the rest of the product already
 * names.</p>
 *
 * <p>{@link #all()} is total by construction: the map is built once here, and a key the frontend
 * asks for and does not find resolves to the key itself (see {@link #get(String)}) rather than to a
 * blank, so a missing term is visible rather than silently empty.</p>
 */
@Component
public class Terminology {

  private final Map<String, String> terms = new LinkedHashMap<>();

  public Terminology() {
    // The asset and its parts
    term("PROPERTY", "Property");
    term("PROPERTY_PLURAL", "Properties");
    term("BUILDING", "Building");
    term("BUILDING_PLURAL", "Buildings");
    term("BLOCK", "Block");
    term("UNIT", "Unit");
    term("UNIT_PLURAL", "Units");
    term("UNIT_TYPE", "Unit Type");
    term("FLOOR", "Floor");
    term("LEASABLE", "Leasable");
    term("PORTFOLIO", "Portfolio");
    term("PORTFOLIO_PLURAL", "Portfolios");

    // The people
    term("OWNER", "Owner");
    term("OWNER_PLURAL", "Owners");
    term("TENANT", "Tenant");
    term("TENANT_PLURAL", "Tenants");
    term("BUYER", "Buyer");
    term("BUYER_PLURAL", "Buyers");
    term("AGENT", "Agent");
    term("AGENT_PLURAL", "Agents");
    term("OFFICE", "Office");
    term("OFFICE_PLURAL", "Offices");

    // The letting pipeline
    term("LEAD", "Lead");
    term("LEAD_PLURAL", "Leads");
    term("LISTING", "Listing");
    term("LISTING_PLURAL", "Listings");
    term("VIEWING", "Viewing");
    term("VIEWING_PLURAL", "Viewings");
    term("APPLICATION", "Application");
    term("APPLICATION_PLURAL", "Applications");
    term("LEASE", "Lease");
    term("LEASE_PLURAL", "Leases");
    term("TENANCY", "Tenancy");
    term("RENEWAL", "Renewal");

    // The money
    term("RENT", "Rent");
    term("RENT_ROLL", "Rent Roll");
    term("DEPOSIT", "Deposit");
    term("PAYMENT", "Payment");
    term("PAYMENT_PLURAL", "Payments");
    term("RECEIPT", "Receipt");
    term("INVOICE", "Invoice");
    term("OUTSTANDING", "Outstanding");
    term("OVERDUE", "Overdue");
    term("ARREARS", "Arrears");
    term("EXPENSE", "Expense");
    term("EXPENSE_PLURAL", "Expenses");
    term("NET_OPERATING_INCOME", "Net Operating Income");
    term("YIELD", "Yield");
    term("VALUATION", "Valuation");

    // The stock's condition and the capital side
    term("MAINTENANCE", "Maintenance");
    term("VENDOR", "Vendor");
    term("SALE", "Sale");
    term("SALE_PLURAL", "Sales");
    term("OFFER", "Offer");

    // Occupancy
    term("OCCUPANCY", "Occupancy");
    term("VACANCY", "Vacancy");
    term("TARGET_OCCUPANCY", "Target Occupancy");

    // Cross-cutting
    term("DASHBOARD", "Dashboard");
    term("REPORT", "Report");
    term("REPORT_PLURAL", "Reports");
    term("DOCUMENT", "Document");
    term("DOCUMENT_PLURAL", "Documents");
    term("COMMUNICATION", "Communication");
    term("NOTIFICATION", "Notification");
    term("NOTIFICATION_PLURAL", "Notifications");
    term("DATA_IMPORT", "Data Import");
    term("AUDIT_LOG", "Audit Log");
    term("AUDIT_LOG_PLURAL", "Audit Logs");
    term("SETTINGS", "Settings");
    term("PROFILE", "Profile");
    term("STAFF", "Staff");
  }

  private void term(String key, String value) {
    terms.put(key, value);
  }

  /** Resolve a term, falling back to the key so a missing term is visible rather than blank. */
  public String get(String key) {
    return terms.getOrDefault(key, key);
  }

  public Map<String, String> all() {
    return Map.copyOf(terms);
  }
}
