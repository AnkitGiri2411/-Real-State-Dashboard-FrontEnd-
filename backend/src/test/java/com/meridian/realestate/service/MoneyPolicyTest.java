package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The money rules, asserted without a database or a Spring context.
 *
 * <p>These are the figures a tenant reads on an invoice and an accountant reconciles a receipt
 * against, so the properties worth pinning down are the ones a later refactor would quietly change:
 * the scale every amount carries, which way an exact half rounds, what an absent amount means, and
 * that a gross figure is always the net plus the tax shown beside it.</p>
 */
@DisplayName("MoneyPolicy: the product's money rules")
class MoneyPolicyTest {

  private static final BigDecimal NET = new BigDecimal("100.00");

  private final MoneyPolicy policy = new MoneyPolicy(18);

  @Test
  @DisplayName("every amount carries exactly two decimal places")
  void scaleIsAlwaysTwo() {
    assertThat(MoneyPolicy.money(new BigDecimal("10")).scale()).isEqualTo(2);
    assertThat(MoneyPolicy.money(new BigDecimal("10.0")).scale()).isEqualTo(2);
    assertThat(MoneyPolicy.money(new BigDecimal("10.000")).scale()).isEqualTo(2);
    assertThat(MoneyPolicy.zero().scale()).isEqualTo(MoneyPolicy.SCALE);
    assertThat(policy.taxOn(new BigDecimal("1234.5678")).scale()).isEqualTo(2);
    assertThat(policy.grossOf(new BigDecimal("1234.5678")).scale()).isEqualTo(2);

    // And the rounded value is the money, not just the scale.
    assertThat(MoneyPolicy.money(new BigDecimal("10"))).isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("an exact half rounds up, not to even")
  void roundsHalfUpOnAHalfCent() {
    // HALF_UP: the third decimal being exactly five moves the second one up. HALF_EVEN, the other
    // candidate, would round 10.005 down to 10.00 and 2.345 up to 2.34 - the second of those is the
    // case that tells the two modes apart.
    assertThat(MoneyPolicy.money(new BigDecimal("10.005"))).isEqualByComparingTo("10.01");
    assertThat(MoneyPolicy.money(new BigDecimal("2.345"))).isEqualByComparingTo("2.35");
    assertThat(MoneyPolicy.money(new BigDecimal("0.045"))).isEqualByComparingTo("0.05");

    // Below a half cent still rounds down.
    assertThat(MoneyPolicy.money(new BigDecimal("10.004"))).isEqualByComparingTo("10.00");

    // Half up means away from zero, so a negative half-cent moves down.
    assertThat(MoneyPolicy.money(new BigDecimal("-1.005"))).isEqualByComparingTo("-1.01");
  }

  @Test
  @DisplayName("an absent amount is zero, never negative")
  void moneyOfNullIsZero() {
    assertThat(MoneyPolicy.money(null)).isEqualByComparingTo("0.00");
    assertThat(MoneyPolicy.money(null).signum()).isZero();
    assertThat(MoneyPolicy.money(null)).isNotNegative();
    assertThat(MoneyPolicy.money(null).scale()).isEqualTo(2);
  }

  @Test
  @DisplayName("isPositive is false for null and zero, and true only above zero")
  void isPositiveOnNullZeroNegativeAndPositive() {
    assertThat(MoneyPolicy.isPositive(null)).isFalse();
    assertThat(MoneyPolicy.isPositive(BigDecimal.ZERO)).isFalse();
    assertThat(MoneyPolicy.isPositive(new BigDecimal("0.00"))).isFalse();
    assertThat(MoneyPolicy.isPositive(new BigDecimal("-0.01"))).isFalse();
    assertThat(MoneyPolicy.isPositive(new BigDecimal("-500.00"))).isFalse();
    assertThat(MoneyPolicy.isPositive(new BigDecimal("0.01"))).isTrue();
    assertThat(MoneyPolicy.isPositive(new BigDecimal("500.00"))).isTrue();

    // Tested on the value as given, so a caller that means "a settled amount above zero" says so.
    assertThat(MoneyPolicy.isPositive(MoneyPolicy.money(new BigDecimal("0.004")))).isFalse();
  }

  @Test
  @DisplayName("gross is exactly the net plus the tax shown beside it")
  void taxAndGrossReconcile() {
    assertThat(policy.taxRatePercent()).isEqualTo(18);
    assertThat(policy.taxRate()).isEqualByComparingTo("0.18");

    assertThat(policy.taxOn(NET)).isEqualByComparingTo("18.00");
    assertThat(policy.grossOf(NET)).isEqualByComparingTo("118.00");

    for (String net : new String[] {"0.01", "0.25", "999.99", "1234.5678", "50000.00"}) {
      BigDecimal netAmount = new BigDecimal(net);
      BigDecimal settled = MoneyPolicy.money(netAmount);
      BigDecimal tax = policy.taxOn(netAmount);
      BigDecimal gross = policy.grossOf(netAmount);

      assertThat(gross)
          .as("gross of %s must be its net plus its tax", net)
          .isEqualByComparingTo(settled.add(tax));
      assertThat(gross.subtract(tax))
          .as("subtracting the tax from the gross of %s must give its net", net)
          .isEqualByComparingTo(settled);
    }
  }

  @Test
  @DisplayName("no net means no tax, and no gross")
  void zeroNetHasZeroTax() {
    assertThat(policy.taxOn(BigDecimal.ZERO)).isEqualByComparingTo("0.00");
    assertThat(policy.taxOn(null)).isEqualByComparingTo("0.00");
    assertThat(policy.taxOn(null).scale()).isEqualTo(2);
    assertThat(policy.grossOf(BigDecimal.ZERO)).isEqualByComparingTo("0.00");
    assertThat(policy.grossOf(null)).isEqualByComparingTo("0.00");
    assertThat(MoneyPolicy.isPositive(policy.taxOn(BigDecimal.ZERO))).isFalse();
  }

  @Test
  @DisplayName("tax is rounded per line, so twelve rounded lines are the quoted total")
  void perLineRoundingIsWhatTheInvoiceQuotes() {
    // Twelve lines of five paise. Each line's own tax is 0.05 * 0.18 = 0.009, rounded here to 0.01.
    // Rounding once at the end instead - 0.60 * 0.18 = 0.108 -> 0.11 - would quote a total that its
    // own twelve printed lines do not add up to, which is the bug this rule exists to prevent.
    BigDecimal lineNet = new BigDecimal("0.05");
    BigDecimal perLineTax = policy.taxOn(lineNet);
    assertThat(perLineTax).isEqualByComparingTo("0.01");

    BigDecimal summedTax = BigDecimal.ZERO;
    for (int line = 1; line <= 12; line++) {
      summedTax = summedTax.add(policy.taxOn(lineNet));
    }

    assertThat(summedTax).isEqualByComparingTo("0.12");
    assertThat(policy.taxOn(lineNet.multiply(new BigDecimal("12"))))
        .as("rounding once at the end is deliberately not what this class does")
        .isEqualByComparingTo("0.11");
  }

  @ParameterizedTest
  @DisplayName("the rate is whatever the installation configured")
  @ValueSource(ints = {0, 5, 12, 18, 28})
  void rateFollowsTheInstallation(int ratePercent) {
    MoneyPolicy configured = new MoneyPolicy(ratePercent);

    assertThat(configured.taxRatePercent()).isEqualTo(ratePercent);
    assertThat(configured.taxRate())
        .isEqualByComparingTo(BigDecimal.valueOf(ratePercent).divide(new BigDecimal("100")));
    assertThat(configured.grossOf(NET))
        .isEqualByComparingTo(MoneyPolicy.money(NET).add(configured.taxOn(NET)));
  }
}
