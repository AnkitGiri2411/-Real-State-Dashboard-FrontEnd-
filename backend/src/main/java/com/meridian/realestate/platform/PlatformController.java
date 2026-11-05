package com.meridian.realestate.platform;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.dto.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The configuration the frontend boots from.
 *
 * <p>Read-only, and requiring an authenticated caller. The frontend uses this rather than build-time
 * constants so the backend is the runtime source of truth for the product's identity, the firm's
 * vocabulary and the sidebar; any {@code VITE_*} variables are only the fallback used before this
 * request answers.</p>
 *
 * <p>There is deliberately no endpoint that changes the industry or the product: a build is one
 * product for one industry, and {@code app.platform.industry} is {@code REAL_ESTATE} and
 * {@code app.platform.product} is {@code REAL_ESTATE_DASHBOARD}. There is no switcher to expose.</p>
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

  private final ProductIdentity productIdentity;
  private final Terminology terminology;
  private final PlatformNavigation navigation;
  private final BrandTheme brandTheme;
  private final AppProperties properties;

  public PlatformController(
      ProductIdentity productIdentity,
      Terminology terminology,
      PlatformNavigation navigation,
      BrandTheme brandTheme,
      AppProperties properties) {
    this.productIdentity = productIdentity;
    this.terminology = terminology;
    this.navigation = navigation;
    this.brandTheme = brandTheme;
    this.properties = properties;
  }

  @GetMapping("/profile")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<PlatformDtos.PlatformProfile> profile() {
    List<PlatformDtos.NavigationEntryView> entries =
        navigation.entries().stream()
            .map(
                entry ->
                    new PlatformDtos.NavigationEntryView(
                        entry.key(),
                        entry.label(),
                        entry.to(),
                        entry.iconKey(),
                        entry.group(),
                        entry.permission(),
                        entry.displayOrder()))
            .toList();

    Map<String, String> terms = terminology.all();
    AppProperties.Firm firm = properties.getFirm();
    AppProperties.Portfolio portfolio = properties.getPortfolio();
    AppProperties.Money money = properties.getMoney();
    AppProperties.Imports imports = properties.getImports();

    return ApiResponse.ok(
        new PlatformDtos.PlatformProfile(
            new PlatformDtos.ProductView(
                productIdentity.getProductCode(), brandTheme.productName(), brandTheme.version()),
            new PlatformDtos.IndustryView(productIdentity.getIndustry(), brandTheme.industryName()),
            terms,
            terms.keySet().stream().sorted().toList(),
            entries.stream().map(PlatformDtos.NavigationEntryView::key).toList(),
            entries,
            navigation.iconKeys(),
            brandTheme.view(),
            new PlatformDtos.FirmProfileView(
                firm.getName(), firm.getCode(), firm.getEmail(), firm.getPhone(), firm.getAddress(),
                firm.getLicence()),
            new PlatformDtos.PolicyThresholdsView(
                portfolio.getLeaseExpiryWarningDays(),
                portfolio.getLeaseExpiryUrgentDays(),
                portfolio.getRentDueDay(),
                portfolio.getRentOverdueGraceDays(),
                portfolio.getOccupancyTargetPercent(),
                portfolio.getListingStaleDays(),
                portfolio.getViewingReminderHours(),
                portfolio.getMaintenanceUrgentHours(),
                portfolio.getTrendMonths(),
                imports.getMaxFileSizeMb(),
                imports.getMaxRows(),
                money.getCurrency(),
                money.getCurrencySymbol())));
  }

  /** The registered products and industries, so a deployment can confirm what it is running. */
  @GetMapping("/catalogue")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<PlatformDtos.PlatformCatalogue> catalogue() {
    return ApiResponse.ok(
        new PlatformDtos.PlatformCatalogue(
            List.of(
                new PlatformDtos.ProductView(
                    productIdentity.getProductCode(), brandTheme.productName(), brandTheme.version())),
            List.of(
                new PlatformDtos.IndustryView(
                    productIdentity.getIndustry(), brandTheme.industryName()))));
  }
}
