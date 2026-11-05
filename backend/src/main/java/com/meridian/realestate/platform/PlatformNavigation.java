package com.meridian.realestate.platform;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The sidebar, served as data.
 *
 * <p>The frontend renders whatever this returns rather than owning a hardcoded menu, so the
 * navigation a deployment shows is part of the product's configuration. Each entry names the
 * permission it needs - a real code from {@code V2}, never an invented one - and the frontend
 * filters on the permissions carried on the signed-in user's access token. Every destination
 * re-checks server-side when it is actually called, so hiding an entry is presentation and never
 * protection.</p>
 *
 * <p><b>Only routes that exist are listed.</b> An entry that leads nowhere is worse than a missing
 * one, so this list is exactly the product's own screens.</p>
 *
 * <p>The {@code iconKey} is a stable key the frontend maps to a component - sending a component name
 * would tie the API to the frontend's icon library. It is drawn from a fixed set; {@code /offices}
 * carries none because there is no distinct icon for a branch in that set.</p>
 *
 * <p>{@code /profile} carries no permission: it is a person's own account, which every authenticated
 * user must be able to reach, and {@code V2} defines no permission for it - inventing one would
 * either hide a universal screen or grant a capability nobody asked for. Every other entry carries a
 * real {@code V2} code.</p>
 */
@Component
public class PlatformNavigation {

  /** One sidebar destination. */
  public record NavigationEntry(
      String key,
      String label,
      String to,
      String iconKey,
      String group,
      String permission,
      int displayOrder) {}

  /** The icon keys the frontend may be asked to render, and nothing else. Declared first so the
   * entry list below, which validates against it at class-initialisation time, sees it. */
  private static final List<String> ICON_KEYS =
      List.of(
          "LayoutDashboard", "Building2", "DoorOpen", "Users", "UserCheck", "Briefcase", "Handshake",
          "Target", "Tags", "CalendarCheck", "FileCheck2", "FileText", "Wallet", "Wrench", "Receipt",
          "TrendingUp", "PieChart", "FolderOpen", "MessagesSquare", "BarChart3", "Upload",
          "ShieldCheck", "Settings", "UserCircle");

  private static final List<NavigationEntry> ENTRIES =
      List.of(
          // Overview
          entry("dashboard", "Dashboard", "/dashboard", "LayoutDashboard", "Overview", "REPORT_VIEW", 10),

          // The register
          entry("properties", "Properties", "/properties", "Building2", "Register", "PROPERTY_VIEW", 20),
          entry("units", "Units", "/units", "DoorOpen", "Register", "UNIT_VIEW", 30),
          entry("portfolios", "Portfolios", "/portfolios", "PieChart", "Register", "PORTFOLIO_VIEW", 40),

          // The people
          entry("owners", "Owners", "/owners", "Users", "People", "OWNER_VIEW", 50),
          entry("tenants", "Tenants", "/tenants", "UserCheck", "People", "TENANT_VIEW", 60),
          entry("buyers", "Buyers", "/buyers", "Briefcase", "People", "BUYER_VIEW", 70),
          entry("offices", "Offices", "/offices", null, "People", "AGENT_VIEW", 80),
          entry("agents", "Agents", "/agents", "Handshake", "People", "AGENT_VIEW", 90),

          // The letting desk
          entry("leads", "Leads", "/leads", "Target", "Letting", "LEAD_VIEW", 100),
          entry("listings", "Listings", "/listings", "Tags", "Letting", "LISTING_VIEW", 110),
          entry("viewings", "Viewings", "/viewings", "CalendarCheck", "Letting", "VIEWING_VIEW", 120),
          entry("applications", "Applications", "/applications", "FileCheck2", "Letting", "APPLICATION_VIEW", 130),
          entry("leases", "Leases", "/leases", "FileText", "Letting", "LEASE_VIEW", 140),

          // The money
          entry("payments", "Payments", "/payments", "Wallet", "Money", "PAYMENT_VIEW", 150),
          entry("expenses", "Expenses", "/expenses", "Receipt", "Money", "EXPENSE_VIEW", 160),

          // The stock's condition and the capital side
          entry("maintenance", "Maintenance", "/maintenance", "Wrench", "Operations", "MAINTENANCE_VIEW", 170),
          entry("sales", "Sales", "/sales", "TrendingUp", "Operations", "SALE_VIEW", 180),

          // Records and communication
          entry("documents", "Documents", "/documents", "FolderOpen", "Records", "DOCUMENT_VIEW", 190),
          entry("communication", "Communication", "/communication", "MessagesSquare", "Records", "CONVERSATION_VIEW", 200),

          // Insight
          entry("reports", "Reports", "/reports", "BarChart3", "Insight", "REPORT_VIEW", 210),
          entry("audit-logs", "Audit Logs", "/audit-logs", "ShieldCheck", "Insight", "AUDIT_VIEW", 220),

          // Data and administration
          entry("imports", "Data Import", "/imports", "Upload", "Administration", "DATA_IMPORT", 230),
          entry("settings", "Settings", "/settings", "Settings", "Administration", "SETTINGS_VIEW", 240),

          // Account - reachable by everyone, so it carries no permission
          entry("profile", "Profile", "/profile", "UserCircle", "Account", null, 250));

  private static NavigationEntry entry(
      String key, String label, String to, String iconKey, String group, String permission, int order) {
    if (iconKey != null && !ICON_KEYS.contains(iconKey)) {
      throw new IllegalStateException(
          "The icon key '" + iconKey + "' is not one the frontend renders; use a key from the "
              + "agreed set or null.");
    }
    return new NavigationEntry(key, label, to, iconKey, group, permission, order);
  }

  public List<NavigationEntry> entries() {
    return ENTRIES;
  }

  /** The icon keys the frontend must be able to render, so a deployment can assert the set. */
  public List<String> iconKeys() {
    return ICON_KEYS;
  }
}
