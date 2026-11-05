package com.meridian.realestate.platform;

import com.meridian.realestate.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Which industry and which product this installation is.
 *
 * <p>Both are deployment configuration rather than runtime state: a product is shipped as source
 * configured for one industry, so there is deliberately no runtime switcher. This build is the real
 * estate product - {@code REAL_ESTATE} / {@code REAL_ESTATE_DASHBOARD}. Changing these does
 * <em>not</em> change authorization: permissions, row scope and every {@code @PreAuthorize} rule are
 * unaffected by the industry configuration.</p>
 */
@Component
public class ProductIdentity {

  private final String industry;
  private final String productCode;

  public ProductIdentity(AppProperties properties) {
    AppProperties.Platform platform = properties.getPlatform();
    this.industry = platform == null || platform.getIndustry() == null || platform.getIndustry().isBlank()
        ? "REAL_ESTATE"
        : platform.getIndustry();
    this.productCode =
        platform == null || platform.getProduct() == null || platform.getProduct().isBlank()
            ? "REAL_ESTATE_DASHBOARD"
            : platform.getProduct();
  }

  public String getIndustry() {
    return industry;
  }

  public String getProductCode() {
    return productCode;
  }
}
