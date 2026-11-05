package com.meridian.realestate.platform;

import java.util.List;
import java.util.Map;

/** The platform profile the frontend boots from, and the product catalogue. */
public final class PlatformDtos {

  private PlatformDtos() {}

  /** Which product this is: its code, its display name and its version. */
  public record ProductView(String code, String name, String version) {}

  /** Which industry this is: its code and its display name. */
  public record IndustryView(String code, String name) {}

  /** A navigation entry, flattened for the client. */
  public record NavigationEntryView(
      String key,
      String label,
      String to,
      String iconKey,
      String group,
      String permission,
      int displayOrder) {}

  /**
   * The active brand.
   *
   * <p>Every value falls back to Meridian's own identity, so a deployment that supplies no branding
   * renders Meridian Estates rather than leaking a blank or another product's marks.
   * {@code primaryColor} and {@code secondaryColor} are applied to the document's custom
   * properties, which is why a rebrand needs no restyle.</p>
   */
  public record BrandThemeView(
      String productName,
      String brandName,
      String companyName,
      String logoUrl,
      String faviconUrl,
      String loginHeadline,
      String loginSubheadline,
      String sidebarBrandLabel,
      String footerText,
      String primaryColor,
      String primarySoftColor,
      String secondaryColor,
      String colorScheme) {}

  /** The firm's own details, from configuration - one installation serves one firm. */
  public record FirmProfileView(
      String name, String code, String email, String phone, String address, String licence) {}

  /**
   * The policy thresholds the dashboard and the reports reason about, so a screen can draw the
   * occupancy gauge and explain an "expiring" badge without inventing its own numbers.
   */
  public record PolicyThresholdsView(
      int leaseExpiryWarningDays,
      int leaseExpiryUrgentDays,
      int rentDueDay,
      int rentOverdueGraceDays,
      int occupancyTargetPercent,
      int listingStaleDays,
      int viewingReminderHours,
      int maintenanceUrgentHours,
      int trendMonths,
      int importMaxFileSizeMb,
      int importMaxRows,
      String currency,
      String currencySymbol) {}

  /**
   * Everything the frontend boots from.
   *
   * <p>{@code terminology} is the map and {@code terminologyKeys} is its sorted key set, so a
   * client can assert it asked for every term the product defines.</p>
   */
  public record PlatformProfile(
      ProductView product,
      IndustryView industry,
      Map<String, String> terminology,
      List<String> terminologyKeys,
      List<String> enabledModules,
      List<NavigationEntryView> navigation,
      List<String> iconKeys,
      BrandThemeView theme,
      FirmProfileView firm,
      PolicyThresholdsView policies) {}

  /** The registered products and industries, so a deployment can confirm what it is running. */
  public record PlatformCatalogue(List<ProductView> products, List<IndustryView> industries) {}
}
