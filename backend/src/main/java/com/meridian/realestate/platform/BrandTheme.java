package com.meridian.realestate.platform;

import com.meridian.realestate.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * The active brand and the product's own profile.
 *
 * <p>Two jobs that belong together because they are the same question from different angles: what
 * is this installation called, and what does it look like. Both are configuration driven, so a
 * purchased deployment is rebranded by setting environment values rather than by editing source.</p>
 *
 * <p><b>Every branding value falls back to Meridian's own identity.</b> A blank in the environment
 * yields "Meridian Estates", never an empty sidebar label or a missing mark - the platform
 * configuration ships blank on purpose so an installation that sets nothing keeps the product's own
 * name rather than leaking an empty string into the shell.</p>
 */
@Component
public class BrandTheme {

  private static final String VERSION = "0.1.0";

  private final String productName;
  private final String brandName;
  private final String companyName;
  private final String logoUrl;
  private final String faviconUrl;
  private final String loginHeadline;
  private final String loginSubheadline;
  private final String sidebarBrandLabel;
  private final String footerText;
  private final String primaryColor;
  private final String primarySoftColor;
  private final String secondaryColor;

  public BrandTheme(AppProperties properties) {
    AppProperties.Brand brand =
        properties.getPlatform() == null ? new AppProperties.Brand() : properties.getPlatform().getBrand();

    this.productName = pick(brand.getProductName(), "Real Estate Dashboard");
    this.brandName = pick(brand.getBrandName(), "Meridian Estates");
    this.companyName = pick(brand.getCompanyName(), "Meridian Estates");
    this.logoUrl = pick(brand.getLogoUrl(), "/meridian-logo.svg");
    this.faviconUrl = pick(brand.getFaviconUrl(), "/meridian-favicon.svg");
    this.loginHeadline = pick(brand.getLoginHeadline(), "One desk for the whole portfolio.");
    this.loginSubheadline =
        pick(
            brand.getLoginSubheadline(),
            "Sign in to run the register, the rent roll, the letting and sales desks, maintenance "
                + "and the reporting from a single place.");
    this.sidebarBrandLabel = this.brandName;
    this.footerText = this.brandName;
    this.primaryColor = pick(brand.getPrimaryColor(), "#12352A");
    this.primarySoftColor = "#DDE7E2";
    this.secondaryColor = pick(brand.getSecondaryColor(), "#B08D4F");
  }

  private static String pick(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  public String productName() {
    return productName;
  }

  public String version() {
    return VERSION;
  }

  /** The industry's display name, for the catalogue endpoint. */
  public String industryName() {
    return "Real Estate";
  }

  public PlatformDtos.BrandThemeView view() {
    return new PlatformDtos.BrandThemeView(
        productName,
        brandName,
        companyName,
        logoUrl,
        faviconUrl,
        loginHeadline,
        loginSubheadline,
        sidebarBrandLabel,
        footerText,
        primaryColor,
        primarySoftColor,
        secondaryColor,
        "light");
  }
}
