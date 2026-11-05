package com.meridian.realestate.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The property's money rules, in one place.
 *
 * <p><b>Why this class exists.</b> Rent, deposits, invoice lines, receipts, expenses and sale prices
 * are all amounts of money, and every one of them has to be rounded, taxed and added up the same way
 * or the rent roll stops agreeing with the receipts that settle it. A rate expressed as
 * {@code 18} in configuration and as {@code 0.18} in an arithmetic path is exactly the kind of
 * drift this product cannot afford, so no other class performs money arithmetic: it asks this one.
 * Two screens that round differently are not a style difference, they are a reconciliation
 * difference - a tenant reading {@code Rs. 41,300.00} on an invoice and {@code Rs. 41,299.99} on the
 * receipt for the same month will not accept any explanation.</p>
 *
 * <p><b>The rule it protects.</b> Money is {@link BigDecimal} only. A {@code double} cannot hold
 * {@code 0.1} exactly, so a ledger built on one drifts by fractions of a paisa per line and by real
 * money over a portfolio; {@code float} is worse. Nothing here accepts or returns a primitive
 * floating-point value, and anything that did would have to be changed at its source rather than
 * accommodated here.</p>
 *
 * <p><b>Rounding.</b> {@link RoundingMode#HALF_UP} at {@link #SCALE} decimal places - two, because
 * that is what a rupee settles to. {@code HALF_UP} rather than {@code HALF_EVEN} because the two
 * differ only on an exact half, where a person reading the bill expects the second decimal to go up
 * rather than to whichever digit the banker's rule prefers.</p>
 *
 * <p><b>Where the rounding happens.</b> Tax is rounded at the point of calculation, not at the end
 * of a sum. Each line's tax is computed and rounded to two places on its own, and the total is the
 * sum of those rounded figures; the alternative - summing exact products and rounding once - makes
 * the invoice's quoted total differ from its own printed lines by a paisa or two, because twelve
 * lines each carrying a third of a paisa round differently in aggregate than each one alone. The
 * quoted total must be the sum of what is printed above it, so the rounding is per line.</p>
 *
 * <p>The rate itself is configuration ({@code app.money.tax-rate-percent}), because two firms bill
 * under different regimes and one of them changes the rate without a release. It is read once, at
 * construction, so a request can never see a rate change halfway through a calculation.</p>
 */
@Service
public class MoneyPolicy {

  /** The decimal places money carries in this product: what a currency amount settles to. */
  public static final int SCALE = 2;

  /**
   * The places the rate itself keeps.
   *
   * <p>Four, so a rate like {@code 12.5} or {@code 7.25} is exact rather than a repeating decimal,
   * and so a future rate with a fraction of a percent does not silently lose it.</p>
   */
  private static final int RATE_SCALE = 4;

  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  private final int taxRatePercent;

  /**
   * @param taxRatePercent the rate this installation bills at, as a whole-or-fractional percent
   *     ({@code 18} means 18%). Defaults to 18 when the installation sets nothing.
   */
  public MoneyPolicy(@Value("${app.money.tax-rate-percent:18}") int taxRatePercent) {
    this.taxRatePercent = taxRatePercent;
  }

  /** The configured rate as a percentage, for copy that reads "18% tax". */
  public int taxRatePercent() {
    return taxRatePercent;
  }

  /**
   * The configured rate as a multiplier, for arithmetic: 18% is {@code 0.1800}.
   *
   * <p>Derived from {@link #taxRatePercent()} rather than configured separately, so the two can
   * never disagree - and derived with {@link BigDecimal}, because {@code 18 / 100.0} is {@code 0.18}
   * only for as long as a double happens to print that way.</p>
   */
  public BigDecimal taxRate() {
    return BigDecimal.valueOf(taxRatePercent).divide(ONE_HUNDRED, RATE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * A money amount at the product's scale, rounding half up.
   *
   * <p>{@code null} is zero, not negative and not an exception: an absent figure in this product
   * means "nothing recorded", and a null that flowed into an arithmetic expression would otherwise
   * throw inside a report rather than report a zero. It is emphatically <em>not</em> a licence to
   * store null where a figure is required - the callers that read absent amounts as zero are the
   * read paths; the write paths refuse a null price.</p>
   */
  public static BigDecimal money(BigDecimal value) {
    return value == null ? zero() : value.setScale(SCALE, RoundingMode.HALF_UP);
  }

  /** Zero at the product's scale, so {@code 0} and {@code 0.00} are never compared as different. */
  public static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Whether an amount is greater than zero.
   *
   * <p>Tested on the value as given, not after rounding: {@code 0.001} is positive. A caller that
   * needs "a settled amount greater than zero" - a receipt amount, an offer price - compares
   * {@code isPositive(money(amount))}, which is the honest way to say it, rather than asking this
   * method to round a value whose scale it was never told about.</p>
   */
  public static boolean isPositive(BigDecimal value) {
    return value != null && value.signum() > 0;
  }

  /**
   * The tax on a net amount, rounded to two places <em>here</em>.
   *
   * <p>See the class note on where rounding happens: this is the per-line figure that gets printed
   * and summed, so an invoice's twelve rounded lines add up to its quoted total exactly.</p>
   */
  public BigDecimal taxOn(BigDecimal netAmount) {
    return money(money(netAmount).multiply(taxRate()));
  }

  /**
   * The gross amount a net amount attracts: the net plus the tax on it.
   *
   * <p>Deliberately expressed as {@code net + taxOn(net)} rather than {@code net * (1 + rate)}, so
   * gross is exactly the sum of the two figures a screen shows side by side. The rearrangement is
   * mathematically identical and financially different: the version that multiplies once can print
   * a gross that does not equal the net and tax on the same line.</p>
   */
  public BigDecimal grossOf(BigDecimal netAmount) {
    BigDecimal net = money(netAmount);
    return money(net.add(taxOn(net)));
  }
}
