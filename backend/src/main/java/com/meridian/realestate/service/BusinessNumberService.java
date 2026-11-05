package com.meridian.realestate.service;

import com.meridian.realestate.domain.BusinessNumberSequence;
import com.meridian.realestate.repository.BusinessNumberSequenceRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints the human-readable numbers the desk reads out loud: {@code ME-P-000016},
 * {@code ME-L-001042}, {@code ME-PAY-003118}.
 *
 * <p><b>Why this class exists.</b> Every record the firm talks about on the phone needs a name a
 * person can say, write on a receipt and search for, which a UUID is not. Those names come from the
 * counter rows the migration chain seeds into {@code business_number_sequences}, and this is the only
 * place that moves one of them forward. The series codes are exposed here as constants so a caller
 * writes {@code LEASE} rather than {@code "LEASE"}: the code is a database key, and a typo in a
 * string literal is a missing series that only shows up the first time somebody drafts a lease.</p>
 *
 * <p><b>The rule it protects.</b> Numbering is per series and unique. The read takes the row's
 * pessimistic write lock ({@code findByCodeForUpdate}), so two colleagues working the same portfolio
 * at the same second are handed the following number each rather than the same one - the lock is
 * held until this transaction commits, and the second caller then reads the value the first one
 * wrote. Nothing here is cached or pre-fetched: a number is minted when the record is written, not
 * when a form is opened, because a number reserved for a form nobody submitted is a hole in a series
 * that a person then has to have explained to them.</p>
 *
 * <p><b>Why {@code REQUIRES_NEW}.</b> Minting a number runs in its own transaction, so the series row
 * lock is not held for the length of the caller's work. A lease check-in writes many rows - the
 * lease, its terms, the status history, the unit's state, the audit entry - and if the number were
 * minted inside that transaction the lock on {@code LEASE} would be held for all of it, serialising
 * every other lease check-in behind the slowest one. The cost is that a caller which rolls back
 * after taking a number has burned it: there is a gap in the series. That trade is deliberate and
 * one-sided - a gap in a receipt series is harmless (nothing in the product asserts that receipts are
 * consecutive, and a missing number is at worst a question), while two receipts sharing a number is
 * a reconciliation failure the firm cannot fix after the fact. Uniqueness is therefore bought with
 * gaps.</p>
 *
 * <p>A code with no series row throws {@link IllegalStateException} rather than an API error, because
 * it is not something a caller can correct: it means this installation's migrations have not seeded a
 * series the code writing the record assumes exists. That is a configuration defect in the build, and
 * it should read like one.</p>
 */
@Service
public class BusinessNumberService {

  // ---------------------------------------------------------------------------
  // The seeded series. One constant per row V2 inserts into
  // business_number_sequences; the strings must stay exactly equal to the `code`
  // column, because that column is what findByCodeForUpdate matches on.
  // ---------------------------------------------------------------------------

  /** Property register numbers, {@code ME-P-00001}. */
  public static final String PROPERTY = "PROPERTY";

  /** Unit numbers, {@code ME-U-000001}. */
  public static final String UNIT = "UNIT";

  /** Owner reference numbers, {@code ME-O-00001}. */
  public static final String OWNER = "OWNER";

  /** Tenant reference numbers, {@code ME-T-00001}. */
  public static final String TENANT = "TENANT";

  /** Lead numbers, {@code ME-LD-000001}. */
  public static final String LEAD = "LEAD";

  /** Listing numbers, {@code ME-LS-000001}. */
  public static final String LISTING = "LISTING";

  /** Viewing numbers, {@code ME-V-000001}. */
  public static final String VIEWING = "VIEWING";

  /** Rental application numbers, {@code ME-A-00001}. */
  public static final String APPLICATION = "APPLICATION";

  /** Lease numbers, {@code ME-L-00001}. */
  public static final String LEASE = "LEASE";

  /** Rent invoice numbers, {@code ME-R-000001}. */
  public static final String INVOICE = "INVOICE";

  /** Receipt numbers, {@code ME-PAY-000001}. */
  public static final String RECEIPT = "RECEIPT";

  /** Maintenance request numbers, {@code ME-M-00001}. */
  public static final String MAINTENANCE = "MAINTENANCE";

  /** Sales opportunity numbers, {@code ME-S-00001}. */
  public static final String SALE = "SALE";

  private final BusinessNumberSequenceRepository sequences;

  public BusinessNumberService(BusinessNumberSequenceRepository sequences) {
    this.sequences = sequences;
  }

  /**
   * The next number in a series, and the counter advanced past it.
   *
   * <p>The returned value is {@code prefix} followed by the value zero-padded to the series' own
   * {@code padding} - the padding lives on the row, not in a constant here, so an installation can
   * widen a series without a release. A value already wider than the padding is printed in full
   * rather than truncated: at that point the series has outgrown its shape, and a quieter wrong
   * answer (two numbers colliding after truncation, or a number that reads as a smaller one) would be
   * far worse than a longer number.</p>
   *
   * @param code the series code, one of this class's constants
   * @return the minted number, prefix included
   * @throws IllegalStateException if no series with that code has been seeded
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String next(String code) {
    BusinessNumberSequence sequence =
        sequences
            .findByCodeForUpdate(code)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No business-number series is configured for code '"
                            + code
                            + "'. The migration chain seeds these series; a caller asking for one "
                            + "that is not there is a configuration defect, not a client mistake."));

    long value = sequence.getNextValue();
    sequence.setNextValue(value + 1);
    sequence.setUpdatedAt(LocalDateTime.now());
    sequences.save(sequence);

    return sequence.getPrefix() + pad(value, sequence.getPadding());
  }

  /** The value zero-padded to the series' width, printed in full when it is already wider. */
  private static String pad(long value, int padding) {
    String digits = Long.toString(value);
    int width = Math.max(padding, 1);
    return digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
  }
}
