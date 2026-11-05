package com.meridian.realestate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The installation's configuration, bound from {@code app.*} in {@code application.yml}.
 *
 * <p>One installation serves one firm, so the firm profile is configuration rather than a table.
 * Everything here is a value two firms would disagree about: the firm's own details, the policy
 * thresholds the dashboard and reports reason about, the money rules, the import limits, and the
 * product/industry identity. Keeping them in one bound object means no service reaches for a
 * literal, and no threshold is duplicated between the dashboard and a report.</p>
 *
 * <p>Every field carries the same default the YAML does, so an installation that sets nothing
 * still starts and behaves sensibly.</p>
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

  private String env = "development";
  private final Cors cors = new Cors();
  private final Auth auth = new Auth();
  private final Firm firm = new Firm();
  private final Portfolio portfolio = new Portfolio();
  private final Money money = new Money();
  private final Imports imports = new Imports();
  private final Platform platform = new Platform();

  public String getEnv() {
    return env;
  }

  public void setEnv(String env) {
    this.env = env;
  }

  public Cors getCors() {
    return cors;
  }

  public Auth getAuth() {
    return auth;
  }

  public Firm getFirm() {
    return firm;
  }

  public Portfolio getPortfolio() {
    return portfolio;
  }

  public Money getMoney() {
    return money;
  }

  public Imports getImports() {
    return imports;
  }

  public Platform getPlatform() {
    return platform;
  }

  /** True when this installation is running somewhere a development default is acceptable. */
  public boolean isLocal() {
    return env == null || env.isBlank() || env.equalsIgnoreCase("development")
        || env.equalsIgnoreCase("local") || env.equalsIgnoreCase("test");
  }

  public static class Cors {
    private String allowedOrigins =
        "http://localhost:5175,http://localhost:4175,http://localhost:3000";

    public String getAllowedOrigins() {
      return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
      this.allowedOrigins = allowedOrigins;
    }
  }

  public static class Auth {
    private long accessTokenExpirationSeconds = 900L;
    private long refreshTokenExpirationSeconds = 2_592_000L;

    public long getAccessTokenExpirationSeconds() {
      return accessTokenExpirationSeconds;
    }

    public void setAccessTokenExpirationSeconds(long value) {
      this.accessTokenExpirationSeconds = value;
    }

    public long getRefreshTokenExpirationSeconds() {
      return refreshTokenExpirationSeconds;
    }

    public void setRefreshTokenExpirationSeconds(long value) {
      this.refreshTokenExpirationSeconds = value;
    }
  }

  /** The firm, as it appears on the shell, reports and notification copy. */
  public static class Firm {
    private String name = "Meridian Estates";
    private String code = "ME-001";
    private String email = "advisory@meridianestates.example";
    private String phone = "+91-22-6155-4400";
    private String address = "Meridian House, 14 Ballard Estate, Fort, Mumbai 400001";
    private String licence = "RERA/ME/2026/0114";

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getEmail() {
      return email;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public String getPhone() {
      return phone;
    }

    public void setPhone(String phone) {
      this.phone = phone;
    }

    public String getAddress() {
      return address;
    }

    public void setAddress(String address) {
      this.address = address;
    }

    public String getLicence() {
      return licence;
    }

    public void setLicence(String licence) {
      this.licence = licence;
    }
  }

  /**
   * The thresholds the portfolio is managed against.
   *
   * <p>Read by the dashboard, the "needs attention" rail and the reports alike, so a lease cannot
   * be called expiring on one screen and not on another.</p>
   */
  public static class Portfolio {
    private int leaseExpiryWarningDays = 60;
    private int leaseExpiryUrgentDays = 30;
    private int rentDueDay = 5;
    private int rentOverdueGraceDays = 5;
    private int occupancyTargetPercent = 92;
    private int listingStaleDays = 45;
    private int viewingReminderHours = 24;
    private int maintenanceUrgentHours = 24;
    private int trendMonths = 14;

    public int getLeaseExpiryWarningDays() {
      return leaseExpiryWarningDays;
    }

    public void setLeaseExpiryWarningDays(int value) {
      this.leaseExpiryWarningDays = value;
    }

    public int getLeaseExpiryUrgentDays() {
      return leaseExpiryUrgentDays;
    }

    public void setLeaseExpiryUrgentDays(int value) {
      this.leaseExpiryUrgentDays = value;
    }

    public int getRentDueDay() {
      return rentDueDay;
    }

    public void setRentDueDay(int value) {
      this.rentDueDay = value;
    }

    public int getRentOverdueGraceDays() {
      return rentOverdueGraceDays;
    }

    public void setRentOverdueGraceDays(int value) {
      this.rentOverdueGraceDays = value;
    }

    public int getOccupancyTargetPercent() {
      return occupancyTargetPercent;
    }

    public void setOccupancyTargetPercent(int value) {
      this.occupancyTargetPercent = value;
    }

    public int getListingStaleDays() {
      return listingStaleDays;
    }

    public void setListingStaleDays(int value) {
      this.listingStaleDays = value;
    }

    public int getViewingReminderHours() {
      return viewingReminderHours;
    }

    public void setViewingReminderHours(int value) {
      this.viewingReminderHours = value;
    }

    public int getMaintenanceUrgentHours() {
      return maintenanceUrgentHours;
    }

    public void setMaintenanceUrgentHours(int value) {
      this.maintenanceUrgentHours = value;
    }

    public int getTrendMonths() {
      return trendMonths;
    }

    public void setTrendMonths(int value) {
      this.trendMonths = value;
    }
  }

  public static class Money {
    private String currency = "INR";
    private String currencySymbol = "Rs.";

    public String getCurrency() {
      return currency;
    }

    public void setCurrency(String currency) {
      this.currency = currency;
    }

    public String getCurrencySymbol() {
      return currencySymbol;
    }

    public void setCurrencySymbol(String currencySymbol) {
      this.currencySymbol = currencySymbol;
    }
  }

  public static class Imports {
    private int maxFileSizeMb = 10;
    private int maxRows = 5000;
    private int batchSize = 200;

    public int getMaxFileSizeMb() {
      return maxFileSizeMb;
    }

    public void setMaxFileSizeMb(int value) {
      this.maxFileSizeMb = value;
    }

    public int getMaxRows() {
      return maxRows;
    }

    public void setMaxRows(int value) {
      this.maxRows = value;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int value) {
      this.batchSize = value;
    }
  }

  /**
   * The product and industry this build is.
   *
   * <p>Chosen at build/deploy time, deliberately with no runtime switcher: a product ships as
   * source code configured for one industry. Branding values are all blank by default, so a
   * purchased installation that sets nothing keeps Meridian's own identity rather than leaking
   * an empty string into the shell.</p>
   */
  public static class Platform {
    private String industry = "REAL_ESTATE";
    private String product = "REAL_ESTATE_DASHBOARD";
    private final Brand brand = new Brand();

    public String getIndustry() {
      return industry;
    }

    public void setIndustry(String industry) {
      this.industry = industry;
    }

    public String getProduct() {
      return product;
    }

    public void setProduct(String product) {
      this.product = product;
    }

    public Brand getBrand() {
      return brand;
    }
  }

  public static class Brand {
    private String productName = "";
    private String brandName = "";
    private String companyName = "";
    private String logoUrl = "";
    private String faviconUrl = "";
    private String loginHeadline = "";
    private String loginSubheadline = "";
    private String primaryColor = "";
    private String secondaryColor = "";

    public String getProductName() {
      return productName;
    }

    public void setProductName(String value) {
      this.productName = value;
    }

    public String getBrandName() {
      return brandName;
    }

    public void setBrandName(String value) {
      this.brandName = value;
    }

    public String getCompanyName() {
      return companyName;
    }

    public void setCompanyName(String value) {
      this.companyName = value;
    }

    public String getLogoUrl() {
      return logoUrl;
    }

    public void setLogoUrl(String value) {
      this.logoUrl = value;
    }

    public String getFaviconUrl() {
      return faviconUrl;
    }

    public void setFaviconUrl(String value) {
      this.faviconUrl = value;
    }

    public String getLoginHeadline() {
      return loginHeadline;
    }

    public void setLoginHeadline(String value) {
      this.loginHeadline = value;
    }

    public String getLoginSubheadline() {
      return loginSubheadline;
    }

    public void setLoginSubheadline(String value) {
      this.loginSubheadline = value;
    }

    public String getPrimaryColor() {
      return primaryColor;
    }

    public void setPrimaryColor(String value) {
      this.primaryColor = value;
    }

    public String getSecondaryColor() {
      return secondaryColor;
    }

    public void setSecondaryColor(String value) {
      this.secondaryColor = value;
    }
  }
}
